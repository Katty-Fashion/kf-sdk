// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RequestContext.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { HookResult } from './hookResult.js';
import { KfException } from './kfException.js';
import { PlatformProblem } from './platformProblem.js';
import { Signatures } from './signatures.js';

/**
 * Who calls the module's route: `{"type": "user" | "system", "via": "hook" | null, "hookId": "<uuid>" | null}`.
 * - `user`: a user's request; `user` is set, `via` and `hookId` are null.
 * - `system`: the platform calls a `hookable` route from a hook; `user` is null, `via` is `hook` and `hookId`
 *   names the hook.
 * - `system` via `platform` (protocol 1.1): the platform calls a route the module declares in `provides`; `user` and `hookId` are null and `rights` is empty. No user is ever deduced from a system context.
 *
 * A context without `actor` (an older platform) reads as {@link Actor.USER}.
 */
export class Actor {
  /** The actor of a context that carries none: a user, no hook. */
  static readonly USER = new Actor('user', null, null);

  /** The platform itself, calling a provided read (protocol 1.1): no user, no hook. */
  static readonly PLATFORM = new Actor('system', 'platform', null);

  /** The prefix of `via` on a call between modules (protocol 2.0): `module:{slug}`. */
  static readonly VIA_MODULE_PREFIX = 'module:';

  /** The grammar of the slug after {@link Actor.VIA_MODULE_PREFIX}. */
  static readonly MODULE_SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;

  constructor(
    readonly type: 'user' | 'system',
    readonly via: string | null,
    readonly hookId: string | null,
  ) {
    if (type !== 'user' && type !== 'system') throw new TypeError('type');
  }

  /** The calling module of a call between modules (`via = module:{slug}`), or `null`. */
  viaModule(): string | null {
    return this.via != null && this.via.startsWith(Actor.VIA_MODULE_PREFIX) ? this.via.substring(Actor.VIA_MODULE_PREFIX.length) : null;
  }
}

/** A refused context: 401, `permanent`, the reason in `detail`. */
export class Rejected extends KfException {
  constructor(reason: string) {
    super(new PlatformProblem(401, 'https://kf.ro/problems/invalid-context', 'Invalid context',
      `the request context was refused: ${reason}`, ErrorClass.PERMANENT, null));
    this.name = 'Rejected';
  }

  /** The RFC 9457 body to answer with (`application/problem+json`). */
  problemJson(): string {
    return JSON.stringify({
      type: this.problem.type,
      title: this.problem.title,
      status: this.problem.status,
      detail: this.problem.detail,
      [ErrorClass.PROPERTY]: this.problem.errorClass,
    });
  }

  /** The answer for an adapter. */
  toResult(): HookResult {
    return new HookResult(401, this.problemJson(), HookResult.PROBLEM_CONTENT_TYPE);
  }
}

/**
 * What the platform tells the module about one request it forwards to the module's own routes:
 * sent INSTEAD of the user's token, in `X-KF-Context`, signed with the module's own key.
 *
 * The JSON keys, in this order: `v, tenant, user, actor, locale, rights, method, path, query, ts, requestId` (`actor`
 * right after `user`), and from protocol 1.2 `person` last, only on the routes `audience: member`. `v` stays `1`: a key added to the context is not a change.
 * - `tenant`: the caller's active tenant (UUID text); the module filters its data on it
 * - `user`: the caller's OIDC subject (opaque, the same identifier as `kfactor` on events); `null` when the platform
 *   calls as the system
 * - `actor`: who calls, a user or the system through a hook
 * - `locale`: the caller's language, never null
 * - `rights`: the caller's codes under `api.v1.m.{slug}.` in the active tenant, sorted
 * - `method`: the HTTP method, upper case; `path`: the decoded path the module receives (without `/m/{slug}`)
 * - `query`: the raw query string, or `null`; `ts`: epoch seconds, equal to `X-KF-Timestamp`
 * - `requestId`: the platform's id of this forward, for correlating the two logs
 * - `person`: protocol 1.2: the caller's e-mail and display name at the time of the request,
 *   sent only on the routes `audience: member`; `null` when the context carries none (every other route, a system
 *   context, a platform before 1.2). Never a ground for authorization: the identity stays `user`. The constructor
 *   without it (the eleven components of SDK 0.7.x) still builds a context
 */
export class RequestContext {
  /** The context version this SDK reads. */
  static readonly CURRENT_VERSION = 1;
  /** The request headers the platform sends with the context. */
  static readonly CONTEXT_HEADER = 'X-KF-Context';
  static readonly TIMESTAMP_HEADER = 'X-KF-Timestamp';
  static readonly SIGNATURE_HEADER = 'X-KF-Signature';
  /**
   * The platform's own proof over `X-KF-Context` for this module: opaque to the module, which keeps it with the
   * context and sends both back when it calls the platform on behalf of the request (`AuditClient`). The module's key
   * cannot produce it, so it is what proves to the PLATFORM that the context (and its tenant) is one the platform
   * issued. Absent from an older platform.
   */
  static readonly PROOF_HEADER = 'X-KF-Context-Proof';

  readonly rights: readonly string[];

  constructor(
    readonly v: number,
    readonly tenant: string,
    readonly user: string | null,
    readonly actor: Actor,
    readonly locale: string,
    rights: readonly string[] | null,
    readonly method: string,
    readonly path: string,
    readonly query: string | null,
    readonly ts: number,
    readonly requestId: string,
    readonly person: Person | null = null,
  ) {
    if (actor == null) throw new TypeError('actor');
    this.rights = Object.freeze([...(rights ?? [])]);
  }

  /** Whether the caller holds `right` in the active tenant. */
  hasRight(right: string): boolean {
    return this.rights.includes(right);
  }

  /**
   * Verifies one forwarded request and returns its context:
   * 1. the three headers are present;
   * 2. `X-KF-Signature` = lowercase hex `HMAC-SHA256(secret, ts + "." + X-KF-Context + "." + body)`, compared in constant
   *    time, with `ts` within ±300 s of `now` (Signatures);
   * 3. `X-KF-Context` is base64url (no padding) of a JSON object of version `1`, whose `ts` equals `X-KF-Timestamp`, with
   *    `tenant`, `locale`, `method`, `path`, `requestId` as text and `rights` as a list of text;
   * 4. `actor` is coherent: `user` = a user (`via`, `hookId` null, `user` set); `system` = through a hook
   *    (`user` null, `via` = `hook`, `hookId` set) or, from protocol 1.1, the platform itself calling a
   *    route the module declares in `provides` (`user` null, `via` = `platform`, `hookId` null, `rights` empty); any
   *    other type or combination is refused. A context WITHOUT `actor`
   *    (an older platform) reads as {@link Actor.USER}.
   *
   * @throws Rejected 401, `permanent`, naming the reason (never the secret)
   */
  static verify(
    contextHeader: string | null | undefined,
    timestampHeader: string | null | undefined,
    signatureHeader: string | null | undefined,
    body: Uint8Array | string | null | undefined,
    secret: string | null | undefined,
    nowSeconds: number,
  ): RequestContext {
    if (contextHeader == null || contextHeader.trim() === '') throw new Rejected(`missing ${RequestContext.CONTEXT_HEADER}`);
    const payload = body == null ? Buffer.alloc(0) : typeof body === 'string' ? Buffer.from(body, 'utf8') : Buffer.from(body);
    const signed = Buffer.concat([Buffer.from(`${contextHeader}.`, 'ascii'), payload]);
    const invalid = Signatures.verify(secret, timestampHeader, signatureHeader, signed, nowSeconds);
    if (invalid != null) throw new Rejected(invalid);
    return decode(contextHeader, String(timestampHeader).trim());
  }

  /**
   * The context of an after delivery (protocol 2.0, sdk-protocol.md §5.5): `X-KF-Context` as the platform put
   * it on a delivery whose signature (`ts + "." + body`) the module already verified. The header itself is not covered
   * by that signature -- the platform's proof binds it, and the platform checks the proof when the module sends both
   * back -- so it is read with the same rules as {@link RequestContext.verify}, without a signature and without the
   * match on `X-KF-Timestamp`. Internal: `DeliveryContext` is the API.
   *
   * @throws Rejected when the header is not a coherent context of version `1`
   * @internal
   */
  static decodeDelivered(contextHeader: string | null | undefined): RequestContext {
    if (contextHeader == null || contextHeader.trim() === '') throw new Rejected(`missing ${RequestContext.CONTEXT_HEADER}`);
    return decode(contextHeader.trim(), null);
  }
}

/** The checks of `verify` after the signature; `timestamp` null = not matched against a header. */
function decode(contextHeader: string, timestamp: string | null): RequestContext {
  {
    let json: Record<string, unknown>;
    try {
      if (!/^[A-Za-z0-9_-]*={0,2}$/.test(contextHeader)) throw new Error('not base64url');
      const parsed: unknown = JSON.parse(Buffer.from(contextHeader, 'base64url').toString('utf8'));
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
      json = parsed as Record<string, unknown>;
    } catch {
      throw new Rejected(`${RequestContext.CONTEXT_HEADER} is not base64url of a JSON object`);
    }
    if (typeof json.v !== 'number' || Math.trunc(json.v) !== RequestContext.CURRENT_VERSION) {
      throw new Rejected(`context version ${json.v === undefined ? 'null' : JSON.stringify(json.v)} is not supported (supported: ${RequestContext.CURRENT_VERSION})`);
    }
    const ts = typeof json.ts === 'number' ? Math.trunc(json.ts) : Number.MIN_SAFE_INTEGER;
    if (timestamp == null && ts === Number.MIN_SAFE_INTEGER) throw new Rejected('context ts is required');
    if (timestamp != null && String(ts) !== timestamp) {
      throw new Rejected(`the context ts does not match ${RequestContext.TIMESTAMP_HEADER}`);
    }
    const tenant = requiredText(json, 'tenant');
    const locale = requiredText(json, 'locale');
    const method = requiredText(json, 'method');
    const path = requiredText(json, 'path');
    const requestId = requiredText(json, 'requestId');
    if (json.user != null && typeof json.user !== 'string') throw new Rejected('context user must be text');
    const user = (json.user as string | undefined) ?? null;
    if (json.query != null && typeof json.query !== 'string') throw new Rejected('context query must be text');
    if (!Array.isArray(json.rights) || json.rights.some((r) => typeof r !== 'string')) {
      throw new Rejected('context rights must be a list of text');
    }
    const actor = readActor(json, user, json.rights as string[]);
    const person = readPerson(json, actor);
    return new RequestContext(RequestContext.CURRENT_VERSION, tenant, user, actor, locale, json.rights as string[], method,
      path, (json.query as string | undefined) ?? null, ts, requestId, person);
  }
}

/**
 * The caller as a person (protocol 1.2): what a module shows of the reporter of a ticket, for
 * instance. Sent only on the routes `audience: member`, from the platform user of the request.
 * - `email`: the caller's e-mail, or `null`
 * - `displayName`: the caller's display name, or `null`
 */
export interface Person {
  readonly email: string | null;
  readonly displayName: string | null;
}

/**
 * Protocol 1.2: `person`, the last key, only on the routes `audience: member`; absent or `null` = none. A
 * system context never carries one.
 */
function readPerson(json: Record<string, unknown>, actor: Actor): Person | null {
  const raw = json.person;
  if (raw == null) return null;
  if (actor.type === 'system') throw new Rejected('a system context carries no person');
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new Rejected('context person must be an object');
  const { email, displayName } = raw as Record<string, unknown>;
  if (email != null && typeof email !== 'string') throw new Rejected('context person.email must be text or null');
  if (displayName != null && typeof displayName !== 'string') {
    throw new Rejected('context person.displayName must be text or null');
  }
  return Object.freeze({ email: (email as string | undefined) ?? null, displayName: (displayName as string | undefined) ?? null });
}

/**
 * the actor, coherent with `user`; absent = a user (an older platform). Protocol 1.1:
 * `system` via `platform` has no user, no hookId and no rights.
 */
function readActor(json: Record<string, unknown>, user: string | null, rights: readonly string[]): Actor {
  if (!Object.prototype.hasOwnProperty.call(json, 'actor')) return Actor.USER;
  const raw = json.actor;
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) throw new Rejected('context actor must be an object');
  const { type, via, hookId } = raw as Record<string, unknown>;
  if (type === 'user') {
    if (user == null || user.trim() === '') throw new Rejected('actor user requires a user');
    // Protocol 2.0: a user via a module -- a call between modules, through the platform (§15.9)
    if (typeof via === 'string' && via.startsWith(Actor.VIA_MODULE_PREFIX)) {
      if (hookId != null) throw new Rejected('actor user via a module has no hookId');
      return new Actor('user', viaModule(via), null);
    }
    if (via != null || hookId != null) throw new Rejected('actor user has no via and no hookId');
    return Actor.USER;
  }
  if (type === 'system') {
    if (user != null) throw new Rejected('actor system has no user');
    // Protocol 2.0: a background job's call between modules, as the system via the caller
    if (typeof via === 'string' && via.startsWith(Actor.VIA_MODULE_PREFIX)) {
      if (hookId != null) throw new Rejected('actor system via a module has no hookId');
      if (rights.length > 0) throw new Rejected('actor system via a module has no rights');
      return new Actor('system', viaModule(via), null);
    }
    if (via === 'platform') {
      if (hookId != null) throw new Rejected('actor system via platform has no hookId');
      if (rights.length > 0) throw new Rejected('actor system via platform has no rights');
      return Actor.PLATFORM;
    }
    if (via !== 'hook') throw new Rejected('actor system requires via = hook or platform');
    if (typeof hookId !== 'string' || hookId.trim() === '') throw new Rejected('actor system requires a hookId');
    return new Actor('system', 'hook', hookId);
  }
  throw new Rejected(`actor type ${type == null ? 'null' : `"${String(type)}"`} is not user or system`);
}

/** `module:{slug}` with the slug's grammar, or refused. */
function viaModule(via: string): string {
  const slug = via.substring(Actor.VIA_MODULE_PREFIX.length);
  if (!Actor.MODULE_SLUG.test(slug)) {
    throw new Rejected(`actor via ${via} is not module:{slug} (the slug must match ${Actor.MODULE_SLUG.source})`);
  }
  return via;
}

function requiredText(json: Record<string, unknown>, key: string): string {
  const s = json[key];
  if (typeof s !== 'string' || s.trim() === '') throw new Rejected(`context ${key} is required`);
  return s;
}
