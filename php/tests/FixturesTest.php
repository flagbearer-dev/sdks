<?php

declare(strict_types=1);

namespace Flagbearer\Tests;

use Flagbearer\Evaluator;
use Flagbearer\User;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

final class FixturesTest extends TestCase
{
    /** @return array<mixed> */
    private static function fixtures(): array
    {
        $data = json_decode(
            (string) file_get_contents(__DIR__ . '/../../fixtures/evaluation.json'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
        return is_array($data) ? $data : [];
    }

    /** @return iterable<string, array{string, string, int}> */
    public static function buckets(): iterable
    {
        $items = self::fixtures()['buckets'] ?? [];
        foreach (is_array($items) ? $items : [] as $item) {
            if (is_array($item) && is_string($item['flag']) && is_string($item['user']) && is_int($item['bucket'])) {
                yield $item['flag'] . ' / ' . mb_substr($item['user'], 0, 20) => [$item['flag'], $item['user'], $item['bucket']];
            }
        }
    }

    #[DataProvider('buckets')]
    public function testBucket(string $flag, string $user, int $expected): void
    {
        self::assertSame($expected, Evaluator::bucket($flag, $user));
    }

    /** @return iterable<string, array{mixed, mixed, bool, bool, string}> */
    public static function cases(): iterable
    {
        $items = self::fixtures()['cases'] ?? [];
        foreach (is_array($items) ? $items : [] as $item) {
            if (!is_array($item) || !is_string($item['name']) || !is_bool($item['default'])
                || !is_array($item['expect']) || !is_bool($item['expect']['value'])
                || !is_string($item['expect']['reason'])) {
                continue;
            }
            yield $item['name'] => [
                $item['flag'],
                $item['user'],
                $item['default'],
                $item['expect']['value'],
                $item['expect']['reason'],
            ];
        }
    }

    #[DataProvider('cases')]
    public function testCase(mixed $flag, mixed $user, bool $default, bool $value, string $reason): void
    {
        $rule = null;
        $key = 'missing';
        if (is_array($flag)) {
            $ruleset = Evaluator::parseRuleset((string) json_encode(['environmentId' => '1', 'version' => 7, 'flags' => [$flag]]));
            self::assertNotNull($ruleset);
            $key = is_string($flag['key']) ? $flag['key'] : '';
            $rule = $ruleset['flags'][$key];
        }
        self::assertIsArray($user);
        $result = Evaluator::evaluate($key, $rule, User::from($user), 7, $default);
        self::assertSame(['value' => $value, 'reason' => $reason, 'version' => 7], $result);
    }

    /** @return iterable<string, array{string}> */
    public static function invalidFlags(): iterable
    {
        yield 'rollout above 10000' => ['{"key":"a","enabled":true,"rolloutBps":10001,"overrideEmails":[]}'];
        yield 'fractional rollout' => ['{"key":"a","enabled":true,"rolloutBps":50.5,"overrideEmails":[]}'];
        yield 'boolean rollout' => ['{"key":"a","enabled":true,"rolloutBps":true,"overrideEmails":[]}'];
        yield 'numeric enabled' => ['{"key":"a","enabled":1,"rolloutBps":0,"overrideEmails":[]}'];
        yield 'numeric email' => ['{"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":[1]}'];
        yield 'object emails' => ['{"key":"a","enabled":true,"rolloutBps":0,"overrideEmails":{}}'];
        yield 'missing emails' => ['{"key":"a","enabled":true,"rolloutBps":0}'];
    }

    #[DataProvider('invalidFlags')]
    public function testInvalidRulesRejectTheWholeRuleset(string $flag): void
    {
        $good = '{"key":"b","enabled":true,"rolloutBps":0,"overrideEmails":[]}';
        self::assertNull(Evaluator::parseRuleset('{"environmentId":"1","version":1,"flags":[' . $good . ',' . $flag . ']}'));
    }

    public function testTrailingDataAndIntegralFloats(): void
    {
        self::assertNull(Evaluator::parseRuleset('{"environmentId":"1","version":1,"flags":[]} trailing'));
        self::assertNotNull(Evaluator::parseRuleset('{"environmentId":"1","version":1.0,"flags":[]}'));
    }

    public function testEmailsTrimLikeJavaScript(): void
    {
        self::assertSame('ann@example.com', Evaluator::normalizeEmail("\u{FEFF}\u{00A0} Ann@Example.COM\u{2028}\t"));
    }
}
