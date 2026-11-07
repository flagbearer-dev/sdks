package dev.flagbearer;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

/** Retry classification and timing helpers shared by every request. */
final class Http {
    private Http() {}

    /** Milliseconds to wait from a Retry-After header, in seconds or as an HTTP date. */
    static long retryAfter(String header, long nowMillis) {
        if (header == null || header.isEmpty()) {
            return 0;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
                return 0;
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException notSeconds) {
            // Fall through to the date form.
        }
        try {
            long at = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(0, at - nowMillis);
        } catch (DateTimeParseException notDate) {
            return 0;
        }
    }

    /** Throws for any status but 2xx. A 3xx lands here too: requests never follow redirects. */
    static void checkStatus(int status, HttpHeaders headers) {
        if (status >= 200 && status < 300) {
            return;
        }
        throw new FlagbearerException(
                "Flagbearer request failed: " + status,
                status == 408 || status == 429 || status >= 500,
                retryAfter(headers.firstValue("Retry-After").orElse(null), System.currentTimeMillis()),
                null);
    }

    static long backoff(int attempt, long base, long cap) {
        long delay = Math.min(cap, base * (1L << Math.min(attempt, 5)));
        return Math.min(cap, (long) (delay * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4)));
    }

    static long jitter(long interval, double spread) {
        return (long) (interval * (1 - spread + ThreadLocalRandom.current().nextDouble() * 2 * spread));
    }

    static Duration millis(long value) {
        return Duration.ofMillis(Math.max(1, value));
    }

    static FlagbearerException network(String what, Throwable cause) {
        // Skip the future's wrappers to the first real cause, such as ConnectException.
        Throwable error = cause;
        while ((error instanceof java.util.concurrent.ExecutionException
                || error instanceof java.util.concurrent.CompletionException) && error.getCause() != null) {
            error = error.getCause();
        }
        String detail = error.getClass().getSimpleName();
        if (error.getMessage() != null) {
            detail += ": " + error.getMessage();
        }
        return new FlagbearerException(what + " failed: " + detail, true, 0, cause);
    }
}
