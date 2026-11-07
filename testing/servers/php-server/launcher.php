<?php

// Runs the PHP SDK behind the same GET /flags and GET /flush routes as
// node-server.ts. Each HTTP request is a fresh PHP request under `php -S`,
// sharing rules through a file cache, the way PHP-FPM would.

declare(strict_types=1);

require __DIR__ . '/../../../php/vendor/autoload.php';

use Flagbearer\Cache\FileCache;
use Flagbearer\Client;
use Flagbearer\Options;

$key = getenv('FLAGBEARER_SERVER_KEY');
if (!is_string($key) || $key === '') {
    fwrite(STDERR, "The integration fixture requires FLAGBEARER_SERVER_KEY.\n");
    exit(1);
}

$port = (int) (getenv('PORT') ?: 0);
if ($port === 0) {
    $socket = stream_socket_server('tcp://127.0.0.1:0') ?: exit(1);
    $port = (int) substr((string) stream_socket_get_name($socket, false), strrpos((string) stream_socket_get_name($socket, false), ':') + 1);
    fclose($socket);
}

$cacheDirectory = sys_get_temp_dir() . '/flagbearer-php-fixture-' . bin2hex(random_bytes(6));
mkdir($cacheDirectory, 0700);
$environment = getenv();
$environment['FLAGBEARER_CACHE_DIR'] = $cacheDirectory;

// Load the rules once before serving, like the other fixtures' sync at startup.
try {
    $warm = Client::create($key, new Options(
        url: getenv('FLAGBEARER_URL') ?: Options::DEFAULT_URL,
        cache: new FileCache($cacheDirectory),
        ttl: 1.0,
        debug: true,
    ));
    $warm->sync();
    echo "Initial Flagbearer ruleset loaded.\n";
} catch (Throwable $error) {
    echo 'Initial sync failed; serving defaults while the SDK recovers. ' . $error->getMessage() . "\n";
}

$server = proc_open(
    [PHP_BINARY, '-S', "127.0.0.1:{$port}", __DIR__ . '/router.php'],
    [0 => ['file', '/dev/null', 'r'], 1 => STDOUT, 2 => STDERR],
    $pipes,
    null,
    $environment,
);
if (!is_resource($server)) {
    fwrite(STDERR, "php -S failed to start\n");
    exit(1);
}

function cleanUp(string $directory): void
{
    foreach (glob($directory . '/*') ?: [] as $path) {
        @unlink($path);
    }
    @rmdir($directory);
}

pcntl_async_signals(true);
$stop = static function () use ($server, $cacheDirectory): void {
    proc_terminate($server, SIGTERM);
    proc_close($server);
    cleanUp($cacheDirectory);
    exit(0);
};
pcntl_signal(SIGTERM, $stop);
pcntl_signal(SIGINT, $stop);

$deadline = microtime(true) + 10;
while (true) {
    $connection = @stream_socket_client("tcp://127.0.0.1:{$port}", $code, $message, 0.1);
    if ($connection !== false) {
        fclose($connection);
        break;
    }
    if (microtime(true) > $deadline || !proc_get_status($server)['running']) {
        fwrite(STDERR, "php -S never accepted connections\n");
        proc_terminate($server);
        cleanUp($cacheDirectory);
        exit(1);
    }
    usleep(20_000);
}
echo "PHP SDK fixture listening on http://127.0.0.1:{$port}\n";
fflush(STDOUT);

while (proc_get_status($server)['running']) {
    usleep(200_000);
}
cleanUp($cacheDirectory);
exit(1);
