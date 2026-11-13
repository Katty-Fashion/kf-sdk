// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/EventsClient.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { DeliveryContext, type ModuleEventHandler, moduleEventPoint } from './hookEvents.js';
import { KfException } from './kfException.js';
import type { ForwardedContext, KfModule } from './kfModule.js';
import { PEER_NAME } from './manifestCheck.js';
import { type Answer, local, PlatformChannel } from './platformChannel.js';
import { PlatformProblem } from './platformProblem.js';
import type { RequestContext } from './requestContext.js';

const SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;

/** What the platform answered an emission: the event's `eventId` and its `type`. Java: `EventsClient.Emitted`. */
export interface EmittedEvent {
  readonly eventId: string;
  readonly type: string;
}

/**
 * The module events `m.{owner}.after.{name}` (protocol 2.0, sdk-protocol.md §6.3): emitting the
 * module's own, declared in `capabilities.events[]`, and subscribing to another module's, declared in
 * `capabilities.hooks[{point: "m.{owner}.after.{name}"}]` with the owner in `requires`. Obtained from
 * `KfModule.events()`.
 *
 * ```ts
 * await kf.events().emit(ctx, 'level-changed', { sku: 'KF-42', level: 7 });     // the owner, kf-stock
 * kf.events().on('kf-stock', 'level-changed', (ev) => jobs.submit(ev));          // a subscriber
 * ```
 *
 * - {@link emit}: `POST …/modules/{self}/events` `{"name", "data"}` with the module token and a proved context (the
 *   {@link RequestContext} of a request the module serves, or the {@link DeliveryContext} of an after delivery for a
 *   background job) → `202 {"eventId", "type"}`; the platform records it in its outbox, in the context's
 *   tenant. Refused locally, `permanent`, nothing sent: a name outside `[a-z][a-z0-9-]{1,40}`, `data` null or over
 *   {@link EventsClient.MAX_DATA_BYTES} bytes as JSON, an event the manifest does not declare in
 *   `capabilities.events` (when the manifest is known), a context not verified or without proof. The platform's
 *   refusals are `PlatformRefusal`s: `401 invalid-context`, `403 module-not-live`, `400 validation`,
 *   `422 event-not-declared`.
 * - {@link on}: the handler of one event of another module; registered as the hook `m.{owner}.after.{name}` (no
 *   `onFailure`). Delivered at least once: the handler is idempotent on `ModuleEvent.eventId`. A subscription the owner
 *   does not offer now is stored inactive by the platform, with a warning.
 */
export class EventsClient {
  /** The largest `data`, serialized as JSON (64 KiB, §6.3). */
  static readonly MAX_DATA_BYTES = 64 * 1024;
  /** The grammar of an event's name (and of an owner's slug, {@link on}). */
  static readonly NAME = PEER_NAME;

  private readonly channel: PlatformChannel;
  private readonly handlers = new Map<string, ModuleEventHandler[]>();

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'events');
  }

  /**
   * Emits the module's event `name` with `data`, in the tenant of `context` (a request's context, or an after
   * delivery's for a background job).
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the event
   */
  async emit(context: RequestContext | DeliveryContext, name: string,
    data: Readonly<Record<string, unknown>> | null | undefined): Promise<EmittedEvent> {
    const body = this.check(name, data);
    const forwarded: ForwardedContext = context instanceof DeliveryContext
      ? this.channel.forwardedDelivery(context) : this.channel.forwarded(context);
    return this.emitted(await this.channel.send('POST', forwarded, '', '', body));
  }

  /**
   * Subscribes `handler` to the event `name` of the module `owner`: the point `m.{owner}.after.{name}`, sent with the
   * registration.
   *
   * @throws TypeError an owner or a name outside the grammar (Java: `IllegalArgumentException`)
   */
  on(owner: string, name: string, handler: ModuleEventHandler): this {
    if (owner == null || !SLUG.test(owner)) throw new TypeError(`owner must be a module slug (${SLUG.source})`);
    if (name == null || !EventsClient.NAME.test(name)) throw new TypeError(`event name must match ${EventsClient.NAME.source}`);
    if (typeof handler !== 'function') throw new TypeError('handler');
    const point = moduleEventPoint(owner, name);
    const list = this.handlers.get(point) ?? [];
    list.push(handler);
    this.handlers.set(point, list);
    return this;
  }

  /** @internal The handlers of a point `m.{owner}.after.{name}`; empty when none. */
  handlersOf(point: string): readonly ModuleEventHandler[] {
    return this.handlers.get(point) ?? [];
  }

  /** @internal The `hooks` part of the registration body: one `{"event": point}` per subscribed point, sorted. */
  registration(): Array<{ event: string }> {
    return [...this.handlers.keys()].sort(compareUtf16).map((point) => ({ event: point }));
  }

  // ---------------------------------------------------------------------------------------------------------

  private check(name: string, data: Readonly<Record<string, unknown>> | null | undefined): Record<string, unknown> {
    const errors: string[] = [];
    if (name == null || !EventsClient.NAME.test(name)) errors.push(`name must match ${EventsClient.NAME.source}`);
    if (data == null) {
      errors.push('data must be an object');
    } else if (Buffer.byteLength(JSON.stringify(data), 'utf8') > EventsClient.MAX_DATA_BYTES) {
      errors.push(`data is over ${EventsClient.MAX_DATA_BYTES} bytes as JSON`);
    }
    if (errors.length > 0) throw local(`invalid event: ${errors.join('; ')}`);
    const manifest = this.channel.kf().manifest();
    if (manifest != null && !manifest.capabilities.events.some((e) => name === e.name)) {
      throw local(`the event ${name} is not declared in capabilities.events of the manifest`);
    }
    return { name, data };
  }

  private emitted(answer: Answer): EmittedEvent {
    if (answer.status < 200 || answer.status > 299) throw this.channel.betweenModulesFailure(answer);
    const body = this.channel.answerObject(answer, '{eventId, type}');
    if (typeof body.eventId !== 'string' || typeof body.type !== 'string') {
      throw new KfException(new PlatformProblem(answer.status, null, null, 'the events answer is not {eventId, type}',
        ErrorClass.PERMANENT, null));
    }
    return Object.freeze({ eventId: body.eventId, type: body.type });
  }
}

/** Java's `String.compareTo` order (UTF-16 code units). */
function compareUtf16(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
