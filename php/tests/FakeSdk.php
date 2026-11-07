<?php

declare(strict_types=1);

namespace Flagbearer\Tests;

/**
 * Runs fake-sdk-router.php under `php -S` on a free port. Tests set what it
 * returns and read what it received through its state file.
 */
final class FakeSdk
{
    public readonly string $url;
    private readonly string $statePath;
    /** @var resource */
    private $process;

    public function __construct()
    {
        $this->statePath = tempnam(sys_get_temp_dir(), 'fake-sdk-') ?: throw new \RuntimeException('tempnam');
        $this->reset();
        $socket = stream_socket_server('tcp://127.0.0.1:0') ?: throw new \RuntimeException('no port');
        $name = stream_socket_get_name($socket, false) ?: throw new \RuntimeException('no port');
        fclose($socket);
        $this->url = "http://{$name}";
        $process = proc_open(
            [PHP_BINARY, '-S', $name, __DIR__ . '/fake-sdk-router.php'],
            [0 => ['file', '/dev/null', 'r'], 1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']],
            $pipes,
            null,
            ['FAKE_SDK_STATE' => $this->statePath],
        );
        if (!is_resource($process)) {
            throw new \RuntimeException('Fake SDK server failed to start');
        }
        $this->process = $process;
        $deadline = microtime(true) + 5;
        while (microtime(true) < $deadline) {
            $connection = @stream_socket_client("tcp://{$name}", $code, $message, 0.1);
            if ($connection !== false) {
                fclose($connection);
                return;
            }
            usleep(10_000);
        }
        throw new \RuntimeException('Fake SDK server never accepted connections');
    }

    public function reset(): void
    {
        $this->write([
            'version' => 1,
            'environmentId' => 'env-1',
            'flags' => [self::rule('checkout')],
            'rulesetStatuses' => [],
            'exposureStatuses' => [],
            'rulesetRequests' => [],
            'exposures' => [],
        ]);
    }

    /** @return array{key: string, enabled: bool, rolloutBps: int, overrideEmails: list<string>} */
    public static function rule(string $key, bool $enabled = true, int $rolloutBps = 10000): array
    {
        return ['key' => $key, 'enabled' => $enabled, 'rolloutBps' => $rolloutBps, 'overrideEmails' => []];
    }

    /** @param array<string, mixed> $changes */
    public function set(array $changes): void
    {
        $this->write(array_merge($this->state(), $changes));
    }

    /** @return array<mixed> */
    public function state(): array
    {
        $data = json_decode((string) file_get_contents($this->statePath), true);
        return is_array($data) ? $data : [];
    }

    /** @return list<string|null> */
    public function rulesetRequests(): array
    {
        $requests = $this->state()['rulesetRequests'] ?? [];
        return is_array($requests) ? array_values(array_filter($requests, static fn ($r) => $r === null || is_string($r))) : [];
    }

    /** @return list<array<mixed>> */
    public function exposures(): array
    {
        $uploads = $this->state()['exposures'] ?? [];
        return is_array($uploads) ? array_values(array_filter($uploads, 'is_array')) : [];
    }

    public function stop(): void
    {
        proc_terminate($this->process);
        proc_close($this->process);
        @unlink($this->statePath);
    }

    /** @param array<mixed> $state */
    private function write(array $state): void
    {
        file_put_contents($this->statePath, json_encode($state), LOCK_EX);
    }
}
