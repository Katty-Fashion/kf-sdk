package ro.kf.modules.democard.pins;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.modules.democard.testsupport.ModuleDb;
import ro.kf.modules.democard.testsupport.ModuleIT;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ro.kf.modules.democard.testsupport.ModuleCalls.json;

/** The cards and the pins: model.v1 through the SDK, the pins in the module's database, isolated per tenant and user. */
class PinsIT extends ModuleIT {

    @Test
    @DisplayName("a member pins a model it sees; its card says pinned; /cards lists it with the model.v1 fields; unpin removes it")
    void pinCardsUnpin() throws Exception {
        String t = tenant();
        model(t, 42, "Rochie Ana");
        var pin = calls().user(t, "sub-ana", List.of(), "PUT", "/pins/42", null);
        assertEquals(200, pin.statusCode(), pin.body());
        assertThat(json(pin).get("pinned").asBoolean()).isTrue();

        var card = calls().user(t, "sub-ana", List.of(), "GET", "/models/42", null);
        assertEquals(200, card.statusCode(), card.body());
        assertEquals("Rochie Ana", json(card).get("name").asString());
        assertThat(json(card).get("pinned").asBoolean()).isTrue();

        var cards = calls().user(t, "sub-ana", List.of(), "GET", "/cards", null);
        assertEquals(200, cards.statusCode(), cards.body());
        var item = json(cards).get("items").get(0);
        assertEquals(42, item.get("modelId").asLong());
        assertEquals("ST-42", item.get("styleNumber").asString());
        assertEquals(421, item.get("currentVersionId").asLong());
        assertEquals("Owner 42", item.get("modelOwner").asString());
        assertThat(item.get("pinnedAt").asString()).isNotBlank();

        assertEquals(200, calls().user(t, "sub-ana", List.of(), "DELETE", "/pins/42", null).statusCode());
        assertThat(json(calls().user(t, "sub-ana", List.of(), "GET", "/cards", null)).get("items").size()).isZero();
        assertEquals(0, ModuleDb.pinsOf(t));
    }

    @Test
    @DisplayName("/cards gives each pinned model the image of its current version (one garment-image read), null without one")
    void cardsCarryTheImages() throws Exception {
        String t = tenant();
        model(t, 61, "Cu poză");
        model(t, 62, "Fără poză", null);
        image(t, 611, "https://s3.local/models/61/versions/611/garment-image/a.png");
        image(t, 621, null);
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/61", null);
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/62", null);

        var items = json(calls().user(t, "sub-ana", List.of(), "GET", "/cards", null)).get("items");
        assertEquals(2, items.size());
        var withImage = items.get(0).get("modelId").asLong() == 61 ? items.get(0) : items.get(1);
        var without = items.get(0).get("modelId").asLong() == 62 ? items.get(0) : items.get(1);
        assertEquals("https://s3.local/models/61/versions/611/garment-image/a.png",
                withImage.get("image").get("url").asString());
        assertEquals("2026-11-02T10:10:00Z", withImage.get("image").get("expiresAt").asString());
        assertThat(without.get("image").isNull()).isTrue();
        assertThat(without.get("modelOwner").isNull()).isTrue();
    }

    @Test
    @DisplayName("the pins are per user and per tenant: another user and another tenant see none")
    void isolation() throws Exception {
        String t = tenant();
        String other = tenant();
        model(t, 7, "Fustă");
        model(other, 7, "Alt model");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/7", null);
        assertThat(json(calls().user(t, "sub-ion", List.of(), "GET", "/cards", null)).get("items").size()).isZero();
        assertThat(json(calls().user(other, "sub-ana", List.of(), "GET", "/cards", null)).get("items").size()).isZero();
        assertThat(json(calls().user(t, "sub-ion", List.of(), "GET", "/models/7", null)).get("pinned").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a model the caller cannot see is the platform's 404, and nothing is pinned")
    void invisibleModel() throws Exception {
        String t = tenant();
        model(tenant(), 9, "Al altui tenant");
        assertEquals(404, calls().user(t, "sub-ana", List.of(), "PUT", "/pins/9", null).statusCode());
        assertEquals(404, calls().user(t, "sub-ana", List.of(), "GET", "/models/9", null).statusCode());
        assertEquals(0, ModuleDb.pinsOf(t));
    }

    @Test
    @DisplayName("a pin whose model is no longer visible is listed in missing, not as a card")
    void missing() throws Exception {
        String t = tenant();
        model(t, 11, "Bluză");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/11", null);
        FAKE.reads("model.v1").remove("11");
        var cards = json(calls().user(t, "sub-ana", List.of(), "GET", "/cards", null));
        assertThat(cards.get("items").size()).isZero();
        assertEquals(11, cards.get("missing").get(0).asLong());
    }

    @Test
    @DisplayName("the member routes refuse a hook's context (403) and a bad model id (400)")
    void memberOnly() throws Exception {
        String t = tenant();
        assertEquals(403, calls().hook(t, "PUT", "/pins/42").statusCode());
        assertEquals(400, calls().user(t, "sub-ana", List.of(), "PUT", "/pins/abc", null).statusCode());
    }

    @Test
    @DisplayName("DELETE /admin/pins (admin: tenant) needs the route's right; with it, every pin of the tenant goes")
    void adminReset() throws Exception {
        String t = tenant();
        model(t, 5, "Palton");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/5", null);
        calls().user(t, "sub-ion", List.of(), "PUT", "/pins/5", null);
        assertEquals(403, calls().user(t, "sub-ana", List.of(), "DELETE", "/admin/pins", null).statusCode());
        var reset = calls().user(t, "sub-admin", List.of("api.v1.m.kf-demo-model-card.admin.pins.delete"), "DELETE",
                "/admin/pins", null);
        assertEquals(200, reset.statusCode(), reset.body());
        assertEquals(2, json(reset).get("deleted").asInt());
        assertEquals(0, ModuleDb.pinsOf(t));
    }
}
