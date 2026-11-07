<?php

declare(strict_types=1);

namespace Flagbearer;

/**
 * One request over ext-curl. Never follows redirects.
 *
 * @internal
 */
final class Http
{
    /**
     * @param non-empty-string $method
     * @param array<string, string> $headers
     * @return array{status: int, headers: array<string, string>, body: string}
     */
    public static function request(
        string $method,
        string $url,
        array $headers,
        ?string $body,
        float $timeout,
    ): array {
        $handle = curl_init($url);
        $responseHeaders = [];
        $lines = [];
        foreach ($headers as $name => $value) {
            $lines[] = "{$name}: {$value}";
        }
        curl_setopt_array($handle, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_HTTPHEADER => $lines,
            CURLOPT_TIMEOUT_MS => (int) ($timeout * 1000),
            CURLOPT_CONNECTTIMEOUT_MS => (int) ($timeout * 1000),
            CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
            CURLOPT_HEADERFUNCTION => static function ($handle, string $line) use (&$responseHeaders): int {
                $parts = explode(':', $line, 2);
                if (count($parts) === 2) {
                    $responseHeaders[strtolower(trim($parts[0]))] = trim($parts[1]);
                }
                return strlen($line);
            },
        ]);
        if ($body !== null) {
            curl_setopt($handle, CURLOPT_POSTFIELDS, $body);
        }
        $result = curl_exec($handle);
        if (!is_string($result)) {
            $error = curl_error($handle);
            throw new FlagbearerException("Flagbearer request failed: {$error}", true);
        }
        $status = curl_getinfo($handle, CURLINFO_RESPONSE_CODE);
        /** @var array<string, string> $responseHeaders */
        return ['status' => $status, 'headers' => $responseHeaders, 'body' => $result];
    }

    /** Seconds to wait from a Retry-After header, in seconds or as an HTTP date. */
    public static function retryAfter(?string $header, float $now): float
    {
        if ($header === null || $header === '') {
            return 0.0;
        }
        if (is_numeric($header)) {
            $seconds = (float) $header;
            return is_finite($seconds) && $seconds > 0 ? $seconds : 0.0;
        }
        $at = strtotime($header);
        return $at === false ? 0.0 : max(0.0, $at - $now);
    }

    /**
     * Turns a status other than 2xx (and 304) into an exception. A 3xx lands here
     * too: requests never follow redirects.
     *
     * @param array{status: int, headers: array<string, string>, body: string} $response
     */
    public static function check(array $response, float $now): void
    {
        $status = $response['status'];
        if (($status >= 200 && $status < 300) || $status === 304) {
            return;
        }
        throw new FlagbearerException(
            "Flagbearer request failed: {$status}",
            $status === 408 || $status === 429 || $status >= 500 || $status === 0,
            self::retryAfter($response['headers']['retry-after'] ?? null, $now),
        );
    }

    /** 1s × 2^attempt for attempts 0–5, capped at 30s, ±20%. */
    public static function backoff(int $attempt): float
    {
        $delay = min(30.0, 2 ** min($attempt, 5));
        return min(30.0, $delay * (0.8 + mt_rand() / mt_getrandmax() * 0.4));
    }
}
