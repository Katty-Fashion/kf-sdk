package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditClient} against a platform stub: the module token, the context and the platform's proof of it
 * go back unchanged; the record is checked locally first; {@code 401} / {@code 403} map to {@link AuditClient.Unauthorized}
 * / {@link AuditClient.Forbidden}.
 */
class AuditClientTest {

    private static final String SECRET = "audit-secret";
    private static final String TOKEN = "kfm_audit-token";
    private static final String PROOF = "f".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 201;
    private volatile String answer = "{\"aggregateType\":\"m.audit-mod.Note\"}";
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
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "audit-mod", TOKEN, SECRET, null);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    /** A context as the platform forwards it, verified through the module (which keeps it with its proof). */
    private RequestContext verified(String requestId, String proof) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"00000000-0000-0000-0000-00000000000a\",\"user\":\"sub-1\",\"locale\":\"en\","
                + "\"rights\":[],\"method\":\"POST\",\"path\":\"/notes\",\"query\":null,\"ts\":" + ts
                + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, proof, new byte[0]);
    }

    private String header(String name) {
        return headers.get().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> e.getValue().getFirst()).orElse(null);
    }

    @Test
    @DisplayName("record: POST …/audit-mod/audit with the token, the context and the proof unchanged, the record as JSON")
    void record_sendsTokenContextAndProof() throws Exception {
        RequestContext ctx = verified("req-1", PROOF);

        kf.audit().record(ctx, new AuditRecord("NOTE_CREATED", "Note", "42"));

        assertEquals(1, requests.get());
        assertEquals("POST", method.get());
        assertEquals("/api/v1/platform/modules/audit-mod/audit", uri.get());
        assertEquals("Bearer " + TOKEN, header("Authorization"));
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        String context = header("X-KF-Context");
        assertTrue(new String(Base64.getUrlDecoder().decode(context), StandardCharsets.UTF_8).contains("\"requestId\":\"req-1\""));
        assertEquals(Map.of("action", "NOTE_CREATED", "aggregateType", "Note", "aggregateRef", "42"),
                JSON.readValue(body.get(), Map.class));
    }

    @Test
    @DisplayName("list: GET …/audit?aggregateType&aggregateRef, the rows of the answer")
    void list_readsTheRows() {
        RequestContext ctx = verified("req-2", PROOF);
        status = 200;
        answer = "{\"rows\":[{\"id\":7,\"aggregateType\":\"Note\",\"aggregateRef\":\"42\",\"action\":\"NOTE_CREATED\","
                + "\"actor\":\"sub-1\",\"performedAt\":\"2026-10-20T10:00:00Z\",\"requestId\":\"req-1\"}]}";

        List<Map<String, Object>> rows = kf.audit().list(ctx, "Note", "4 2");

        assertEquals("GET", method.get());
        assertEquals("/api/v1/platform/modules/audit-mod/audit?aggregateType=Note&aggregateRef=4+2", uri.get());
        assertEquals(PROOF, header("X-KF-Context-Proof"));
        assertEquals(1, rows.size());
        assertEquals("NOTE_CREATED", rows.getFirst().get("action"));

        kf.audit().list(ctx, "Note", null);
        assertEquals("/api/v1/platform/modules/audit-mod/audit?aggregateType=Note", uri.get());
    }

    @Test
    @DisplayName("an invalid record is refused locally, permanent, every problem named, no request")
    void invalidRecord_isRefusedLocally() {
        RequestContext ctx = verified("req-3", PROOF);

        KfException e = assertThrows(KfException.class,
                () -> kf.audit().record(ctx, new AuditRecord("note.created", "m/x", " ")));

        assertEquals(ErrorClass.PERMANENT, e.errorClass());
        assertTrue(e.getMessage().contains("action must match"), e.getMessage());
        assertTrue(e.getMessage().contains("aggregateType must match"), e.getMessage());
        assertTrue(e.getMessage().contains("aggregateRef is required"), e.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("a context not verified by this module, or verified without the platform's proof: local refusal")
    void unknownContextOrNoProof_isRefusedLocally() {
        RequestContext built = new RequestContext(1, "t", "u", RequestContext.Actor.USER, "en", List.of(), "POST", "/n",
                null, Instant.now().getEpochSecond(), "never-verified");
        KfException unknown = assertThrows(KfException.class,
                () -> kf.audit().record(built, new AuditRecord("NOTE_CREATED", "Note", "42")));
        assertTrue(unknown.getMessage().contains("was not verified by this module"), unknown.getMessage());

        RequestContext withoutProof = verified("req-4", null);
        KfException noProof = assertThrows(KfException.class,
                () -> kf.audit().list(withoutProof, "Note", null));
        assertTrue(noProof.getMessage().contains("X-KF-Context-Proof"), noProof.getMessage());
        assertEquals(ErrorClass.PERMANENT, noProof.errorClass());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("403 permanent from the platform -> AuditClient.Forbidden; 401 -> AuditClient.Unauthorized")
    void platformRefusals_mapToTheSdkExceptions() {
        RequestContext ctx = verified("req-5", PROOF);
        status = 403;
        answer = "{\"type\":\"https://kf.ro/problems/capability-not-declared\",\"title\":\"Capability not declared\","
                + "\"status\":403,\"detail\":\"The active version of audit-mod does not declare capabilities.audit\","
                + "\"class\":\"permanent\"}";

        KfException forbidden = assertThrows(KfException.class,
                () -> kf.audit().record(ctx, new AuditRecord("NOTE_CREATED", "Note", "42")));
        assertInstanceOf(AuditClient.Forbidden.class, forbidden);
        assertEquals(ErrorClass.PERMANENT, forbidden.errorClass());
        assertEquals(403, forbidden.problem().status());

        status = 401;
        answer = "{\"type\":\"https://kf.ro/problems/invalid-context\",\"title\":\"Invalid context\",\"status\":401,"
                + "\"class\":\"permanent\"}";
        KfException unauthorized = assertThrows(KfException.class, () -> kf.audit().list(ctx, "Note", null));
        assertInstanceOf(AuditClient.Unauthorized.class, unauthorized);

        status = 503;
        answer = "";
        KfException transientError = assertThrows(KfException.class, () -> kf.audit().list(ctx, "Note", null));
        assertEquals(ErrorClass.TRANSIENT, transientError.errorClass());
        assertTrue(!(transientError instanceof AuditClient.Forbidden) && !(transientError instanceof AuditClient.Unauthorized));
    }

    @Test
    @DisplayName("the token never appears in an error")
    void theTokenIsNeverInAnError() {
        RequestContext ctx = verified("req-6", PROOF);
        status = 403;
        answer = "{\"status\":403,\"class\":\"permanent\"}";

        KfException e = assertThrows(KfException.class,
                () -> kf.audit().record(ctx, new AuditRecord("NOTE_CREATED", "Note", "42")));
        assertTrue(!e.getMessage().contains(TOKEN), e.getMessage());
        assertTrue(!e.getMessage().contains(PROOF), e.getMessage());
    }
}
