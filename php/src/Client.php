<?php

declare(strict_types=1);

namespace Flagbearer;

use Flagbearer\Cache\AtomicAdd;
use Flagbearer\Cache\MemoryCache;
use Psr\SimpleCache\CacheInterface;

/**
 * Checks flags against rules shared through a cache. Rules older than the TTL
 * are checked for changes by one request at a time; the others keep using the
 * cached rules. Checks are reported once, at the end of the request.
 *
 * @phpstan-import-type Ruleset from Evaluator
 * @phpstan-type Entry array{ruleset: Ruleset, expiresAt: float}
 * @phpstan-type State array{nextFetchAt: float, failures: int}
 */
final class Client
{
    // Long enough for a slow fetch to finish; short enough that a crashed request doesn't block refreshes for long.
    private const LOCK_SECONDS = 10;
    private const PERMANENT_BACKOFF = 60.0;

    private readonly string $rulesetUrl;
    private readonly CacheInterface $cache;
    private readonly string $prefix;
    private readonly DebugLog $debug;
    private readonly ExposureReporter $reporter;
    /** @var \Closure(): float */
    private readonly \Closure $clock;
    /** @var Entry|null */
    private ?array $entry = null;
    private bool $closed = false;

    private function __construct(private readonly string $key, private readonly Options $options)
    {
        if ($key === '' || preg_match('/\s/u', $key) === 1) {
            throw new \InvalidArgumentException('A nonempty server SDK key is required');
        }
        $parts = parse_url($options->url);
        $scheme = is_array($parts) ? strtolower($parts['scheme'] ?? '') : '';
        if (!is_array($parts) || !in_array($scheme, ['http', 'https'], true) || ($parts['host'] ?? '') === '') {
            throw new \InvalidArgumentException('Flagbearer url must use HTTP or HTTPS');
        }
        $base = rtrim(explode('?', $options->url, 2)[0], '/');
        $this->rulesetUrl = $base . '/v1/ruleset';
        $this->cache = $options->cache ?? new MemoryCache();
        // One key per environment and SDK address, without putting the secret key in the cache.
        $this->prefix = 'flagbearer.' . substr(hash('sha256', $base . "\n" . $key), 0, 32);
        $this->debug = new DebugLog($options->debug);
        $this->clock = $options->clock ?? static fn (): float => microtime(true);
        $this->reporter = new ExposureReporter($base . '/v1/exposures', $key, $options->appVersion, $this->clock);
    }

    /**
     * Creates a client. It never makes a request here: rules load on the first check.
     * Pending checks are uploaded when the request ends.
     */
    public static function create(string $key, ?Options $options = null): self
    {
        $client = new self($key, $options ?? new Options());
        register_shutdown_function(static function () use ($client): void {
            if ($client->reporter->hasPending() && function_exists('fastcgi_finish_request')) {
                // Send the response first, so uploading never delays it.
                fastcgi_finish_request();
            }
            $client->close();
        });
        return $client;
    }

    /**
     * Checks a flag. Never throws. The default is used until rules are available,
     * and when the flag doesn't exist or can't be evaluated.
     *
     * @param User|array<mixed>|null $user
     */
    public function isEnabled(string $flag, User|array|null $user = null, bool $default = false): bool
    {
        try {
            $user = User::from($user);
        } catch (\Throwable) {
            $this->reporter->drop();
            return $default;
        }
        $this->refreshIfStale();
        $ruleset = $this->entry['ruleset'] ?? null;
        $version = $ruleset['version'] ?? null;
        try {
            $details = Evaluator::evaluate($flag, $ruleset['flags'][$flag] ?? null, $user, $version, $default);
        } catch (\Throwable) {
            $details = ['value' => $default, 'reason' => 'fallback', 'version' => $version];
        }
        $this->reporter->record($flag, $user, $details);
        return $details['value'];
    }

    /** Fetches the latest rules now, ignoring the TTL and any backoff. Throws when the request fails. */
    public function sync(): void
    {
        if ($this->closed) {
            throw new \LogicException('Flagbearer client is closed');
        }
        $this->entry = $this->readEntry() ?? $this->entry;
        $this->fetch();
    }

    /** Uploads pending exposure reports. Throws when an upload fails. */
    public function flush(): void
    {
        $this->reporter->flush();
    }

    /** Uploads pending reports once and stops reporting. Safe to call more than once. */
    public function close(): void
    {
        if ($this->closed) {
            return;
        }
        $this->closed = true;
        $this->reporter->close();
    }

    private function now(): float
    {
        return ($this->clock)();
    }

    private function refreshIfStale(): void
    {
        if ($this->entry !== null && $this->now() < $this->entry['expiresAt']) {
            return;
        }
        try {
            // Another request may have refreshed the cache already.
            $this->entry = $this->readEntry() ?? $this->entry;
            if ($this->entry !== null && $this->now() < $this->entry['expiresAt']) {
                return;
            }
            $state = $this->readState();
            if ($state !== null && $this->now() < $state['nextFetchAt']) {
                return;
            }
            if (!$this->lock()) {
                ($this->debug)('Refresh in progress elsewhere; using cached rules');
                return;
            }
            try {
                $this->fetch();
            } finally {
                $this->cache->delete($this->prefix . '.lock');
            }
        } catch (FlagbearerException $error) {
            error_log('[Flagbearer] Refresh failed: ' . $error->getMessage());
        } catch (\Throwable $error) {
            // A broken cache must never break a flag check.
            error_log('[Flagbearer] Refresh failed: ' . $error->getMessage());
        }
    }

    private function fetch(): void
    {
        ($this->debug)('Refresh started');
        $current = $this->entry['ruleset'] ?? null;
        $headers = ['Authorization' => "Bearer {$this->key}"];
        if ($current !== null) {
            $headers['If-None-Match'] = '"' . $current['version'] . '"';
        }
        try {
            $response = Http::request('GET', $this->rulesetUrl, $headers, null, 5.0);
            Http::check($response, $this->now());
            if ($response['status'] === 304 && $current !== null) {
                ($this->debug)('Snapshot unchanged', ['version' => $current['version']]);
                $next = $current;
            } else {
                $next = Evaluator::parseRuleset($response['body']);
                if ($next === null) {
                    throw new FlagbearerException('Invalid ruleset response', false);
                }
                if ($current !== null && $next['environmentId'] !== $current['environmentId']) {
                    throw new FlagbearerException('Ruleset environment changed unexpectedly', false);
                }
                if ($current !== null && $next['version'] < $current['version']) {
                    ($this->debug)('Older snapshot ignored', [
                        'version' => $next['version'],
                        'currentVersion' => $current['version'],
                    ]);
                    $next = $current;
                } else {
                    ($this->debug)('Snapshot applied', [
                        'version' => $next['version'],
                        'previousVersion' => $current['version'] ?? null,
                    ]);
                }
            }
        } catch (FlagbearerException $error) {
            $this->backOff($error);
            throw $error;
        }
        $this->entry = ['ruleset' => $next, 'expiresAt' => $this->now() + $this->options->ttl];
        // Kept without a cache TTL: stale rules beat no rules while Flagbearer is unreachable.
        $this->cache->set($this->prefix . '.rules', $this->entry);
        $this->cache->delete($this->prefix . '.state');
        ($this->debug)('Cache refreshed', ['ttlSeconds' => $this->options->ttl]);
    }

    private function backOff(FlagbearerException $error): void
    {
        $failures = ($this->readState()['failures'] ?? 0) + 1;
        $delay = $error->isPermanent()
            ? self::PERMANENT_BACKOFF
            : Http::backoff($failures - 1);
        $delay = max($delay, $error->retryAfter());
        ($this->debug)('Refresh retry scheduled', [
            'delayMs' => (int) round($delay * 1000),
            'attempt' => $failures,
            'permanent' => $error->isPermanent(),
        ]);
        $this->cache->set(
            $this->prefix . '.state',
            ['nextFetchAt' => $this->now() + $delay, 'failures' => $failures],
            (int) ceil($delay) + 60,
        );
    }

    /** Only one request refreshes at a time. */
    private function lock(): bool
    {
        $key = $this->prefix . '.lock';
        if ($this->cache instanceof AtomicAdd) {
            return $this->cache->add($key, 1, self::LOCK_SECONDS);
        }
        // Best effort for caches without an atomic add: a rare double fetch is harmless.
        if ($this->cache->has($key)) {
            return false;
        }
        $token = bin2hex(random_bytes(8));
        $this->cache->set($key, $token, self::LOCK_SECONDS);
        return $this->cache->get($key) === $token;
    }

    /** @return Entry|null */
    private function readEntry(): ?array
    {
        $entry = $this->cache->get($this->prefix . '.rules');
        if (!is_array($entry) || !is_array($entry['ruleset'] ?? null) || !is_float($entry['expiresAt'] ?? null)) {
            return null;
        }
        // Rules in the cache were validated before they were stored.
        /** @var Entry $entry */
        $current = $this->entry['ruleset']['version'] ?? -1;
        return $entry['ruleset']['version'] >= $current ? $entry : null;
    }

    /** @return State|null */
    private function readState(): ?array
    {
        $state = $this->cache->get($this->prefix . '.state');
        if (!is_array($state) || !is_float($state['nextFetchAt'] ?? null) || !is_int($state['failures'] ?? null)) {
            return null;
        }
        /** @var State $state */
        return $state;
    }
}
