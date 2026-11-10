package ro.kf.modules.democard.testsupport;

import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Signatures;
import ro.kf.sdk.testing.FakePlatform;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Calls the module the way the platform does: signed contexts (§9.2) and signed callbacks (§5). */
public final class ModuleCalls {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakePlatform fake;
    private final int port;

    public ModuleCalls(FakePlatform fake, int port) {
        this.fake = fake;
        this.port = port;
    }

    /** A person's call ({@code actor = user}) of {@code user} in {@code tenant} holding {@code rights}. */
    public HttpResponse<String> user(String tenant, String user, List<String> rights, String method, String pathAndQuery,
                                     String body) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return send(fake.signedContext(tenant, user, rights, method, pathOf(pathAndQuery), queryOf(pathAndQuery), bytes),
                method, pathAndQuery, body);
    }

    /** The platform's own context ({@code system} via {@code platform}). */
    public HttpResponse<String> platform(String tenant, String method, String pathAndQuery) throws Exception {
        return send(fake.systemContext(tenant, method, pathOf(pathAndQuery)), method, pathAndQuery, null);
    }

    /** A tenant hook's context ({@code system} via {@code hook}). */
    public HttpResponse<String> hook(String tenant, String method, String pathAndQuery) throws Exception {
        return send(fake.hookContext(tenant, "hook-261102", method, pathOf(pathAndQuery), new byte[0]), method,
                pathAndQuery, null);
    }

    public HttpResponse<String> unsigned(String method, String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * The platform's delivery of {@code model.after.delete} for model {@code modelId} in {@code tenant} (§5.1): the
     * CloudEvent signed under the hook secret on {@code POST /_kf/hooks}; {@code eventId} is {@code kfeventid}.
     */
    public HttpResponse<String> modelDeleted(String tenant, long modelId, String name, String eventId) throws Exception {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", String.valueOf(modelId));
        before.put("name", name);
        before.put("extensions", Map.of());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", "model");
        data.put("id", String.valueOf(modelId));
        data.put("before", before);
        data.put("after", null);
        data.put("changed", List.of());
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", UUID.randomUUID().toString());
        ce.put("kfeventid", eventId);
        ce.put("type", "kf.model.after.delete");
        ce.put("source", "/api/v1/models/" + modelId);
        ce.put("subject", String.valueOf(modelId));
        ce.put("time", Instant.now().toString());
        ce.put("datacontenttype", "application/json");
        ce.put("kfmodule", fake.slug());
        ce.put("kfflags", Map.of());
        ce.put("kftenant", tenant);
        ce.put("kflocale", "ro");
        ce.put("kfactor", "sub-deleter");
        ce.put("kfaggregatetype", "model");
        ce.put("kfenvelopeversion", "1");
        ce.put("data", data);
        byte[] body = JSON.writeValueAsBytes(ce);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_kf/hooks"))
                .header("Content-Type", "application/cloudevents+json")
                .header(RequestContext.TIMESTAMP_HEADER, ts)
                .header(RequestContext.SIGNATURE_HEADER, Signatures.sign(fake.hookSecret(), ts, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    public static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String pathOf(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        return URLDecoder.decode(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q), StandardCharsets.UTF_8);
    }

    private static String queryOf(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        return q < 0 ? null : pathAndQuery.substring(q + 1);
    }

    private HttpResponse<String> send(FakePlatform.SignedContext ctx, String method, String pathAndQuery, String body)
            throws Exception {
        HttpRequest.Builder request = ctx.applyTo(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery)));
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
