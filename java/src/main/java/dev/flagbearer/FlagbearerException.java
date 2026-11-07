package dev.flagbearer;

/**
 * A failed Flagbearer request. {@link #isPermanent()} tells a failure retrying won't
 * fix, such as a revoked key or an invalid ruleset, from a passing one like a
 * timeout or a 503.
 */
public class FlagbearerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final transient long retryAfterMillis;

    FlagbearerException(String message, boolean retryable) {
        this(message, retryable, 0, null);
    }

    FlagbearerException(String message, boolean retryable, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    /** True when retrying won't help until something changes, such as the key. */
    public boolean isPermanent() {
        return !retryable;
    }

    long retryAfterMillis() {
        return retryAfterMillis;
    }
}
