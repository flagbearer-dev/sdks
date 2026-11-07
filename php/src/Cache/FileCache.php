<?php

declare(strict_types=1);

namespace Flagbearer\Cache;

use Psr\SimpleCache\CacheInterface;

/**
 * Shares rules between PHP-FPM requests on one machine through files in a
 * directory. Writes go to a temporary file renamed into place, so a reader
 * never sees half a value; add() takes an exclusive lock. APCu or Redis are
 * faster when available.
 */
final class FileCache implements CacheInterface, AtomicAdd
{
    public function __construct(private readonly string $directory)
    {
        if (!is_dir($directory) && !@mkdir($directory, 0700, true) && !is_dir($directory)) {
            throw new \InvalidArgumentException("Can't create cache directory {$directory}");
        }
    }

    public function get(string $key, mixed $default = null): mixed
    {
        $item = $this->read($this->path($key));
        return $item === null ? $default : $item['value'];
    }

    public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool
    {
        $path = $this->path($key);
        $temporary = $path . '.' . bin2hex(random_bytes(6)) . '.tmp';
        $data = serialize(['value' => $value, 'expiresAt' => MemoryCache::expiry($ttl)]);
        if (@file_put_contents($temporary, $data) === false) {
            return false;
        }
        if (!@rename($temporary, $path)) {
            @unlink($temporary);
            return false;
        }
        return true;
    }

    public function add(string $key, mixed $value, int $ttl): bool
    {
        $path = $this->path($key);
        $lock = @fopen($path . '.lock', 'c');
        if ($lock === false) {
            return false;
        }
        try {
            if (!flock($lock, LOCK_EX)) {
                return false;
            }
            if ($this->read($path) !== null) {
                return false;
            }
            return $this->set($key, $value, $ttl);
        } finally {
            flock($lock, LOCK_UN);
            fclose($lock);
        }
    }

    public function delete(string $key): bool
    {
        $path = $this->path($key);
        return !file_exists($path) || @unlink($path) || !file_exists($path);
    }

    public function clear(): bool
    {
        foreach (glob($this->directory . '/*.cache') ?: [] as $path) {
            @unlink($path);
        }
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
        $ok = true;
        foreach ($values as $key => $value) {
            $ok = $this->set($key, $value, $ttl) && $ok;
        }
        return $ok;
    }

    /** @param iterable<string> $keys */
    public function deleteMultiple(iterable $keys): bool
    {
        $ok = true;
        foreach ($keys as $key) {
            $ok = $this->delete($key) && $ok;
        }
        return $ok;
    }

    public function has(string $key): bool
    {
        return $this->read($this->path($key)) !== null;
    }

    private function path(string $key): string
    {
        InvalidKey::check($key);
        return $this->directory . '/' . hash('sha256', $key) . '.cache';
    }

    /** @return array{value: mixed}|null */
    private function read(string $path): ?array
    {
        $data = @file_get_contents($path);
        if ($data === false) {
            return null;
        }
        $item = @unserialize($data, ['allowed_classes' => false]);
        if (!is_array($item) || !array_key_exists('value', $item)) {
            return null;
        }
        $expiresAt = $item['expiresAt'] ?? null;
        if (is_float($expiresAt) && $expiresAt <= microtime(true)) {
            return null;
        }
        return ['value' => $item['value']];
    }
}
