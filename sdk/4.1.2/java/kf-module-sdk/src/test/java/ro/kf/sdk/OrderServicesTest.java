package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderServices} (protocol 2.0, {@code order.services.v1}, sdk-protocol.md §16.6): the upsert {@code POST
 * …/services/order.services.v1} and the delete {@code DELETE …/order.services.v1/{externalRef}?orderId=} with the token,
 * the context and its proof; the local refusals (nothing sent); the platform's {@code 422 not-delivered} and
 * {@code 501 no-provider} as typed refusals.
 */
class OrderServicesTest {

    private static final String SECRET = "services-secret";
    private static final String TOKEN = "kfm_services-token";
    private static final String PROOF = "d".repeat(64);
    private static final String TENANT = "00000000-0000-0000-0000-00000000000c";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MANIFEST = """
            { "schemaVersion": 3, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
              "i18n": { "en": { "module.name": "Step" } },
              "capabilities": { "services": [ { "port": "order.services.v1" } ] } }""";

    private HttpServer platform;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> proof = new AtomicReference<>();
    private volatile int status = 204;
    private volatile String answer = "";
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                uri.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                proof.set(exchange.getRequestHeaders().getFirst(RequestContext.PROOF_HEADER));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "kf-step", TOKEN, SECRET, null)
                .withManifest(MANIFEST);
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private RequestContext verified(String requestId) {
        long ts = Instant.now().getEpochSecond();
        String json = "{\"v\":1,\"tenant\":\"" + TENANT + "\",\"user\":\"sub-1\",\"actor\":{\"type\":\"user\",\"via\":null,"
                + "\"hookId\":null},\"locale\":\"en\",\"rights\":[],\"method\":\"POST\",\"path\":\"/x\",\"query\":null,"
                + "\"ts\":" + ts + ",\"requestId\":\"" + requestId + "\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(ts), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return kf.verifyContext(context, String.valueOf(ts), signature, PROOF, new byte[0]);
    }

    private static OrderServices.Line line(String amount, String currency, String externalRef) {
        return new OrderServices.Line("812", "4401", "Embroidery", amount == null ? null : new BigDecimal(amount), currency,
                externalRef);
    }

    @Test
    void upsertPostsTheLineWithTheContextAndItsProof() {
        kf.orderServices().upsert(verified("r-1"), line("12.50", "EUR", "embroidery:4401"));
        assertEquals("POST /api/v1/platform/modules/kf-step/services/order.services.v1", uri.get());
        assertEquals(PROOF, proof.get());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = JSON.readValue(body.get(), Map.class);
        assertEquals("812", sent.get("orderId"));
        assertEquals("4401", sent.get("entryId"));
        assertEquals("Embroidery", sent.get("name"));
        assertEquals("embroidery:4401", sent.get("externalRef"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) sent.get("cost");
        assertEquals(0, new BigDecimal(String.valueOf(cost.get("amount"))).compareTo(new BigDecimal("12.50")));
        assertEquals("EUR", cost.get("currency"));
        assertTrue(!sent.containsKey("source"), "the source is the token's slug, never the body's");
    }

    @Test
    void deleteNamesTheLineAndTheOrder() {
        kf.orderServices().delete(verified("r-2"), "812", "embroidery:4401");
        assertEquals("DELETE /api/v1/platform/modules/kf-step/services/order.services.v1/embroidery:4401?orderId=812",
                uri.get());
    }

    @Test
    void anInvalidLineIsRefusedLocallyAndNothingIsSent() {
        RequestContext ctx = verified("r-3");
        KfException negative = assertThrows(KfException.class,
                () -> kf.orderServices().upsert(ctx, line("-1", "EUR", "x:1")));
        assertTrue(negative.getMessage().contains("cost.amount must be a number >= 0"), negative.getMessage());
        KfException currency = assertThrows(KfException.class,
                () -> kf.orderServices().upsert(ctx, line("1", "eur", "x:1")));
        assertTrue(currency.getMessage().contains("cost.currency must be an ISO 4217 code"), currency.getMessage());
        KfException ref = assertThrows(KfException.class,
                () -> kf.orderServices().upsert(ctx, line("1", "EUR", "has space")));
        assertTrue(ref.getMessage().contains("externalRef must match"), ref.getMessage());
        assertThrows(KfException.class, () -> kf.orderServices().upsert(ctx,
                new OrderServices.Line("812", "4401", "x".repeat(201), BigDecimal.ONE, "EUR", "x:1")));
        assertThrows(KfException.class, () -> kf.orderServices().delete(ctx, " ", "x:1"));
        assertEquals(0, requests.get());
    }

    @Test
    void anUndeclaredServiceIsRefusedLocally() {
        kf.withManifest(MANIFEST.replace("\"services\": [ { \"port\": \"order.services.v1\" } ]", "\"routes\": []"));
        KfException e = assertThrows(KfException.class,
                () -> kf.orderServices().upsert(verified("r-4"), line("1", "EUR", "x:1")));
        assertTrue(e.getMessage().contains("not declared in capabilities.services"), e.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    void notDeliveredAndNoProviderAreTypedRefusals() {
        status = 422;
        answer = "{\"type\":\"https://kf.ro/problems/not-delivered\",\"title\":\"Not delivered by this platform\","
                + "\"status\":422,\"detail\":\"order.services.v1 is part of protocol 2.0 but this platform does not "
                + "deliver it yet\",\"class\":\"permanent\"}";
        PlatformRefusal notDelivered = assertThrows(PlatformRefusal.class,
                () -> kf.orderServices().upsert(verified("r-5"), line("1", "EUR", "x:1")));
        assertEquals(PlatformRefusal.Kind.REFUSED, notDelivered.kind());
        assertEquals("not-delivered", notDelivered.reason());
        assertEquals(ErrorClass.PERMANENT, notDelivered.problem().errorClass());

        status = 501;
        answer = "{\"type\":\"https://kf.ro/problems/no-provider\",\"title\":\"No provider\",\"status\":501,"
                + "\"class\":\"permanent\"}";
        PlatformRefusal noProvider = assertThrows(PlatformRefusal.class,
                () -> kf.orderServices().delete(verified("r-6"), "812", "x:1"));
        assertEquals(PlatformRefusal.Kind.UNAVAILABLE, noProvider.kind());
        assertEquals("no-provider", noProvider.reason());

        status = 400;
        answer = "{\"type\":\"https://kf.ro/problems/validation\",\"status\":400,\"class\":\"permanent\","
                + "\"errors\":{\"entryId\":\"is required\"}}";
        PlatformRefusal invalid = assertThrows(PlatformRefusal.class,
                () -> kf.orderServices().upsert(verified("r-7"), line("1", "EUR", "x:1")));
        assertEquals(PlatformRefusal.Kind.INVALID, invalid.kind());
        assertEquals("is required", invalid.errors().get("entryId"));
    }

    @Test
    void deletingTheLineOfAnotherModuleIsAConflictRefusal() {
        status = 409;
        answer = "{\"type\":\"https://kf.ro/problems/order-service-line-owned-by-module\",\"status\":409,"
                + "\"reason\":\"order-service-line-owned-by-module\",\"class\":\"permanent\"}";
        PlatformRefusal refusal = assertThrows(PlatformRefusal.class,
                () -> kf.orderServices().delete(verified("r-8"), "812", "x:1"));
        assertEquals(PlatformRefusal.Kind.CONFLICT, refusal.kind());
        assertEquals(OrderServices.LINE_OWNED_BY_MODULE, refusal.reason());
        assertEquals(ErrorClass.PERMANENT, refusal.problem().errorClass());
    }
}
