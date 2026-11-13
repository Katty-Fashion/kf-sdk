package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code person} in the signed context (protocol 1.2; sdk-protocol.md §9.2): on the
 * routes {@code audience: member} the platform adds, as the last key after {@code requestId},
 * {@code "person": {"email": <text|null>, "displayName": <text|null>}} -- the caller's e-mail and display name at the
 * time of the request. Absent on every other route and on a system context. A module never authorizes on it (the
 * identity stays {@code user}); a context without it is read as before.
 */
class ContextPersonTest {

    private static final String SECRET = "person-secret";
    private static final long NOW = 1_760_000_000L;
    private static final String USER = "{\"type\":\"user\",\"via\":null,\"hookId\":null}";
    private static final String HOOK = "{\"type\":\"system\",\"via\":\"hook\",\"hookId\":\"h-1\"}";

    private static RequestContext verify(String user, String actor, String person) {
        String json = "{\"v\":1,\"tenant\":\"t-1\",\"user\":" + user + ",\"actor\":" + actor + ",\"locale\":\"ro\","
                + "\"rights\":[],\"method\":\"POST\",\"path\":\"/tickets\",\"query\":null,\"ts\":" + NOW
                + ",\"requestId\":\"r-1\"" + (person == null ? "" : ",\"person\":" + person) + "}";
        String context = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = Signatures.sign(SECRET, String.valueOf(NOW), (context + ".").getBytes(StandardCharsets.US_ASCII));
        return RequestContext.verify(context, String.valueOf(NOW), signature, new byte[0], SECRET, NOW);
    }

    private static void refused(String user, String actor, String person, String reason) {
        RequestContext.Rejected rejected = assertThrows(RequestContext.Rejected.class, () -> verify(user, actor, person));
        assertEquals(401, rejected.problem().status());
        assertEquals("https://kf.ro/problems/invalid-context", rejected.problem().type());
        assertEquals(ErrorClass.PERMANENT, rejected.problem().errorClass());
        assertTrue(rejected.problem().detail().contains(reason), reason + " not in " + rejected.problem().detail());
    }

    @Test
    @DisplayName("a member route's context carries person: e-mail and display name are read")
    void personIsRead() {
        RequestContext ctx = verify("\"sub-1\"", USER, "{\"email\":\"ana@kf.ro\",\"displayName\":\"Ana Pop\"}");
        assertEquals(new RequestContext.Person("ana@kf.ro", "Ana Pop"), ctx.person());
        assertEquals("sub-1", ctx.user());
    }

    @Test
    @DisplayName("person with null fields is accepted; a missing field reads null")
    void personWithNullFieldsIsAccepted() {
        assertEquals(new RequestContext.Person(null, "Ana Pop"),
                verify("\"sub-1\"", USER, "{\"email\":null,\"displayName\":\"Ana Pop\"}").person());
        assertEquals(new RequestContext.Person("ana@kf.ro", null),
                verify("\"sub-1\"", USER, "{\"email\":\"ana@kf.ro\"}").person());
    }

    @Test
    @DisplayName("a context without person is accepted and reads person null (every route but member, a platform before 1.2)")
    void aContextWithoutPersonIsAccepted() {
        assertNull(verify("\"sub-1\"", USER, null).person());
        assertNull(verify("\"sub-1\"", USER, "null").person());
    }

    @Test
    @DisplayName("person that is not an object, or with a field that is not text, is refused")
    void aMalformedPersonIsRefused() {
        refused("\"sub-1\"", USER, "\"ana@kf.ro\"", "context person must be an object");
        refused("\"sub-1\"", USER, "{\"email\":42,\"displayName\":\"Ana\"}", "context person.email must be text or null");
        refused("\"sub-1\"", USER, "{\"email\":\"a@kf.ro\",\"displayName\":[]}",
                "context person.displayName must be text or null");
    }

    @Test
    @DisplayName("a system context with person is refused")
    void aSystemContextCarriesNoPerson() {
        refused("null", HOOK, "{\"email\":\"ana@kf.ro\",\"displayName\":\"Ana Pop\"}", "a system context carries no person");
    }

    @Test
    @DisplayName("the constructor with the eleven components of SDK 0.7.x still builds a context, without person")
    void theElevenComponentConstructorStays() {
        RequestContext ctx = new RequestContext(1, "t", "u", RequestContext.Actor.USER, "en", List.of(), "POST", "/n",
                null, NOW, "r");
        assertNull(ctx.person());
    }
}
