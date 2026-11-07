package dev.flagbearer;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rules, their validation, and evaluating a flag for a user. */
final class Evaluator {
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000);

    private Evaluator() {}

    static final class FlagRule {
        final String key;
        final boolean enabled;
        final long rolloutBps;
        final List<String> overrideEmails;

        FlagRule(String key, boolean enabled, long rolloutBps, List<String> overrideEmails) {
            this.key = key;
            this.enabled = enabled;
            this.rolloutBps = rolloutBps;
            this.overrideEmails = overrideEmails;
        }
    }

    static final class Ruleset {
        final String environmentId;
        final long version;
        final Map<String, FlagRule> flags;

        Ruleset(String environmentId, long version, Map<String, FlagRule> flags) {
            this.environmentId = environmentId;
            this.version = version;
            this.flags = flags;
        }
    }

    enum Reason {
        OFF("off"),
        TESTER("tester"),
        ROLLOUT("rollout"),
        FALLBACK("fallback");

        final String wire;

        Reason(String wire) {
            this.wire = wire;
        }
    }

    static final class Evaluation {
        final boolean value;
        final Reason reason;
        final Long version;

        Evaluation(boolean value, Reason reason, Long version) {
            this.value = value;
            this.reason = reason;
            this.version = version;
        }
    }

    /** A user's place in a flag's rollout, 0–9999. Below rolloutBps means on. */
    static long bucket(String flagKey, String userKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(utf8(flagKey + ":" + userKey));
            return new BigInteger(1, digest).mod(TEN_THOUSAND).longValue();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    // UTF-8 with lone surrogates as U+FFFD, as Node's hashing encodes them.
    private static byte[] utf8(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
        try {
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(text));
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return out;
        } catch (CharacterCodingException error) {
            throw new IllegalStateException(error);
        }
    }

    static Evaluation evaluate(FlagRule flag, User user, Long version, boolean fallback) {
        if (flag == null) {
            return new Evaluation(fallback, Reason.FALLBACK, version);
        }
        if (!flag.enabled) {
            return new Evaluation(false, Reason.OFF, version);
        }
        String email = normalizeEmail(user.getEmail());
        if (!email.isEmpty()) {
            for (String item : flag.overrideEmails) {
                if (normalizeEmail(item).equals(email)) {
                    return new Evaluation(true, Reason.TESTER, version);
                }
            }
        }
        String key = user.getKey();
        if (key == null || key.isEmpty()) {
            if (flag.rolloutBps != 0 && flag.rolloutBps != 10_000) {
                return new Evaluation(fallback, Reason.FALLBACK, version);
            }
            return new Evaluation(flag.rolloutBps == 10_000, Reason.ROLLOUT, version);
        }
        return new Evaluation(bucket(flag.key, key) < flag.rolloutBps, Reason.ROLLOUT, version);
    }

    /** Trimmed and lowercased the way JavaScript's trim() and toLowerCase() do it. */
    static String normalizeEmail(String email) {
        return email == null ? "" : jsTrim(email).toLowerCase(Locale.ROOT);
    }

    // JavaScript's whitespace set, which differs from Java's Character.isWhitespace.
    private static boolean isJsWhitespace(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ':
            case 0x00A0: case 0x1680: case 0x2028: case 0x2029: case 0x202F:
            case 0x205F: case 0x3000: case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    static String jsTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isJsWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    static Long parseVersion(Object value) {
        Long number = Json.integer(value);
        return number != null && number >= 0 ? number : null;
    }

    /** The ruleset in a GET /v1/ruleset body, or null if any of it is invalid. */
    static Ruleset parseRuleset(String body) {
        Object value;
        try {
            value = Json.parse(body);
        } catch (Json.ParseException error) {
            return null;
        }
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> object = (Map<?, ?>) value;
        Object environmentId = object.get("environmentId");
        Long version = parseVersion(object.get("version"));
        Object flags = object.get("flags");
        if (!(environmentId instanceof String) || version == null || !(flags instanceof List)) {
            return null;
        }
        Map<String, FlagRule> rules = new HashMap<>();
        for (Object item : (List<?>) flags) {
            FlagRule rule = parseFlagRule(item);
            if (rule == null) {
                return null;
            }
            rules.put(rule.key, rule);
        }
        return new Ruleset((String) environmentId, version, Collections.unmodifiableMap(rules));
    }

    private static FlagRule parseFlagRule(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> object = (Map<?, ?>) value;
        Object key = object.get("key");
        Object enabled = object.get("enabled");
        Long rollout = Json.integer(object.get("rolloutBps"));
        Object emails = object.get("overrideEmails");
        if (!(key instanceof String) || !(enabled instanceof Boolean) || rollout == null
                || rollout < 0 || rollout > 10_000 || !(emails instanceof List)) {
            return null;
        }
        List<?> list = (List<?>) emails;
        String[] overrides = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String)) {
                return null;
            }
            overrides[i] = (String) list.get(i);
        }
        return new FlagRule((String) key, (Boolean) enabled, rollout, List.of(overrides));
    }
}
