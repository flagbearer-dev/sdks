<?php

declare(strict_types=1);

namespace Flagbearer\Tests;

use Flagbearer\Cache\AtomicAdd;
use Flagbearer\Cache\FileCache;
use Flagbearer\Cache\MemoryCache;
use Flagbearer\Client;
use Flagbearer\FlagbearerException;
use Flagbearer\Options;
use Psr\SimpleCache\CacheInterface;
use PHPUnit\Framework\TestCase;

final class ClientTest extends TestCase
{
    private static FakeSdk $sdk;
    private float $now = 1_800_000_000.0;
    /** @var list<Client> */
    private array $clients = [];
    private ?string $directory = null;

    public static function setUpBeforeClass(): void
    {
        self::$sdk = new FakeSdk();
    }

    public static function tearDownAfterClass(): void
    {
        self::$sdk->stop();
    }

    protected function setUp(): void
    {
        self::$sdk->reset();
    }

    protected function tearDown(): void
    {
        foreach ($this->clients as $client) {
            $client->close();
        }
        if ($this->directory !== null) {
            array_map('unlink', glob($this->directory . '/*') ?: []);
            @rmdir($this->directory);
        }
    }

    private function client(?CacheInterface $cache = null, ?string $url = null, bool $debug = false, ?string $appVersion = null): Client
    {
        $client = Client::create('fbs_test', new Options(
            url: $url ?? self::$sdk->url,
            cache: $cache,
            ttl: 30,
            debug: $debug,
            appVersion: $appVersion,
            clock: fn (): float => $this->now,
        ));
        $this->clients[] = $client;
        return $client;
    }

    private function fileCache(): FileCache
    {
        $this->directory ??= sys_get_temp_dir() . '/flagbearer-test-' . bin2hex(random_bytes(4));
        return new FileCache($this->directory);
    }

    private const USER = ['key' => 'user-1'];

    public function testDefaultsUntilRulesArriveThenLocalRules(): void
    {
        self::$sdk->set(['rulesetStatuses' => [503]]);
        $client = $this->client();
        self::assertTrue($client->isEnabled('checkout', self::USER, true));
        self::assertFalse($client->isEnabled('checkout', self::USER, false));
        // The failure backs off: the second check didn't ask again.
        self::assertCount(1, self::$sdk->rulesetRequests());

        $this->now += 31;
        self::assertTrue($client->isEnabled('checkout', self::USER, false));
        self::assertTrue($client->isEnabled('missing', self::USER, true));
        self::assertCount(2, self::$sdk->rulesetRequests());
    }

    public function testExpiredRulesRefetchWithIfNoneMatchAnd304RenewsThem(): void
    {
        $client = $this->client();
        $client->sync();
        $client->isEnabled('checkout', self::USER);
        self::assertSame([null], self::$sdk->rulesetRequests());

        $this->now += 31;
        self::assertTrue($client->isEnabled('checkout', self::USER));
        self::assertSame([null, '"1"'], self::$sdk->rulesetRequests());

        $this->now += 10;
        $client->isEnabled('checkout', self::USER);
        self::assertCount(2, self::$sdk->rulesetRequests());

        // A 304 is a success: the next expiry asks again instead of backing off.
        $this->now += 21;
        $client->isEnabled('checkout', self::USER);
        self::assertCount(3, self::$sdk->rulesetRequests());
    }

    public function testChecksWithinTheTtlDontReadTheCache(): void
    {
        $cache = new HeldLockCache();
        $client = $this->client($cache);
        $client->isEnabled('checkout', self::USER);
        $reads = $cache->reads;
        $client->isEnabled('checkout', self::USER);
        $client->isEnabled('missing', self::USER);
        self::assertSame($reads, $cache->reads);
    }

    public function testExpiredRulesPickUpChanges(): void
    {
        $client = $this->client();
        $client->sync();
        self::$sdk->set(['version' => 2, 'flags' => [FakeSdk::rule('checkout', false)]]);
        self::assertTrue($client->isEnabled('checkout', self::USER));
        $this->now += 31;
        self::assertFalse($client->isEnabled('checkout', self::USER, true));
    }

    public function testCachedRulesAreServedWhileAnotherRequestRefreshes(): void
    {
        $cache = new HeldLockCache();
        $client = $this->client($cache);
        $client->sync();
        self::$sdk->set(['version' => 2, 'flags' => [FakeSdk::rule('checkout', false)]]);
        $this->now += 31;
        $cache->held = true;
        self::assertTrue($client->isEnabled('checkout', self::USER));
        self::assertCount(1, self::$sdk->rulesetRequests());

        $cache->held = false;
        self::assertFalse($client->isEnabled('checkout', self::USER, true));
    }

    public function testAFailedRefreshServesCachedRulesAndHonorsRetryAfter(): void
    {
        $client = $this->client();
        $client->sync();
        self::$sdk->set(['rulesetStatuses' => [503], 'retryAfter' => 120]);
        $this->now += 31;
        self::assertTrue($client->isEnabled('checkout', self::USER));
        self::assertCount(2, self::$sdk->rulesetRequests());

        $this->now += 60;
        $client->isEnabled('checkout', self::USER);
        self::assertCount(2, self::$sdk->rulesetRequests());

        $this->now += 61;
        $client->isEnabled('checkout', self::USER);
        self::assertCount(3, self::$sdk->rulesetRequests());
    }

    public function testAPermanentFailureKeepsTheRulesAndBacksOff(): void
    {
        $client = $this->client();
        $client->sync();
        self::$sdk->set([
            'version' => 2,
            'rulesetBody' => '{"environmentId":"env-1","version":2,"flags":[{"key":"checkout"}]}',
        ]);
        try {
            $client->sync();
            self::fail('Expected a failure');
        } catch (FlagbearerException $error) {
            self::assertTrue($error->isPermanent());
        }
        self::assertTrue($client->isEnabled('checkout', self::USER));
        $this->now += 31;
        $client->isEnabled('checkout', self::USER);
        self::assertCount(2, self::$sdk->rulesetRequests());

        // An explicit sync tries again despite the backoff.
        self::$sdk->set(['rulesetBody' => null, 'flags' => [FakeSdk::rule('checkout', false)]]);
        $client->sync();
        self::assertFalse($client->isEnabled('checkout', self::USER, true));
    }

    public function testARulesetForAnotherEnvironmentIsRejected(): void
    {
        $client = $this->client();
        $client->sync();
        self::$sdk->set(['environmentId' => 'env-2', 'version' => 2, 'flags' => [FakeSdk::rule('checkout', false)]]);
        $this->expectExceptionMessage('environment changed');
        try {
            $client->sync();
        } finally {
            self::assertTrue($client->isEnabled('checkout', self::USER));
        }
    }

    public function testARedirectIsAPermanentFailure(): void
    {
        $client = $this->client(url: self::$sdk->url . '/redirect');
        try {
            $client->sync();
            self::fail('Expected a failure');
        } catch (FlagbearerException $error) {
            self::assertTrue($error->isPermanent());
        }
        self::assertSame([], self::$sdk->rulesetRequests());
    }

    public function testRequestsSharingAFileCacheShareRules(): void
    {
        $first = $this->client($this->fileCache());
        $first->sync();
        $second = $this->client($this->fileCache());
        self::assertTrue($second->isEnabled('checkout', self::USER));
        self::assertCount(1, self::$sdk->rulesetRequests());

        // One request refreshes; the other picks up its result without asking.
        self::$sdk->set(['version' => 2, 'flags' => [FakeSdk::rule('checkout', false)]]);
        $this->now += 31;
        self::assertFalse($second->isEnabled('checkout', self::USER, true));
        self::assertFalse($first->isEnabled('checkout', self::USER, true));
        self::assertCount(2, self::$sdk->rulesetRequests());
    }

    public function testTheFileCacheLocksOutASecondRefresh(): void
    {
        $cache = $this->fileCache();
        self::assertTrue($cache->add('lock', 1, 10));
        self::assertFalse($cache->add('lock', 2, 10));
        self::assertSame(1, $cache->get('lock'));
        $cache->delete('lock');
        self::assertTrue($cache->add('lock', 3, 10));
    }

    public function testExposuresCountRepeatedChecksAndBatchByUser(): void
    {
        $client = $this->client(appVersion: 'build-7');
        $client->sync();
        for ($i = 0; $i < 3; $i++) {
            $client->isEnabled('checkout', ['key' => 'user-1', 'email' => ' Ann@Example.com ']);
        }
        $client->isEnabled('checkout', ['key' => 'user-2']);
        $client->flush();

        $byUser = [];
        foreach (self::$sdk->exposures() as $upload) {
            self::assertIsArray($upload['user']);
            self::assertIsString($upload['user']['key']);
            $byUser[$upload['user']['key']] = $upload;
            self::assertSame(['php', '0.1.0', 'build-7'], [$upload['sdk'], $upload['sdkVersion'], $upload['appVersion']]);
        }
        $first = $byUser['user-1'];
        self::assertSame(['key' => 'user-1', 'email' => 'ann@example.com'], $first['user']);
        self::assertIsArray($first['events']);
        self::assertSame([
            'flagKey' => 'checkout',
            'result' => true,
            'reason' => 'rollout',
            'configurationVersion' => 1,
            'bucket' => '2027-01-15T08:00:00.000Z',
            'checkCount' => 3,
            'firstCheckedAt' => '2027-01-15T08:00:00.000Z',
            'lastCheckedAt' => '2027-01-15T08:00:00.000Z',
        ], $first['events'][0]);
        self::assertIsArray($byUser['user-2']['events']);
        self::assertIsArray($byUser['user-2']['events'][0]);
        self::assertSame(1, $byUser['user-2']['events'][0]['checkCount']);
        self::assertSame($first['reporterId'], $byUser['user-2']['reporterId']);
    }

    public function testAFailedUploadRetriesTheSameBatch(): void
    {
        $client = $this->client();
        $client->sync();
        $client->isEnabled('checkout', self::USER);
        self::$sdk->set(['exposureStatuses' => [500]]);
        try {
            $client->flush();
            self::fail('Expected the first upload to fail');
        } catch (FlagbearerException) {
        }
        $client->flush();
        $uploads = self::$sdk->exposures();
        self::assertCount(2, $uploads);
        self::assertSame($uploads[0]['batchId'], $uploads[1]['batchId']);
    }

    public function testARefusedUploadIsDroppedAndCountedAsLost(): void
    {
        $client = $this->client();
        $client->sync();
        $client->isEnabled('checkout', self::USER);
        $client->isEnabled('checkout', self::USER);
        self::$sdk->set(['exposureStatuses' => [400]]);
        try {
            $client->flush();
            self::fail('Expected the upload to fail');
        } catch (FlagbearerException) {
        }
        $client->flush();
        $last = self::$sdk->exposures()[1];
        self::assertSame([], $last['events']);
        self::assertSame(2, $last['droppedChecks']);
    }

    public function testChecksThatCantBeRecordedAreCountedAsLost(): void
    {
        $client = $this->client();
        $client->sync();
        self::assertTrue($client->isEnabled(str_repeat('x', 257), self::USER, true));
        // 200 emoji are 400 UTF-16 units: past the server's 256 limit.
        self::assertTrue($client->isEnabled('checkout', ['key' => str_repeat('😀', 200)], true));
        self::assertTrue($client->isEnabled('checkout', ['email' => 5], true));
        $client->flush();
        $last = self::$sdk->exposures()[0];
        self::assertSame([], $last['events']);
        self::assertSame(3, $last['droppedChecks']);
    }

    public function testFlushWithNothingPendingSendsNothing(): void
    {
        $this->client()->flush();
        self::assertSame([], self::$sdk->exposures());
    }

    public function testCloseFlushesOnceAndKeepsRulesUsable(): void
    {
        $client = $this->client();
        $client->sync();
        $client->isEnabled('checkout', self::USER);
        $client->close();
        $client->close();
        self::assertCount(1, self::$sdk->exposures());
        self::assertTrue($client->isEnabled('checkout', self::USER));
        $this->expectException(\LogicException::class);
        $client->sync();
    }

    public function testDebugLinesMatchTheOtherSdks(): void
    {
        $log = (string) tempnam(sys_get_temp_dir(), 'flagbearer-log-');
        $previous = ini_set('error_log', $log);
        try {
            $this->client(debug: true)->sync();
        } finally {
            ini_set('error_log', (string) $previous);
        }
        $output = (string) file_get_contents($log);
        @unlink($log);
        self::assertStringContainsString('[Flagbearer] Snapshot applied { version: 1, previousVersion: undefined }', $output);
        self::assertStringNotContainsString('fbs_test', $output);
    }

    public function testCreateRejectsBadKeysAndUrls(): void
    {
        foreach ([['', Options::DEFAULT_URL], ['fbs_ bad', Options::DEFAULT_URL], ['fbs_test', 'ftp://example.com']] as [$key, $url]) {
            try {
                Client::create($key, new Options(url: $url));
                self::fail("Accepted {$key} {$url}");
            } catch (\InvalidArgumentException) {
                self::addToAssertionCount(1);
            }
        }
    }
}

/** A cache that counts reads, where another request holds the refresh lock while $held is true. */
final class HeldLockCache implements CacheInterface, AtomicAdd
{
    public bool $held = false;
    public int $reads = 0;
    private readonly MemoryCache $inner;

    public function __construct()
    {
        $this->inner = new MemoryCache();
    }

    public function add(string $key, mixed $value, int $ttl): bool
    {
        return !$this->held && $this->inner->add($key, $value, $ttl);
    }

    public function get(string $key, mixed $default = null): mixed
    {
        $this->reads++;
        return $this->inner->get($key, $default);
    }

    public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool
    {
        return $this->inner->set($key, $value, $ttl);
    }

    public function delete(string $key): bool
    {
        return $this->inner->delete($key);
    }

    public function clear(): bool
    {
        return $this->inner->clear();
    }

    /**
     * @param iterable<string> $keys
     * @return iterable<string, mixed>
     */
    public function getMultiple(iterable $keys, mixed $default = null): iterable
    {
        return $this->inner->getMultiple($keys, $default);
    }

    /** @param iterable<string, mixed> $values */
    public function setMultiple(iterable $values, null|int|\DateInterval $ttl = null): bool
    {
        return $this->inner->setMultiple($values, $ttl);
    }

    /** @param iterable<string> $keys */
    public function deleteMultiple(iterable $keys): bool
    {
        return $this->inner->deleteMultiple($keys);
    }

    public function has(string $key): bool
    {
        return $this->inner->has($key);
    }
}
