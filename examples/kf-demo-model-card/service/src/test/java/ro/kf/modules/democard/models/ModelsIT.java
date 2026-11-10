package ro.kf.modules.democard.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.modules.democard.testsupport.ModuleIT;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ro.kf.modules.democard.testsupport.ModuleCalls.json;

/**
 * Protocol 1.3.1 in the demo (SDK 0.9.1): the paged list of the tenant's models ({@code model.v1} without {@code ids},
 * with {@code modelOwner}) and the version's garment image ({@code model-version.garment-image.v1}, a presigned URL of
 * 10 minutes), on {@code GET /models} and {@code GET /models/{id}}.
 */
class ModelsIT extends ModuleIT {

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(item -> out.add(item.get("modelId").asLong()));
        return out;
    }

    private static JsonNode byId(JsonNode items, long id) {
        for (JsonNode item : items) if (item.get("modelId").asLong() == id) return item;
        throw new AssertionError("no card " + id + " in " + items);
    }

    @Test
    @DisplayName("GET /models pages the tenant's models by id, with modelOwner, the pin and the current version's image")
    void pagedList() throws Exception {
        String t = tenant();
        String other = tenant();
        model(t, 3, "Fustă", "Ana");
        model(t, 1, "Rochie", "Ioana");
        model(t, 2, "Bluză", null);
        model(other, 9, "Al altui tenant");
        image(t, 11, "https://s3.local/models/1/versions/11/garment-image/a.png");
        image(other, 21, "https://s3.local/models/9/versions/21/garment-image/b.png");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/2", null);

        var first = calls().user(t, "sub-ana", List.of(), "GET", "/models?page=0&size=2", null);
        assertEquals(200, first.statusCode(), first.body());
        JsonNode body = json(first);
        assertEquals(List.of(1L, 2L), ids(body.get("items")));
        assertEquals(0, body.get("page").asInt());
        assertEquals(2, body.get("size").asInt());
        assertEquals(3, body.get("total").asLong());

        JsonNode rochie = byId(body.get("items"), 1);
        assertEquals("Ioana", rochie.get("modelOwner").asString());
        assertEquals("https://s3.local/models/1/versions/11/garment-image/a.png", rochie.get("image").get("url").asString());
        assertEquals("2026-11-02T10:10:00Z", rochie.get("image").get("expiresAt").asString());
        assertThat(rochie.get("pinned").asBoolean()).isFalse();

        JsonNode bluza = byId(body.get("items"), 2);
        assertThat(bluza.get("modelOwner").isNull()).isTrue();
        assertThat(bluza.get("image").isNull()).as("the version of tenant B is not an image of tenant A").isTrue();
        assertThat(bluza.get("pinned").asBoolean()).isTrue();

        JsonNode second = json(calls().user(t, "sub-ana", List.of(), "GET", "/models?page=1&size=2", null));
        assertEquals(List.of(3L), ids(second.get("items")));
        assertEquals("Ana", second.get("items").get(0).get("modelOwner").asString());
    }

    @Test
    @DisplayName("GET /models without page and size: page 0, size 12; out of bounds (size 0, size 49, page -1, not a number) is 400")
    void pageBounds() throws Exception {
        String t = tenant();
        model(t, 4, "Palton");
        JsonNode body = json(calls().user(t, "sub-ana", List.of(), "GET", "/models", null));
        assertEquals(0, body.get("page").asInt());
        assertEquals(12, body.get("size").asInt());
        assertEquals(List.of(4L), ids(body.get("items")));
        for (String query : List.of("size=0", "size=49", "page=-1", "size=abc")) {
            var refused = calls().user(t, "sub-ana", List.of(), "GET", "/models?" + query, null);
            assertEquals(400, refused.statusCode(), query + ": " + refused.body());
        }
    }

    @Test
    @DisplayName("a member without api.v1.models.get gets the platform's 403 on the list and on the card")
    void withoutTheRight() throws Exception {
        String t = tenant();
        model(t, 5, "Sacou");
        FAKE.userRights(t, "sub-none");
        var list = calls().user(t, "sub-none", List.of(), "GET", "/models", null);
        assertEquals(403, list.statusCode(), list.body());
        assertThat(list.body()).contains("insufficient-rights");
        assertEquals(403, calls().user(t, "sub-none", List.of(), "GET", "/models/5", null).statusCode());
    }

    @Test
    @DisplayName("GET /models refuses a hook's context (403): the list is a person's")
    void memberOnly() throws Exception {
        assertEquals(403, calls().hook(tenant(), "GET", "/models").statusCode());
    }

    @Test
    @DisplayName("GET /models/{id}: modelOwner, and the image of the selected version (versionId of the model), else the current one")
    void cardImageOfTheSelectedVersion() throws Exception {
        String t = tenant();
        model(t, 6, "Cămașă", "Maria");
        image(t, 60, "https://s3.local/models/6/versions/60/garment-image/v1.png");
        image(t, 61, "https://s3.local/models/6/versions/61/garment-image/v2.png");

        JsonNode current = json(calls().user(t, "sub-ana", List.of(), "GET", "/models/6", null));
        assertEquals("Maria", current.get("modelOwner").asString());
        assertThat(current.get("image").get("url").asString()).endsWith("/61/garment-image/v2.png");

        JsonNode selected = json(calls().user(t, "sub-ana", List.of(), "GET", "/models/6?versionId=60", null));
        assertThat(selected.get("image").get("url").asString()).endsWith("/60/garment-image/v1.png");

        JsonNode foreign = json(calls().user(t, "sub-ana", List.of(), "GET", "/models/6?versionId=999", null));
        assertThat(foreign.get("image").get("url").asString()).as("a version of another model: the current one")
                .endsWith("/61/garment-image/v2.png");

        assertEquals(400, calls().user(t, "sub-ana", List.of(), "GET", "/models/6?versionId=x", null).statusCode());
    }

    @Test
    @DisplayName("GET /models/{id}: a version without an image (url null) or not visible is image null, the monogram")
    void cardWithoutImage() throws Exception {
        String t = tenant();
        model(t, 7, "Vestă");
        image(t, 71, null);
        assertThat(json(calls().user(t, "sub-ana", List.of(), "GET", "/models/7", null)).get("image").isNull()).isTrue();
        model(t, 8, "Geacă");
        image(tenant(), 81, "https://s3.local/models/8/versions/81/garment-image/x.png");
        assertThat(json(calls().user(t, "sub-ana", List.of(), "GET", "/models/8", null)).get("image").isNull()).isTrue();
    }
}
