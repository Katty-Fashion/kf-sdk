package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReadsClient} against a platform stub (protocol 1.1 §15.1): the module token, the context and the
 * platform's proof of it go back unchanged; the list answer becomes a {@link ReadPage}; {@code 404} on an object is
 * {@link Optional#empty()}; the platform's refusals are {@link PlatformRefusal}s with their reason; the local rules
 * (port, paging, filters, the system context) refuse before any request.
 */
class ReadsClientTest {

    private static final String SECRET = "reads-secret";
    private static final String TOKEN = "kfm_reads-token";
    private static final String PROOF = "a".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                method.set(exchange.getRequestMethod());
                uri.set(exchange.getRequestURI().toString());
                headers.set(Map.copyOf(exchange.getRequestHeaders()));
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "reads-mod", TOKEN, SECRET, null);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    /** A user's context as the platform forwards it, verified through the module (which keeps it with its proof). */
    private RequestContext verified(String requestId, String proof) {
        return verified(requestId, proof, "\"user\":\"sub-1\",\"actor\":{\"type\":\"user\",\"via\":null,\"hookId\":null}");
    }

    private RequestContext verifiedSystem(String requestId) {
        return verified(requestId, PROOF, "\"user\":null,\"actor\":{\"type\":\"system\",\"via\":\"hook\","
                + "\"hookId\":\"3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b0a\"}");
    }

    /** The platform's own context ({@code system} via {@code platform}), as on an {@code admin/export} call (1.3). */
    private RequestContext verifiedPlatform(String requestId) {
        return verified(requestId, PROOF, "\"user\":null,\"actor\":{\"type\":\"system\",\"via\":\"platform\","
                + "\"hookId\":null}");
    }

    private RequestContext verified(String requestId, String proof, String userAndActor) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\"," + userAndActor + ",\"locale\":\"en\",\"rights\":[],"
                + "\"method\":\"GET\",\"path\":\"/x\",\"query\":null,\"ts\":" + ts + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, proof, new byte[0]);
    }

    private String header(String name) {
        return headers.get().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> e.getValue().getFirst()).orElse(null);
    }

    @Test
    @DisplayName("list: GET …/reads/model.v1?filters&page&size with the token, the context and the proof unchanged → ReadPage")
    void list_sendsTokenContextAndProof_readsThePage() {
        RequestContext ctx = verified("req-1", PROOF);
        answer = "{\"items\":[{\"id\":\"42\",\"name\":\"Rochie\",\"price\":12.50}],\"page\":0,\"size\":50,\"total\":51}";
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("updatedSince", "2026-10-01T00:00:00Z");

        ReadPage page = kf.reads().list(ctx, "model.v1", filters, 0, 50);

        assertEquals(1, requests.get());
        assertEquals("GET", method.get());
        assertEquals("/api/v1/platform/modules/reads-mod/reads/model.v1?updatedSince=2026-10-01T00%3A00%3A00Z&page=0&size=50",
                uri.get());
        assertEquals("Bearer " + TOKEN, header("Authorization"));
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        assertTrue(new String(Base64.getUrlDecoder().decode(header("X-KF-Context")), StandardCharsets.UTF_8)
                .contains("\"requestId\":\"req-1\""));
        assertEquals(0, page.page());
        assertEquals(50, page.size());
        assertEquals(51, page.total());
        assertTrue(page.hasNext());
        assertEquals("Rochie", page.items().getFirst().get("name"));
        assertEquals(new BigDecimal("12.50"), page.items().getFirst().get("price"), "a decimal is kept exactly as sent");
    }

    @Test
    @DisplayName("list without paging: page 0, size 20 (the protocol's default)")
    void list_defaults() {
        RequestContext ctx = verified("req-2", PROOF);
        answer = "{\"items\":[],\"page\":0,\"size\":20,\"total\":0}";

        ReadPage page = kf.reads().list(ctx, "technician.v1", Map.of());

        assertEquals("/api/v1/platform/modules/reads-mod/reads/technician.v1?page=0&size=20", uri.get());
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasNext());
    }

    @Test
    @DisplayName("get: GET …/reads/model.v1/42 → the object; with include → ?include=a,b; 404 → Optional.empty()")
    void get_readsTheObject_404IsEmpty() {
        RequestContext ctx = verified("req-3", PROOF);
        answer = "{\"id\":\"42\",\"name\":\"Rochie\",\"createdBySubject\":\"oidc-1\"}";

        Optional<Map<String, Object>> model = kf.reads().get(ctx, "model.v1", "42");

        assertEquals("/api/v1/platform/modules/reads-mod/reads/model.v1/42", uri.get());
        assertEquals("Rochie", model.orElseThrow().get("name"));

        kf.reads().get(ctx, "model-version.v1", "7", List.of("grading", "consumption"));
        assertEquals("/api/v1/platform/modules/reads-mod/reads/model-version.v1/7?include=grading,consumption", uri.get());

        status = 404;
        answer = "{\"type\":\"https://kf.ro/problems/not-found\",\"title\":\"Not found\",\"status\":404,\"class\":\"permanent\"}";
        assertTrue(kf.reads().get(ctx, "model.v1", "9 9").isEmpty());
        assertEquals("/api/v1/platform/modules/reads-mod/reads/model.v1/9%209", uri.get());
    }

    @Test
    @DisplayName("403 → PlatformRefusal FORBIDDEN with the reason; 401 → UNAUTHORIZED; 400 → INVALID; 503 → transient")
    void platformRefusals_mapToTheSdkErrors() {
        RequestContext ctx = verified("req-4", PROOF);
        status = 403;
        answer = "{\"type\":\"https://kf.ro/problems/insufficient-rights\",\"title\":\"Insufficient rights\",\"status\":403,"
                + "\"detail\":\"The user does not hold api.v1.models.get\",\"class\":\"permanent\"}";

        KfException forbidden = assertThrows(KfException.class, () -> kf.reads().get(ctx, "model.v1", "42"));
        PlatformRefusal refusal = assertInstanceOf(PlatformRefusal.class, forbidden);
        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refusal.kind());
        assertEquals("insufficient-rights", refusal.reason());
        assertEquals(ErrorClass.PERMANENT, refusal.errorClass());
        assertTrue(refusal.getMessage().contains("api.v1.models.get"), refusal.getMessage());

        status = 401;
        answer = "{\"type\":\"https://kf.ro/problems/invalid-context\",\"title\":\"Invalid context\",\"status\":401,"
                + "\"class\":\"permanent\"}";
        PlatformRefusal unauthorized = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.reads().list(ctx, "model.v1", Map.of())));
        assertEquals(PlatformRefusal.Kind.UNAUTHORIZED, unauthorized.kind());
        assertEquals("invalid-context", unauthorized.reason());

        status = 400;
        answer = "{\"type\":\"https://kf.ro/problems/validation\",\"title\":\"Validation\",\"status\":400,"
                + "\"detail\":\"unknown filter\",\"class\":\"permanent\",\"errors\":{\"colour\":\"unknown filter\"}}";
        PlatformRefusal invalid = assertInstanceOf(PlatformRefusal.class,
                assertThrows(KfException.class, () -> kf.reads().list(ctx, "model.v1", Map.of("colour", "red"))));
        assertEquals(PlatformRefusal.Kind.INVALID, invalid.kind());
        assertEquals(Map.of("colour", "unknown filter"), invalid.errors());
        assertTrue(invalid.getMessage().contains("colour"), invalid.getMessage());

        status = 503;
        answer = "";
        KfException unavailable = assertThrows(KfException.class, () -> kf.reads().get(ctx, "model.v1", "42"));
        assertEquals(ErrorClass.TRANSIENT, unavailable.errorClass());
        assertFalse(unavailable instanceof PlatformRefusal);
        assertEquals(4, requests.get());
    }

    @Test
    @DisplayName("a context not verified by this module, or verified without the platform's proof: local refusal, no request")
    void unknownContextOrNoProof_isRefusedLocally() {
        RequestContext built = new RequestContext(1, TENANT, "u", RequestContext.Actor.USER, "en", List.of(), "GET", "/x",
                null, Instant.now().getEpochSecond(), "never-verified");
        KfException unknown = assertThrows(KfException.class, () -> kf.reads().get(built, "model.v1", "42"));
        assertTrue(unknown.getMessage().contains("was not verified by this module"), unknown.getMessage());
        assertEquals(ErrorClass.PERMANENT, unknown.errorClass());

        RequestContext withoutProof = verified("req-5", null);
        KfException noProof = assertThrows(KfException.class, () -> kf.reads().list(withoutProof, "model.v1", Map.of()));
        assertTrue(noProof.getMessage().contains("X-KF-Context-Proof"), noProof.getMessage());
        assertEquals(ErrorClass.PERMANENT, noProof.errorClass());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("the local rules: the port grammar, size 1..200, page >= 0, at most 200 ids, the filter names, the include names")
    void localRules_refuseBeforeAnyRequest() {
        RequestContext ctx = verified("req-6", PROOF);

        KfException port = assertThrows(KfException.class, () -> kf.reads().get(ctx, "Model", "1"));
        assertTrue(port.getMessage().contains("port must match"), port.getMessage());
        KfException size = assertThrows(KfException.class, () -> kf.reads().list(ctx, "model.v1", Map.of(), 0, 201));
        assertTrue(size.getMessage().contains("size must be between 1 and 200"), size.getMessage());
        KfException page = assertThrows(KfException.class, () -> kf.reads().list(ctx, "model.v1", Map.of(), -1, 20));
        assertTrue(page.getMessage().contains("page must be at least 0"), page.getMessage());
        KfException reserved = assertThrows(KfException.class,
                () -> kf.reads().list(ctx, "model.v1", Map.of("page", "2")));
        assertTrue(reserved.getMessage().contains("filter page is reserved"), reserved.getMessage());
        KfException include = assertThrows(KfException.class,
                () -> kf.reads().get(ctx, "model-version.v1", "7", List.of("grading,consumption")));
        assertTrue(include.getMessage().contains("include grading,consumption must match"), include.getMessage());
        KfException id = assertThrows(KfException.class, () -> kf.reads().get(ctx, "model.v1", " "));
        assertTrue(id.getMessage().contains("id is required"), id.getMessage());
        KfException ids = assertThrows(KfException.class,
                () -> kf.reads().list(ctx, "model.v1", Map.of("ids", idList(201))));
        assertTrue(ids.getMessage().contains("filter ids takes at most 200 values"), ids.getMessage());
        for (KfException e : List.of(port, size, page, reserved, include, id, ids)) {
            assertEquals(ErrorClass.PERMANENT, e.errorClass());
            assertEquals(0, e.problem().status());
        }
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("the system context reads only objects, without include: a list or an include is refused locally")
    void systemContext_onlyObjectsWithBaseFields() {
        RequestContext system = verifiedSystem("req-7");

        KfException list = assertThrows(KfException.class, () -> kf.reads().list(system, "model.v1", Map.of()));
        assertTrue(list.getMessage().contains("the system context reads only objects"), list.getMessage());
        KfException include = assertThrows(KfException.class,
                () -> kf.reads().get(system, "model-version.v1", "7", List.of("grading")));
        assertTrue(include.getMessage().contains("the system context reads only the base fields"), include.getMessage());
        assertEquals(0, requests.get());

        answer = "{\"id\":\"7\"}";
        assertEquals("7", kf.reads().get(system, "model-version.v1", "7").orElseThrow().get("id"));
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("protocol 1.3: the platform's own context lists tenant.v1 without ids; any other port, or a hook's context, still not")
    void platformContext_listsTenantV1Only() {
        RequestContext platformCtx = verifiedPlatform("req-13");
        answer = "{\"items\":[{\"id\":\"" + TENANT + "\",\"name\":\"KF Dev\",\"deletedAt\":null}],"
                + "\"page\":0,\"size\":200,\"total\":1}";

        ReadPage page = kf.reads().list(platformCtx, "tenant.v1", Map.of(), 0, 200);

        assertEquals(1, requests.get());
        assertEquals("/api/v1/platform/modules/reads-mod/reads/tenant.v1?page=0&size=200", uri.get());
        assertEquals("KF Dev", page.items().get(0).get("name"));
        assertTrue(page.items().get(0).containsKey("deletedAt"));

        KfException model = assertThrows(KfException.class, () -> kf.reads().list(platformCtx, "model.v1", Map.of()));
        assertTrue(model.getMessage().contains("the system context reads only objects"), model.getMessage());
        RequestContext hook = verifiedSystem("req-14");
        KfException hookList = assertThrows(KfException.class, () -> kf.reads().list(hook, "tenant.v1", Map.of()));
        assertTrue(hookList.getMessage().contains("the system context reads only objects"), hookList.getMessage());
        assertEquals(1, requests.get(), "the two refusals are local");
    }

    @Test
    @DisplayName("with a manifest, a port the module does not declare in capabilities.reads is refused locally")
    void undeclaredPort_isRefusedLocally_whenTheManifestIsKnown() {
        kf.withManifest("""
                {"schemaVersion":2,"slug":"reads-mod","name":"n","version":"1.0.0",
                 "i18n":{"en":{"n":"Reads"}},
                 "capabilities":{"reads":[{"port":"model.v1"}]}}""");
        RequestContext ctx = verified("req-8", PROOF);

        KfException e = assertThrows(KfException.class, () -> kf.reads().get(ctx, "order.v1", "1"));
        assertTrue(e.getMessage().contains("order.v1 is not declared in capabilities.reads"), e.getMessage());
        assertEquals(0, requests.get());

        answer = "{\"id\":\"1\"}";
        assertTrue(kf.reads().get(ctx, "model.v1", "1").isPresent());
    }

    @Test
    @DisplayName("the token and the proof never appear in an error")
    void theTokenIsNeverInAnError() {
        RequestContext ctx = verified("req-9", PROOF);
        status = 403;
        answer = "{\"status\":403,\"class\":\"permanent\"}";

        KfException e = assertThrows(KfException.class, () -> kf.reads().get(ctx, "model.v1", "42"));
        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        assertFalse(e.getMessage().contains(PROOF), e.getMessage());
    }

    @Test
    @DisplayName("ids at the limit: 200 values are sent as asked, comma-separated")
    void ids_atTheLimit_areSent() {
        RequestContext ctx = verified("req-10", PROOF);
        answer = "{\"items\":[],\"page\":0,\"size\":20,\"total\":0}";

        ReadPage page = kf.reads().list(ctx, "model.v1", Map.of("ids", idList(ReadsClient.MAX_IDS)));

        assertEquals(1, requests.get());
        assertTrue(uri.get().startsWith("/api/v1/platform/modules/reads-mod/reads/model.v1?ids=1%2C2%2C"), uri.get());
        assertTrue(uri.get().endsWith("%2C200&page=0&size=20"), uri.get());
        assertEquals(0, page.total());
    }

    /** The values 1..n, comma-separated, as a module writes the {@code ids} filter. */
    private static String idList(int n) {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= n; i++) ids.append(i == 1 ? "" : ",").append(i);
        return ids.toString();
    }
}
