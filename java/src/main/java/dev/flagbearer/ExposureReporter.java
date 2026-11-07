package dev.flagbearer;

import dev.flagbearer.Evaluator.Evaluation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Counts flag checks into 30-second buckets and uploads them in the background. */
final class ExposureReporter {
    private static final long WINDOW_MS = 30_000;
    private static final int MAX_RECORDS = 1000;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_CHECK_COUNT = 1_000_000_000;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final class Event {
        final String flagKey;
        final boolean result;
        final String reason;
        final Long configurationVersion;
        final String bucket;
        final String firstCheckedAt;
        String lastCheckedAt;
        int checkCount = 1;

        Event(String flagKey, boolean result, String reason, Long configurationVersion, String bucket, String at) {
            this.flagKey = flagKey;
            this.result = result;
            this.reason = reason;
            this.configurationVersion = configurationVersion;
            this.bucket = bucket;
            this.firstCheckedAt = at;
            this.lastCheckedAt = at;
        }

        Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("flagKey", flagKey);
            body.put("result", result);
            body.put("reason", reason);
            body.put("configurationVersion", configurationVersion);
            body.put("bucket", bucket);
            body.put("checkCount", checkCount);
            body.put("firstCheckedAt", firstCheckedAt);
            body.put("lastCheckedAt", lastCheckedAt);
            return body;
        }
    }

    // One upload, fixed when sealed, so a retry sends exactly the same body and the
    // server can recognize it by batchId.
    private static final class Batch {
        final String id = UUID.randomUUID().toString();
        final Map<String, Object> user;
        final List<Event> events = new ArrayList<>();

        Batch(Map<String, Object> user) {
            this.user = user;
        }
    }

    private static final class Active {
        final Map<String, Object> user;
        final Event event;

        Active(Map<String, Object> user, Event event) {
            this.user = user;
            this.event = event;
        }
    }

    private final HttpClient http;
    private final URI uri;
    private final String authorization;
    private final String sdkVersion;
    private final String appVersion;
    private final long timeout;
    private final String reporterId = UUID.randomUUID().toString();

    private final Object sendLock = new Object(); // one upload at a time
    private final Object lock = new Object();
    private final Map<List<Object>, Active> active = new LinkedHashMap<>();
    private final List<Batch> sealed = new ArrayList<>();
    private int sealedEvents;
    private long dropped;
    private long reportedDropped;
    private long retryAt; // System.currentTimeMillis()
    private int failures;
    private final CountDownLatch closed = new CountDownLatch(1);

    ExposureReporter(HttpClient http, URI uri, String authorization, String sdkVersion, String appVersion, Timings t) {
        this.http = http;
        this.uri = uri;
        this.authorization = authorization;
        this.sdkVersion = sdkVersion;
        this.appVersion = appVersion == null ? null : truncate(appVersion, 100);
        this.timeout = t.exposureTimeout;
        Thread thread = new Thread(() -> run(t.exposureFlushInterval), "flagbearer-exposures");
        thread.setDaemon(true);
        thread.start();
    }

    // Java strings are UTF-16, so length() matches JavaScript and the server's limits.
    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    void record(String flagKey, User user, Evaluation details) {
        synchronized (lock) {
            if (closed.getCount() == 0) {
                return;
            }
            try {
                String key = user.getKey();
                if (flagKey == null || flagKey.isEmpty() || flagKey.length() > 256
                        || (key != null && key.length() > 256)) {
                    dropped++;
                    return;
                }
                long now = System.currentTimeMillis();
                String bucket = ISO.format(Instant.ofEpochMilli(now - Math.floorMod(now, WINDOW_MS)));
                String rawEmail = user.getEmail();
                String email = rawEmail != null && rawEmail.length() <= 254 ? Evaluator.normalizeEmail(rawEmail) : "";
                String userKey = key == null || key.isEmpty() ? null : key;
                String userEmail = email.isEmpty() ? null : email;
                List<Object> identity = Arrays.asList(flagKey, userKey, userEmail, details.value,
                        details.reason.wire, details.version, bucket);
                Active existing = active.get(identity);
                String at = ISO.format(Instant.ofEpochMilli(now));
                if (existing != null) {
                    if (existing.event.checkCount < MAX_CHECK_COUNT) {
                        existing.event.checkCount++;
                        existing.event.lastCheckedAt = at;
                    } else {
                        dropped++;
                    }
                    return;
                }
                if (active.size() + sealedEvents >= MAX_RECORDS) {
                    dropped++;
                    return;
                }
                Map<String, Object> who = new LinkedHashMap<>();
                if (userKey != null) {
                    who.put("key", userKey);
                }
                if (userEmail != null) {
                    who.put("email", userEmail);
                }
                active.put(identity, new Active(who,
                        new Event(flagKey, details.value, details.reason.wire, details.version, bucket, at)));
            } catch (RuntimeException error) {
                dropped++;
            }
        }
    }

    private void run(long interval) {
        try {
            while (!closed.await(Http.jitter(interval, 0.1), TimeUnit.MILLISECONDS)) {
                boolean due;
                synchronized (lock) {
                    due = System.currentTimeMillis() >= retryAt;
                }
                if (due) {
                    try {
                        flush();
                    } catch (RuntimeException ignored) {
                        // Background uploads retry later.
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // The daemon thread ends with the process.
        }
    }

    /** Uploads every pending check, throwing on the first failure. */
    void flush() {
        synchronized (sendLock) {
            try {
                send();
            } catch (RuntimeException error) {
                synchronized (lock) {
                    failures++;
                    long delay = Math.min(60_000, 1000L << Math.min(failures, 6));
                    retryAt = Math.max(retryAt, System.currentTimeMillis() + delay);
                }
                throw error;
            }
        }
    }

    void close() {
        synchronized (lock) {
            if (closed.getCount() == 0) {
                return;
            }
            closed.countDown();
        }
        flush();
    }

    // Moves active checks into batches. A batch never mixes users. Expects lock held.
    private void seal() {
        Map<Map<String, Object>, Batch> open = new LinkedHashMap<>();
        for (Active item : active.values()) {
            Batch batch = open.get(item.user);
            if (batch == null || batch.events.size() >= BATCH_SIZE) {
                batch = new Batch(item.user);
                open.put(item.user, batch);
                sealed.add(batch);
            }
            batch.events.add(item.event);
            sealedEvents++;
        }
        active.clear();
    }

    private void settle(Batch batch) {
        synchronized (lock) {
            if (!sealed.isEmpty() && sealed.get(0) == batch) {
                sealed.remove(0);
                sealedEvents -= batch.events.size();
            }
        }
    }

    private void send() {
        synchronized (lock) {
            seal();
        }
        while (true) {
            Batch batch;
            long droppedNow;
            synchronized (lock) {
                if (sealed.isEmpty() && dropped <= reportedDropped) {
                    return;
                }
                // Only lost checks to report: an empty batch carries the count.
                batch = sealed.isEmpty() ? new Batch(new LinkedHashMap<>()) : sealed.get(0);
                droppedNow = dropped;
            }
            long checks = 0;
            List<Object> events = new ArrayList<>();
            for (Event event : batch.events) {
                checks += event.checkCount;
                events.add(event.body());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batchId", batch.id);
            body.put("reporterId", reporterId);
            body.put("user", batch.user);
            body.put("events", events);
            body.put("droppedChecks", droppedNow);
            body.put("sdk", "java");
            body.put("sdkVersion", sdkVersion);
            if (appVersion != null && !appVersion.isEmpty()) {
                body.put("appVersion", appVersion);
            }
            HttpResponse<String> response = post(Json.write(body));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                boolean refused = status >= 400 && status < 500 && status != 429;
                synchronized (lock) {
                    long wait = Http.retryAfter(response.headers().firstValue("Retry-After").orElse(null),
                            System.currentTimeMillis());
                    if (wait > 0) {
                        retryAt = Math.max(retryAt, System.currentTimeMillis() + Math.min(wait, 3_600_000));
                    }
                    if (refused) {
                        // The server won't take this batch, so retrying can't help.
                        dropped += checks;
                        retryAt = System.currentTimeMillis() + 60_000;
                    }
                }
                if (refused) {
                    settle(batch);
                }
                throw new FlagbearerException("Exposure upload failed (" + status + ")", !refused);
            }
            long rejected = receipt(response.body(), checks, batch.events.size());
            synchronized (lock) {
                dropped += rejected;
                reportedDropped = Math.max(reportedDropped, droppedNow);
            }
            settle(batch);
        }
    }

    private HttpResponse<String> post(String json) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .timeout(Http.millis(timeout))
                .build();
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException | CancellationException error) {
            future.cancel(true);
            throw Http.network("Exposure upload", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw Http.network("Exposure upload", error);
        }
    }

    private static long receipt(String data, long checks, int events) {
        try {
            Object parsed = Json.parse(data);
            if (parsed instanceof Map) {
                Long accepted = Json.integer(((Map<?, ?>) parsed).get("accepted"));
                Long rejected = Json.integer(((Map<?, ?>) parsed).get("rejectedChecks"));
                if (accepted != null && rejected != null && accepted >= 0 && accepted <= events
                        && rejected >= 0 && rejected <= checks) {
                    return rejected;
                }
            }
        } catch (Json.ParseException ignored) {
            // Falls through to the invalid receipt error.
        }
        throw new FlagbearerException("Invalid exposure upload receipt", true);
    }
}
