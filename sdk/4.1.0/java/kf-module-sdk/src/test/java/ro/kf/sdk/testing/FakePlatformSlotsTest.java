package ro.kf.sdk.testing;

import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Surface;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} answers a registration on a slot as {@code SurfaceValidator} does -- the
 * kind of the contribution must fit the kind of the slot, a {@code layer} needs a GET route the module declares -- so a
 * module's tests do not pass where the platform would refuse the upload.
 */
class FakePlatformSlotsTest {

    private static final String MANIFEST = """
            {"schemaVersion":3,"slug":"slot-contrib","name":"module.name","version":"1.0.0","platform":">=4.1",
             "i18n":{"en":{"module.name":"Slot contributor"}},
             "requires":[{"slug":"kf-3d","version":">=1.0.0"}],
             "capabilities":{"routes":[{"method":"GET","path":"/m/slot-contrib/api/pins","right":"api.v1.m.slot-contrib.api.pins.get"}]}}""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Answer(int status, String body) {
    }

    private static Answer register(FakePlatform platform, KfModule kf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(platform.url()
                        + "/api/v1/platform/modules/slot-contrib/registrations"))
                .header("Authorization", "Bearer " + platform.token())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(kf.registrationBody()),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body());
    }

    private static FakePlatform fake() throws Exception {
        FakePlatform platform = FakePlatform.start("slot-contrib");
        platform.manifest(MANIFEST);
        platform.slot("kf-3d", "viewport.layers.v1", "B", "public", "pin")
                .slot("kf-3d", "studio.inspector.v1", "A", "public")
                .slot("kf-3d", "materials.v1", "R", "public")
                .slot("kf-3d", "bus.v1", "D", "public");
        return platform;
    }

    private static KfModule module(FakePlatform platform, Object... slotAndSurface) {
        KfModule kf = KfModule.of(platform.url(), "slot-contrib", platform.token(), platform.hookSecret(), null);
        for (int i = 0; i < slotAndSurface.length; i += 2) {
            kf.ui().contribute((String) slotAndSurface[i], (Surface) slotAndSurface[i + 1]);
        }
        return kf;
    }

    @Test
    void aContributionWhoseKindDoesNotFitTheSlotIs422() throws Exception {
        try (FakePlatform platform = fake()) {
            KfModule react = module(platform, "m.kf-3d.viewport.layers.v1", Surface.react("/m/slot-contrib/ui/p.mjs", "Panel"));
            Answer onB = register(platform, react);
            assertEquals(422, onB.status(), onB.body());
            assertTrue(onB.body().contains("does not fit the slot"), onB.body());

            KfModule data = module(platform, "m.kf-3d.studio.inspector.v1", Surface.data(Map.of("a", 1)));
            assertEquals(422, register(platform, data).status());

            KfModule any = module(platform, "m.kf-3d.bus.v1", Surface.react("/m/slot-contrib/ui/p.mjs", "Panel"));
            Answer onD = register(platform, any);
            assertEquals(422, onD.status());
            assertTrue(onD.body().contains("the bus in the page; nothing is registered on it"), onD.body());
        }
    }

    @Test
    void aLayerWhoseSourceIsNotADeclaredGetRouteIs422() throws Exception {
        try (FakePlatform platform = fake()) {
            KfModule kf = module(platform, "m.kf-3d.viewport.layers.v1", Surface.layer("pin", "/m/slot-contrib/api/nope"));
            Answer answer = register(platform, kf);
            assertEquals(422, answer.status(), answer.body());
            assertTrue(answer.body().contains("that is a GET route this module declares"), answer.body());
        }
    }

    @Test
    void aFittingContributionStillGetsTheD19Warnings() throws Exception {
        try (FakePlatform platform = fake()) {
            KfModule fitting = module(platform,
                    "m.kf-3d.viewport.layers.v1", Surface.layer("pin", "/m/slot-contrib/api/pins"),
                    "m.kf-3d.materials.v1", Surface.data(Map.of("a", 1)).key("a"),
                    "m.kf-3d.studio.inspector.v1", Surface.react("/m/slot-contrib/ui/p.mjs", "Panel"),
                    "m.absent.slot.v1", Surface.react("/m/slot-contrib/ui/p.mjs", "Other"));
            Answer answer = register(platform, fitting);
            assertEquals(200, answer.status(), answer.body());
            Map<?, ?> parsed = JSON.readValue(answer.body(), Map.class);
            List<?> warnings = (List<?>) parsed.get("warnings");
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).toString().contains("m.absent.slot.v1"));
        }
    }
}
