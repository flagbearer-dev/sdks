<?php

declare(strict_types=1);

namespace Flagbearer\Cache;

use Psr\SimpleCache\CacheInterface;

/**
 * Lasts as long as the process: right for long-running workers and tests. Under
 * PHP-FPM each request starts empty, so pass FileCache, APCu or Redis there.
 */
final class MemoryCache implements CacheInterface, AtomicAdd
{
    /** @var array<string, array{value: mixed, expiresAt: float|null}> */
    private array $items = [];

    public function get(string $key, mixed $default = null): mixed
    {
        InvalidKey::check($key);
        $item = $this->items[$key] ?? null;
        if ($item === null || ($item['expiresAt'] !== null && $item['expiresAt'] <= microtime(true))) {
            unset($this->items[$key]);
            return $default;
        }
        return $item['value'];
    }

    public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool
    {
        InvalidKey::check($key);
        $this->items[$key] = ['value' => $value, 'expiresAt' => self::expiry($ttl)];
        return true;
    }

    public function add(string $key, mixed $value, int $ttl): bool
    {
        if ($this->has($key)) {
            return false;
        }
        return $this->set($key, $value, $ttl);
    }

    public function delete(string $key): bool
    {
        InvalidKey::check($key);
        unset($this->items[$key]);
        return true;
    }

    public function clear(): bool
    {
        $this->items = [];
        return true;
    }

    /**
     * @param iterable<string> $keys
     * @return array<string, mixed>
     */
    public function getMultiple(iterable $keys, mixed $default = null): iterable
    {
        $values = [];
        foreach ($keys as $key) {
            $values[$key] = $this->get($key, $default);
        }
        return $values;
    }

    /** @param iterable<string, mixed> $values */
    public function setMultiple(iterable $values, null|int|\DateInterval $ttl = null): bool
    {
        foreach ($values as $key => $value) {
            $this->set($key, $value, $ttl);
        }
        return true;
    }

    /** @param iterable<string> $keys */
    public function deleteMultiple(iterable $keys): bool
    {
        foreach ($keys as $key) {
            $this->delete($key);
        }
        return true;
    }

    public function has(string $key): bool
    {
        return $this->get($key, $this) !== $this;
    }

    public static function expiry(null|int|\DateInterval $ttl): ?float
    {
        if ($ttl === null) {
            return null;
        }
        if ($ttl instanceof \DateInterval) {
            $now = new \DateTimeImmutable();
            return (float) $now->add($ttl)->format('U.u');
        }
        return microtime(true) + $ttl;
    }
}
