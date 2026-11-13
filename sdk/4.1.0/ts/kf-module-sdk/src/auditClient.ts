// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/AuditClient.java. Do not edit by hand: fix Java, then re-derive.
import type { AuditRecord } from './auditRecord.js';
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import { type ForwardedContext, KfModule } from './kfModule.js';
import { PlatformProblem } from './platformProblem.js';
import { RequestContext } from './requestContext.js';

/**
 * The module's access to the platform's audit trail: capability `audit` in `kf-module-conf.json`
 * (`[{"access": "write"}, {"access": "read-own"}]`). Obtained from `KfModule.audit()`.
 *
 * **Implemented by the platform.** Both calls go to `/api/v1/platform/modules/{slug}/audit` with the module
 * token and the context the module received for the request -- `X-KF-Context` and the platform's own proof of it,
 * `X-KF-Context-Proof`, both exactly as they arrived (kept by `KfModule.verifyContext(…, proofHeader)`, which the
 * adapters call). The platform takes the tenant and the actor from that context and from nowhere else.
 * - {@link record}: access `write` -- one row in the tenant of the request, attributed to the module's slug, its type
 *   stored as `m.<slug>.<type>` (from a hook context, `actor.type = system`, the row's actor is `system:hook:<hookId>`);
 * - {@link list}: access `read-own` -- only the module's own rows of that tenant, newest first.
 *
 * The record is checked locally with the platform's rules before any request ({@link AuditRecord}); a context this
 * instance did not verify (or verified more than 300 s ago), or one that came without the platform's proof, is refused
 * locally too. Every local refusal is a {@link KfException} of class `permanent` and sends nothing. The platform's
 * refusals: `401` → {@link AuditUnauthorized}, `403` → {@link AuditForbidden} (the capability is not declared, or the
 * module is not live for the tenant), anything else → {@link KfException}.
 */
export class AuditClient {
  /** The action: an upper-case code, as the platform validates it. */
  static readonly ACTION = /^[A-Z][A-Z0-9_]{1,63}$/;
  /** The module's own type (the platform stores it as `m.<slug>.<type>`). */
  static readonly AGGREGATE_TYPE = /^[A-Za-z][A-Za-z0-9_.-]{0,49}$/;
  /** The longest aggregate reference. */
  static readonly MAX_AGGREGATE_REF = 255;

  /** @internal */
  constructor(private readonly kf: KfModule) {}

  /**
   * Writes one audit row in the tenant of `context` (`POST …/audit`, answered `201`).
   *
   * @throws KfException a local refusal (invalid record, unknown context, no proof) or a platform error
   * @throws AuditUnauthorized the platform refused the token or the context (`401`)
   * @throws AuditForbidden the capability is not declared or the module is not live for the tenant (`403`)
   */
  async record(context: RequestContext, record: AuditRecord): Promise<void> {
    const errors: string[] = [];
    if (record == null) {
      errors.push('the record is required');
    } else {
      checkCode(errors, 'action', record.action, AuditClient.ACTION, 'must match [A-Z][A-Z0-9_]{1,63}');
      checkCode(errors, 'aggregateType', record.aggregateType, AuditClient.AGGREGATE_TYPE,
        'must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)');
      checkRef(errors, record.aggregateRef, true);
    }
    if (errors.length > 0) throw local(`invalid audit record: ${errors.join('; ')}`);
    const forwarded = this.forwarded(context);

    const body = { action: record.action, aggregateType: record.aggregateType, aggregateRef: record.aggregateRef };
    await this.send('POST', forwarded, '', JSON.stringify(body), 201);
  }

  /**
   * The module's own audit rows of one aggregate type (and, when `aggregateRef` is not null, of one aggregate) of the
   * tenant of `context`, newest first (`GET …/audit`). Each row: `id`, `aggregateType` (the module's own type),
   * `aggregateRef`, `action`, `actor`, `performedAt`, `requestId`.
   *
   * @param aggregateRef one aggregate, or null for every aggregate of the type
   * @throws KfException a local refusal or a platform error
   * @throws AuditUnauthorized the platform refused the token or the context (`401`)
   * @throws AuditForbidden the capability is not declared or the module is not live for the tenant (`403`)
   */
  async list(context: RequestContext, aggregateType: string, aggregateRef: string | null | undefined): Promise<Record<string, unknown>[]> {
    const errors: string[] = [];
    checkCode(errors, 'aggregateType', aggregateType, AuditClient.AGGREGATE_TYPE,
      'must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)');
    checkRef(errors, aggregateRef, false);
    if (errors.length > 0) throw local(`invalid audit query: ${errors.join('; ')}`);
    const forwarded = this.forwarded(context);

    const query = `?aggregateType=${encode(aggregateType)}`
      + (aggregateRef == null || aggregateRef.trim() === '' ? '' : `&aggregateRef=${encode(aggregateRef)}`);
    const answer = await this.send('GET', forwarded, query, null, 200);
    let parsed: unknown = null;
    try {
      parsed = JSON.parse(answer);
    } catch {
      parsed = null;
    }
    const rows = parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>).rows : null;
    if (!Array.isArray(rows)) {
      throw new KfException(new PlatformProblem(200, null, null, 'the audit answer has no rows', ErrorClass.PERMANENT, null));
    }
    return rows
      .filter((row): row is Record<string, unknown> => row !== null && typeof row === 'object' && !Array.isArray(row))
      .map((row) => ({ ...row }));
  }

  // ---------------------------------------------------------------------------------------------------------

  private forwarded(context: RequestContext): ForwardedContext {
    if (this.kf.platformUrlValue() == null || this.kf.slug() == null || this.kf.tokenValue() == null) {
      throw local('audit needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN');
    }
    const forwarded = this.kf.forwardedContext(context);
    if (forwarded == null) {
      throw local('the context of this request was not verified by this module in the last 300 s '
        + '(use the RequestContext the adapter gives the route handler)');
    }
    if (forwarded.proof == null) {
      throw local(`the context of this request came without ${RequestContext.PROOF_HEADER} (an older platform)`);
    }
    return forwarded;
  }

  private async send(method: 'GET' | 'POST', forwarded: ForwardedContext, query: string, body: string | null,
    expected: number): Promise<string> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.kf.tokenValue()}`,
      Accept: 'application/json',
      [RequestContext.CONTEXT_HEADER]: forwarded.contextHeader,
      [RequestContext.PROOF_HEADER]: forwarded.proof as string,
    };
    if (body != null) headers['Content-Type'] = 'application/json';
    let response: Response;
    let text: string;
    try {
      response = await fetch(`${this.kf.platformUrlValue()}/api/v1/platform/modules/${this.kf.slug()}/audit${query}`, {
        method,
        headers,
        body: body ?? undefined,
        redirect: 'manual',
        signal: AbortSignal.timeout(10_000),
      });
      text = await response.text();
    } catch (e) {
      throw new KfException(PlatformProblem.network(KfModule.describe(e)));
    }
    if (response.status === expected) return text;
    const problem = PlatformProblem.parse(response.status, text, response.headers.get('Retry-After'));
    if (response.status === 401) throw new AuditUnauthorized(problem);
    if (response.status === 403) throw new AuditForbidden(problem);
    throw new KfException(problem);
  }
}

/** The platform refused the module token or the context (`401`, `permanent`). Java: `AuditClient.Unauthorized`. */
export class AuditUnauthorized extends KfException {
  constructor(problem: PlatformProblem) {
    super(problem);
    this.name = 'AuditUnauthorized';
  }
}

/** The capability `audit` is not declared, or the module is not live for the tenant (`403`). Java: `AuditClient.Forbidden`. */
export class AuditForbidden extends KfException {
  constructor(problem: PlatformProblem) {
    super(problem);
    this.name = 'AuditForbidden';
  }
}

function checkCode(errors: string[], name: string, value: string | null | undefined, rule: RegExp, text: string): void {
  if (value == null || value.trim() === '') errors.push(`${name} is required`);
  else if (!rule.test(value)) errors.push(`${name} ${text}`);
}

function checkRef(errors: string[], value: string | null | undefined, required: boolean): void {
  if (value == null || value.trim() === '') {
    if (required) errors.push('aggregateRef is required');
    return;
  }
  if (value.trim().length > AuditClient.MAX_AGGREGATE_REF) {
    errors.push(`aggregateRef must be at most ${AuditClient.MAX_AGGREGATE_REF} characters`);
  } else if (hasControl(value)) {
    errors.push('aggregateRef must not contain control characters');
  }
}

/** `Character.isISOControl`: U+0000–U+001F and U+007F–U+009F. */
function hasControl(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c <= 0x1f || (c >= 0x7f && c <= 0x9f)) return true;
  }
  return false;
}

/** `URLEncoder.encode(value, UTF_8)`: application/x-www-form-urlencoded, a space as `+`. */
function encode(value: string): string {
  return encodeURIComponent(value)
    .replace(/%20/g, '+')
    .replace(/[!'()~]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

function local(detail: string): KfException {
  return new KfException(PlatformProblem.local(detail));
}
