package dev.flagbearer;

import dev.flagbearer.Evaluator.Evaluation;
import dev.flagbearer.Evaluator.FlagRule;
import dev.flagbearer.Evaluator.Ruleset;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks flags against the rules in memory. Create one per process with
 * {@link Flagbearer#init} and keep it for the life of the process. It's safe to use
 * from any thread.
 */
public final class FlagbearerClient implements AutoCloseable {
    static final String VERSION = loadVersion();

    private final URI rulesetUri;
    private final String authorization;
    private final Timings timings;
    private final HttpClient http;
    private final ExecutorService executor;
    private final ScheduledExecutorService timers;
    private final AtomicReference<Ruleset> ruleset = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<?>> activeFetch = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Coordinator coordinator;
    private final VersionStream stream;
    private final ExposureReporter reporter;

    FlagbearerClient(String key, FlagbearerOptions options) {
        if (key == null || key.isEmpty() || key.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A nonempty server SDK key is required");
        }
        URI base = parseUrl(options.url);
        this.rulesetUri = base.resolve(base.getPath() + "/v1/ruleset");
        this.authorization = "Bearer " + key;
        this.timings = options.timings;
        this.executor = Executors.newCachedThreadPool(daemon("flagbearer-http"));
        this.timers = Executors.newSingleThreadScheduledExecutor(daemon("flagbearer-timer"));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Http.millis(timings.streamConnectTimeout))
                .executor(executor)
                .build();
        DebugLog debug = new DebugLog(options.debug);

        this.reporter = new ExposureReporter(http, base.resolve(base.getPath() + "/v1/exposures"), authorization,
                VERSION, options.appVersion, timings);
        VersionStream[] streamRef = new VersionStream[1];
        this.coordinator = new Coordinator(this::fetch, ruleset::set, () -> {
            if (streamRef[0] != null) {
                streamRef[0].close();
            }
        }, timings, debug);
        this.stream = new VersionStream(http, base.resolve(base.getPath() + "/v1/stream"), authorization, timings,
                debug, coordinator::onVersion,
                // After a reconnect, refetch in case an announcement was missed.
                () -> coordinator.setStreaming(true, connected.getAndSet(true)),
                () -> coordinator.setStreaming(false, false), timers);
        streamRef[0] = stream;
        coordinator.refreshInBackground();
    }

    private static URI parseUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("Flagbearer url must use HTTP or HTTPS");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path, null, null);
        } catch (URISyntaxException | NullPointerException error) {
            throw new IllegalArgumentException("Flagbearer url must use HTTP or HTTPS", error);
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String loadVersion() {
        try (InputStream input = FlagbearerClient.class.getResourceAsStream("version.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                return properties.getProperty("version", "0.0.0");
            }
        } catch (IOException ignored) {
            // Falls through to the unknown version.
        }
        return "0.0.0";
    }

    /**
     * Waits for the latest rules.
     *
     * @throws FlagbearerException when the request fails; the client keeps retrying
     *     in the background either way
     * @throws IllegalStateException after {@link #close()}
     */
    public void sync() {
        coordinator.sync(null);
    }

    /** Like {@link #sync()}, but gives up waiting after timeout. */
    public void sync(Duration timeout) {
        coordinator.sync(timeout.toMillis());
    }

    /**
     * Checks a flag using only the rules in memory. Never blocks on the network or
     * throws. defaultValue is returned until the first rules arrive, and when the flag
     * doesn't exist or can't be evaluated.
     */
    public boolean isEnabled(String flag, User user, boolean defaultValue) {
        User who = user == null ? User.anonymous() : user;
        Ruleset current = ruleset.get();
        Evaluation details;
        if (current == null) {
            details = new Evaluation(defaultValue, Evaluator.Reason.FALLBACK, null);
        } else {
            try {
                FlagRule rule = flag == null ? null : current.flags.get(flag);
                details = Evaluator.evaluate(rule, who, current.version, defaultValue);
            } catch (RuntimeException error) {
                details = new Evaluation(defaultValue, Evaluator.Reason.FALLBACK, current.version);
            }
        }
        reporter.record(flag, who, details);
        return details.value;
    }

    /**
     * Uploads pending exposure reports.
     *
     * @throws FlagbearerException when an upload fails
     */
    public void flush() {
        reporter.flush();
    }

    /**
     * Stops streaming and background work, then uploads pending exposures. Safe to
     * call more than once. Rules in memory stay usable. A failed final upload is
     * logged, not thrown; call {@link #flush()} first to handle it yourself.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        coordinator.close();
        stream.close();
        CompletableFuture<?> fetch = activeFetch.get();
        if (fetch != null) {
            fetch.cancel(true);
        }
        try {
            reporter.close();
        } catch (RuntimeException error) {
            DebugLog.warn("Flagbearer exposure upload failed during close: " + error.getMessage());
        } finally {
            timers.shutdownNow();
            executor.shutdown();
        }
    }

    VersionStream stream() {
        return stream;
    }

    private Ruleset fetch() {
        Ruleset current = ruleset.get();
        HttpRequest.Builder builder = HttpRequest.newBuilder(rulesetUri)
                .GET()
                .header("Authorization", authorization)
                .timeout(Http.millis(timings.requestTimeout));
        if (current != null) {
            builder.header("If-None-Match", "\"" + current.version + "\"");
        }
        CompletableFuture<HttpResponse<byte[]>> future =
                http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        activeFetch.set(future);
        if (closed.get()) {
            future.cancel(true);
        }
        HttpResponse<byte[]> response;
        try {
            response = future.get(timings.requestTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException | CancellationException error) {
            future.cancel(true);
            throw Http.network("Flagbearer request", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw Http.network("Flagbearer request", error);
        } finally {
            activeFetch.compareAndSet(future, null);
        }
        if (response.statusCode() == 304) {
            return null;
        }
        Http.checkStatus(response.statusCode(), response.headers());
        Ruleset next = Evaluator.parseRuleset(new String(response.body(), StandardCharsets.UTF_8));
        if (next == null) {
            throw new FlagbearerException("Invalid ruleset response", false);
        }
        if (current != null && !next.environmentId.equals(current.environmentId)) {
            throw new FlagbearerException("Ruleset environment changed unexpectedly", false);
        }
        return next;
    }
}
