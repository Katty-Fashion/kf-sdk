// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FeatureFlags.java. Do not edit by hand: fix Java, then re-derive.

/**
 * The module's effective feature flags (declared in `kf-module-conf.json`, toggled by the platform admin). Fetched once
 * after the registration (`GET /api/v1/platform/modules/{slug}/flags` with the module token) and refreshed from the
 * `kfflags` attribute of every callback, so a handler always sees the values the platform had when it sent the event.
 *
 *   kf.models().beforeUpdate((ctx) => (kf.flags().isEnabled('autocalc') ? ctx.patch().extension('x', 1) : ctx.allow()));
 */
export class FeatureFlags {
  private values: Readonly<Record<string, boolean>> = Object.freeze({});
  private known = false;

  /** Effective value; an unknown key (or flags not received yet) is off. */
  isEnabled(key: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.values, key) && this.values[key] === true;
  }

  /** Every flag, sorted by key (read-only). */
  all(): Readonly<Record<string, boolean>> {
    return this.values;
  }

  /** True once the flags were fetched or received with a callback. */
  isKnown(): boolean {
    return this.known;
  }

  /** @internal */
  replace(raw: Record<string, unknown>): void {
    const next: Record<string, boolean> = {};
    for (const k of Object.keys(raw).sort()) next[k] = raw[k] === true;
    this.values = Object.freeze(next);
    this.known = true;
  }
}
