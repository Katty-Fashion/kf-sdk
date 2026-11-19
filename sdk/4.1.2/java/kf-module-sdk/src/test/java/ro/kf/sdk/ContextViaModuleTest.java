package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 2.0 (sdk-protocol.md §9.2): a call between modules carries
 * {@code actor.via = module:{slug}} -- on a user (the user kept) and on the system (a background job: no user, no rights).
 */
class ContextViaModuleTest {

    private static final String SECRET = "via-module-secret";
    private static final long NOW = 1_760_000_000L;

    private static RequestContext verify(String user, String actor, String rights) {
        String json = "{\"v\":1,\"tenant\":\"t-1\",\"user\":" + user + ",\"actor\":" + actor + ",\"locale\":\"ro\","
                + "\"rights\":" + rights + ",\"method\":\"GET\",\"path\":\"/stations\",\"query\":null,\"ts\":" + NOW
                + ",\"requestId\":\"r-1\"}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(NOW), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return RequestContext.verify(context, String.valueOf(NOW), signature, new byte[0], SECRET, NOW);
    }

    private static void refused(String user, String actor, String rights, String reason) {
        RequestContext.Rejected rejected = assertThrows(RequestContext.Rejected.class, () -> verify(user, actor, rights));
        assertEquals(401, rejected.problem().status());
        assertEquals(ErrorClass.PERMANENT, rejected.problem().errorClass());
        assertTrue(rejected.problem().detail().contains(reason), reason + " not in " + rejected.problem().detail());
    }

    @Test
    void aUserViaAModuleIsAcceptedWithTheUserKept() {
        RequestContext ctx = verify("\"oidc-sub-1\"", "{\"type\":\"user\",\"via\":\"module:kf-3d\",\"hookId\":null}",
                "[\"api.v1.m.kf-qc.checks.get\"]");
        assertEquals(RequestContext.Actor.Type.USER, ctx.actor().type());
        assertEquals("module:kf-3d", ctx.actor().via());
        assertEquals("kf-3d", ctx.actor().viaModule());
        assertEquals("oidc-sub-1", ctx.user());
    }

    @Test
    void theSystemViaAModuleIsAcceptedWithoutUserOrRights() {
        RequestContext ctx = verify("null", "{\"type\":\"system\",\"via\":\"module:kf-3d\",\"hookId\":null}", "[]");
        assertEquals(RequestContext.Actor.Type.SYSTEM, ctx.actor().type());
        assertEquals("kf-3d", ctx.actor().viaModule());
        assertNull(ctx.user());
    }

    @Test
    void aBadSlugAUserOnTheSystemRightsOnTheSystemOrAHookIdAreRefused() {
        refused("\"oidc-sub-1\"", "{\"type\":\"user\",\"via\":\"module:Bad\",\"hookId\":null}", "[]", "is not module:{slug}");
        refused("\"oidc-sub-1\"", "{\"type\":\"user\",\"via\":\"module:\",\"hookId\":null}", "[]", "is not module:{slug}");
        refused("\"oidc-sub-1\"", "{\"type\":\"system\",\"via\":\"module:kf-3d\",\"hookId\":null}", "[]", "actor system has no user");
        refused("null", "{\"type\":\"system\",\"via\":\"module:kf-3d\",\"hookId\":null}", "[\"x\"]", "has no rights");
        refused("\"oidc-sub-1\"", "{\"type\":\"user\",\"via\":\"module:kf-3d\",\"hookId\":\"h-1\"}", "[]", "has no hookId");
    }

    @Test
    void anotherViaStaysRefusedAndTheOldActorsHaveNoCallingModule() {
        refused("\"oidc-sub-1\"", "{\"type\":\"user\",\"via\":\"hook\",\"hookId\":null}", "[]", "actor user has no via and no hookId");
        assertNull(RequestContext.Actor.USER.viaModule());
        assertNull(RequestContext.Actor.PLATFORM.viaModule());
    }
}
