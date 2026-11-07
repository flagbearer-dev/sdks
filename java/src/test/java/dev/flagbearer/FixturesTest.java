package dev.flagbearer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class FixturesTest {
    private static Map<?, ?> fixtures() throws Exception {
        String text = Files.readString(Path.of("../fixtures/evaluation.json"), StandardCharsets.UTF_8);
        return (Map<?, ?>) Json.parse(text);
    }

    @TestFactory
    Stream<DynamicTest> buckets() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object item : (List<?>) fixtures().get("buckets")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            String flag = (String) entry.get("flag");
            String user = (String) entry.get("user");
            long expected = Json.integer(entry.get("bucket"));
            tests.add(DynamicTest.dynamicTest(flag + ":" + user.substring(0, Math.min(20, user.length())),
                    () -> assertEquals(expected, Evaluator.bucket(flag, user))));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object item : (List<?>) fixtures().get("cases")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            tests.add(DynamicTest.dynamicTest((String) entry.get("name"), () -> {
                Evaluator.FlagRule rule = null;
                if (entry.get("flag") != Json.NULL) {
                    String body = "{\"environmentId\":\"1\",\"version\":7,\"flags\":[" + Json.write(entry.get("flag")) + "]}";
                    Evaluator.Ruleset parsed = Evaluator.parseRuleset(body);
                    assertNotNull(parsed);
                    rule = parsed.flags.values().iterator().next();
                }
                Map<?, ?> user = (Map<?, ?>) entry.get("user");
                Evaluator.Evaluation result = Evaluator.evaluate(rule,
                        User.of((String) user.get("key"), (String) user.get("email")), 7L,
                        (Boolean) entry.get("default"));
                Map<?, ?> expect = (Map<?, ?>) entry.get("expect");
                assertEquals(expect.get("value"), result.value);
                assertEquals(expect.get("reason"), result.reason.wire);
                assertEquals(7L, result.version);
            }));
        }
        return tests.stream();
    }

    @Test
    void invalidRulesRejectTheWholeRuleset() {
        String good = "{\"key\":\"b\",\"enabled\":true,\"rolloutBps\":0,\"overrideEmails\":[]}";
        for (String flag : new String[] {
            "{\"key\":\"a\",\"enabled\":true,\"rolloutBps\":10001,\"overrideEmails\":[]}",
            "{\"key\":\"a\",\"enabled\":true,\"rolloutBps\":50.5,\"overrideEmails\":[]}",
            "{\"key\":\"a\",\"enabled\":true,\"rolloutBps\":true,\"overrideEmails\":[]}",
            "{\"key\":\"a\",\"enabled\":1,\"rolloutBps\":0,\"overrideEmails\":[]}",
            "{\"key\":\"a\",\"enabled\":true,\"rolloutBps\":0,\"overrideEmails\":[1]}",
            "{\"key\":\"a\",\"enabled\":true,\"rolloutBps\":0}",
        }) {
            assertNull(Evaluator.parseRuleset("{\"environmentId\":\"1\",\"version\":1,\"flags\":[" + good + "," + flag + "]}"), flag);
        }
        assertNull(Evaluator.parseRuleset("{\"environmentId\":\"1\",\"version\":1,\"flags\":[]} trailing"));
        assertNotNull(Evaluator.parseRuleset("{\"environmentId\":\"1\",\"version\":1.0,\"flags\":[]}"));
        assertNull(Evaluator.parseRuleset("{\"environmentId\":\"1\",\"version\":-1,\"flags\":[]}"));
    }

    @Test
    void emailsTrimJavaScriptWhitespace() {
        assertEquals("ann@example.com", Evaluator.normalizeEmail(" ﻿ Ann@Example.com　"));
    }
}
