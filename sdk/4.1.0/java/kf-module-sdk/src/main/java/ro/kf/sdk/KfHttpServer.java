package ro.kf.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Serves the module with the JDK's built-in HTTP server, for modules that do not use Spring:
 * {@code POST /_kf/hooks} ({@link #mount}) and the module's own routes behind the verified context
 * ({@link #route}).
 */
public final class KfHttpServer {

    private KfHttpServer() {
    }

    /** A module route: receives the verified context and the raw body, answers status + JSON. */
    @FunctionalInterface
    public interface RouteHandler {
        HookResult handle(RequestContext context, HttpExchange exchange, byte[] body) throws Exception;
    }

    /** Adds the hook endpoint to an existing server. */
    public static void mount(HttpServer server, KfModule kf) {
        server.createContext(kf.hookPath(), exchange -> handle(kf, exchange));
    }

    /** Starts a server with only the hook endpoint on {@code port}. */
    public static HttpServer start(KfModule kf, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        mount(server, kf);
        server.start();
        return server;
    }

    /**
     * Adds a module route under {@code path}: the request is answered {@code 401} ({@code class: permanent}, RFC 9457)
     * unless it carries a valid context signed by the platform for this method and path ({@link KfModule#verifyContext});
     * otherwise {@code handler} gets the {@link RequestContext}. A handler that throws answers {@code 500}.
     */
    public static void route(HttpServer server, KfModule kf, String path, RouteHandler handler) {
        server.createContext(path, exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HookResult result;
                try {
                    RequestContext ctx = kf.verifyContext(exchange.getRequestHeaders().getFirst(RequestContext.CONTEXT_HEADER),
                            exchange.getRequestHeaders().getFirst(RequestContext.TIMESTAMP_HEADER),
                            exchange.getRequestHeaders().getFirst(RequestContext.SIGNATURE_HEADER),
                            exchange.getRequestHeaders().getFirst(RequestContext.PROOF_HEADER), body);
                    if (!ctx.method().equals(exchange.getRequestMethod()) || !ctx.path().equals(exchange.getRequestURI().getPath())) {
                        throw new RequestContext.Rejected("the context was signed for " + ctx.method() + " " + ctx.path());
                    }
                    result = handler.handle(ctx, exchange, body);
                } catch (RequestContext.Rejected rejected) {
                    result = rejected.toResult();
                } catch (Exception e) {
                    System.getLogger("ro.kf.sdk").log(System.Logger.Level.ERROR, "KF SDK: route " + path + " failed", e);
                    result = new HookResult(500, "{\"type\":\"https://kf.ro/problems/handler-failed\",\"title\":\"Handler failed\","
                            + "\"status\":500,\"class\":\"transient\"}", HookResult.PROBLEM_CONTENT_TYPE);
                }
                write(exchange, result);
            }
        });
    }

    private static void handle(KfModule kf, HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            HookResult result = kf.handle(body, exchange.getRequestHeaders().getFirst("X-KF-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-KF-Signature"),
                    exchange.getRequestHeaders().getFirst(RequestContext.CONTEXT_HEADER),
                    exchange.getRequestHeaders().getFirst(RequestContext.PROOF_HEADER));
            write(exchange, result);
        }
    }

    private static void write(HttpExchange exchange, HookResult result) throws IOException {
        byte[] out = result.body() == null ? new byte[0] : result.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", result.contentType());
        exchange.sendResponseHeaders(result.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
    }
}
