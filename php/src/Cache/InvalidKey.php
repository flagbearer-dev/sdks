<?php

declare(strict_types=1);

namespace Flagbearer\Cache;

/** @internal */
final class InvalidKey extends \InvalidArgumentException implements \Psr\SimpleCache\InvalidArgumentException
{
    public static function check(string $key): void
    {
        if ($key === '' || preg_match('/[{}()\/\\\\@:]/', $key) === 1) {
            throw new self("Invalid cache key: {$key}");
        }
    }
}
