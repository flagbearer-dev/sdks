<?php

declare(strict_types=1);

namespace Flagbearer;

use Psr\SimpleCache\CacheInterface;

final class Options
{
    public const DEFAULT_URL = 'https://sdk.flagbearer.dev';

    /**
     * @param string $url The SDK server address, for staging or local development.
     * @param CacheInterface|null $cache Where rules are shared between requests. Pass an
     *   APCu or Redis PSR-16 cache, or Cache\FileCache, under PHP-FPM; the default
     *   in-memory cache only lasts as long as the process.
     * @param float $ttl Seconds before cached rules are checked for changes.
     * @param bool $debug Log refreshes, retries and applied rules through error_log.
     * @param string|null $appVersion Labels which deployment of your app reported each check.
     * @param (\Closure(): float)|null $clock Internal: the current Unix time in seconds, for tests.
     */
    public function __construct(
        public readonly string $url = self::DEFAULT_URL,
        public readonly ?CacheInterface $cache = null,
        public readonly float $ttl = 30.0,
        public readonly bool $debug = false,
        public readonly ?string $appVersion = null,
        public readonly ?\Closure $clock = null,
    ) {
    }
}
