package dev.flagbearer;

import dev.flagbearer.Evaluator.Ruleset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Decides when to fetch the ruleset. One worker thread makes every fetch, so there
 * is never more than one in flight.
 */
final class Coordinator {
    interface Fetcher {
        /** The new ruleset, or null when the server says it's unchanged (304). */
        Ruleset fetch();
    }

    private static final long NONE = Long.MIN_VALUE;

    private final Fetcher fetcher;
    private final Consumer<Ruleset> apply;
    private final Runnable onPermanent;
    private final Timings t;
    private final DebugLog debug;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private boolean stopped;
    private boolean blocked;
    private boolean streaming;
    private Long version;
    private Long wanted;
    private int attempt;
    // Times from System.nanoTime(), or NONE. Nothing is fetched before notBefore.
    private long notBefore = NONE;
    private long retryAt = NONE;
    private long pollAt = NONE;
    private long versionAt = NONE;
    private boolean requested;
    private boolean inFlight;
    private List<CompletableFuture<Void>> waiters = new ArrayList<>();

    Coordinator(Fetcher fetcher, Consumer<Ruleset> apply, Runnable onPermanent, Timings t, DebugLog debug) {
        this.fetcher = fetcher;
        this.apply = apply;
        this.onPermanent = onPermanent;
        this.t = t;
        this.debug = debug;
        lock.lock();
        try {
            ensurePolling();
        } finally {
            lock.unlock();
        }
        Thread worker = new Thread(this::run, "flagbearer-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    void sync(Long timeoutMillis) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        lock.lock();
        try {
            if (stopped) {
                throw new IllegalStateException("Flagbearer client is closed");
            }
            blocked = false;
            ensurePolling();
            waiters.add(done);
            if (inFlight) {
                debug.log("Joining active refresh");
            } else {
                retryAt = NONE;
                requested = true;
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            if (timeoutMillis == null) {
                done.get();
            } else {
                done.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new FlagbearerException("Flagbearer sync failed", true, 0, cause);
        } catch (TimeoutException error) {
            throw new FlagbearerException("Flagbearer sync timed out", true, 0, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FlagbearerException("Flagbearer sync interrupted", true, 0, error);
        }
    }

    void refreshInBackground() {
        lock.lock();
        try {
            backgroundLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void onVersion(long next) {
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            debug.log("Stream version received", "version", next, "currentVersion", version);
            if (wanted == null || next > wanted) {
                wanted = next;
            }
            if (behind() && versionAt == NONE && !inFlight) {
                long delay = (long) (t.maxVersionRefreshDelay * ThreadLocalRandom.current().nextDouble());
                versionAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                wake.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void setStreaming(boolean connected, boolean refresh) {
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            if (streaming != connected) {
                debug.log(connected ? "Stream connected" : "Stream disconnected; using polling");
            }
            streaming = connected;
            if (connected) {
                clearPolling();
                if (refresh) {
                    backgroundLocked();
                }
            } else {
                ensurePolling();
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void close() {
        List<CompletableFuture<Void>> pending;
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            stopped = true;
            debug.log("Coordinator closed");
            pending = waiters;
            waiters = new ArrayList<>();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        for (CompletableFuture<Void> waiter : pending) {
            waiter.completeExceptionally(new IllegalStateException("Flagbearer client is closed"));
        }
    }

    // The helpers below expect the lock to be held.

    private boolean behind() {
        return wanted != null && (version == null || version < wanted);
    }

    private void backgroundLocked() {
        if (stopped || blocked || retryAt != NONE || inFlight) {
            return;
        }
        requested = true;
    }

    private void ensurePolling() {
        if (stopped || blocked || streaming || pollAt != NONE) {
            return;
        }
        long interval = Http.jitter(t.pollInterval, 0.1);
        debug.log("Polling scheduled", "intervalMs", interval);
        pollAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);
    }

    private void clearPolling() {
        if (pollAt != NONE) {
            debug.log("Polling disabled");
        }
        pollAt = NONE;
    }

    private void scheduleRetry(Throwable error) {
        long delay = Http.backoff(attempt, t.retryBase, t.maxRetry);
        attempt++;
        if (error instanceof FlagbearerException) {
            delay = Math.max(delay, ((FlagbearerException) error).retryAfterMillis());
        }
        notBefore = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        retryAt = notBefore;
        debug.log("Refresh retry scheduled", "delayMs", delay, "attempt", attempt);
    }

    private static boolean due(long at, long now) {
        return at != NONE && at - now <= 0;
    }

    /** Nanoseconds until the worker has something to do, or -1 if never. */
    private long nextDue(long now) {
        if (requested) {
            return notBefore == NONE ? 0 : Math.max(0, notBefore - now);
        }
        long next = -1;
        for (long at : new long[] {retryAt, pollAt, versionAt}) {
            if (at != NONE) {
                long wait = Math.max(0, at - now);
                next = next < 0 ? wait : Math.min(next, wait);
            }
        }
        return next;
    }

    /** Turns any timer that has fired into a fetch request. */
    private boolean takeDue(long now) {
        if (due(versionAt, now)) {
            versionAt = NONE;
            if (behind()) {
                backgroundLocked();
            }
        }
        if (due(retryAt, now)) {
            retryAt = NONE;
            backgroundLocked();
        }
        if (due(pollAt, now)) {
            pollAt = NONE;
            backgroundLocked();
            ensurePolling();
        }
        return requested && (notBefore == NONE || notBefore - now <= 0);
    }

    // The worker thread.

    private void run() {
        while (true) {
            List<CompletableFuture<Void>> taken;
            lock.lock();
            try {
                while (true) {
                    if (stopped) {
                        return;
                    }
                    long now = System.nanoTime();
                    if (takeDue(now)) {
                        break;
                    }
                    long wait = nextDue(now);
                    try {
                        if (wait < 0) {
                            wake.await();
                        } else {
                            wake.awaitNanos(Math.max(wait, 1));
                        }
                    } catch (InterruptedException error) {
                        return;
                    }
                }
                requested = false;
                inFlight = true;
                versionAt = NONE;
                taken = waiters;
                waiters = new ArrayList<>();
            } finally {
                lock.unlock();
            }

            Throwable error = null;
            try {
                refresh();
            } catch (Throwable caught) {
                error = caught;
            }

            boolean wasStopped;
            lock.lock();
            try {
                inFlight = false;
                // Sync calls that arrived during this fetch join it.
                taken.addAll(waiters);
                waiters = new ArrayList<>();
                wasStopped = stopped;
            } finally {
                lock.unlock();
            }
            for (CompletableFuture<Void> waiter : taken) {
                if (error == null) {
                    waiter.complete(null);
                } else {
                    waiter.completeExceptionally(error);
                }
            }
            if (error != null && !wasStopped && taken.isEmpty()) {
                DebugLog.warn("Flagbearer sync failed: " + error.getMessage());
            }
        }
    }

    /** One refresh, repeated while a stream announcement is still ahead of us. */
    private void refresh() {
        while (true) {
            debug.log("Refresh started");
            Ruleset result;
            try {
                result = fetcher.fetch();
            } catch (RuntimeException error) {
                boolean permanent = false;
                lock.lock();
                try {
                    if (stopped) {
                        throw error;
                    }
                    if (error instanceof FlagbearerException && ((FlagbearerException) error).isPermanent()) {
                        debug.log("Automatic refresh stopped after permanent failure");
                        blocked = true;
                        streaming = false;
                        retryAt = NONE;
                        clearPolling();
                        permanent = true;
                    } else {
                        scheduleRetry(error);
                    }
                } finally {
                    lock.unlock();
                }
                if (permanent) {
                    onPermanent.run();
                }
                throw error;
            }

            lock.lock();
            try {
                if (stopped) {
                    return;
                }
                if (result == null) {
                    debug.log("Snapshot unchanged", "version", version);
                    if (behind()) {
                        // The backend is still behind: retry later rather than spin.
                        scheduleRetry(null);
                    } else {
                        attempt = 0;
                        notBefore = NONE;
                    }
                    return;
                }
                Long previous = version;
                if (previous == null || result.version >= previous) {
                    version = result.version;
                    apply.accept(result);
                    debug.log("Snapshot applied", "version", result.version, "previousVersion", previous);
                } else {
                    debug.log("Older snapshot ignored", "version", result.version, "currentVersion", previous);
                }
                if (!behind()) {
                    attempt = 0;
                    notBefore = NONE;
                    return;
                }
                if (previous != null && result.version <= previous) {
                    scheduleRetry(null);
                    return;
                }
            } finally {
                lock.unlock();
            }
            // An announcement arrived during the request: fetch again now.
        }
    }
}
