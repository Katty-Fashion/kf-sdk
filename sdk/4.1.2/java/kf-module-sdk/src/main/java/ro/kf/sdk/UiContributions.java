package ro.kf.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The module's UI contributions to the host's frontend contact surfaces, sent with the registration. Only the surfaces
 * of the platform's release list exist for a module: any other slug is refused at registration
 * ({@code 422}, the same answer for an internal and an unknown surface). The release list is
 * {@code sdk/catalog/surfaces.public.json}; its slugs are the constants of {@code Surfaces} (GENERATED).
 */
public final class UiContributions {

    private record Entry(String slug, Surface surface) {
    }

    private final List<Entry> entries = new ArrayList<>();

    UiContributions() {
    }

    /** Contributes {@code surface} to the host surface {@code slug}, e.g. {@code models.form.edit.fields.after}. */
    public UiContributions contribute(String slug, Surface surface) {
        entries.add(new Entry(Objects.requireNonNull(slug, "slug"), Objects.requireNonNull(surface, "surface")));
        return this;
    }

    /** The {@code surfaces} part of the registration body. */
    List<Map<String, Object>> registration() {
        return entries.stream().map(e -> e.surface().toJson(e.slug())).toList();
    }
}
