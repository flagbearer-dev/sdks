<?php

declare(strict_types=1);

namespace Flagbearer\Cache;

/**
 * A cache that can store a value only if the key is absent, atomically. The
 * client uses it so one request refreshes the rules while others keep serving
 * the cached ones. Other PSR-16 caches get a best-effort check-then-set.
 */
interface AtomicAdd
{
    public function add(string $key, mixed $value, int $ttl): bool;
}
