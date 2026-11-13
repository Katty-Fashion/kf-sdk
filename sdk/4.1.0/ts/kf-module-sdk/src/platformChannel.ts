// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformChannel.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import { type ForwardedContext, KfModule } from './kfModule.js';
import { PlatformProblem } from './platformProblem.js';
import { PlatformRefusal, type PlatformRefusalKind, refusalKindBetweenModules, refusalKindOf, refusalKindServices } from './platformRefusal.js';
import { RequestContext } from './requestContext.js';
import { DeliveryContext } from './hookEvents.js';

/** @internal One answer of the platform: the status, the body (never null) and its `Retry-After`, if any. */
export interface Answer {
  readonly status: number;
  readonly body: string;
  readonly retryAfter: string | null;
}

/**
 * @internal The module's channel to one platform service of protocol 1.1 (§15: `reads`, `settings`, `secrets`,
 * `extensions`), shared by `ReadsClient`, `SettingsClient`, `SecretsClient` and `ExtensionsClient`. Not public: the
 * clients are the API.
 *
 * Every call goes to `/api/v1/platform/modules/{slug}/{service}…` with the module token and the context the module
 * received for the request -- `X-KF-Context` and the platform's proof of it, `X-KF-Context-Proof`, both exactly as they
 * arrived (kept by `KfModule.verifyContext(…, proofHeader)`), as the audit does (§11.2). A context this instance did
 * not verify (or verified more than 300 s ago), or one that came without the proof, is refused locally, `permanent`,
 * and nothing is sent.
 *
 * The platform's `400`/`401`/`403`/`404` are {@link PlatformRefusal}s; any other non-2xx answer is a
 * {@link KfException} with the class the body (or the status table) gives; a network error is `transient`. Numbers are
 * JSON numbers (Java reads the ones with a fraction as `BigDecimal`; JavaScript has only `number`).
 */
export class PlatformChannel {
  constructor(private readonly kfModule: KfModule, private readonly service: string) {}

  kf(): KfModule {
    return this.kfModule;
  }

  /**
   * Protocol 2.0: the context and proof of an after delivery, for a background job, or a local refusal
   * (nothing is sent) when the configuration is missing or the context is outside its ±300 s window. Java:
   * `forwarded(HookEvents.DeliveryContext)`.
   */
  forwardedDelivery(delivery: DeliveryContext | null | undefined): ForwardedContext {
    if (this.kfModule.platformUrlValue() == null || this.kfModule.slug() == null || this.kfModule.tokenValue() == null) {
      throw local(`${this.service} needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN`);
    }
    if (delivery == null) throw local('a delivery context is required');
    if (!delivery.usable()) {
      throw local(`the delivery context is outside its ${DeliveryContext.WINDOW_SECONDS} s window (ts ${delivery.ts()}); `
        + 'the platform would refuse it');
    }
    return { contextHeader: delivery.contextHeader, proof: delivery.proof, ts: delivery.ts() };
  }

  /** The raw context and proof of `context`, or a local refusal (nothing is sent). */
  forwarded(context: RequestContext | null | undefined): ForwardedContext {
    if (this.kfModule.platformUrlValue() == null || this.kfModule.slug() == null || this.kfModule.tokenValue() == null) {
      throw local(`${this.service} needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN`);
    }
    const forwarded = this.kfModule.forwardedContext(context);
    if (forwarded == null) {
      throw local('the context of this request was not verified by this module in the last 300 s '
        + '(use the RequestContext the adapter gives the route handler)');
    }
    if (forwarded.proof == null) {
      throw local(`the context of this request came without ${RequestContext.PROOF_HEADER} (an older platform)`);
    }
    return forwarded;
  }

  /**
   * Sends one call: `method` on `…/{service}{path}{query}` (`path` and `query` already encoded; `query` empty or
   * starting with `?`), `body` as JSON when not null.
   */
  async send(method: string, forwarded: ForwardedContext, path: string, query: string, body: unknown,
    extraHeaders: Readonly<Record<string, string>> = {}): Promise<Answer> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.kfModule.tokenValue()}`,
      Accept: 'application/json',
      [RequestContext.CONTEXT_HEADER]: forwarded.contextHeader,
      [RequestContext.PROOF_HEADER]: forwarded.proof as string,
      ...extraHeaders,
    };
    if (body != null) headers['Content-Type'] = 'application/json';
    try {
      const response = await fetch(`${this.kfModule.platformUrlValue()}/api/v1/platform/modules/${this.kfModule.slug()}/`
        + `${this.service}${path}${query}`, {
        method,
        headers,
        body: body == null ? undefined : JSON.stringify(body),
        redirect: 'manual',
        signal: AbortSignal.timeout(10_000),
      });
      const text = await response.text();
      return { status: response.status, body: text ?? '', retryAfter: response.headers.get('Retry-After') };
    } catch (e) {
      throw new KfException(PlatformProblem.network(KfModule.describe(e)));
    }
  }

  /**
   * The error of a non-2xx answer: a {@link PlatformRefusal} for 400/401/403/404, a {@link KfException} otherwise;
   * every text of the answer passed through `redact` first (the secrets client removes a value the platform might
   * echo).
   */
  failure(answer: Answer, redact: (text: string) => string = (t) => t): KfException {
    return this.failureOf(answer, redact, refusalKindOf(answer.status));
  }

  /**
   * The error of a non-2xx answer on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3): also
   * `409`, `422`, `502` and `503` are {@link PlatformRefusal}s, with the reason of the body.
   */
  betweenModulesFailure(answer: Answer): KfException {
    return this.failureOf(answer, (t) => t, refusalKindBetweenModules(answer.status));
  }

  /**
   * The error of a non-2xx answer of a platform service (protocol 2.0, `order.services.v1`, §16.6): also
   * `422 not-delivered` (`refused`) and `501 no-provider` (`unavailable`) are {@link PlatformRefusal}s, with the reason
   * of the body.
   */
  servicesFailure(answer: Answer): KfException {
    return this.failureOf(answer, (t) => t, refusalKindServices(answer.status));
  }

  private failureOf(answer: Answer, redact: (text: string) => string, kind: PlatformRefusalKind | null): KfException {
    const parsed = PlatformProblem.parse(answer.status, answer.body, answer.retryAfter);
    const body = object(answer.body);
    const errors: Record<string, string> = {};
    if (body != null) {
      const raw = body.errors;
      if (isObject(raw)) {
        for (const [k, v] of Object.entries(raw)) errors[redact(String(k))] = redact(valueOf(v));
      } else if (Array.isArray(raw)) {
        for (const o of raw) {
          if (isObject(o) && o.field != null) {
            const message = o.message != null ? o.message : o.code;
            errors[redact(valueOf(o.field))] = redact(valueOf(message));
          }
        }
      }
    }
    let detail = parsed.detail == null ? null : redact(parsed.detail);
    const named = Object.entries(errors).map(([field, message]) => `${field}: ${message}`);
    if (named.length > 0) detail = `${detail == null ? '' : detail + ' '}(errors: ${named.join('; ')})`;
    const problem = new PlatformProblem(parsed.status, parsed.type, parsed.title == null ? null : redact(parsed.title),
      detail, parsed.errorClass, parsed.retryAfterSeconds);
    if (kind == null) return new KfException(problem);
    return new PlatformRefusal(problem, kind, reason(body, parsed.type), errors);
  }

  /** The JSON value of a 2xx answer (object, array or scalar), or a permanent error when it is not JSON. */
  answerJson(answer: Answer, what: string): unknown {
    try {
      if (answer.body.trim() === '') throw new Error('empty');
      return JSON.parse(answer.body);
    } catch {
      throw new KfException(new PlatformProblem(answer.status, null, null, `the ${this.service} answer is not ${what}`,
        ErrorClass.PERMANENT, null));
    }
  }

  /** The JSON object of a 2xx answer, or a permanent error naming what was expected. */
  answerObject(answer: Answer, what: string): Record<string, unknown> {
    const parsed = object(answer.body);
    if (parsed == null) {
      throw new KfException(new PlatformProblem(answer.status, null, null, `the ${this.service} answer is not ${what}`,
        ErrorClass.PERMANENT, null));
    }
    return parsed;
  }
}

/** @internal A refusal of the SDK itself, before any request: permanent, status `0`. */
export function local(detail: string): KfException {
  return new KfException(PlatformProblem.local(detail));
}

/** @internal One path segment, percent-encoded as Java's `URLEncoder` + `%20` (`9 9` → `9%209`). */
export function segment(value: string): string {
  return queryValue(value).replace(/\+/g, '%20');
}

/** @internal One query value, form-encoded as Java's `URLEncoder` (`4 2` → `4+2`). */
export function queryValue(value: string): string {
  return encodeURIComponent(value)
    .replace(/%20/g, '+')
    .replace(/[!'()~]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

/** @internal A JSON text as an object (order kept), or null when it is not one. */
export function object(text: string | null | undefined): Record<string, unknown> | null {
  if (text == null || text.trim() === '') return null;
  try {
    const parsed: unknown = JSON.parse(text);
    return isObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** Java's `String.valueOf` of a parsed JSON value. */
function valueOf(v: unknown): string {
  return v === null || v === undefined ? 'null' : typeof v === 'string' ? v : typeof v === 'object' ? JSON.stringify(v) : String(v);
}

function reason(body: Record<string, unknown> | null, type: string | null): string | null {
  if (body != null && typeof body.reason === 'string' && body.reason.trim() !== '') return body.reason;
  if (type == null || type.trim() === '') return null;
  const last = type.substring(type.lastIndexOf('/') + 1);
  return last.trim() === '' ? null : last;
}
