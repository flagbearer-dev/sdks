package dev.flagbearer;

/** Settings for {@link Flagbearer#init(String, FlagbearerOptions)}. */
public final class FlagbearerOptions {
    /** The production SDK address. */
    public static final String DEFAULT_URL = "https://sdk.flagbearer.dev";

    final String url;
    final boolean debug;
    final String appVersion;
    final Timings timings;

    private FlagbearerOptions(Builder builder) {
        this.url = builder.url;
        this.debug = builder.debug;
        this.appVersion = builder.appVersion;
        this.timings = builder.timings;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String url = DEFAULT_URL;
        private boolean debug;
        private String appVersion;
        private Timings timings = Timings.DEFAULT;

        private Builder() {}

        /** The SDK server address, for staging or local development. */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Logs stream connections, received versions, refreshes, retries and applied
         * rules to stderr. Logs never include keys, user data, flag names or rules.
         */
        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /** Labels which deployment of your app reported each check. */
        public Builder appVersion(String appVersion) {
            this.appVersion = appVersion;
            return this;
        }

        Builder timings(Timings timings) {
            this.timings = timings;
            return this;
        }

        public FlagbearerOptions build() {
            return new FlagbearerOptions(this);
        }
    }
}
