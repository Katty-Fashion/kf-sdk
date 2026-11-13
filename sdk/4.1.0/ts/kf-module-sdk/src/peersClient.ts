// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PeersClient.java. Do not edit by hand: fix Java, then re-derive.
import { randomUUID } from 'node:crypto';
import { ErrorClass } from './errorClass.js';
import { DeliveryContext } from './hookEvents.js';
import { KfException } from './kfException.js';
import type { ForwardedContext, KfModule } from './kfModule.js';
import { PEER_NAME, peerPort } from './manifestCheck.js';
import { type Answer as ChannelAnswer, local, PlatformChannel, queryValue, segment } from './platformChannel.js';
import { PlatformProblem } from './platformProblem.js';
import type { RequestContext } from './requestContext.js';

const SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;

/**
 * The owner's answer, passed on unchanged by the platform: its `2xx` status and its JSON body (an object, an array or a
 * scalar); `null` when the owner answered a `2xx` without a body, in which case
 * {@link PeerAnswer.object} is a permanent error. Java: `PeersClient.Answer`.
 */
export class PeerAnswer {
  constructor(readonly status: number, readonly body: unknown) {}

  /** The body as an object; a permanent {@link KfException} when it is not one. */
  object(): Record<string, unknown> {
    if (this.body !== null && typeof this.body === 'object' && !Array.isArray(this.body)) {
      return this.body as Record<string, unknown>;
    }
    throw new KfException(new PlatformProblem(this.status, null, null, "the owner's answer is not a JSON object",
      ErrorClass.PERMANENT, null));
  }
}

/**
 * The module's reads and commands of ANOTHER module, through the platform (protocol 2.0, sdk-protocol.md §15.9): the owner offers `capabilities.peerReads[]` / `capabilities.commands[]`, this module declares
 * `capabilities.peers[] {owner, reads, commands}` with the owner in its `requires`. Obtained from `KfModule.peers()`.
 *
 * ```ts
 * const stock = await kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', { sku: 'KF-42' });
 * const reserved = await kf.peers().command(ctx, 'kf-stock', 'reserve', PeersClient.newIdempotencyKey(), { sku: 'KF-42', qty: 3 });
 * ```
 *
 * - {@link read}: `GET …/modules/{self}/peers/{owner}/reads/{port}[?query]` → the owner's status and JSON body,
 *   unchanged;
 * - {@link command}: `POST …/modules/{self}/peers/{owner}/commands/{name}` with the JSON body and the mandatory
 *   `Idempotency-Key`: the same key with the same body replays the stored answer (the owner is not called again),
 *   another body is `409 idempotency-conflict`; a key is kept 7 days. Use one key per intended effect
 *   ({@link PeersClient.newIdempotencyKey}) and the SAME key when retrying it.
 *
 * Both calls carry the module token and a proved context, unchanged (`PlatformChannel`): the {@link RequestContext} of
 * the request the module serves, or -- for a background job -- the {@link DeliveryContext} of an after delivery, within
 * ±300 s of its `ts`; the platform then calls the owner as `system via module:{self}`. Refused locally,
 * `permanent`, nothing sent: an owner outside the slug grammar, a port outside `m.{owner}.{name}.v{N}`, a command name
 * outside `[a-z][a-z0-9-]{1,40}`, a blank query name or a null query value, a missing or invalid idempotency key
 * ({@link PeersClient.IDEMPOTENCY_KEY}), a port or command the manifest's `capabilities.peers` does not name (when the
 * manifest is known), a context not verified or without proof, a delivery context outside its window.
 *
 * The platform's refusals are `PlatformRefusal`s with the reason of §15.9: `peer-not-required`,
 * `capability-not-declared`, `peer-forbidden` (`403`); `peer-port-unknown`, `peer-command-unknown`,
 * {@link PeersClient.NOT_CONFIGURED} (`404`); `idempotency-key-required`, `validation` (`400`);
 * {@link PeersClient.IDEMPOTENCY_CONFLICT}, {@link PeersClient.IDEMPOTENCY_IN_PROGRESS} and {@link PeersClient.IDEMPOTENCY_ANSWER_NOT_STORED}
 * (`409`; the last is permanent: use a new key to run the command again);
 * {@link PeersClient.MODULE_REFUSED} (`422`); {@link PeersClient.INVALID_ANSWER} (`502`);
 * {@link PeersClient.MODULE_UNAVAILABLE} (`503`, transient when the owner is stopped, permanent when it is absent -- an
 * absent optional dependency included). The class is the body's (`KfException.errorClass`).
 *
 * Java's overloads on `RequestContext` / `DeliveryContext` are one method taking either.
 */
export class PeersClient {
  /** The header of a command's idempotency key. */
  static readonly IDEMPOTENCY_HEADER = 'Idempotency-Key';
  /** The grammar of an idempotency key (§15.9). */
  static readonly IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/;

  /** The owner answered `404` (`NOT_CONFIGURED`). */
  static readonly NOT_CONFIGURED = 'not-configured';
  /** The owner answered another `4xx` (`MODULE_REFUSED`, `422`). */
  static readonly MODULE_REFUSED = 'module-refused';
  /** The owner is absent, stopped, failing or unreachable (`MODULE_UNAVAILABLE`, `503`). */
  static readonly MODULE_UNAVAILABLE = 'module-unavailable';
  /** The owner answered a `2xx` that is not JSON (`502`). */
  static readonly INVALID_ANSWER = 'invalid-answer';
  /** The owner is not in this module's `requires` (`403`). */
  static readonly PEER_NOT_REQUIRED = 'peer-not-required';
  /** The key is missing or outside {@link PeersClient.IDEMPOTENCY_KEY} (`400`). */
  static readonly IDEMPOTENCY_KEY_REQUIRED = 'idempotency-key-required';
  /** The same key with another body (`409`, permanent). */
  static readonly IDEMPOTENCY_CONFLICT = 'idempotency-conflict';
  /**
   * The same key again, the first `2xx` too large to store (`409`, permanent): use a new key to run the command again.
   */
  static readonly IDEMPOTENCY_ANSWER_NOT_STORED = 'idempotency-answer-not-stored';
  /** The same key, still running (`409`, transient). */
  static readonly IDEMPOTENCY_IN_PROGRESS = 'idempotency-in-progress';

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'peers');
  }

  /** A fresh idempotency key (`UUID`, 36 characters, inside {@link PeersClient.IDEMPOTENCY_KEY}). */
  static newIdempotencyKey(): string {
    return randomUUID();
  }

  /**
   * Reads `port` of `owner` in the context's tenant, on behalf of the request the module serves -- or, for a background
   * job, with an after delivery's context.
   *
   * @param query the port's query parameters (name → value, sent in this order); empty or null for none
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call
   */
  async read(context: RequestContext | DeliveryContext, owner: string, port: string,
    query?: Record<string, string> | null): Promise<PeerAnswer> {
    const q = this.checkRead(owner, port, query);
    return this.answer(await this.channel.send('GET', this.forwarded(context), readPath(owner, port), q, null));
  }

  /**
   * Runs the command `name` of `owner` in the context's tenant, once per `idempotencyKey`.
   *
   * @param idempotencyKey mandatory, {@link PeersClient.IDEMPOTENCY_KEY}; the same one when retrying the same effect
   * @param body the command's JSON body; null sends `{}`
   * @throws KfException a local refusal (a missing or invalid key included), a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call
   */
  async command(context: RequestContext | DeliveryContext, owner: string, name: string,
    idempotencyKey: string | null | undefined, body?: unknown): Promise<PeerAnswer> {
    this.checkCommand(owner, name, idempotencyKey);
    return this.answer(await this.channel.send('POST', this.forwarded(context), commandPath(owner, name), '',
      body == null ? {} : body, { [PeersClient.IDEMPOTENCY_HEADER]: idempotencyKey as string }));
  }

  // ---------------------------------------------------------------------------------------------------------

  private forwarded(context: RequestContext | DeliveryContext): ForwardedContext {
    return context instanceof DeliveryContext ? this.channel.forwardedDelivery(context) : this.channel.forwarded(context);
  }

  private answer(answer: ChannelAnswer): PeerAnswer {
    if (answer.status < 200 || answer.status > 299) throw this.channel.betweenModulesFailure(answer);
    // an owner's 2xx without a body (204) is passed on as its status and no body
    if (answer.body == null || answer.body.trim() === '') return new PeerAnswer(answer.status, null);
    return new PeerAnswer(answer.status, this.channel.answerJson(answer, 'JSON'));
  }

  private checkRead(owner: string, port: string, query: Record<string, string> | null | undefined): string {
    const errors: string[] = [];
    const ownerOk = checkOwner(errors, owner);
    if (port == null || (ownerOk && !peerPort(owner).test(port))) {
      errors.push(`port must be m.${ownerOk ? owner : '{owner}'}.{name}.v{N}`);
    }
    let q = '';
    for (const [name, value] of Object.entries(query ?? {})) {
      if (name == null || name.trim() === '') errors.push('a query name is blank');
      else if (value == null) errors.push(`query ${name} has no value`);
      else q += `${q === '' ? '?' : '&'}${queryValue(name)}=${queryValue(value)}`;
    }
    if (errors.length > 0) throw local(`invalid peer read: ${errors.join('; ')}`);
    this.checkDeclared(owner, port, true);
    return q;
  }

  private checkCommand(owner: string, name: string, idempotencyKey: string | null | undefined): void {
    const errors: string[] = [];
    checkOwner(errors, owner);
    if (name == null || !PEER_NAME.test(name)) errors.push(`command must match ${PEER_NAME.source}`);
    if (idempotencyKey == null || idempotencyKey.trim() === '') {
      errors.push(`${PeersClient.IDEMPOTENCY_HEADER} is required (PeersClient.newIdempotencyKey(), the same one on a retry)`);
    } else if (!PeersClient.IDEMPOTENCY_KEY.test(idempotencyKey)) {
      errors.push(`${PeersClient.IDEMPOTENCY_HEADER} must match ${PeersClient.IDEMPOTENCY_KEY.source}`);
    }
    if (errors.length > 0) throw local(`invalid peer command: ${errors.join('; ')}`);
    this.checkDeclared(owner, name, false);
  }

  /** With the manifest known, `capabilities.peers` must name the owner and the port or command (else 403). */
  private checkDeclared(owner: string, what: string, read: boolean): void {
    const manifest = this.channel.kf().manifest();
    if (manifest == null) return;
    const declared = manifest.capabilities.peers.some((p) => owner === p.owner && (read ? p.reads : p.commands).includes(what));
    if (!declared) {
      throw local(`${read ? 'the read ' : 'the command '}${what} of ${owner} is not declared in capabilities.peers of the manifest`);
    }
  }
}

function checkOwner(errors: string[], owner: string | null | undefined): owner is string {
  if (owner == null || !SLUG.test(owner)) {
    errors.push(`owner must be a module slug (${SLUG.source})`);
    return false;
  }
  return true;
}

function readPath(owner: string, port: string): string {
  return `/${owner}/reads/${segment(port)}`;
}

function commandPath(owner: string, name: string): string {
  return `/${owner}/commands/${name}`;
}
