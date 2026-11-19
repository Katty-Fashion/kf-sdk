package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDK adapter of the module's own routes: only a context signed for this method and path reaches the handler. */
class ContextRouteTest {

    private static final String SECRET = "route-secret";
    private HttpServer server;
    private final AtomicReference<RequestContext> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        KfModule kf = KfModule.of(null, "route-mod", null, SECRET, null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.route(server, kf, "/events", (ctx, exchange, body) -> {
            seen.set(ctx);
            return new HookResult(200, "{\"tenant\":\"" + ctx.tenant() + "\"}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> get(String path, String contextPath, boolean signed) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"t-1\",\"user\":\"sub-1\",\"locale\":\"ro\",\"rights\":[],\"method\":\"GET\","
                + "\"path\":\"" + contextPath + "\",\"query\":null,\"ts\":" + ts + ",\"requestId\":\"r-1\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)).GET();
        if (signed) {
            request.header("X-KF-Context", context).header("X-KF-Timestamp", String.valueOf(ts)).header("X-KF-Signature", signature);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a context signed for GET /events reaches the handler, actor user")
    void validContext() throws Exception {
        HttpResponse<String> response = get("/events", "/events", true);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("t-1", seen.get().tenant());
        assertEquals(RequestContext.Actor.Type.USER, seen.get().actor().type());
    }

    @Test
    @DisplayName("no context: 401 RFC 9457, class permanent, the handler is not called")
    void missingContext() throws Exception {
        HttpResponse<String> response = get("/events", "/events", false);
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertTrue(response.body().contains("\"class\":\"permanent\""), response.body());
        assertNull(seen.get());
    }

    @Test
    @DisplayName("a context signed for another path is refused")
    void contextForAnotherPath() throws Exception {
        HttpResponse<String> response = get("/events", "/other", true);
        assertEquals(401, response.statusCode(), response.body());
        assertNull(seen.get());
    }
}
