<?php

declare(strict_types=1);

namespace Flagbearer;

/**
 * A failed request to Flagbearer. Permanent failures, such as a revoked key or
 * an invalid ruleset, won't be fixed by retrying.
 */
final class FlagbearerException extends \RuntimeException
{
    public function __construct(
        string $message,
        private readonly bool $retryable,
        private readonly float $retryAfter = 0.0,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($message, 0, $previous);
    }

    public function isPermanent(): bool
    {
        return !$this->retryable;
    }

    /** Seconds the server asked us to wait, from Retry-After. */
    public function retryAfter(): float
    {
        return $this->retryAfter;
    }
}
