// The Java SDK behind the same GET /flags and GET /flush routes as node-server.ts.
// Launched as a single-file program against the SDK jar:
//   java -cp java/build/fixture/flagbearer.jar testing/servers/java-server/Fixture.java

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.flagbearer.Flagbearer;
import dev.flagbearer.FlagbearerClient;
import dev.flagbearer.FlagbearerOptions;
import dev.flagbearer.User;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class Fixture {
    public static void main(String[] args) throws IOException {
        String key = System.getenv("FLAGBEARER_SERVER_KEY");
        if (key == null || key.isEmpty()) {
            System.err.println("The integration fixture requires FLAGBEARER_SERVER_KEY.");
            System.exit(1);
        }
        String url = System.getenv("FLAGBEARER_URL");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "0"));

        // One client for the whole server, not one per HTTP request.
        FlagbearerClient flags = Flagbearer.init(key, FlagbearerOptions.builder()
                .url(url == null || url.isEmpty() ? FlagbearerOptions.DEFAULT_URL : url)
                .debug(true)
                .build());
        try {
            flags.sync();
            System.out.println("Initial Flagbearer ruleset loaded.");
        } catch (RuntimeException error) {
            System.out.println("Initial sync failed; serving defaults while the SDK recovers. " + error.getMessage());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        // Uploads pending exposures now, so a test sees whether the server took them.
        server.createContext("/flush", exchange -> {
            try {
                flags.flush();
                reply(exchange, 200, "{\"flushed\":true}");
            } catch (RuntimeException error) {
                reply(exchange, 502, "{\"error\":" + quote(error.getMessage()) + "}");
            }
        });
        server.createContext("/flags", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String flag = query.get("flag");
            String fallback = query.getOrDefault("default", "false");
            if (flag == null || flag.isEmpty() || !(fallback.equals("true") || fallback.equals("false"))) {
                reply(exchange, 400, "{\"error\":\"Provide flag, and optionally default=true or default=false.\"}");
                return;
            }
            User user = User.of(query.get("userKey"), query.get("email"));
            // Local evaluation: this request does not call Flagbearer's API.
            boolean enabled = flags.isEnabled(flag, user, fallback.equals("true"));
            reply(exchange, 200, "{\"flag\":" + quote(flag) + ",\"enabled\":" + enabled + "}");
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flags.close();
            server.stop(0);
        }));
        server.start();
        System.out.println("Java SDK fixture listening on http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            result.putIfAbsent(name, value);
        }
        return result;
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : String.valueOf(text).toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
