// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/UiContributions.java. Do not edit by hand: fix Java, then re-derive.
import type { Surface, SurfaceRegistration } from './surface.js';
import type { KfSurfaceSlug } from './surfaces.generated.js';

/**
 * The module's UI contributions to the host's frontend contact surfaces, sent with the registration. Only the
 * surfaces of the platform's release list exist for a module: any other slug is refused at
 * registration (`422`, the same answer for an internal and an unknown surface). The release list is
 * `sdk/catalog/surfaces.public.json`; its slugs autocomplete through `KfSurfaceSlug` (surfaces.generated.d.ts).
 */
export class UiContributions {
  private readonly entries: { slug: string; surface: Surface }[] = [];

  /** Contributes `surface` to the host surface `slug`, e.g. `models.form.edit.fields.after`. */
  contribute(slug: KfSurfaceSlug | (string & {}), surface: Surface): this {
    if (slug == null) throw new TypeError('slug');
    if (surface == null) throw new TypeError('surface');
    this.entries.push({ slug, surface });
    return this;
  }

  /** @internal The `surfaces` part of the registration body. */
  registration(): SurfaceRegistration[] {
    return this.entries.map((e) => e.surface.toJson(e.slug));
  }
}
