<?php

declare(strict_types=1);

namespace Flagbearer;

/**
 * Diagnostics for debug mode, through error_log. Never logs keys, user data,
 * flag names or rules.
 *
 * @internal
 */
final class DebugLog
{
    public function __construct(private readonly bool $enabled)
    {
    }

    /** @param array<string, int|float|bool|null> $details */
    public function __invoke(string $message, array $details = []): void
    {
        if ($this->enabled) {
            self::write($message, $details);
        }
    }

    /**
     * Same shape as the Node SDK's console.debug output: { version: 42 }.
     *
     * @param array<string, int|float|bool|null> $details
     */
    public static function write(string $message, array $details = []): void
    {
        if ($details === []) {
            error_log("[Flagbearer] {$message}");
            return;
        }
        $parts = [];
        foreach ($details as $name => $value) {
            $text = match (true) {
                $value === null => 'undefined',
                is_bool($value) => $value ? 'true' : 'false',
                default => (string) $value,
            };
            $parts[] = "{$name}: {$text}";
        }
        error_log("[Flagbearer] {$message} { " . implode(', ', $parts) . ' }');
    }
}
