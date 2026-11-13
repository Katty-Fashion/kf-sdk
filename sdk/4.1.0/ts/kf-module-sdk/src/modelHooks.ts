// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelHooks.java. Do not edit by hand: fix Java, then re-derive.
import type { AfterHandler, BeforeHandler, FieldChangeHandler } from './hookEvents.js';

/**
 * What the platform does when this module's before hook fails (timeout, error, unreadable answer): `allow` lets the
 * mutation through (the default: a module that is down must not stop every tenant from saving), `block` refuses it.
 */
export type OnFailure = 'allow' | 'block';

export const OnFailure = {
  ALLOW: 'allow' as OnFailure,
  BLOCK: 'block' as OnFailure,
} as const;

/** @internal */
export interface Before {
  priority: number;
  onFailure: OnFailure;
  handler: BeforeHandler;
}

/** @internal */
export interface FieldWatch {
  paths: readonly string[];
  handler: FieldChangeHandler;
}

/** One entry of the `hooks` part of the registration body. */
export interface HookRegistration {
  event: string;
  priority?: number;
  onFailure?: OnFailure;
  fields?: string[];
}

/**
 * Hooks on the platform's models (`kf.models()`) and, from protocol 2.0, on its orders (`kf.orders()`: the same shape on
 * the points `order.*`, public, sdk-protocol.md §16.2; a patch there carries only the module's own
 * `extensions`, the SDK refuses one on a core field). Several handlers for the same event run in declaration order inside the module;
 * the platform sees one hook per event:
 * - `priority`: the lowest declared (default {@link ModelHooks.DEFAULT_PRIORITY}, lower runs first);
 * - `onFailure` (before hooks only): `block` if any handler of the event declares it, else `allow`; always
 *   sent explicitly. `block` also needs `capabilities.hooks[].onFailure = "block"` in `kf-module-conf.json` (checked
 *   locally by `ManifestCheck` and by the platform).
 *
 * The hook points are hand-written constants of {@link HookEvents} until the catalog generates them.
 *
 * The Java overloads `beforeX(handler)`, `beforeX(priority, handler)`, `beforeX(priority, onFailure, handler)` are one
 * TS method with an options object: `beforeUpdate(handler, { priority: 50, onFailure: 'block' })`.
 */
export class ModelHooks {
  static readonly DEFAULT_PRIORITY = 100;

  /** @internal */ readonly before = new Map<string, Before[]>();
  /** @internal */ readonly after = new Map<string, AfterHandler[]>();
  /** @internal */ readonly fieldWatches: FieldWatch[] = [];

  /** The entity of the points: `model` (`kf.models()`) or `order` (`kf.orders()`, §16.2). */
  private readonly entityName: string;

  /** @internal The same hooks on the points `{entity}.before|after.*` and `{entity}.field.changed`. */
  constructor(entity = 'model') {
    if (typeof entity !== 'string' || entity === '') throw new TypeError('entity');
    this.entityName = entity;
  }

  /** The entity of these hooks' points (`model` or `order`). */
  entity(): string {
    return this.entityName;
  }

  /** @internal `{entity}.field.changed`. */
  fieldChanged(): string {
    return `${this.entityName}.field.changed`;
  }

  // ---- before -----------------------------------------------------------------------------

  beforeCreate(handler: BeforeHandler, opts?: { priority?: number; onFailure?: OnFailure }): this {
    return this.addBefore(`${this.entityName}.before.create`, handler, opts);
  }

  beforeUpdate(handler: BeforeHandler, opts?: { priority?: number; onFailure?: OnFailure }): this {
    return this.addBefore(`${this.entityName}.before.update`, handler, opts);
  }

  /** Patches are not allowed on delete: return allow or reject. */
  beforeDelete(handler: BeforeHandler, opts?: { priority?: number; onFailure?: OnFailure }): this {
    return this.addBefore(`${this.entityName}.before.delete`, handler, opts);
  }

  // ---- after (asynchronous, retried by the platform until a 2xx) ---------------------------

  afterCreate(handler: AfterHandler): this {
    return this.addAfter(`${this.entityName}.after.create`, handler);
  }

  afterUpdate(handler: AfterHandler): this {
    return this.addAfter(`${this.entityName}.after.update`, handler);
  }

  afterDelete(handler: AfterHandler): this {
    return this.addAfter(`${this.entityName}.after.delete`, handler);
  }

  // ---- field changes ----------------------------------------------------------------------

  /**
   * Called once per changed field among `paths` (`name`, `materialList`, `extensions.{slug}.{field}`); an empty list
   * (or no path) means every field.
   */
  onFieldChange(paths: string | readonly string[], handler: FieldChangeHandler): this {
    const list = typeof paths === 'string' ? [paths] : [...paths];
    this.fieldWatches.push({ paths: Object.freeze(list), handler });
    return this;
  }

  // ---- internals --------------------------------------------------------------------------

  private addBefore(event: string, handler: BeforeHandler, opts?: { priority?: number; onFailure?: OnFailure }): this {
    if (typeof handler !== 'function') throw new TypeError('handler');
    const onFailure = opts?.onFailure ?? OnFailure.ALLOW;
    if (onFailure !== 'allow' && onFailure !== 'block') throw new TypeError('onFailure');
    const list = this.before.get(event) ?? [];
    list.push({ priority: opts?.priority ?? ModelHooks.DEFAULT_PRIORITY, onFailure, handler });
    this.before.set(event, list);
    return this;
  }

  private addAfter(event: string, handler: AfterHandler): this {
    if (typeof handler !== 'function') throw new TypeError('handler');
    const list = this.after.get(event) ?? [];
    list.push(handler);
    this.after.set(event, list);
    return this;
  }

  /** @internal The `hooks` part of the registration body. */
  registration(): HookRegistration[] {
    const hooks: HookRegistration[] = [];
    for (const [event, handlers] of this.before) {
      const block = handlers.some((b) => b.onFailure === OnFailure.BLOCK);
      hooks.push({
        event,
        priority: handlers.length === 0 ? ModelHooks.DEFAULT_PRIORITY : Math.min(...handlers.map((b) => b.priority)),
        onFailure: block ? OnFailure.BLOCK : OnFailure.ALLOW,
      });
    }
    for (const event of this.after.keys()) hooks.push({ event });
    if (this.fieldWatches.length > 0) {
      const all = this.fieldWatches.some((w) => w.paths.length === 0);
      const union = all ? [] : [...new Set(this.fieldWatches.flatMap((w) => w.paths))];
      hooks.push({ event: this.fieldChanged(), fields: union });
    }
    return hooks;
  }
}
