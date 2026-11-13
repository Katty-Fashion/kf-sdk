// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformProblem.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';

const MAX_DETAIL = 500;

/**
 * A platform error as the SDK reads it: an RFC 9457 body (`type`, `title`, `status`, `detail`, `class`) or, without a
 * readable body, just the status; or a network error.
 *
 * The {@link errorClass} decides whether the SDK retries:
 * - `class` present and known: that class;
 * - `class` present but unknown: `permanent` -- an answer the SDK does not understand must never turn into an endless
 *   retry;
 * - `class` absent (an older platform, a filter that writes its own body, no body at all): the status table
 *   {@link ErrorClass.forStatus};
 * - a network error (refused, unresolvable host, timeout): `transient`.
 *
 * {@link describe} is the one line the SDK logs and keeps as `lastRegistrationError()`: the status, the class and the
 * `detail`. It is built from the answer only, never from the request, so the module token cannot reach it.
 */
export class PlatformProblem {
  /**
   * @param status            the HTTP status, `0` for a network error or a local refusal
   * @param type              the problem type URI, or null
   * @param title             the problem title, or null
   * @param detail            the problem detail (or, for a network error, the cause chain), or null
   * @param errorClass        whether retrying can succeed
   * @param retryAfterSeconds the `Retry-After` the platform asked for, or null
   */
  constructor(
    readonly status: number,
    readonly type: string | null,
    readonly title: string | null,
    readonly detail: string | null,
    readonly errorClass: ErrorClass,
    readonly retryAfterSeconds: number | null,
  ) {}

  /** Reads one non-2xx answer of the platform. Never throws: an unreadable body is a body without problem. */
  static parse(status: number, body: string | null, retryAfterHeader: string | null): PlatformProblem {
    let type: string | null = null;
    let title: string | null = null;
    let detail: string | null = null;
    let errorClass = ErrorClass.forStatus(status);
    if (body != null && body.trim() !== '') {
      try {
        const parsed: unknown = JSON.parse(body);
        if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
          const problem = parsed as Record<string, unknown>;
          type = text(problem.type);
          title = text(problem.title);
          detail = text(problem.detail);
          if (Object.prototype.hasOwnProperty.call(problem, ErrorClass.PROPERTY)) {
            errorClass = ErrorClass.fromWire(text(problem[ErrorClass.PROPERTY])) ?? ErrorClass.PERMANENT;
          }
        }
      } catch {
        // a body that is not JSON carries no class: the status decides
      }
    }
    return new PlatformProblem(status, type, title, truncate(detail), errorClass, retryAfter(retryAfterHeader));
  }

  /** A network error: no answer at all. Transient. `describe` is `KfModule.describe` (passed in to avoid a cycle). */
  static network(description: string): PlatformProblem {
    return new PlatformProblem(0, null, null, description, ErrorClass.TRANSIENT, null);
  }

  /**
   * A refusal of the SDK itself, before any request (the local manifest check, a capability the platform does not
   * implement yet): permanent, status `0`.
   */
  static local(detail: string): PlatformProblem {
    return new PlatformProblem(0, null, null, detail, ErrorClass.PERMANENT, null);
  }

  /** Whether the SDK retries the request. */
  retryable(): boolean {
    return this.errorClass === ErrorClass.TRANSIENT;
  }

  /**
   * One line for the log: `HTTP 409 (permanent): Module 'x' is FAILED; expected INSTALLED`, or the cause chain of a
   * network error. Never contains the token (only the answer is read).
   */
  describe(): string {
    if (this.status === 0) return `${this.detail} (${this.errorClass})`; // a network error or a local refusal
    const t = this.detail ?? this.title;
    return `HTTP ${this.status} (${this.errorClass})${t == null ? '' : ': ' + t}`;
  }
}

function text(o: unknown): string | null {
  return o == null ? null : typeof o === 'string' ? o : JSON.stringify(o);
}

function truncate(s: string | null): string | null {
  return s == null || s.length <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + '…';
}

function retryAfter(header: string | null): number | null {
  if (header == null) return null;
  const trimmed = header.trim();
  if (!/^[+-]?\d+$/.test(trimmed)) return null; // an HTTP-date is not used by the platform
  const seconds = Number(trimmed);
  return seconds < 0 ? null : Math.min(seconds, 300);
}
