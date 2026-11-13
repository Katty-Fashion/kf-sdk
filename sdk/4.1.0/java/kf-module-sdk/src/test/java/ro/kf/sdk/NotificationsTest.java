package ro.kf.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Notifications} against a platform stub: the request it sends, the local refusals (nothing sent), the
 * retry of a {@code transient} answer with the SAME idempotency key, and no retry of a {@code permanent} one.
 */
class NotificationsTest {

    private static final String TOKEN = "kfm_notifications-token";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer platform;
    private final List<String> uris = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
    private final List<int[]> statuses = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try (exchange) {
                int i;
                synchronized (bodies) {
                    uris.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = JSON.readValue(raw, Map.class);
                    bodies.add(body);
                    i = Math.min(bodies.size(), statuses.size()) - 1;
                }
                int status = statuses.get(i)[0];
                byte[] bytes = answers.get(i).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        platform.start();
        kf = KfModule.of("http://127.0.0.1:" + platform.getAddress().getPort(), "notif-mod", TOKEN, "secret", null);
        kf.notifications().withRetryDelays(List.of(Duration.ofMillis(10), Duration.ofMillis(20)));
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private void answer(int status, String body) {
        statuses.add(new int[] {status});
        answers.add(body);
    }

    @Test
    void sendPostsTheWireFormWithTheModuleToken() {
        answer(202, "{\"accepted\":2,\"duplicates\":0}");

        Notifications.Result result = kf.notifications().send(TENANT, "author-changed", List.of("sub-1"),
                Map.of("modelName", "X"));

        assertEquals(2, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(List.of("POST /api/v1/platform/modules/notif-mod/notifications"), uris);
        assertEquals("Bearer " + TOKEN, authorizations.get(0));
        Map<String, Object> body = bodies.get(0);
        assertEquals(TENANT.toString(), body.get("tenantId"));
        assertEquals("author-changed", body.get("key"));
        assertEquals(List.of("sub-1"), body.get("recipients"));
        assertEquals(Map.of("modelName", "X"), body.get("variables"));
        assertFalse(body.containsKey("deepLink"));
        assertFalse(body.containsKey("channels"));
        assertNotNull(UUID.fromString((String) body.get("idempotencyKey")), "an idempotency key is always sent");
    }

    @Test
    void theOptionsTravelAsGiven() {
        answer(202, "{\"accepted\":1,\"duplicates\":0}");
        UUID key = UUID.randomUUID();

        kf.notifications().send(TENANT, "author-changed", List.of("sub-1"), Map.of("modelName", "X"),
                NotificationOptions.builder().deepLink("/models/42").channels(List.of("in_app")).idempotencyKey(key)
                        .build());

        Map<String, Object> body = bodies.get(0);
        assertEquals("/models/42", body.get("deepLink"));
        assertEquals(List.of("in_app"), body.get("channels"));
        assertEquals(key.toString(), body.get("idempotencyKey"));
    }

    @Test
    void recipientRolesTravelAndRecipientsMayBeEmpty() {
        answer(202, "{\"accepted\":2,\"duplicates\":0}");

        Notifications.Result result = kf.notifications().send(TENANT, "author-changed", List.of(),
                Map.of("modelName", "X"), NotificationOptions.roles("TENANT_ADMIN", "QC", "TENANT_ADMIN"));

        assertEquals(2, result.accepted());
        Map<String, Object> body = bodies.get(0);
        assertEquals(List.of(), body.get("recipients"), "recipients is sent, empty: a platform before 1.3 refuses it");
        assertEquals(List.of("TENANT_ADMIN", "QC"), body.get("recipientRoles"), "a repeated role counts once");
    }

    @Test
    void withoutRolesTheBodyHasNoRecipientRoles() {
        answer(202, "{\"accepted\":1,\"duplicates\":0}");

        kf.notifications().send(TENANT, "author-changed", List.of("sub-1"), Map.of("modelName", "X"),
                NotificationOptions.builder().deepLink("/models/42").build());

        assertFalse(bodies.get(0).containsKey("recipientRoles"));
    }

    @Test
    void recipientRolesLocalRefusalsSendNothing() {
        Notifications n = kf.notifications();
        List<String> eleven = IntStream.rangeClosed(1, 11).mapToObj(i -> "ROLE_" + i).toList();
        for (Runnable call : List.<Runnable>of(
                () -> n.send(TENANT, "ok", List.of(), Map.of(),
                        NotificationOptions.builder().recipientRoles(List.of()).build()),
                () -> n.send(TENANT, "ok", List.of(), Map.of(), NotificationOptions.roles(" ")),
                () -> n.send(TENANT, "ok", List.of(), Map.of(), NotificationOptions.roles("tenant_admin")),
                () -> n.send(TENANT, "ok", List.of(), Map.of(),
                        NotificationOptions.builder().recipientRoles(eleven).build()),
                () -> n.send(TENANT, "ok", null, Map.of()))) {
            KfException e = assertThrows(KfException.class, call::run);
            assertEquals(ErrorClass.PERMANENT, e.errorClass());
            assertEquals(0, e.problem().status());
        }
        assertTrue(bodies.isEmpty(), "a local refusal sends nothing");
    }

    @Test
    void a422IsPermanentAndNotRetried() {
        answer(422, "{\"type\":\"https://kf.ro/problems/notification-rejected\",\"status\":422,"
                + "\"detail\":\"The active version of notif-mod does not declare the notification 'x'.\","
                + "\"reason\":\"unknown-key\",\"class\":\"permanent\"}");

        Notifications.Rejected e = assertThrows(Notifications.Rejected.class, () -> kf.notifications().send(TENANT,
                "x", List.of("sub-1"), Map.of()));

        assertEquals(1, bodies.size(), "no retry");
        assertEquals(ErrorClass.PERMANENT, e.errorClass());
        assertEquals(422, e.problem().status());
        assertEquals("unknown-key", e.reason());
        assertTrue(e.getMessage().contains("does not declare the notification"));
        assertFalse(e.getMessage().contains(TOKEN));
    }

    @Test
    void a503IsRetriedWithTheSameIdempotencyKey() {
        answer(503, "{\"status\":503,\"detail\":\"busy\",\"class\":\"transient\"}");
        answer(202, "{\"accepted\":0,\"duplicates\":1}");

        Notifications.Result result = kf.notifications().send(TENANT, "author-changed", List.of("sub-1"),
                Map.of("modelName", "X"));

        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0).get("idempotencyKey"), bodies.get(1).get("idempotencyKey"));
        assertEquals(bodies.get(0), bodies.get(1), "the retry sends the same request");
        assertEquals(1, result.duplicates());
    }

    @Test
    void transientUntilTheScheduleEndsIsAKfException() {
        answer(503, "{\"status\":503,\"detail\":\"busy\",\"class\":\"transient\"}");

        KfException e = assertThrows(KfException.class, () -> kf.notifications().send(TENANT, "author-changed",
                List.of("sub-1"), Map.of("modelName", "X")));

        assertEquals(3, bodies.size(), "the first try and one per step of the schedule");
        assertEquals(ErrorClass.TRANSIENT, e.errorClass());
    }

    @Test
    void localRefusalsSendNothing() {
        Notifications n = kf.notifications();
        List<String> many = IntStream.rangeClosed(1, 101).mapToObj(i -> "s" + i).toList();
        for (Runnable call : List.<Runnable>of(
                () -> n.send(null, "author-changed", List.of("s"), Map.of()),
                () -> n.send(TENANT, "m.notif-mod.x", List.of("s"), Map.of()),
                () -> n.send(TENANT, "Bad", List.of("s"), Map.of()),
                () -> n.send(TENANT, "ok", List.of(), Map.of()),
                () -> n.send(TENANT, "ok", many, Map.of()),
                () -> n.send(TENANT, "ok", List.of(" "), Map.of()),
                () -> n.send(TENANT, "ok", List.of("s"), Map.of("v", List.of(1))),
                () -> n.send(TENANT, "ok", List.of("s"), Map.of(),
                        NotificationOptions.builder().deepLink("https://evil.example").build()),
                () -> n.send(TENANT, "ok", List.of("s"), Map.of(),
                        NotificationOptions.builder().channels(List.of("sms")).build()))) {
            KfException e = assertThrows(KfException.class, call::run);
            assertEquals(ErrorClass.PERMANENT, e.errorClass());
            assertEquals(0, e.problem().status());
        }
        assertTrue(bodies.isEmpty(), "a local refusal sends nothing");
    }
}
