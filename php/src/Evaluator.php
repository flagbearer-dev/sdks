<?php

declare(strict_types=1);

namespace Flagbearer;

/**
 * Flag evaluation and ruleset parsing, identical to every other Flagbearer SDK.
 *
 * @internal
 *
 * @phpstan-type Rule array{enabled: bool, rolloutBps: int, overrideEmails: list<string>}
 * @phpstan-type Ruleset array{environmentId: string, version: int, flags: array<string, Rule>}
 * @phpstan-type Evaluation array{value: bool, reason: 'off'|'tester'|'rollout'|'fallback', version: int|null}
 */
final class Evaluator
{
    public const MAX_SAFE_INTEGER = 9007199254740991;

    // JavaScript's String.prototype.trim removes these, so every SDK does too.
    private const JS_WHITESPACE = '\x{0009}-\x{000D}\x{0020}\x{00A0}\x{1680}\x{2000}-\x{200A}'
        . '\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}\x{FEFF}';

    /** A user's place in a flag's rollout, 0–9999. Below rolloutBps means on. */
    public static function bucket(string $flagKey, string $userKey): int
    {
        $digest = hash('sha256', $flagKey . ':' . $userKey, true);
        // The digest read as a big-endian integer, mod 10000, one byte at a time.
        $remainder = 0;
        $length = strlen($digest);
        for ($index = 0; $index < $length; $index++) {
            $remainder = ($remainder * 256 + ord($digest[$index])) % 10000;
        }
        return $remainder;
    }

    /** Trims like JavaScript, then lowercases. Throws on invalid UTF-8. */
    public static function normalizeEmail(string $email): string
    {
        $pattern = '/^[' . self::JS_WHITESPACE . ']+|[' . self::JS_WHITESPACE . ']+$/u';
        $trimmed = preg_replace($pattern, '', $email);
        if ($trimmed === null) {
            throw new \InvalidArgumentException('Invalid UTF-8');
        }
        return mb_strtolower($trimmed, 'UTF-8');
    }

    /**
     * @param Rule|null $flag
     * @return Evaluation
     */
    public static function evaluate(string $flagKey, ?array $flag, User $user, ?int $version, bool $default): array
    {
        if ($flag === null) {
            return ['value' => $default, 'reason' => 'fallback', 'version' => $version];
        }
        if (!$flag['enabled']) {
            return ['value' => false, 'reason' => 'off', 'version' => $version];
        }
        $email = self::normalizeEmail($user->email ?? '');
        if ($email !== '') {
            foreach ($flag['overrideEmails'] as $item) {
                if (self::normalizeEmail($item) === $email) {
                    return ['value' => true, 'reason' => 'tester', 'version' => $version];
                }
            }
        }
        $key = $user->key ?? '';
        if ($key === '') {
            if ($flag['rolloutBps'] !== 0 && $flag['rolloutBps'] !== 10000) {
                return ['value' => $default, 'reason' => 'fallback', 'version' => $version];
            }
            return ['value' => $flag['rolloutBps'] === 10000, 'reason' => 'rollout', 'version' => $version];
        }
        if (!mb_check_encoding($key, 'UTF-8')) {
            throw new \InvalidArgumentException('Invalid UTF-8');
        }
        return [
            'value' => self::bucket($flagKey, $key) < $flag['rolloutBps'],
            'reason' => 'rollout',
            'version' => $version,
        ];
    }

    /** A JSON number with no fractional part, as JavaScript accepts it. */
    public static function integer(mixed $value): ?int
    {
        if (is_int($value)) {
            return $value;
        }
        if (is_float($value) && is_finite($value) && floor($value) === $value
            && abs($value) <= self::MAX_SAFE_INTEGER) {
            return (int) $value;
        }
        return null;
    }

    public static function version(mixed $value): ?int
    {
        $number = self::integer($value);
        return $number !== null && $number >= 0 && $number <= self::MAX_SAFE_INTEGER ? $number : null;
    }

    /**
     * Reads a GET /v1/ruleset body, rejecting it whole if any part is invalid.
     *
     * @return Ruleset|null
     */
    public static function parseRuleset(string $body): ?array
    {
        try {
            $value = json_decode($body, false, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }
        if (!$value instanceof \stdClass) {
            return null;
        }
        $environmentId = $value->environmentId ?? null;
        $version = self::version($value->version ?? null);
        $flags = $value->flags ?? null;
        if (!is_string($environmentId) || $version === null || !is_array($flags)) {
            return null;
        }
        $rules = [];
        foreach ($flags as $item) {
            if (!$item instanceof \stdClass) {
                return null;
            }
            $key = $item->key ?? null;
            $enabled = $item->enabled ?? null;
            $rollout = self::integer($item->rolloutBps ?? null);
            $emails = $item->overrideEmails ?? null;
            if (!is_string($key) || !is_bool($enabled) || $rollout === null || $rollout < 0
                || $rollout > 10000 || !is_array($emails)) {
                return null;
            }
            $overrides = [];
            foreach ($emails as $email) {
                if (!is_string($email)) {
                    return null;
                }
                $overrides[] = $email;
            }
            $rules[$key] = ['enabled' => $enabled, 'rolloutBps' => $rollout, 'overrideEmails' => $overrides];
        }
        return ['environmentId' => $environmentId, 'version' => $version, 'flags' => $rules];
    }

    /** UTF-16 code units, as JavaScript and the server's limits count them. */
    public static function jsLength(string $text): int
    {
        return intdiv(strlen(mb_convert_encoding($text, 'UTF-16LE', 'UTF-8')), 2);
    }
}
