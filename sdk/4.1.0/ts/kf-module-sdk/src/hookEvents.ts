// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java. Do not edit by hand: fix Java, then re-derive.
import type { BeforeContext } from './beforeContext.js';
import type { Decision } from './decision.js';
import { ModelSnapshot } from './modelSnapshot.js';
import { RequestContext } from './requestContext.js';
import { Signatures } from './signatures.js';

/**
 * The hook points, the event payloads delivered to after / field-change handlers, and the handler types.
 *
 * The point ids are hand-written here, aligned with the vocabulary fixed (`HookEvent` / `HookPointCatalog` of
 * the platform) and, for `TENANT_AFTER_DELETE`, with protocol 1.2, for `TENANT_AFTER_SOFT_DELETE` and
 * `TENANT_AFTER_RESTORE` with protocol 1.3; generating them from the release catalog is deferred.
 */
export const HookEvents = {
  BEFORE_CREATE: 'model.before.create',
  BEFORE_UPDATE: 'model.before.update',
  BEFORE_DELETE: 'model.before.delete',
  AFTER_CREATE: 'model.after.create',
  AFTER_UPDATE: 'model.after.update',
  AFTER_DELETE: 'model.after.delete',
  FIELD_CHANGED: 'model.field.changed',
  /**
   * Protocol 1.2 (delivered by the platform, its SDK handler): the
   * after-commit point of a tenant's deletion, delivered at least once to every INSTALLED module subscribed to it
   * (liveness is not evaluated: the tenant no longer exists); the module deletes what it keeps for the tenant,
   * idempotently. Its envelope: `kftenant` = the deleted tenant, `data = {entity: "tenant", id, before: {id}, after:
   * null, changed: []}` (sdk-protocol.md §6.1).
   */
  TENANT_AFTER_DELETE: 'tenant.after.delete',
  /**
   * Protocol 1.3 (delivered by the platform): the after-commit point of
   * a tenant's soft-delete -- access to the tenant is off and recoverable -- delivered at least once to every module
   * subscribed to it and LIVE for the tenant. The module stops its work for the tenant (jobs, synchronisations) and
   * deletes nothing: `TENANT_AFTER_DELETE` stays the purge. Its envelope: `kftenant` = the tenant,
   * `data = {entity: "tenant", id, before: {id}, after: {id}, changed: []}` (sdk-protocol.md §6.1).
   */
  TENANT_AFTER_SOFT_DELETE: 'tenant.after.soft-delete',
  /**
   * Protocol 1.3 (delivered by the platform): the after-commit point of
   * a soft-deleted tenant's restore, delivered like `TENANT_AFTER_SOFT_DELETE`; the module resumes its work for the
   * tenant. The same envelope (sdk-protocol.md §6.1).
   */
  TENANT_AFTER_RESTORE: 'tenant.after.restore',
  /**
   * Protocol 2.0 (sdk-protocol.md §6.1, §16.2): the order points, public since -- the
   * model's shape on the order (`kf.orders()`, a {@link ModelHooks} on the entity `order`). A before hook may veto, and
   * patch only `extensions.{own slug}`: the order's core fields carry `patch: []`. The `customer.*` points stay
   * internal (not carried here).
   */
  ORDER_BEFORE_CREATE: 'order.before.create',
  ORDER_BEFORE_UPDATE: 'order.before.update',
  ORDER_BEFORE_DELETE: 'order.before.delete',
  ORDER_AFTER_CREATE: 'order.after.create',
  ORDER_AFTER_UPDATE: 'order.after.update',
  ORDER_AFTER_DELETE: 'order.after.delete',
  /**
   * `order.field.changed` (`fieldFilter: true`): one delivery per changed path, on `ORDER_PATH_STATUS`,
   * `ORDER_PATH_ENTRIES` and `extensions.{slug}.{field}`.
   */
  ORDER_FIELD_CHANGED: 'order.field.changed',
  /** The path of the order's status on `ORDER_FIELD_CHANGED` (the intent `{status}`). */
  ORDER_PATH_STATUS: 'status',
  /** The path of the order's lines on `ORDER_FIELD_CHANGED`: `[{id, modelVersionId, quantity?}]`. */
  ORDER_PATH_ENTRIES: 'entries',
  /** The statuses of an order (§16.2). `ORDER_STATUS_COMPLETED` is terminal and reached only from a flow. */
  ORDER_STATUS_DRAFT: 'DRAFT',
  ORDER_STATUS_CONFIRMED: 'CONFIRMED',
  ORDER_STATUS_IN_PRODUCTION: 'IN_PRODUCTION',
  ORDER_STATUS_SHIPPED: 'SHIPPED',
  ORDER_STATUS_CANCELLED: 'CANCELLED',
  ORDER_STATUS_COMPLETED: 'COMPLETED',
} as const;

/** The seven order points, in the documented order. Java: `HookEvents.ORDER_POINTS`. */
export const ORDER_POINTS: readonly string[] = [HookEvents.ORDER_BEFORE_CREATE, HookEvents.ORDER_BEFORE_UPDATE,
  HookEvents.ORDER_BEFORE_DELETE, HookEvents.ORDER_AFTER_CREATE, HookEvents.ORDER_AFTER_UPDATE,
  HookEvents.ORDER_AFTER_DELETE, HookEvents.ORDER_FIELD_CHANGED];

/** Every status of an order, in the order of the platform's `OrderStatus`. Java: `HookEvents.ORDER_STATUSES`. */
export const ORDER_STATUSES: readonly string[] = [HookEvents.ORDER_STATUS_DRAFT, HookEvents.ORDER_STATUS_CONFIRMED,
  HookEvents.ORDER_STATUS_IN_PRODUCTION, HookEvents.ORDER_STATUS_SHIPPED, HookEvents.ORDER_STATUS_CANCELLED,
  HookEvents.ORDER_STATUS_COMPLETED];

/** The tenant points (protocols 1.2 and 1.3): the same envelope, checked the same way. Java: `HookEvents.TENANT_POINTS`. */
export const TENANT_POINTS: readonly string[] = [HookEvents.TENANT_AFTER_DELETE, HookEvents.TENANT_AFTER_SOFT_DELETE,
  HookEvents.TENANT_AFTER_RESTORE];

/**
 * Protocol 2.0 (sdk-protocol.md §6.3): the prefix of a module event's point, `m.{owner}.after.{name}`. No
 * platform point starts with it. Java: `HookEvents.MODULE_EVENT_PREFIX`.
 */
export const MODULE_EVENT_PREFIX = 'm.';

/** The grammar of a module event's point: `m.{owner}.after.{name}` (the owner's slug, the event's name). */
export const MODULE_EVENT_POINT = /^m\.([a-z0-9][a-z0-9-]{1,40})\.after\.([a-z][a-z0-9-]{1,40})$/;

/** The point of the event `name` of `owner`: `m.{owner}.after.{name}`. Java: `HookEvents.moduleEventPoint`. */
export function moduleEventPoint(owner: string, name: string): string {
  return `${MODULE_EVENT_PREFIX}${owner}.after.${name}`;
}

/** Return a {@link Decision} (null/undefined = allow). A throw becomes HTTP 500 (the platform applies `onFailure`). */
export type BeforeHandler = (ctx: BeforeContext) => Decision | null | undefined | void | Promise<Decision | null | undefined | void>;
export type AfterHandler = (event: AfterEvent) => void | Promise<void>;
export type FieldChangeHandler = (change: FieldChange) => void | Promise<void>;
/**
 * A module event `m.{owner}.after.{name}` (protocol 2.0, §6.3): a throw becomes HTTP 500 `transient` (retried; the
 * delivery is at least once).
 */
export type ModuleEventHandler = (event: ModuleEvent) => void | Promise<void>;
/** `tenant.after.delete` (protocol 1.2): a throw becomes HTTP 500 `transient` (retried). */
export type TenantDeletedHandler = (deleted: TenantDeleted) => void | Promise<void>;
/** `tenant.after.soft-delete` (protocol 1.3): a throw becomes HTTP 500 `transient` (retried). */
export type TenantSoftDeletedHandler = (softDeleted: TenantSoftDeleted) => void | Promise<void>;
/** `tenant.after.restore` (protocol 1.3): a throw becomes HTTP 500 `transient` (retried). */
export type TenantRestoredHandler = (restored: TenantRestored) => void | Promise<void>;

/**
 * The CloudEvents attributes of one callback (the envelope on the wire):
 * - `event`: the hook point, `type` without `kf.`, e.g. `model.before.update`
 * - `deliveryId`: CloudEvents `id`, one per delivery (per module and, for a field change, per path), stable across
 *   retries
 * - `eventId`: `kfeventid`, the `eventId` (`outbox_event.event_id`), the same on every copy of one event
 *   -- the idempotency key; null on a delivery queued by an older platform
 * - `time`: ISO-8601; `tenant`: `kftenant`; `locale`: `kflocale` (`en` by default); `actor`: `kfactor`, the OIDC subject
 *   of the user, null for the system; `aggregateType`: `kfaggregatetype`; `subject`: the aggregate's id as text (null on
 *   a before-create); `envelopeVersion`: `kfenvelopeversion`, e.g. `"1"`; `module`: `kfmodule`
 * - `delivery`: protocol 2.0, the context and proof the platform put on this after delivery; null on a
 *   before hook, from a platform before 2.0, or when the headers were not usable -- read it through
 *   {@link Envelope.deliveryContext}
 */
export class Envelope {
  constructor(
    readonly event: string,
    readonly deliveryId: string | null,
    readonly eventId: string | null,
    readonly time: string | null,
    readonly tenant: string | null,
    readonly locale: string | null,
    readonly actor: string | null,
    readonly aggregateType: string | null,
    readonly subject: string | null,
    readonly envelopeVersion: string | null,
    readonly module: string | null,
    readonly delivery: DeliveryContext | null = null,
  ) {}

  /** @internal */
  static of(ce: Record<string, unknown>, delivery: DeliveryContext | null = null): Envelope {
    const type = text(ce.type);
    const event = type == null ? '' : type.startsWith('kf.') ? type.substring(3) : type;
    return new Envelope(event, text(ce.id), text(ce.kfeventid), text(ce.time), text(ce.kftenant), text(ce.kflocale),
      text(ce.kfactor), text(ce.kfaggregatetype), text(ce.subject), text(ce.kfenvelopeversion), text(ce.kfmodule),
      delivery);
  }

  /**
   * Protocol 2.0 (sdk-protocol.md §5.5): the context and proof of this after delivery, for a background job's
   * call to the platform (`PeersClient`, `EventsClient`) within ±300 s of the context's `ts`; null when the platform
   * sent none (a before hook, a platform before 2.0). Java: `Optional<DeliveryContext> deliveryContext()`.
   */
  deliveryContext(): DeliveryContext | null {
    return this.delivery;
  }
}

/**
 * The context of one after delivery (protocol 2.0, sdk-protocol.md §5.5): the platform's `X-KF-Context` --
 * `actor {"type": "system", "via": "hook"}`, no user, no rights, the event's tenant and locale -- and its proof
 * `X-KF-Context-Proof`, signed by the platform for THIS module. A background job started by the delivery passes it to
 * `PeersClient` or `EventsClient` in place of a {@link RequestContext}, within {@link DeliveryContext.WINDOW_SECONDS} s
 * of {@link DeliveryContext.ts}; the platform then calls the owner as `system via module:{caller}`. The tenant always
 * comes from the platform's context, never from the module.
 *
 * `contextHeader` and `proof` are opaque; a job queued in another process can carry both and rebuild the context with
 * {@link DeliveryContext.of}. The proof is never printed (`toString`, `toJSON`).
 * - `context`: the decoded context
 * - `contextHeader`: `X-KF-Context`, exactly as delivered
 * - `proof`: `X-KF-Context-Proof`, exactly as delivered
 */
export class DeliveryContext {
  /** How long around its `ts` the platform accepts the context (±300 s, as every context). */
  static readonly WINDOW_SECONDS = Signatures.MAX_SKEW_SECONDS;

  constructor(readonly context: RequestContext, readonly contextHeader: string, readonly proof: string) {
    if (context == null || contextHeader == null || contextHeader.trim() === '' || proof == null || proof.trim() === '') {
      throw new TypeError(`a delivery context needs the context, ${RequestContext.CONTEXT_HEADER} and ${RequestContext.PROOF_HEADER}`);
    }
  }

  /**
   * The context of `X-KF-Context` and `X-KF-Context-Proof` as delivered (e.g. carried by a job queue).
   *
   * @throws Rejected  when the header is not a coherent context
   * @throws TypeError when the proof is missing (Java: `IllegalArgumentException`)
   */
  static of(contextHeader: string | null | undefined, proof: string | null | undefined): DeliveryContext {
    if (proof == null || proof.trim() === '') {
      throw new TypeError(`a delivery context needs ${RequestContext.PROOF_HEADER}`);
    }
    return new DeliveryContext(RequestContext.decodeDelivered(contextHeader), (contextHeader as string).trim(), proof.trim());
  }

  /** The tenant of the event, as the platform signed it. */
  tenant(): string {
    return this.context.tenant;
  }

  /** The context's `ts`, epoch seconds: the middle of the ±{@link DeliveryContext.WINDOW_SECONDS} s window. */
  ts(): number {
    return this.context.ts;
  }

  /** Whether the platform would still accept the context at `nowSeconds`. */
  usableAt(nowSeconds: number): boolean {
    return Math.abs(nowSeconds - this.context.ts) <= DeliveryContext.WINDOW_SECONDS;
  }

  /** Whether the platform would still accept the context now. */
  usable(): boolean {
    return this.usableAt(Math.floor(Date.now() / 1000));
  }

  toString(): string {
    return `DeliveryContext[tenant=${this.context.tenant}, requestId=${this.context.requestId}, ts=${this.context.ts}, proof=***]`;
  }

  /** Never the proof in a JSON log line. */
  toJSON(): Record<string, unknown> {
    return { tenant: this.context.tenant, requestId: this.context.requestId, ts: this.context.ts, proof: '***' };
  }
}

/**
 * A module event `m.{owner}.after.{name}` (protocol 2.0, §6.3), delivered at least once to a module that subscribed
 * with `EventsClient.on`: `data` is the owner's object unchanged.
 */
export class ModuleEvent {
  constructor(
    readonly owner: string,
    readonly name: string,
    readonly data: Readonly<Record<string, unknown>>,
    readonly envelope: Envelope,
  ) {}

  /** The `eventId` (`kfeventid`): the idempotency key of the event. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  /** CloudEvents `id`: one per delivery, stable across retries. */
  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  get time(): string | null {
    return this.envelope.time;
  }

  /** The emitter's tenant (`kftenant`). */
  get tenant(): string | null {
    return this.envelope.tenant;
  }

  get locale(): string | null {
    return this.envelope.locale;
  }

  /** The point, `m.{owner}.after.{name}`. */
  point(): string {
    return moduleEventPoint(this.owner, this.name);
  }

  /** The context and proof of the delivery, for a background job. */
  deliveryContext(): DeliveryContext | null {
    return this.envelope.deliveryContext();
  }
}

/** `model.after.create|update|delete`. */
export class AfterEvent {
  constructor(
    readonly event: string,
    readonly id: string | null,
    readonly before: ModelSnapshot | null,
    readonly after: ModelSnapshot | null,
    readonly changed: readonly string[],
    readonly data: Readonly<Record<string, unknown>>,
    readonly envelope: Envelope,
  ) {}

  /** @internal */
  static of(event: string, envelope: Envelope, data: Record<string, unknown>): AfterEvent {
    return new AfterEvent(event, data.id == null ? null : String(data.id), ModelSnapshot.fromMap(data.before),
      ModelSnapshot.fromMap(data.after), stringList(data.changed), data, envelope);
  }

  /** The `eventId` (`kfeventid`), the idempotency key of the event. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  get time(): string | null {
    return this.envelope.time;
  }

  get tenant(): string | null {
    return this.envelope.tenant;
  }

  get locale(): string | null {
    return this.envelope.locale;
  }

  get envelopeVersion(): string | null {
    return this.envelope.envelopeVersion;
  }

  /** The context and proof of the delivery, for a background job (protocol 2.0). */
  deliveryContext(): DeliveryContext | null {
    return this.envelope.deliveryContext();
  }

  /** The version paths of `changed`, in order: a module's write on `model_version` (§5.1). */
  changedVersionFields(): readonly VersionField[] {
    return versionFields(this.changed);
  }

  /** The versions those paths touch, distinct, in order of first appearance. */
  changedVersionIds(): readonly string[] {
    return versionIds(this.changedVersionFields());
  }
}

/** `model.field.changed`: one per changed field path. */
export class FieldChange {
  constructor(
    readonly id: string | null,
    readonly field: string,
    readonly oldValue: unknown,
    readonly newValue: unknown,
    readonly before: ModelSnapshot | null,
    readonly after: ModelSnapshot | null,
    readonly data: Readonly<Record<string, unknown>>,
    readonly envelope: Envelope,
  ) {}

  /** @internal */
  static of(envelope: Envelope, data: Record<string, unknown>): FieldChange {
    return new FieldChange(data.id == null ? null : String(data.id), String(data.field ?? null), data.oldValue ?? null,
      data.newValue ?? null, ModelSnapshot.fromMap(data.before), ModelSnapshot.fromMap(data.after), data, envelope);
  }

  get eventId(): string | null {
    return this.envelope.eventId;
  }

  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  get time(): string | null {
    return this.envelope.time;
  }

  get tenant(): string | null {
    return this.envelope.tenant;
  }

  get locale(): string | null {
    return this.envelope.locale;
  }

  /** The context and proof of the delivery, for a background job (protocol 2.0). */
  deliveryContext(): DeliveryContext | null {
    return this.envelope.deliveryContext();
  }

  /** The version path of `field` (`versions.{versionId}.extensions.{slug}.{field}`), or null. */
  versionField(): VersionField | null {
    return VersionField.parse(this.field);
  }
}

/**
 * One path `versions.{versionId}.extensions.{slug}.{field}` of `changed` or of a field change's `field`: a module's
 * write of its own value on a version, delivered as an update of the version's model (protocol 1.1, §5.1). Read the values with `ModelSnapshot.versionExtension(versionId, slug, field)` on `before` /
 * `after` (SDK 0.7.4).
 */
export class VersionField {
  /** The path's first segment. */
  static readonly VERSIONS = 'versions';

  /** The segment between the version id and the module's slug. */
  static readonly EXTENSIONS = 'extensions';

  constructor(
    readonly versionId: string,
    readonly slug: string,
    readonly field: string,
  ) {}

  /**
   * The version field of a path; null when the path is not exactly `versions.{versionId}.extensions.{slug}.{field}`
   * with no empty segment (e.g. `name`, `extensions.{slug}.{field}`).
   */
  static parse(path: string | null | undefined): VersionField | null {
    if (path == null) return null;
    const parts = path.split('.');
    if (parts.length !== 5 || parts[0] !== VersionField.VERSIONS || parts[2] !== VersionField.EXTENSIONS) return null;
    if (parts[1] === '' || parts[3] === '' || parts[4] === '') return null;
    return new VersionField(parts[1], parts[3], parts[4]);
  }

  /** `versions.{versionId}.extensions.{slug}.{field}` */
  path(): string {
    return `${VersionField.VERSIONS}.${this.versionId}.${VersionField.EXTENSIONS}.${this.slug}.${this.field}`;
  }
}

/**
 * What `KfModule.onCallback` observers see for every verified callback. `eventId` is `kfeventid` (the delivery id
 * when absent); `flags`: the module's effective feature flags sent with the callback (`kfflags`).
 */
export interface CallbackRecord {
  receivedAt: string;
  type: string;
  eventId: string | null;
  entityId: string | null;
  changed: readonly string[];
  field: string | null;
  status: number;
  answer: unknown;
  flags: Readonly<Record<string, boolean>>;
  tenant: string | null;
  locale: string | null;
}

/** @internal The version paths of a `changed` list, in order; the model's own paths are left out. */
export function versionFields(changed: readonly string[]): readonly VersionField[] {
  const out: VersionField[] = [];
  for (const path of changed) {
    const f = VersionField.parse(path);
    if (f != null) out.push(f);
  }
  return Object.freeze(out);
}

/** @internal The version ids of those paths, distinct, in order of first appearance. */
export function versionIds(fields: readonly VersionField[]): readonly string[] {
  const out: string[] = [];
  for (const f of fields) if (!out.includes(f.versionId)) out.push(f.versionId);
  return Object.freeze(out);
}

/** @internal */
export function stringList(raw: unknown): readonly string[] {
  return Object.freeze(Array.isArray(raw) ? raw.map((o) => String(o)) : []);
}

function text(o: unknown): string | null {
  return o == null ? null : typeof o === 'string' ? o : typeof o === 'object' ? JSON.stringify(o) : String(o);
}

/**
 * `tenant.after.delete` (protocol 1.2 §6.1): the tenant `tenantId` (`data.id`) was deleted; the module
 * deletes what it keeps for it, idempotently (the same `eventId` may come again).
 */
export class TenantDeleted {
  constructor(readonly tenantId: string, readonly envelope: Envelope) {}

  /** The `eventId` (`kfeventid`), the idempotency key of the event. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  /** CloudEvents `id`: one per delivery, stable across retries. */
  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  /** `time`, ISO-8601. */
  get time(): string | null {
    return this.envelope.time;
  }
}

/**
 * `tenant.after.soft-delete` (protocol 1.3 §6.1): the tenant `tenantId` (`data.id`) was soft-deleted at
 * `time`; the module stops its work for it and keeps its data. The same `eventId` may come again.
 */
export class TenantSoftDeleted {
  constructor(readonly tenantId: string, readonly envelope: Envelope) {}

  /** The `eventId` (`kfeventid`), the idempotency key of the event. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  /** CloudEvents `id`: one per delivery, stable across retries. */
  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  /** `time`, ISO-8601: when the tenant was soft-deleted. */
  get time(): string | null {
    return this.envelope.time;
  }
}

/**
 * `tenant.after.restore` (protocol 1.3 §6.1): the soft-deleted tenant `tenantId` was restored at `time`;
 * the module resumes its work for it. The same `eventId` may come again.
 */
export class TenantRestored {
  constructor(readonly tenantId: string, readonly envelope: Envelope) {}

  /** The `eventId` (`kfeventid`), the idempotency key of the event. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  /** CloudEvents `id`: one per delivery, stable across retries. */
  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  /** `time`, ISO-8601: when the tenant was restored. */
  get time(): string | null {
    return this.envelope.time;
  }
}
