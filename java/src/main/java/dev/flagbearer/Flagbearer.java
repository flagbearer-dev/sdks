package dev.flagbearer;

/** Entry point: create one {@link FlagbearerClient} per process. */
public final class Flagbearer {
    private Flagbearer() {}

    /** Starts a client for the production SDK address. See {@link #init(String, FlagbearerOptions)}. */
    public static FlagbearerClient init(String key) {
        return init(key, FlagbearerOptions.builder().build());
    }

    /**
     * Starts a client: it fetches the rules and opens the stream in the background,
     * without blocking. Call {@link FlagbearerClient#sync()} to wait for the first rules.
     *
     * @throws IllegalArgumentException for an empty key, or a URL that isn't HTTP or HTTPS
     */
    public static FlagbearerClient init(String key, FlagbearerOptions options) {
        return new FlagbearerClient(key, options);
    }
}
