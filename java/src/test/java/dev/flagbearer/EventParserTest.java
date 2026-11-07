package dev.flagbearer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventParserTest {
    private static List<Long> parse(String... pieces) throws EventParser.TooLargeException {
        List<Long> versions = new ArrayList<>();
        EventParser parser = new EventParser(versions::add);
        for (String piece : pieces) {
            parser.feed(piece);
        }
        return versions;
    }

    @Test
    void readsVersionEventsAndIgnoresOthers() throws Exception {
        assertEquals(List.of(4L), parse(
                "retry: 9000\n\n",
                ": comment\n",
                "event: version\ndata: {\"version\":4}\n\n",
                "event: ping\ndata: {}\n\n",
                "data: {\"version\":99}\n\n"));
    }

    @Test
    void anyLineEndingSplitAnywhere() throws Exception {
        for (String ending : new String[] {"\n", "\r\n", "\r"}) {
            String text = "event: version" + ending + "data: {\"version\":7}" + ending + ending;
            for (int cut = 0; cut <= text.length(); cut++) {
                // A trailing CR might be half a CRLF, so it's read once the next byte arrives.
                assertEquals(List.of(7L), parse(text.substring(0, cut), text.substring(cut), ": heartbeat\n"),
                        "cut " + cut);
            }
        }
    }

    @Test
    void multiByteCharactersSplitAcrossReads() throws Exception {
        byte[] bytes = "event: version\ndata: {\"version\":5,\"note\":\"ü😀\"}\n\n".getBytes(StandardCharsets.UTF_8);
        for (int cut = 0; cut <= bytes.length; cut++) {
            List<Long> versions = new ArrayList<>();
            EventParser parser = new EventParser(versions::add);
            byte[] first = Arrays.copyOfRange(bytes, 0, cut);
            byte[] second = Arrays.copyOfRange(bytes, cut, bytes.length);
            parser.feed(first, first.length);
            parser.feed(second, second.length);
            assertEquals(List.of(5L), versions, "cut " + cut);
        }
    }

    @Test
    void multiLineDataJoinsWithNewlines() throws Exception {
        assertEquals(List.of(5L), parse("event: version\ndata: {\"version\":\ndata: 5}\n\n"));
    }

    @Test
    void malformedVersionsAreIgnored() throws Exception {
        for (String data : new String[] {"nope", "{\"version\":-1}", "{\"version\":true}", "{\"version\":1.5}", "[]"}) {
            assertEquals(List.of(), parse("event: version\ndata: " + data + "\n\n"), data);
        }
    }

    @Test
    void oversizedEventsFailTheConnection() {
        assertThrows(EventParser.TooLargeException.class,
                () -> parse("data: " + "x".repeat(EventParser.MAX_EVENT_LENGTH) + "\n"));
        assertThrows(EventParser.TooLargeException.class, () -> parse("x".repeat(EventParser.MAX_EVENT_LENGTH + 1)));
    }
}
