package dev.flagbearer;

/** Diagnostics for debug mode. Never logs keys, user data, flag names or rules. */
final class DebugLog {
    private final boolean enabled;

    DebugLog(boolean enabled) {
        this.enabled = enabled;
    }

    /** Details are name, value pairs. A null value prints as undefined, like the Node SDK. */
    void log(String message, Object... details) {
        if (!enabled) {
            return;
        }
        try {
            StringBuilder line = new StringBuilder("[Flagbearer] ").append(message);
            if (details.length > 0) {
                // Same shape as the Node SDK's console.debug output: { version: 42 }.
                line.append(" { ");
                for (int i = 0; i + 1 < details.length; i += 2) {
                    if (i > 0) {
                        line.append(", ");
                    }
                    Object value = details[i + 1];
                    line.append(details[i]).append(": ").append(value == null ? "undefined" : value);
                }
                line.append(" }");
            }
            System.err.println(line);
        } catch (RuntimeException ignored) {
            // Diagnostics must never interrupt flag updates.
        }
    }

    static void warn(String message) {
        System.err.println(message);
    }
}
