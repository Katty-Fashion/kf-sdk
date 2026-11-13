package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfHttpServer;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.RequestContext;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} 0.9.0, protocol 1.3: the tenant's soft-delete and restore delivered as the
 * platform delivers them ({@code tenant.after.soft-delete}, {@code tenant.after.restore}: signed CloudEvents on
 * {@code /_kf/hooks}, only to a module subscribed and live in the tenant, nothing purged), and {@code tenant.v1} listed
 * without {@code ids} only by the platform's actor -- the platform's own context, or a user on an
 * {@code admin: platform} route -- with {@code deletedAt}; anyone else gets today's {@code 400 ids: required}.
 */
class FakePlatformTenantLifecycleTest {

    private static final String SLUG = "life-mod";
    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"life-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Lifecycle"}},
             "capabilities":{
               "hooks":[{"point":"tenant.after.delete"},{"point":"tenant.after.soft-delete"},{"point":"tenant.after.restore"}],
               "reads":[{"port":"tenant.v1"}],
               "storage":[{"contentTypes":["image/jpeg"],"maxBytes":1024}],
               "routes":[{"method":"GET","path":"/m/life-mod/admin/tenants","right":"api.v1.m.life-mod.admin.tenants.get","admin":"platform"},
                         {"method":"GET","path":"/m/life-mod/reports","right":"api.v1.m.life-mod.reports.get","roles":["QC"]}]}}""";
    private static final String ONLY_DELETE = """
            {"schemaVersion":2,"slug":"life-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"Lifecycle"}},
             "capabilities":{"hooks":[{"point":"tenant.after.delete"}]}}""";

    private FakePlatform fake;
    private KfModule kf;
    private HttpServer module;
    /** The module's sync jobs per tenant: stopped on a soft-delete, resumed on a restore. */
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    private final List<HookEvents.TenantSoftDeleted> softDeleted = new CopyOnWriteArrayList<>();
    private final List<HookEvents.TenantRestored> restored = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
        kf.tenants()
                .onSoftDelete(event -> {
                    softDeleted.add(event);
                    paused.add(event.tenantId());
                })
                .onRestore(event -> {
                    restored.add(event);
                    paused.remove(event.tenantId());
                });
        module = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KfHttpServer.mount(module, kf);
        module.start();
        fake.callbackBase("http://127.0.0.1:" + module.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        module.stop(0);
        fake.close();
    }

    private static RequestContext verified(KfModule module, FakePlatform.SignedContext signed) {
        return module.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    // ---- soft-delete and restore ------------------------------------------------------------------------

    @Test
    @DisplayName("deliverTenantSoftDeleted → onSoftDelete runs, 200; the tenant's files are NOT purged (a soft-delete keeps everything)")
    void softDelete_reachesTheHandler_nothingPurged() {
        fake.storage().put(TENANT_A, "image/jpeg", new byte[]{1});

        FakePlatform.Delivery delivery = fake.deliverTenantSoftDeleted(TENANT_A);

        assertEquals(200, delivery.status(), delivery.body());
        assertEquals(1, softDeleted.size());
        assertEquals(TENANT_A, softDeleted.get(0).tenantId());
        assertEquals(delivery.eventId(), softDeleted.get(0).eventId());
        assertTrue(paused.contains(TENANT_A));
        assertEquals(1, fake.storage().keys(TENANT_A).size(), "the soft-delete purges nothing");
    }

    @Test
    @DisplayName("deliverTenantRestored → onRestore runs; a redelivery keeps the event id under a new delivery id")
    void restore_reachesTheHandler_redeliveryKeepsTheEventId() {
        fake.deliverTenantSoftDeleted(TENANT_A);

        FakePlatform.Delivery first = fake.deliverTenantRestored(TENANT_A);
        FakePlatform.Delivery again = fake.deliverTenantRestored(TENANT_A, first.eventId());

        assertEquals(200, first.status(), first.body());
        assertEquals(2, restored.size());
        assertEquals(first.eventId(), again.eventId());
        assertNotEquals(first.deliveryId(), again.deliveryId());
        assertTrue(paused.isEmpty());
    }

    @Test
    @DisplayName("a module not live in the tenant is not reached by the soft-delete or the restore (the platform delivers to live modules)")
    void notLive_notDelivered() {
        fake.notLive(TENANT_B);

        assertThrows(IllegalStateException.class, () -> fake.deliverTenantSoftDeleted(TENANT_B));
        assertThrows(IllegalStateException.class, () -> fake.deliverTenantRestored(TENANT_B));
        assertTrue(softDeleted.isEmpty() && restored.isEmpty());
    }

    @Test
    @DisplayName("a manifest that subscribes only to tenant.after.delete is not delivered the soft-delete or the restore")
    void notSubscribed_notDelivered() {
        fake.manifest(ONLY_DELETE);

        assertThrows(IllegalStateException.class, () -> fake.deliverTenantSoftDeleted(TENANT_A));
        assertThrows(IllegalStateException.class, () -> fake.deliverTenantRestored(TENANT_A));
    }

    // ---- tenant.v1 listed by the platform ---------------------------------------------------------------

    private void twoTenants() {
        fake.reads("tenant.v1").put(TENANT_A, TENANT_A, Map.of("id", TENANT_A, "name", "KF Dev"));
        fake.reads("tenant.v1").put(TENANT_B, TENANT_B, Map.of("id", TENANT_B, "name", "ALADIN",
                "deletedAt", "2026-10-30T12:00:00Z"));
    }

    @Test
    @DisplayName("the platform's own context lists tenant.v1 without ids: every tenant, with deletedAt (null when active)")
    void platformContext_listsEveryTenant() {
        twoTenants();
        RequestContext ctx = verified(kf, fake.systemContext(TENANT_A, "GET", "/admin/export"));

        ReadPage page = kf.reads().list(ctx, "tenant.v1", Map.of(), 0, 200);

        assertEquals(2, page.total());
        assertEquals(List.of("KF Dev", "ALADIN"), page.items().stream().map(t -> t.get("name")).toList());
        assertTrue(page.items().get(0).containsKey("deletedAt"));
        assertNull(page.items().get(0).get("deletedAt"));
        assertEquals("2026-10-30T12:00:00Z", page.items().get(1).get("deletedAt"));
    }

    @Test
    @DisplayName("a user on an admin: platform route (the platform administrator) lists tenant.v1 without ids too")
    void platformAdminRoute_listsEveryTenant() {
        twoTenants();
        RequestContext ctx = verified(kf, fake.signedContext(TENANT_A, "super-1", List.of(), "GET", "/admin/tenants"));

        ReadPage page = kf.reads().list(ctx, "tenant.v1", Map.of(), 0, 50);

        assertEquals(2, page.total());
    }

    @Test
    @DisplayName("any other user listing tenant.v1 without ids → today's 400 ids: required; with ids → only the tenants of the context")
    void ordinaryUser_needsIds() {
        twoTenants();
        RequestContext ctx = verified(kf, fake.signedContext(TENANT_A, "qc-1", List.of(), "GET", "/reports"));

        PlatformRefusal refusal = assertThrows(PlatformRefusal.class, () -> kf.reads().list(ctx, "tenant.v1", Map.of()));
        ReadPage withIds = kf.reads().list(ctx, "tenant.v1", Map.of("ids", TENANT_A + "," + TENANT_B));

        assertEquals(400, refusal.problem().status());
        assertTrue(refusal.getMessage().contains("ids: required"), refusal.getMessage());
        assertEquals(1, withIds.total());
        assertEquals("KF Dev", withIds.items().get(0).get("name"));
        assertNull(withIds.items().get(0).get("deletedAt"));
    }
}
