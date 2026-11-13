// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Decision.java. Do not edit by hand: fix Java, then re-derive.
import { log } from './log.js';
import type { Material } from './material.js';

/**
 * Answer of a before-hook: {@link Allow}, {@link Reject} (vetoes the operation, 422 on the platform, translated on the
 * user's language) or {@link Patch} (implies allow; own fields and the module's own extensions; not on delete).
 * Get them from `ctx.allow()`, `ctx.reject(key, params)`, `ctx.patch()`.
 */
export abstract class Decision {
  /** The key grammar of the manifest's `i18n` block (the platform's `HookDecision.KEY`). */
  static readonly KEY = /^[a-z][a-z0-9_.-]{0,119}$/;

  abstract toJson(): Record<string, unknown>;

  /**
   * A veto with a translation key and its parameters. A key or parameters the platform considers malformed are
   * dropped there, but the veto stands: the SDK logs a warning and sends them unchanged.
   */
  static reject(key: string, params?: Record<string, unknown> | null): Decision {
    if (key == null) throw new TypeError('key');
    if (!Decision.KEY.test(key)) {
      log().warn(`KF SDK: veto key "${key}" does not match ${Decision.KEY.source}; the platform keeps the veto but drops the key`);
    }
    return new Reject(key, params ?? {});
  }
}

export class Allow extends Decision {
  toJson(): Record<string, unknown> {
    return { decision: 'allow' };
  }
}

/** `{"decision":"reject","key":…,"params":{…}}`; `params` is always sent (`{}`). */
export class Reject extends Decision {
  readonly params: Readonly<Record<string, unknown>>;

  constructor(
    readonly key: string,
    params?: Record<string, unknown> | null,
  ) {
    super();
    this.params = Object.freeze({ ...(params ?? {}) });
  }

  toJson(): Record<string, unknown> {
    return { decision: 'reject', key: this.key, params: this.params };
  }
}

/** Builder-style patch; every setter returns this, so it can be returned directly from a handler. */
export class Patch extends Decision {
  private readonly values = new Map<string, unknown>();
  private readonly ownExtensions = new Map<string, unknown>();

  /** @internal */
  constructor(private readonly slug: string) {
    super();
  }

  name(name: string): this {
    this.values.set('name', name);
    return this;
  }

  materialList(materials: readonly Material[]): this {
    this.values.set('materialList', materials.map((m) => m.toMap()));
    return this;
  }

  /**
   * Sets one own field of the entity (the platform's patch guard decides which are patchable).
   * `extensions` is not set here: use {@link extension}.
   */
  set(field: string, value: unknown): this {
    if (field === 'extensions') throw new Error('use extension(field, value)');
    if (field == null) throw new TypeError('field');
    this.values.set(field, value);
    return this;
  }

  /** Sets `extensions.{own slug}.{field}`. */
  extension(field: string, value: unknown): this {
    this.ownExtensions.set(field, value);
    return this;
  }

  isEmpty(): boolean {
    return this.values.size === 0 && this.ownExtensions.size === 0;
  }

  /** @internal Merges another patch into this one (later wins). */
  merge(other: Patch): this {
    for (const [k, v] of other.values) this.values.set(k, v);
    for (const [k, v] of other.ownExtensions) this.ownExtensions.set(k, v);
    return this;
  }

  patchValues(): Record<string, unknown> {
    const patch: Record<string, unknown> = Object.fromEntries(this.values);
    if (this.ownExtensions.size > 0) patch.extensions = { [this.slug]: Object.fromEntries(this.ownExtensions) };
    return patch;
  }

  toJson(): Record<string, unknown> {
    return { decision: 'patch', patch: this.patchValues() };
  }
}
