package dev.flagbearer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Server-Sent Events, fed bytes in arbitrary pieces. Calls onVersion for each
 * well-formed {@code version} event and ignores everything else.
 */
final class EventParser {
    static final int MAX_EVENT_LENGTH = 65_536;

    static final class TooLargeException extends Exception {
        private static final long serialVersionUID = 1L;

        TooLargeException(String message) {
            super(message);
        }
    }

    private final LongConsumer onVersion;
    private byte[] pending = new byte[0];
    private String buffer = "";
    private String event = "message";
    private final StringBuilder data = new StringBuilder();

    EventParser(LongConsumer onVersion) {
        this.onVersion = onVersion;
    }

    void feed(byte[] chunk, int length) throws TooLargeException {
        byte[] bytes = Arrays.copyOf(pending, pending.length + length);
        System.arraycopy(chunk, 0, bytes, pending.length, length);
        // Hold back a character split across reads; invalid bytes become U+FFFD.
        int cut = bytes.length;
        for (int i = bytes.length - 1; i >= 0 && i >= bytes.length - 4; i--) {
            int b = bytes[i] & 0xFF;
            if ((b & 0xC0) != 0x80) {
                int needed = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
                if (bytes.length - i < needed) {
                    cut = i;
                }
                break;
            }
        }
        pending = Arrays.copyOfRange(bytes, cut, bytes.length);
        feed(new String(bytes, 0, cut, StandardCharsets.UTF_8));
    }

    void feed(String text) throws TooLargeException {
        String all = buffer + text;
        int start = 0;
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            if (c != '\r' && c != '\n') {
                continue;
            }
            // A CR at the end might be the first half of a CRLF in the next piece.
            if (c == '\r' && i == all.length() - 1) {
                break;
            }
            line(all.substring(start, i));
            if (c == '\r' && all.charAt(i + 1) == '\n') {
                i++;
            }
            start = i + 1;
        }
        buffer = all.substring(start);
        if (buffer.length() > MAX_EVENT_LENGTH) {
            throw new TooLargeException("Flagbearer stream line too large");
        }
    }

    private void line(String line) throws TooLargeException {
        if (line.isEmpty()) {
            if (event.equals("version") && data.length() > 0) {
                String payload = data.substring(0, data.length() - 1);
                Long version = null;
                try {
                    Object parsed = Json.parse(payload);
                    if (parsed instanceof Map) {
                        version = Evaluator.parseVersion(((Map<?, ?>) parsed).get("version"));
                    }
                } catch (Json.ParseException ignored) {
                    // Ignore malformed announcements.
                }
                if (version != null) {
                    onVersion.accept(version);
                }
            }
            event = "message";
            data.setLength(0);
            return;
        }
        if (line.startsWith(":")) {
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String content = colon < 0 ? "" : line.substring(colon + 1);
        if (content.startsWith(" ")) {
            content = content.substring(1);
        }
        if (field.equals("event")) {
            event = content;
        } else if (field.equals("data")) {
            data.append(content).append('\n');
            if (data.length() > MAX_EVENT_LENGTH) {
                throw new TooLargeException("Flagbearer stream event too large");
            }
        }
    }
}
