package dev.flagbearer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer, so the SDK needs no dependencies. Objects
 * read as {@code Map<String, Object>}, arrays as {@code List<Object>}, numbers as
 * {@link BigDecimal}, and null as {@link #NULL}.
 */
final class Json {
    /** JSON null, distinct from a missing key. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /** Parses one JSON value. Like JSON.parse, only whitespace may follow it. */
    static Object parse(String text) throws ParseException {
        Json reader = new Json(text);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.index != text.length()) {
            throw new ParseException("Unexpected data after JSON value");
        }
        return value;
    }

    /** A JSON number with no fractional part, as JavaScript treats 42.0 and 42 alike. */
    static Long integer(Object value) {
        if (!(value instanceof BigDecimal)) {
            return null;
        }
        BigDecimal number = (BigDecimal) value;
        try {
            BigDecimal whole = number.stripTrailingZeros();
            if (whole.scale() > 0 || whole.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) > 0) {
                return null;
            }
            return whole.longValueExact();
        } catch (ArithmeticException error) {
            return null;
        }
    }

    private Object value(int depth) throws ParseException {
        if (depth > 512) {
            throw new ParseException("JSON nested too deeply");
        }
        if (index >= text.length()) {
            throw new ParseException("Unexpected end of JSON");
        }
        char c = text.charAt(index);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new ParseException("Unexpected character in JSON");
        }
    }

    private Map<String, Object> object(int depth) throws ParseException {
        Map<String, Object> result = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new ParseException("Expected a string key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return result;
            }
            if (c != ',') {
                throw new ParseException("Expected , or }");
            }
        }
    }

    private List<Object> array(int depth) throws ParseException {
        List<Object> result = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return result;
            }
            if (c != ',') {
                throw new ParseException("Expected , or ]");
            }
        }
    }

    private String string() throws ParseException {
        index++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw new ParseException("Control character in JSON string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"':
                case '\\':
                case '/':
                    out.append(escape);
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (index + 4 > text.length()) {
                        throw new ParseException("Bad unicode escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    } catch (NumberFormatException error) {
                        throw new ParseException("Bad unicode escape");
                    }
                    index += 4;
                    break;
                default:
                    throw new ParseException("Bad escape");
            }
        }
    }

    private BigDecimal number() throws ParseException {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        if (peek() == '0') {
            index++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                index++;
            }
        } else {
            throw new ParseException("Bad number");
        }
        if (peek() == '.') {
            index++;
            if (!isDigit(peek())) {
                throw new ParseException("Bad number");
            }
            while (isDigit(peek())) {
                index++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            index++;
            if (peek() == '+' || peek() == '-') {
                index++;
            }
            if (!isDigit(peek())) {
                throw new ParseException("Bad number");
            }
            while (isDigit(peek())) {
                index++;
            }
        }
        try {
            return new BigDecimal(text.substring(start, index));
        } catch (NumberFormatException error) {
            throw new ParseException("Bad number");
        }
    }

    private void literal(String word) throws ParseException {
        if (!text.startsWith(word, index)) {
            throw new ParseException("Unexpected literal");
        }
        index += word.length();
    }

    private void expect(char c) throws ParseException {
        if (next() != c) {
            throw new ParseException("Expected " + c);
        }
    }

    private char next() throws ParseException {
        if (index >= text.length()) {
            throw new ParseException("Unexpected end of JSON");
        }
        return text.charAt(index++);
    }

    private char peek() {
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            index++;
        }
    }

    /** Writes maps, lists, strings, numbers, booleans and null (Java null or {@link #NULL}). */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null || value == NULL) {
            out.append("null");
        } else if (value instanceof String) {
            quote((String) value, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof BigDecimal) {
            out.append(((BigDecimal) value).toPlainString());
        } else if (value instanceof Map) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> entries = ((Map<?, ?>) value).entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
                if (entries.hasNext()) {
                    out.append(',');
                }
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            List<?> items = (List<?>) value;
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(items.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Can't write " + value.getClass() + " as JSON");
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        // Escaping surrogates keeps a lone one valid JSON, as JSON.stringify does.
                        if (Character.isHighSurrogate(c) && i + 1 < text.length()
                                && Character.isLowSurrogate(text.charAt(i + 1))) {
                            out.append(c).append(text.charAt(++i));
                        } else {
                            out.append(String.format("\\u%04x", (int) c));
                        }
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
