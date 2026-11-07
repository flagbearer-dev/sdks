package dev.flagbearer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ClientTest {
    // Short enough that retries finish within a test, long enough that nothing
    // polls unless a test asks for it.
    static final Timings FAST = new Timings(1_000, 3_600_000, 10, 50, 0, 1_000, 5_000, 3_600_000, 1_000);
    static final User USER = User.of("user-1");

    static Map<String, Object> rule(String key, boolean enabled) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("key", key);
        rule.put("enabled", enabled);
        rule.put("rolloutBps", 10000);
        rule.put("overrideEmails", List.of());
        return rule;
    }

    /** The SDK server's three routes. Tests set what it returns and read what it got. */
    static final class FakeSdk {
        int version = 1;
        String environmentId = "env-1";
        List<Map<String, Object>> flags = new ArrayList<>(List.of(rule("checkout", true)));
        String rulesetBody;
        List<Integer> rulesetStatuses = new ArrayList<>();
        int streamStatus = 200;
        List<Integer> exposureStatuses = new ArrayList<>();
        final List<String> rulesetRequests = new ArrayList<>();
        final List<Map<?, ?>> exposures = new ArrayList<>();
        int streamConnections;
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final CountDownLatch streamOpened = new CountDownLatch(1);
        volatile boolean stopping;
        HttpServer server;
        ExecutorService executor;
        String url;

        synchronized void set(Runnable change) {
            change.run();
        }

        void announce(int next) {
            events.add("event: version\ndata: {\"version\":" + next + "}\n\n");
        }

        synchronized List<Map<?, ?>> uploads() {
            return new ArrayList<>(exposures);
        }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            stopping = true;
            server.stop(0);
            executor.shutdownNow();
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (status == 304) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            }
            exchange.close();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (!"Bearer fbs_test".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/ruleset")) {
                int status;
                String etag;
                String body;
                synchronized (this) {
                    rulesetRequests.add(String.valueOf(exchange.getRequestHeaders().getFirst("If-None-Match")));
                    status = rulesetStatuses.isEmpty() ? 200 : rulesetStatuses.remove(0);
                    etag = "\"" + version + "\"";
                    Map<String, Object> ruleset = new LinkedHashMap<>();
                    ruleset.put("environmentId", environmentId);
                    ruleset.put("version", version);
                    ruleset.put("flags", flags);
                    body = rulesetBody != null ? rulesetBody : Json.write(ruleset);
                }
                if (status != 200) {
                    exchange.getResponseHeaders().set("Retry-After", "0");
                    if (status >= 300 && status < 400) {
                        exchange.getResponseHeaders().set("Location", "/v1/ruleset");
                    }
                    send(exchange, status, "{}");
                } else if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    send(exchange, 304, "");
                } else {
                    exchange.getResponseHeaders().set("ETag", etag);
                    send(exchange, 200, body);
                }
            } else if (path.equals("/v1/stream")) {
                int status;
                int current;
                synchronized (this) {
                    streamConnections++;
                    status = streamStatus;
                    current = version;
                }
                if (status != 200) {
                    send(exchange, status, "{}");
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write(("retry: 5000\n\nevent: version\ndata: {\"version\":" + current + "}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    streamOpened.countDown();
                    while (!stopping) {
                        String event = events.poll(50, TimeUnit.MILLISECONDS);
                        if (event != null) {
                            out.write(event.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                } catch (IOException | InterruptedException ignored) {
                    // The client went away.
                } finally {
                    exchange.close();
                }
            } else if (path.equals("/v1/exposures")) {
                String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<?, ?> body;
                try {
                    body = (Map<?, ?>) Json.parse(text);
                } catch (Json.ParseException error) {
                    send(exchange, 400, "{}");
                    return;
                }
                int status;
                synchronized (this) {
                    exposures.add(body);
                    status = exposureStatuses.isEmpty() ? 200 : exposureStatuses.remove(0);
                }
                if (status != 200) {
                    send(exchange, status, "{}");
                    return;
                }
                int count = ((List<?>) body.get("events")).size();
                send(exchange, 200, "{\"accepted\":" + count + ",\"rejectedChecks\":0}");
            } else {
                send(exchange, 404, "{}");
            }
        }
    }

    FakeSdk sdk;
    final List<FlagbearerClient> clients = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        sdk = new FakeSdk();
        sdk.start();
    }

    @AfterEach
    void stop() {
        clients.forEach(FlagbearerClient::close);
        sdk.stop();
    }

    FlagbearerClient client(Timings timings, String appVersion) {
        FlagbearerClient client = Flagbearer.init("fbs_test",
                FlagbearerOptions.builder().url(sdk.url).appVersion(appVersion).timings(timings).build());
        clients.add(client);
        return client;
    }

    FlagbearerClient client() {
        return client(FAST, null);
    }

    /** Polls a condition the SDK reaches on its own threads. */
    static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    static long number(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    @Test
    void defaultsUntilTheFirstRulesetThenLocalRules() {
        sdk.set(() -> {
            for (int i = 0; i < 1000; i++) {
                sdk.rulesetStatuses.add(503);
            }
        });
        FlagbearerClient client = client();
        assertTrue(client.isEnabled("checkout", USER, true));
        assertFalse(client.isEnabled("checkout", USER, false));
        sdk.set(() -> sdk.rulesetStatuses.clear());
        client.sync();
        assertTrue(client.isEnabled("checkout", USER, false));
        assertTrue(client.isEnabled("missing", USER, true));
        assertTrue(client.isEnabled("checkout", null, false));
    }

    @Test
    void aStreamAnnouncementRefetchesWithTheCurrentVersion() throws Exception {
        FlagbearerClient client = client();
        client.sync();
        assertTrue(sdk.streamOpened.await(2, TimeUnit.SECONDS));
        sdk.set(() -> {
            sdk.flags = List.of(rule("checkout", false));
            sdk.version = 2;
        });
        sdk.announce(2);
        waitFor(() -> !client.isEnabled("checkout", USER, true));
        sdk.set(() -> assertTrue(sdk.rulesetRequests.contains("\"1\""), "refetch didn't send If-None-Match"));
    }

    @Test
    void anUnchangedRulesetKeepsTheRules() {
        FlagbearerClient client = client();
        client.sync();
        client.sync();
        sdk.set(() -> assertEquals("\"1\"", sdk.rulesetRequests.get(sdk.rulesetRequests.size() - 1)));
        assertTrue(client.isEnabled("checkout", USER, false));
    }

    @Test
    void anInvalidRulesetFailsAndKeepsTheLastRules() {
        FlagbearerClient client = client();
        client.sync();
        sdk.set(() -> {
            sdk.version = 2;
            sdk.rulesetBody = "{\"environmentId\":\"env-1\",\"version\":2,\"flags\":[{\"key\":\"checkout\"}]}";
        });
        FlagbearerException error = assertThrows(FlagbearerException.class, client::sync);
        assertTrue(error.isPermanent());
        assertTrue(client.isEnabled("checkout", USER, false));

        // An explicit sync tries again after a permanent failure.
        sdk.set(() -> {
            sdk.rulesetBody = null;
            sdk.flags = List.of(rule("checkout", false));
        });
        client.sync();
        assertFalse(client.isEnabled("checkout", USER, true));
    }

    @Test
    void syncAfterAPermanentFailureRestartsPolling() throws Exception {
        sdk.set(() -> sdk.streamStatus = 401);
        FlagbearerClient client = client(FAST.withPollInterval(50), null);
        client.sync();
        sdk.set(() -> {
            sdk.rulesetBody = "not json";
            sdk.version = 2;
        });
        assertThrows(FlagbearerException.class, client::sync);
        sdk.set(() -> sdk.rulesetBody = null);
        client.sync();
        sdk.set(() -> {
            sdk.flags = List.of(rule("checkout", false));
            sdk.version = 3;
        });
        waitFor(() -> !client.isEnabled("checkout", USER, true));
    }

    @Test
    void aRulesetForAnotherEnvironmentIsRejected() {
        FlagbearerClient client = client();
        client.sync();
        sdk.set(() -> {
            sdk.environmentId = "env-2";
            sdk.flags = List.of(rule("checkout", false));
            sdk.version = 2;
        });
        FlagbearerException error = assertThrows(FlagbearerException.class, client::sync);
        assertTrue(error.getMessage().contains("environment changed"));
        assertTrue(client.isEnabled("checkout", USER, false));
    }

    @Test
    void serverErrorsRetryInTheBackground() throws Exception {
        // Without a stream, only the retry schedule can recover.
        sdk.set(() -> {
            sdk.streamStatus = 401;
            sdk.rulesetStatuses.addAll(List.of(503, 500, 429));
        });
        FlagbearerClient client = client();
        FlagbearerException error = assertThrows(FlagbearerException.class, client::sync);
        assertFalse(error.isPermanent());
        waitFor(() -> client.isEnabled("checkout", USER, false));
    }

    @Test
    @Timeout(10)
    void syncHonorsItsTimeout() {
        sdk.set(() -> sdk.rulesetStatuses.addAll(List.of(503, 503, 503, 503, 503)));
        FlagbearerClient client = client(FAST.withRetry(3_600_000, 3_600_000), null);
        assertThrows(FlagbearerException.class, client::sync);
        long started = System.nanoTime();
        FlagbearerException error = assertThrows(FlagbearerException.class, () -> client.sync(Duration.ofMillis(50)));
        assertTrue(error.getMessage().contains("timed out"));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    void theStreamOutlivesItsConnectTimeoutAndCloseEndsIt() throws Exception {
        FlagbearerClient client = client(FAST.withStreamConnectTimeout(50), null);
        client.sync();
        assertTrue(sdk.streamOpened.await(2, TimeUnit.SECONDS));
        Thread.sleep(300);
        sdk.set(() -> assertEquals(1, sdk.streamConnections));

        // The idle timeout is 5 seconds, so only an abort ends the read this soon.
        client.close();
        assertTrue(client.stream().awaitStopped(1_000), "stream still running after close");
    }

    @Test
    void heartbeatsKeepTheStreamOpenPastTheIdleTimeout() throws Exception {
        FlagbearerClient client = client(FAST.withStreamIdleTimeout(150), null);
        client.sync();
        assertTrue(sdk.streamOpened.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 15; i++) {
            sdk.events.add("event: ping\ndata: {}\n\n");
            Thread.sleep(40);
        }
        sdk.set(() -> assertEquals(1, sdk.streamConnections));
    }

    @Test
    void aSilentStreamReconnectsAfterTheIdleTimeout() throws Exception {
        FlagbearerClient client = client(FAST.withStreamIdleTimeout(100), null);
        client.sync();
        waitFor(() -> {
            synchronized (sdk) {
                return sdk.streamConnections >= 2;
            }
        });
    }

    @Test
    void redirectsAreRefused() {
        sdk.set(() -> sdk.rulesetStatuses.add(302));
        FlagbearerClient client = client();
        FlagbearerException error = assertThrows(FlagbearerException.class, client::sync);
        assertTrue(error.isPermanent());
    }

    @Test
    void aRejectedStreamFallsBackToPolling() throws Exception {
        sdk.set(() -> sdk.streamStatus = 401);
        FlagbearerClient client = client(FAST.withPollInterval(50), null);
        client.sync();
        sdk.set(() -> {
            sdk.flags = List.of(rule("checkout", false));
            sdk.version = 2;
        });
        waitFor(() -> !client.isEnabled("checkout", USER, true));
    }

    @Test
    void exposuresCountRepeatedChecksAndBatchByUser() {
        FlagbearerClient client = client(FAST, "build-7");
        client.sync();
        for (int i = 0; i < 3; i++) {
            client.isEnabled("checkout", User.of("user-1", " Ann@Example.com "), false);
        }
        client.isEnabled("checkout", User.of("user-2"), false);
        client.flush();

        Map<Object, Map<?, ?>> byUser = new LinkedHashMap<>();
        for (Map<?, ?> upload : sdk.uploads()) {
            byUser.put(((Map<?, ?>) upload.get("user")).get("key"), upload);
            assertEquals("java", upload.get("sdk"));
            assertEquals(FlagbearerClient.VERSION, upload.get("sdkVersion"));
            assertEquals("build-7", upload.get("appVersion"));
        }
        Map<?, ?> first = byUser.get("user-1");
        assertEquals("ann@example.com", ((Map<?, ?>) first.get("user")).get("email"));
        Map<?, ?> event = (Map<?, ?>) ((List<?>) first.get("events")).get(0);
        assertEquals(3, number(event.get("checkCount")));
        assertEquals(Boolean.TRUE, event.get("result"));
        assertEquals("rollout", event.get("reason"));
        assertEquals(1, number(event.get("configurationVersion")));
        String bucket = (String) event.get("bucket");
        assertTrue(bucket.endsWith(".000Z") && java.time.Instant.parse(bucket).toEpochMilli() % 30_000 == 0, bucket);
        assertEquals(1, number(((Map<?, ?>) ((List<?>) byUser.get("user-2").get("events")).get(0)).get("checkCount")));
        assertEquals(first.get("reporterId"), byUser.get("user-2").get("reporterId"));
    }

    @Test
    void aFailedUploadRetriesTheSameBatch() {
        FlagbearerClient client = client();
        client.sync();
        client.isEnabled("checkout", USER, false);
        sdk.set(() -> sdk.exposureStatuses.add(500));
        assertThrows(FlagbearerException.class, client::flush);
        client.flush();
        List<Map<?, ?>> uploads = sdk.uploads();
        assertEquals(2, uploads.size());
        assertEquals(uploads.get(0).get("batchId"), uploads.get(1).get("batchId"));
    }

    @Test
    void aRefusedUploadIsDroppedAndCountedAsLost() {
        FlagbearerClient client = client();
        client.sync();
        client.isEnabled("checkout", USER, false);
        client.isEnabled("checkout", USER, false);
        sdk.set(() -> sdk.exposureStatuses.add(400));
        assertThrows(FlagbearerException.class, client::flush);
        client.flush();
        List<Map<?, ?>> uploads = sdk.uploads();
        Map<?, ?> last = uploads.get(uploads.size() - 1);
        assertEquals(0, ((List<?>) last.get("events")).size());
        assertEquals(2, number(last.get("droppedChecks")));
    }

    @Test
    void checksThatCantBeRecordedAreCountedAsLost() {
        FlagbearerClient client = client();
        client.sync();
        assertTrue(client.isEnabled("x".repeat(257), USER, true));
        // 200 emoji are 400 UTF-16 units: past the server's 256 limit.
        client.isEnabled("checkout", User.of("😀".repeat(200)), true);
        client.flush();
        List<Map<?, ?>> uploads = sdk.uploads();
        Map<?, ?> last = uploads.get(uploads.size() - 1);
        assertEquals(2, number(last.get("droppedChecks")));
        assertEquals(0, ((List<?>) last.get("events")).size());
    }

    @Test
    void closeFlushesOnceAndKeepsRulesUsable() {
        FlagbearerClient client = client();
        client.sync();
        client.isEnabled("checkout", USER, false);
        client.close();
        client.close();
        assertEquals(1, sdk.uploads().size());
        assertTrue(client.isEnabled("checkout", USER, false));
        assertThrows(IllegalStateException.class, client::sync);
    }

    @Test
    void initRejectsBadKeysAndUrls() {
        assertThrows(IllegalArgumentException.class, () -> Flagbearer.init(""));
        assertThrows(IllegalArgumentException.class, () -> Flagbearer.init("fbs_ bad"));
        assertThrows(IllegalArgumentException.class,
                () -> Flagbearer.init("fbs_test", FlagbearerOptions.builder().url("ftp://example.com").build()));
    }
}
