<?php

declare(strict_types=1);

namespace Flagbearer;

/**
 * Aggregates checks in memory and uploads them in per-user batches. A PHP
 * request uploads once at its end; a long-running worker calls flush().
 *
 * @internal
 *
 * @phpstan-import-type Evaluation from Evaluator
 * @phpstan-type Who array{key?: string, email?: string}
 * @phpstan-type Event array{flagKey: string, result: bool, reason: string, configurationVersion: int|null, bucket: string, checkCount: int, firstCheckedAt: string, lastCheckedAt: string}
 * @phpstan-type Batch array{batchId: string, user: Who, events: list<Event>}
 */
final class ExposureReporter
{
    private const WINDOW_MS = 30_000;
    private const MAX_RECORDS = 1000;
    private const BATCH_SIZE = 100;
    private const MAX_CHECK_COUNT = 1_000_000_000;

    private readonly string $reporterId;
    /** @var array<string, array{user: Who, event: Event}> */
    private array $active = [];
    /** @var list<Batch> */
    private array $sealed = [];
    private int $sealedEvents = 0;
    private int $dropped = 0;
    private int $reportedDropped = 0;
    private bool $closed = false;

    /** @param \Closure(): float $clock */
    public function __construct(
        private readonly string $url,
        private readonly string $key,
        private readonly ?string $appVersion,
        private readonly \Closure $clock,
    ) {
        $this->reporterId = self::uuid();
    }

    public function hasPending(): bool
    {
        return $this->active !== [] || $this->sealed !== [] || $this->dropped > $this->reportedDropped;
    }

    /** Counts a check that couldn't be evaluated or recorded. */
    public function drop(): void
    {
        if (!$this->closed) {
            $this->dropped++;
        }
    }

    /** @param Evaluation $details */
    public function record(string $flag, User $user, array $details): void
    {
        if ($this->closed) {
            return;
        }
        try {
            $key = $user->key ?? '';
            if ($flag === '' || Evaluator::jsLength($flag) > 256 || Evaluator::jsLength($key) > 256) {
                $this->dropped++;
                return;
            }
            $nowMs = (int) floor(($this->clock)() * 1000);
            $bucket = self::iso(intdiv($nowMs, self::WINDOW_MS) * self::WINDOW_MS);
            $raw = $user->email ?? '';
            $email = $raw !== '' && Evaluator::jsLength($raw) <= 254 ? Evaluator::normalizeEmail($raw) : '';
            $who = [];
            if ($key !== '') {
                $who['key'] = $key;
            }
            if ($email !== '') {
                $who['email'] = $email;
            }
            $identity = json_encode(
                [$flag, $key, $email, $details['value'], $details['reason'], $details['version'], $bucket],
                JSON_THROW_ON_ERROR,
            );
            $existing = $this->active[$identity] ?? null;
            if ($existing !== null) {
                if ($existing['event']['checkCount'] < self::MAX_CHECK_COUNT) {
                    $this->active[$identity]['event']['checkCount']++;
                    $this->active[$identity]['event']['lastCheckedAt'] = self::iso($nowMs);
                } else {
                    $this->dropped++;
                }
                return;
            }
            if (count($this->active) + $this->sealedEvents >= self::MAX_RECORDS) {
                $this->dropped++;
                return;
            }
            $this->active[$identity] = ['user' => $who, 'event' => [
                'flagKey' => $flag,
                'result' => $details['value'],
                'reason' => $details['reason'],
                'configurationVersion' => $details['version'],
                'bucket' => $bucket,
                'checkCount' => 1,
                'firstCheckedAt' => self::iso($nowMs),
                'lastCheckedAt' => self::iso($nowMs),
            ]];
        } catch (\Throwable) {
            $this->dropped++;
        }
    }

    /** Uploads every pending check. Throws when an upload fails; a retry resends the same batch. */
    public function flush(): void
    {
        $this->seal();
        while ($this->sealed !== [] || $this->dropped > $this->reportedDropped) {
            // Only lost checks to report: an empty batch carries the count.
            $batch = $this->sealed[0] ?? ['batchId' => self::uuid(), 'user' => [], 'events' => []];
            $dropped = $this->dropped;
            $checks = array_sum(array_column($batch['events'], 'checkCount'));
            $body = [
                'batchId' => $batch['batchId'],
                'reporterId' => $this->reporterId,
                'user' => (object) $batch['user'],
                'events' => $batch['events'],
                'droppedChecks' => $dropped,
                'sdk' => 'php',
                'sdkVersion' => Version::VERSION,
            ];
            if ($this->appVersion !== null && $this->appVersion !== '') {
                $body['appVersion'] = mb_substr($this->appVersion, 0, 100);
            }
            $response = Http::request('POST', $this->url, [
                'Authorization' => "Bearer {$this->key}",
                'Content-Type' => 'application/json',
            ], json_encode($body, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES), 10.0);
            $status = $response['status'];
            if ($status < 200 || $status >= 300) {
                if ($status >= 400 && $status < 500 && $status !== 429) {
                    // The server won't take this batch, so retrying can't help.
                    $this->dropped += $checks;
                    $this->settle($batch);
                }
                throw new FlagbearerException("Exposure upload failed ({$status})", $status === 429 || $status >= 500);
            }
            $this->dropped += self::receipt($response['body'], $checks, count($batch['events']));
            $this->reportedDropped = max($this->reportedDropped, $dropped);
            $this->settle($batch);
        }
    }

    /** Uploads once more and stops recording. Upload failures are swallowed. */
    public function close(): void
    {
        if ($this->closed) {
            return;
        }
        $this->closed = true;
        try {
            $this->flush();
        } catch (\Throwable $error) {
            error_log('[Flagbearer] Exposure upload failed: ' . $error->getMessage());
        }
    }

    /** Moves active checks into batches. A batch never mixes users. */
    private function seal(): void
    {
        $open = [];
        foreach ($this->active as $item) {
            $userId = json_encode([$item['user']['key'] ?? null, $item['user']['email'] ?? null]);
            $index = $open[$userId] ?? null;
            if ($index === null || count($this->sealed[$index]['events']) >= self::BATCH_SIZE) {
                $this->sealed[] = ['batchId' => self::uuid(), 'user' => $item['user'], 'events' => []];
                $index = array_key_last($this->sealed);
                $open[$userId] = $index;
            }
            $this->sealed[$index]['events'][] = $item['event'];
            $this->sealedEvents++;
        }
        $this->active = [];
    }

    /** @param Batch $batch */
    private function settle(array $batch): void
    {
        if (($this->sealed[0]['batchId'] ?? null) === $batch['batchId']) {
            array_shift($this->sealed);
            $this->sealedEvents -= count($batch['events']);
        }
    }

    private static function receipt(string $body, int $checks, int $events): int
    {
        try {
            $receipt = json_decode($body, false, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $receipt = null;
        }
        $accepted = $receipt instanceof \stdClass ? Evaluator::integer($receipt->accepted ?? null) : null;
        $rejected = $receipt instanceof \stdClass ? Evaluator::integer($receipt->rejectedChecks ?? null) : null;
        if ($accepted === null || $rejected === null || $accepted < 0 || $accepted > $events
            || $rejected < 0 || $rejected > $checks) {
            throw new FlagbearerException('Invalid exposure upload receipt', false);
        }
        return $rejected;
    }

    private static function iso(int $ms): string
    {
        $seconds = intdiv($ms, 1000);
        return gmdate('Y-m-d\TH:i:s', $seconds) . sprintf('.%03dZ', $ms - $seconds * 1000);
    }

    private static function uuid(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr(ord($bytes[6]) & 0x0f | 0x40);
        $bytes[8] = chr(ord($bytes[8]) & 0x3f | 0x80);
        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($bytes), 4));
    }
}
