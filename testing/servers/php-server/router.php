<?php

// One fresh PHP request per HTTP request, under the launcher's `php -S`.

declare(strict_types=1);

require __DIR__ . '/../../../php/vendor/autoload.php';

use Flagbearer\Cache\FileCache;
use Flagbearer\Client;
use Flagbearer\Options;

$cacheDirectory = (string) getenv('FLAGBEARER_CACHE_DIR');
$statusFile = $cacheDirectory . '/last-upload.json';

function reply(int $status, array $body): void
{
    http_response_code($status);
    header('Content-Type: application/json');
    header('Cache-Control: no-store');
    echo json_encode($body);
}

function client(string $cacheDirectory): Client
{
    return Client::create((string) getenv('FLAGBEARER_SERVER_KEY'), new Options(
        url: getenv('FLAGBEARER_URL') ?: Options::DEFAULT_URL,
        cache: new FileCache($cacheDirectory),
        ttl: 1.0,
        debug: true,
    ));
}

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    header('Allow: GET');
    reply(405, ['error' => 'Use GET.']);
    return true;
}

$path = parse_url((string) $_SERVER['REQUEST_URI'], PHP_URL_PATH);

if ($path === '/flush') {
    // Checks are uploaded at the end of each /flags request; report whether the last upload was accepted.
    $last = is_file($statusFile) ? json_decode((string) file_get_contents($statusFile), true) : null;
    if (is_array($last) && isset($last['error'])) {
        reply(502, ['error' => $last['error']]);
        return true;
    }
    try {
        client($cacheDirectory)->flush();
        reply(200, ['flushed' => true]);
    } catch (Throwable $error) {
        reply(502, ['error' => $error->getMessage()]);
    }
    return true;
}

if ($path !== '/flags') {
    reply(404, ['error' => 'Not found.']);
    return true;
}

$flag = $_GET['flag'] ?? '';
$fallback = $_GET['default'] ?? 'false';
if (!is_string($flag) || $flag === '' || ($fallback !== 'true' && $fallback !== 'false')) {
    reply(400, ['error' => 'Provide flag, and optionally default=true or default=false.']);
    return true;
}

// Shutdown functions run in the order they're registered, so this upload runs
// before the SDK's own (which then has nothing left) and records the outcome.
$flags = null;
register_shutdown_function(static function () use (&$flags, $statusFile): void {
    if (!$flags instanceof Client) {
        return;
    }
    try {
        $flags->flush();
        file_put_contents($statusFile, json_encode(['ok' => true]));
    } catch (Throwable $error) {
        file_put_contents($statusFile, json_encode(['error' => $error->getMessage()]));
    }
});

$flags = client($cacheDirectory);
$user = array_filter([
    'key' => is_string($_GET['userKey'] ?? null) ? $_GET['userKey'] : null,
    'email' => is_string($_GET['email'] ?? null) ? $_GET['email'] : null,
], static fn ($value) => $value !== null);
// Local evaluation from the cached rules: refetched at most once a second.
$enabled = $flags->isEnabled($flag, $user, $fallback === 'true');
reply(200, ['flag' => $flag, 'user' => $user, 'enabled' => $enabled]);
return true;
