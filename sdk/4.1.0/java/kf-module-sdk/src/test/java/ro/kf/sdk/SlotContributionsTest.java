package ro.kf.sdk;

import org.junit.jupiter.api.Test;
import ro.kf.sdk.testing.FakePlatform;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (protocol 2.0, sdk-protocol.md §7.12): the contributions of a module to the slots of
 * other modules — the kinds without code ({@link Surface#layer}, {@link Surface#data}), the warnings of the
 * registration ({@link KfModule#registrationWarnings()}), and the local check of the contributor ({@link ManifestCheck}).
 */
class SlotContributionsTest {

    private static final String LAYERS = "m.kf-3d.viewport.layers.v1";

    private static final String INSPECTOR = "m.kf-3d.studio.inspector.v1";

    @Test
    void layerAndData_areSentWithoutCode() {
        KfModule kf = KfModule.of("http://127.0.0.1:1", "slot-contrib", "kfm_x", "secret", null);
        kf.ui().contribute(LAYERS, Surface.layer("pin", "/m/slot-contrib/api/pins").key("pins"))
                .contribute("m.kf-3d.materials.v1", Surface.data(Map.of("name", "wool")).key("wool"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> surfaces = (List<Map<String, Object>>) kf.registrationBody().get("surfaces");
        assertEquals(Map.of("slug", LAYERS, "kind", "layer", "layer", "pin", "source", "/m/slot-contrib/api/pins",
                "key", "pins"), surfaces.get(0));
        assertEquals(Map.of("slug", "m.kf-3d.materials.v1", "kind", "data", "data", Map.of("name", "wool"),
                "key", "wool"), surfaces.get(1));
        assertFalse(surfaces.get(0).containsKey("entry"));
    }

    @Test
    void aDataContributionWithANullValueRegisters() {
        KfModule kf = KfModule.of("http://127.0.0.1:1", "slot-contrib", "kfm_x", "secret", null);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("finish", null);
        data.put("a", 1);
        kf.ui().contribute("m.kf-3d.materials.v1", Surface.data(data).key("wool"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> surfaces = (List<Map<String, Object>>) kf.registrationBody().get("surfaces");
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) surfaces.get(0).get("data");
        assertEquals(List.of("finish", "a"), List.copyOf(sent.keySet()));
        assertTrue(sent.containsKey("finish") && sent.get("finish") == null);
    }

    @Test
    void data_tooLargeOrMissing_isRefusedLocally() {
        IllegalArgumentException big = assertThrows(IllegalArgumentException.class,
                () -> Surface.data(Map.of("blob", "x".repeat(Surface.MAX_DATA_BYTES))));
        assertTrue(big.getMessage().contains("at most 65536 bytes"));
        assertThrows(IllegalArgumentException.class, () -> Surface.data(null));
        assertThrows(IllegalArgumentException.class, () -> Surface.layer("Pin", "/m/x/api/pins"));
        assertThrows(IllegalArgumentException.class, () -> Surface.layer("pin", "https://elsewhere/pins"));
    }

    @Test
    void d19_warningsOfTheRegistration_areExposed_andInternalIsLikeMissing() throws Exception {
        try (FakePlatform platform = FakePlatform.start("slot-contrib")) {
            platform.slot("kf-3d", "viewport.layers.v1", "B", "public", "pin")
                    .slot("kf-3d", "studio.inspector.v1", "A", "internal");
            KfModule kf = KfModule.of(platform.url(), "slot-contrib", platform.token(), platform.hookSecret(), null)
                    .withRetryDelays(List.of(Duration.ofMillis(50)));
            kf.ui().contribute(LAYERS, Surface.layer("pin", "/m/slot-contrib/api/pins"))
                    .contribute(INSPECTOR, Surface.react("/m/slot-contrib/ui/p.mjs", "Panel"))
                    .contribute("m.kf-3d.studio.missing.v1", Surface.react("/m/slot-contrib/ui/p.mjs", "Other"));
            try {
                kf.start();
                long until = System.currentTimeMillis() + 10_000;
                while (!kf.isRegistered() && System.currentTimeMillis() < until) Thread.sleep(20);
                assertTrue(kf.isRegistered());

                List<String> warnings = kf.registrationWarnings();
                assertEquals(2, warnings.size(), warnings.toString());
                // the internal slot and the missing one get the same sentence, only the slug differs
                assertEquals(warnings.get(0).replace("surfaces[1]", "").replace(INSPECTOR, "X"),
                        warnings.get(1).replace("surfaces[2]", "").replace("m.kf-3d.studio.missing.v1", "X"));
            } finally {
                kf.stop();
            }
        }
    }

    @Test
    void d19_anInternalModuleGetsTheInternalSlot() throws Exception {
        try (FakePlatform platform = FakePlatform.start("slot-contrib")) {
            platform.slot("kf-3d", "studio.inspector.v1", "A", "internal").internalClass(true);
            KfModule kf = KfModule.of(platform.url(), "slot-contrib", platform.token(), platform.hookSecret(), null);
            kf.ui().contribute(INSPECTOR, Surface.react("/m/slot-contrib/ui/p.mjs", "Panel"));
            try {
                kf.start();
                long until = System.currentTimeMillis() + 10_000;
                while (!kf.isRegistered() && System.currentTimeMillis() < until) Thread.sleep(20);
                assertTrue(kf.isRegistered());
                assertEquals(List.of(), kf.registrationWarnings());
            } finally {
                kf.stop();
            }
        }
    }

    @Test
    void manifest_theOwnerMustBeRequired_mandatoryOrOptional() {
        String base = """
                {"schemaVersion":3,"slug":"slot-contrib","name":"module.name","version":"1.0.0","platform":">=4.1",
                 "i18n":{"en":{"module.name":"Slot contributor"}},%s
                 "capabilities":{"surfaces":[{"slug":"m.kf-3d.viewport.layers.v1","kind":"layer"}]}}
                """;
        ManifestCheck.Result missing = ManifestCheck.validate(base.formatted(""), java.util.Set.of());
        assertFalse(missing.ok());
        assertTrue(missing.errors().stream().anyMatch(e -> e.contains("the owner \"kf-3d\" is not in requires")),
                missing.errors().toString());

        ManifestCheck.Result optional = ManifestCheck.validate(base.formatted(
                "\"requires\":[{\"slug\":\"kf-3d\",\"versionRange\":\">=1.0.0\",\"optional\":true}],"), java.util.Set.of());
        assertTrue(optional.ok(), optional.errors().toString());

        ManifestCheck.Result onHost = ManifestCheck.validate(base.formatted("")
                .replace("m.kf-3d.viewport.layers.v1", "models.detail.tabs"), java.util.Set.of());
        assertTrue(onHost.errors().stream().anyMatch(e -> e.contains("is only for a slot of a module")),
                onHost.errors().toString());
    }
}
