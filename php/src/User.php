<?php

declare(strict_types=1);

namespace Flagbearer;

/** Who a flag is checked for. Both fields are optional; null or empty means unset. */
final class User
{
    public function __construct(
        public readonly ?string $key = null,
        public readonly ?string $email = null,
    ) {
    }

    /**
     * Accepts a User, an array with 'key' and 'email', or null.
     *
     * @param User|array<mixed>|null $user
     */
    public static function from(User|array|null $user): self
    {
        if ($user instanceof self) {
            return $user;
        }
        if ($user === null) {
            return new self();
        }
        $key = $user['key'] ?? null;
        $email = $user['email'] ?? null;
        if (($key !== null && !is_string($key)) || ($email !== null && !is_string($email))) {
            throw new \InvalidArgumentException('User key and email must be strings');
        }
        return new self($key, $email);
    }
}
