package dev.flagbearer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;

/** Holds GET /v1/stream open on its own thread and reconnects with backoff. */
final class VersionStream {
    private final HttpClient http;
    private final URI uri;
    private final String authorization;
    private final Timings t;
    private final DebugLog debug;
    private final LongConsumer onVersion;
    private final Runnable onOpen;
    private final Runnable onDisconnect;
    private final ScheduledExecutorService timers;

    private final CountDownLatch closed = new CountDownLatch(1);
    private final Thread thread;
    private final Object lock = new Object();
    private CompletableFuture<?> connecting;
    private InputStream body;
    private int attempts;

    VersionStream(HttpClient http, URI uri, String authorization, Timings t, DebugLog debug,
            LongConsumer onVersion, Runnable onOpen, Runnable onDisconnect, ScheduledExecutorService timers) {
        this.http = http;
        this.uri = uri;
        this.authorization = authorization;
        this.t = t;
        this.debug = debug;
        this.onVersion = onVersion;
        this.onOpen = onOpen;
        this.onDisconnect = onDisconnect;
        this.timers = timers;
        this.thread = new Thread(this::run, "flagbearer-stream");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    boolean isClosed() {
        return closed.getCount() == 0;
    }

    /** Stops reconnecting and ends any request or read in progress. */
    void close() {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }
            closed.countDown();
            debug.log("Stream closed");
            abortLocked();
        }
    }

    boolean awaitStopped(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    private void abortLocked() {
        if (connecting != null) {
            connecting.cancel(true);
        }
        if (body != null) {
            closeQuietly(body);
        }
    }

    private void run() {
        while (!isClosed()) {
            Throwable failure = connect();
            if (isClosed()) {
                return;
            }
            onDisconnect.run();
            if (failure instanceof FlagbearerException && ((FlagbearerException) failure).isPermanent()) {
                // Polling remains available; the next ruleset request also checks the key.
                DebugLog.warn("Flagbearer stream stopped; using polling: " + failure.getMessage());
                return;
            }
            long delay = Http.backoff(attempts, t.retryBase, t.maxRetry);
            attempts++;
            if (failure instanceof FlagbearerException) {
                delay = Math.max(delay, ((FlagbearerException) failure).retryAfterMillis());
            }
            debug.log("Stream reconnect scheduled", "delayMs", delay);
            try {
                if (closed.await(delay, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException error) {
                return;
            }
        }
    }

    private Throwable connect() {
        debug.log("Stream connecting");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .header("Authorization", authorization)
                .header("Accept", "text/event-stream")
                .timeout(Http.millis(t.streamConnectTimeout))
                .build();
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        synchronized (lock) {
            connecting = future;
            if (isClosed()) {
                future.cancel(true);
            }
        }
        HttpResponse<InputStream> response;
        try {
            response = future.get(t.streamConnectTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | CancellationException | ExecutionException | InterruptedException error) {
            future.cancel(true);
            return Http.network("Flagbearer stream", error);
        } finally {
            synchronized (lock) {
                connecting = null;
            }
        }

        InputStream stream = response.body();
        synchronized (lock) {
            body = stream;
            if (isClosed()) {
                closeQuietly(stream);
            }
        }
        ScheduledFuture<?>[] idle = new ScheduledFuture<?>[1];
        try {
            Http.checkStatus(response.statusCode(), response.headers());
            String type = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!type.equals("text/event-stream")) {
                return new FlagbearerException("Invalid Flagbearer stream response", false);
            }
            if (isClosed()) {
                return null;
            }
            // Heartbeats arrive every 15 seconds, so silence past the idle limit means
            // a dead connection: closing the body ends the blocked read.
            idle[0] = timers.schedule(() -> closeQuietly(stream), t.streamIdleTimeout, TimeUnit.MILLISECONDS);
            onOpen.run();

            EventParser parser = new EventParser(onVersion);
            byte[] buffer = new byte[16_384];
            while (!isClosed()) {
                int n = stream.read(buffer);
                if (n < 0) {
                    return null;
                }
                idle[0].cancel(false);
                idle[0] = timers.schedule(() -> closeQuietly(stream), t.streamIdleTimeout, TimeUnit.MILLISECONDS);
                attempts = 0;
                parser.feed(buffer, n);
            }
            return null;
        } catch (FlagbearerException | IOException | EventParser.TooLargeException error) {
            return error;
        } finally {
            if (idle[0] != null) {
                idle[0].cancel(false);
            }
            synchronized (lock) {
                body = null;
            }
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException | RuntimeException ignored) {
            // Closing is best effort.
        }
    }
}
