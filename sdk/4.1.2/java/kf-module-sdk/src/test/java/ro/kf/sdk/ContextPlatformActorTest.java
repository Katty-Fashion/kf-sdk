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
 * The actor {@code {type: system, via: platform, hookId: null}} (protocol 1.1; sdk-protocol.md §9.2): the
 * platform calls a route the module declares in {@code provides} as the system, without a user and without a hook.
 * Coherent only with {@code user} null, {@code hookId} null and {@code rights} []; a system context never yields a
 * user the module could deduce.
 */
class ContextPlatformActorTest {

    private static final String SECRET = "platform-actor-secret";
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
    @DisplayName("system via platform, user null, hookId null, rights [] is accepted; no user is deduced")
    void systemViaPlatformIsAccepted() {
        RequestContext ctx = verify("null", "{\"type\":\"system\",\"via\":\"platform\",\"hookId\":null}", "[]");
        assertEquals(RequestContext.Actor.Type.SYSTEM, ctx.actor().type());
        assertEquals("platform", ctx.actor().via());
        assertNull(ctx.actor().hookId());
        assertNull(ctx.user());
        assertTrue(ctx.rights().isEmpty());
        assertEquals(RequestContext.Actor.PLATFORM, ctx.actor());
    }

    @Test
    @DisplayName("system via platform with a hookId is refused")
    void systemViaPlatformWithAHookIdIsRefused() {
        refused("null", "{\"type\":\"system\",\"via\":\"platform\",\"hookId\":\"h-1\"}", "[]",
                "actor system via platform has no hookId");
    }

    @Test
    @DisplayName("system via platform with a user is refused")
    void systemViaPlatformWithAUserIsRefused() {
        refused("\"sub-1\"", "{\"type\":\"system\",\"via\":\"platform\",\"hookId\":null}", "[]",
                "actor system has no user");
    }

    @Test
    @DisplayName("system via platform with rights is refused")
    void systemViaPlatformWithRightsIsRefused() {
        refused("null", "{\"type\":\"system\",\"via\":\"platform\",\"hookId\":null}", "[\"api.v1.m.c11.stations.get\"]",
                "actor system via platform has no rights");
    }

    @Test
    @DisplayName("system via an unknown channel is refused")
    void systemViaAnUnknownChannelIsRefused() {
        refused("null", "{\"type\":\"system\",\"via\":\"cron\",\"hookId\":null}", "[]",
                "actor system requires via = hook or platform");
    }
}
