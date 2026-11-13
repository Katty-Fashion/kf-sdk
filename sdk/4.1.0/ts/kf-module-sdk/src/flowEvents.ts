// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowEvents.java. Do not edit by hand: fix Java, then re-derive.
import type { DeliveryContext, Envelope } from './hookEvents.js';

/**
 * One event of the flow engine, as delivered. Java: `FlowEvents.FlowEvent`.
 */
export class FlowEvent {
  constructor(
    readonly type: string,
    readonly orderId: string,
    readonly instanceId: string,
    readonly flowKey: string | null,
    readonly flowVersion: number | null,
    readonly stepKey: string | null,
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

  /** The order's tenant (`kftenant`). */
  get tenant(): string | null {
    return this.envelope.tenant;
  }

  get time(): string | null {
    return this.envelope.time;
  }

  /** The context and proof of the delivery, for a background job, e.g. `OrderServices`. */
  deliveryContext(): DeliveryContext | null {
    return this.envelope.deliveryContext();
  }
}

/** A handler of one type: a throw becomes HTTP 500 `transient` (retried; the delivery is at least once). */
export type FlowEventHandler = (event: FlowEvent) => void | Promise<void>;

const TYPES: readonly string[] = Object.freeze(['workflow.flow-step-entered', 'workflow.flow-step-completed',
  'workflow.flow-instance-completed', 'workflow.flow-line-promoted']);

/**
 * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): `workflow.flow-step-entered`,
 * `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted` -- events of the
 * engine's outbox (not hook points), delivered after the commit, at least once, signed, with `kfeventid`. A module
 * subscribes with `capabilities.hooks[{point}]` without `onFailure` and `kf.flows().on(type, handler)`. Delivered by
 * the platform with `FLOW_EVENTS`, from the third source of subscribable points
 * (`PublishedPlatformEventCatalog`), not from the hook catalog (§16.3); a module relies on it only when the platform
 * announces the feature. A platform before it refuses the subscription at upload, with the text of the key (§16.7).
 *
 * `data` is fixed; until then an object whose minimal named fields are `orderId`, `instanceId` and `stepKey`
 * (`flowKey`, `flowVersion` too; unknown keys kept in {@link FlowEvent.data}). `stepKey` may be absent only on
 * {@link FlowEvents.INSTANCE_COMPLETED}. The module deduplicates on {@link FlowEvent.eventId}; a flow that comes back to
 * a step delivers {@link FlowEvents.STEP_ENTERED} again under another `kfeventid`.
 */
export class FlowEvents {
  static readonly STEP_ENTERED = 'workflow.flow-step-entered';
  static readonly STEP_COMPLETED = 'workflow.flow-step-completed';
  static readonly INSTANCE_COMPLETED = 'workflow.flow-instance-completed';
  static readonly LINE_PROMOTED = 'workflow.flow-line-promoted';

  /** The four types, in the documented order. */
  static readonly TYPES: readonly string[] = TYPES;

  /**
   * Reads one delivery of `type` (`Envelope.event`).
   *
   * @throws TypeError an unknown type, or `data` without `orderId` / `instanceId` (or `stepKey` on a type other than
   *         {@link FlowEvents.INSTANCE_COMPLETED}) (Java: `IllegalArgumentException`)
   */
  static parse(envelope: Envelope, data: Readonly<Record<string, unknown>> | null | undefined): FlowEvent {
    if (envelope == null) throw new TypeError('envelope');
    const type = envelope.event;
    if (!TYPES.includes(type)) {
      throw new TypeError(`"${type}" is not an event of the flow engine (known: ${TYPES.join(', ')})`);
    }
    const d: Readonly<Record<string, unknown>> = data ?? {};
    const missing: string[] = [];
    const orderId = id(d.orderId);
    const instanceId = id(d.instanceId);
    const stepKey = typeof d.stepKey === 'string' && d.stepKey.trim() !== '' ? d.stepKey : null;
    if (orderId == null) missing.push('orderId');
    if (instanceId == null) missing.push('instanceId');
    if (stepKey == null && type !== FlowEvents.INSTANCE_COMPLETED) missing.push('stepKey');
    if (missing.length > 0) throw new TypeError(`${type} needs data.${missing.join(', data.')}`);
    const flowKey = typeof d.flowKey === 'string' ? d.flowKey : null;
    const flowVersion = typeof d.flowVersion === 'number' ? Math.trunc(d.flowVersion) : null;
    return new FlowEvent(type, orderId as string, instanceId as string, flowKey, flowVersion, stepKey,
      Object.freeze({ ...d }), envelope);
  }

  // ---- the subscriptions of a module ----------------------------------------------------------------------

  private readonly handlers = new Map<string, FlowEventHandler[]>();

  /**
   * Subscribes `handler` to `type` (one of {@link FlowEvents.TYPES}), sent with the registration as `{"event": type}`
   * (never with `onFailure`).
   *
   * @throws TypeError a type that is not an event of the flow engine
   */
  on(type: string, handler: FlowEventHandler): this {
    if (!TYPES.includes(type)) {
      throw new TypeError(`"${type}" is not an event of the flow engine (known: ${TYPES.join(', ')})`);
    }
    if (typeof handler !== 'function') throw new TypeError('handler');
    const list = this.handlers.get(type) ?? [];
    list.push(handler);
    this.handlers.set(type, list);
    return this;
  }

  /** @internal The handlers of `type`; empty when none. */
  handlersOf(type: string): readonly FlowEventHandler[] {
    return this.handlers.get(type) ?? [];
  }

  /** @internal The `hooks` part of the registration body: one `{"event": type}` per subscribed type, in order. */
  registration(): Array<{ event: string }> {
    return TYPES.filter((t) => this.handlers.has(t)).map((t) => ({ event: t }));
  }
}

/** An id as text: a non-blank string or a whole number; else null. Java: `FlowEvents.id`. */
function id(value: unknown): string | null {
  if (typeof value === 'string' && value.trim() !== '') return value;
  if (typeof value === 'number' && Number.isInteger(value)) return String(value);
  if (typeof value === 'bigint') return value.toString();
  return null;
}
