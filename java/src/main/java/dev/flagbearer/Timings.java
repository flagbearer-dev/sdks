package dev.flagbearer;

/** Every delay the client uses, in milliseconds. Tests shorten them. */
final class Timings {
    static final Timings DEFAULT = new Timings(5_000, 30_000, 1_000, 30_000, 2_000, 5_000, 45_000, 30_000, 10_000);

    final long requestTimeout;
    final long pollInterval;
    final long retryBase;
    final long maxRetry;
    final long maxVersionRefreshDelay;
    final long streamConnectTimeout;
    final long streamIdleTimeout;
    final long exposureFlushInterval;
    final long exposureTimeout;

    Timings(
            long requestTimeout,
            long pollInterval,
            long retryBase,
            long maxRetry,
            long maxVersionRefreshDelay,
            long streamConnectTimeout,
            long streamIdleTimeout,
            long exposureFlushInterval,
            long exposureTimeout) {
        this.requestTimeout = requestTimeout;
        this.pollInterval = pollInterval;
        this.retryBase = retryBase;
        this.maxRetry = maxRetry;
        this.maxVersionRefreshDelay = maxVersionRefreshDelay;
        this.streamConnectTimeout = streamConnectTimeout;
        this.streamIdleTimeout = streamIdleTimeout;
        this.exposureFlushInterval = exposureFlushInterval;
        this.exposureTimeout = exposureTimeout;
    }

    Timings withPollInterval(long value) {
        return new Timings(requestTimeout, value, retryBase, maxRetry, maxVersionRefreshDelay,
                streamConnectTimeout, streamIdleTimeout, exposureFlushInterval, exposureTimeout);
    }

    Timings withRetry(long base, long max) {
        return new Timings(requestTimeout, pollInterval, base, max, maxVersionRefreshDelay,
                streamConnectTimeout, streamIdleTimeout, exposureFlushInterval, exposureTimeout);
    }

    Timings withStreamIdleTimeout(long value) {
        return new Timings(requestTimeout, pollInterval, retryBase, maxRetry, maxVersionRefreshDelay,
                streamConnectTimeout, value, exposureFlushInterval, exposureTimeout);
    }

    Timings withStreamConnectTimeout(long value) {
        return new Timings(requestTimeout, pollInterval, retryBase, maxRetry, maxVersionRefreshDelay,
                value, streamIdleTimeout, exposureFlushInterval, exposureTimeout);
    }
}
