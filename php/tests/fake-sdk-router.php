<?php

// The SDK server's ruleset and exposure routes, for tests. Its state lives in a
// JSON file the tests write and read: what to return, and what it received.

declare(strict_types=1);

$path = getenv('FAKE_SDK_STATE');
if (!is_string($path)) {
    http_response_code(500);
    return true;
}
$handle = fopen($path, 'c+');
flock($handle, LOCK_EX);
$state = json_decode(stream_get_contents($handle) ?: '{}', true);

function reply(int $status, string $body = '', array $headers = []): void
{
    http_response_code($status);
    foreach ($headers as $name => $value) {
        header("{$name}: {$value}");
    }
    echo $body;
}

$uri = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
if (($_SERVER['HTTP_AUTHORIZATION'] ?? '') !== 'Bearer fbs_test') {
    reply(401, '{}');
} elseif ($uri === '/v1/ruleset') {
    $state['rulesetRequests'][] = $_SERVER['HTTP_IF_NONE_MATCH'] ?? null;
    $status = array_shift($state['rulesetStatuses']) ?? 200;
    $etag = '"' . $state['version'] . '"';
    if ($status !== 200) {
        reply($status, '{}', isset($state['retryAfter']) ? ['Retry-After' => (string) $state['retryAfter']] : []);
    } elseif (($_SERVER['HTTP_IF_NONE_MATCH'] ?? null) === $etag) {
        reply(304, '', ['ETag' => $etag]);
    } else {
        $body = $state['rulesetBody'] ?? json_encode([
            'environmentId' => $state['environmentId'],
            'version' => $state['version'],
            'flags' => $state['flags'],
        ]);
        reply(200, $body, ['ETag' => $etag, 'Content-Type' => 'application/json']);
    }
} elseif ($uri === '/v1/exposures') {
    $body = json_decode(file_get_contents('php://input'), true);
    $state['exposures'][] = $body;
    $status = array_shift($state['exposureStatuses']) ?? 200;
    if ($status !== 200) {
        reply($status, '{}');
    } else {
        reply(200, json_encode(['accepted' => count($body['events']), 'rejectedChecks' => 0]));
    }
} elseif ($uri === '/redirect/v1/ruleset') {
    reply(302, '', ['Location' => '/v1/ruleset']);
} else {
    reply(404, '{}');
}

ftruncate($handle, 0);
rewind($handle);
fwrite($handle, json_encode($state));
flock($handle, LOCK_UN);
fclose($handle);
return true;
