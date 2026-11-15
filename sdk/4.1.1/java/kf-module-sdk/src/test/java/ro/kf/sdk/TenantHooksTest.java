package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TenantHooks} (protocol 1.2 §6.1): a module subscribes to {@code tenant.after.delete}
 * and receives a {@link HookEvents.TenantDeleted} only after the signature and the envelope are verified; an envelope
 * whose {@code data.entity} is not {@code tenant}, or without an {@code id}, is refused; a second delivery of the same
 * {@code kfeventid} calls the handler again (the module is idempotent, the SDK does not deduplicate for it).
 */
class TenantHooksTest {

    private static final String SECRET = "tenant-hooks-secret";
    private static final String TENANT = "7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<HookEvents.TenantDeleted> received = new CopyOnWriteArrayList<>();
    private final List<HookEvents.TenantSoftDeleted> softDeleted = new CopyOnWriteArrayList<>();
    private final List<HookEvents.TenantRestored> restored = new CopyOnWriteArrayList<>();

    private KfModule module() {
        KfModule kf = KfModule.of("http://127.0.0.1:1", "fb-mod", "kfm_t", SECRET, "http://fb-mod:8080");
        kf.tenants().onDeleted(received::add);
        return kf;
    }

    /** Protocol 1.3: a module that takes the tenant's whole lifecycle. */
    private KfModule lifecycleModule() {
        KfModule kf = module();
        kf.tenants().onSoftDelete(softDeleted::add).onRestore(restored::add);
        return kf;
    }

    private static Map<String, Object> envelope(String entity, String id) {
        return envelope("tenant.after.delete", entity, id);
    }

    private static Map<String, Object> envelope(String point, String entity, String id) {
        boolean deleted = point.equals("tenant.after.delete");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", entity);
        data.put("id", id);
        data.put("before", id == null ? null : Map.of("id", id));
        data.put("after", deleted || id == null ? null : Map.of("id", id));
        data.put("changed", List.of());
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", "d1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6");
        ce.put("kfeventid", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
        ce.put("type", "kf." + point);
        ce.put("source", "/tenant/" + TENANT);
        ce.put("subject", TENANT);
        ce.put("time", "2026-10-29T00:55:01.123456Z");
        ce.put("datacontenttype", "application/json");
        ce.put("kfmodule", "fb-mod");
        ce.put("kfflags", Map.of());
        ce.put("kftenant", TENANT);
        ce.put("kflocale", "ro");
        ce.put("kfactor", null);
        ce.put("kfaggregatetype", "tenant");
        ce.put("kfenvelopeversion", "1");
        ce.put("data", data);
        return ce;
    }

    private static HookResult send(KfModule kf, Map<String, Object> envelope, boolean validSignature) {
        byte[] bytes = JSON.writeValueAsBytes(envelope);
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String sig = validSignature ? Signatures.sign(SECRET, ts, bytes) : "0".repeat(64);
        return kf.handle(bytes, ts, sig, now);
    }

    @Test
    @DisplayName("a signed tenant.after.delete → the handler receives TenantDeleted(tenantId), acknowledged 200")
    void signedCallbackReachesTheHandler() {
        KfModule kf = module();

        HookResult result = send(kf, envelope("tenant", TENANT), true);

        assertEquals(200, result.status(), result.body());
        assertEquals(1, received.size());
        HookEvents.TenantDeleted deleted = received.get(0);
        assertEquals(TENANT, deleted.tenantId());
        assertEquals("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", deleted.eventId());
        assertEquals("d1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6", deleted.deliveryId());
        assertEquals(HookEvents.TENANT_AFTER_DELETE, deleted.envelope().event());
    }

    @Test
    @DisplayName("a wrong signature is refused 401 before the handler")
    void wrongSignatureRefusedBeforeTheHandler() {
        KfModule kf = module();

        HookResult result = send(kf, envelope("tenant", TENANT), false);

        assertEquals(401, result.status());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("data.entity = model on tenant.after.delete, or no id → refused 400 permanent, no handler")
    void wrongEntityOrMissingIdRefused() throws Exception {
        KfModule kf = module();

        HookResult wrongEntity = send(kf, envelope("model", TENANT), true);
        HookResult noId = send(kf, envelope("tenant", null), true);

        assertEquals(400, wrongEntity.status(), wrongEntity.body());
        assertEquals("permanent", JSON.readValue(wrongEntity.body(), Map.class).get("class"));
        assertEquals(400, noId.status(), noId.body());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("the same kfeventid twice → the handler is called twice (the SDK does not deduplicate)")
    void sameEventTwiceCallsTwice() {
        KfModule kf = module();

        send(kf, envelope("tenant", TENANT), true);
        send(kf, envelope("tenant", TENANT), true);

        assertEquals(2, received.size());
    }

    @Test
    @DisplayName("the subscription is in the registration body: hooks[{event: tenant.after.delete}]")
    void registrationCarriesTheSubscription() {
        KfModule kf = module();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");

        assertEquals(List.of(Map.of("event", "tenant.after.delete")), hooks);
    }

    @Test
    @DisplayName("protocol 1.3: tenant.after.soft-delete → onSoftDelete receives TenantSoftDeleted(tenantId, time); nothing deleted")
    void softDeleteReachesItsHandler() {
        KfModule kf = lifecycleModule();

        HookResult result = send(kf, envelope("tenant.after.soft-delete", "tenant", TENANT), true);

        assertEquals(200, result.status(), result.body());
        assertEquals(1, softDeleted.size());
        HookEvents.TenantSoftDeleted event = softDeleted.get(0);
        assertEquals(TENANT, event.tenantId());
        assertEquals("2026-10-29T00:55:01.123456Z", event.time());
        assertEquals("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", event.eventId());
        assertEquals(HookEvents.TENANT_AFTER_SOFT_DELETE, event.envelope().event());
        assertTrue(received.isEmpty(), "a soft-delete is not the purge");
        assertTrue(restored.isEmpty());
    }

    @Test
    @DisplayName("protocol 1.3: tenant.after.restore → onRestore receives TenantRestored(tenantId, time)")
    void restoreReachesItsHandler() {
        KfModule kf = lifecycleModule();

        HookResult result = send(kf, envelope("tenant.after.restore", "tenant", TENANT), true);

        assertEquals(200, result.status(), result.body());
        assertEquals(1, restored.size());
        assertEquals(TENANT, restored.get(0).tenantId());
        assertEquals(HookEvents.TENANT_AFTER_RESTORE, restored.get(0).envelope().event());
        assertTrue(softDeleted.isEmpty());
    }

    @Test
    @DisplayName("protocol 1.3: the soft-delete and restore envelopes are checked like tenant.after.delete (entity tenant, an id)")
    void lifecycleEnvelopesChecked() throws Exception {
        KfModule kf = lifecycleModule();

        HookResult wrongEntity = send(kf, envelope("tenant.after.soft-delete", "model", TENANT), true);
        HookResult noId = send(kf, envelope("tenant.after.restore", "tenant", null), true);

        assertEquals(400, wrongEntity.status(), wrongEntity.body());
        assertTrue(wrongEntity.body().contains("tenant.after.soft-delete"), wrongEntity.body());
        assertEquals("permanent", JSON.readValue(wrongEntity.body(), Map.class).get("class"));
        assertEquals(400, noId.status(), noId.body());
        assertTrue(softDeleted.isEmpty() && restored.isEmpty());
    }

    @Test
    @DisplayName("protocol 1.3: the registration subscribes to each point that has a handler, in the order delete, soft-delete, restore")
    void registrationCarriesTheLifecycleSubscriptions() {
        KfModule kf = lifecycleModule();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");

        assertEquals(List.of(Map.of("event", "tenant.after.delete"), Map.of("event", "tenant.after.soft-delete"),
                Map.of("event", "tenant.after.restore")), hooks);
    }

    @Test
    @DisplayName("without a handler the event is acknowledged and ignored, like any point the module did not take")
    void withoutHandlerIgnored() {
        KfModule kf = KfModule.of("http://127.0.0.1:1", "fb-mod", "kfm_t", SECRET, null);

        HookResult result = send(kf, envelope("tenant", TENANT), true);

        assertEquals(200, result.status());
        assertTrue(new String(result.body().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).contains("ignored"));
    }
}
