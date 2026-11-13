// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Notifications.java. Do not edit by hand: fix Java, then re-derive.
import { randomUUID } from 'node:crypto';
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import { KfModule } from './kfModule.js';
import type { NotificationOptions } from './notificationOptions.js';
import { PlatformProblem } from './platformProblem.js';

/**
 * What the platform did: the rows it wrote (`accepted`, one per recipient and channel the tenant's matrix and the
 * recipient's preferences leave on) and the rows an earlier request with the same idempotency key already wrote
 * (`duplicates`). Java: `Notifications.Result`.
 */
export interface NotificationResult {
  readonly accepted: number;
  readonly duplicates: number;
}

/**
 * The module sends a notification it declared in `capabilities.notifications` of `kf-module-conf.json` (
 * sdk-protocol.md §11.1). Obtained from `KfModule.notifications()`.
 *
 *   await kf.notifications().send(tenantId, 'author-changed', [userSubject], { modelName: 'Rochie' });
 *   await kf.notifications().send(tenantId, 'author-changed', [userSubject], { modelName: 'Rochie' },
 *     { deepLink: '/models/42', channels: ['in_app'] });
 *
 * `POST /api/v1/platform/modules/{slug}/notifications` with the module token. The module names its own key
 * (`author-changed`); the platform stores it as `m.<slug>.<key>`, renders the module's texts in each recipient's
 * language and writes the rows the recipients' bell and mail show. The recipients are OIDC subjects (the `user` of a
 * {@link RequestContext}, `kfactor` of a callback), members of the tenant; from protocol 1.3 (SDK 0.9.0) the module
 * may name roles instead (`options.recipientRoles`): the platform adds the role's active members of the tenant, each
 * person once, and the module never sees who they are.
 *
 * Checked locally first, with the platform's rules (each a {@link KfException} of class `permanent`, nothing sent): a
 * tenant, the key's grammar `[a-z][a-z0-9_-]{0,40}`, at most {@link Notifications.MAX_RECIPIENTS} non-blank
 * recipients, at least one recipient or one role, 1 to {@link Notifications.MAX_ROLES} role names of the grammar
 * `[A-Z][A-Z0-9_]{0,63}` (a repeated one counts once), variables whose values are strings, numbers or booleans, a
 * relative `deepLink`, channels among `in_app`/`email`. Whether the key, its variables and its channels are DECLARED,
 * and whether a role is one of the grantable system roles, is the platform's answer (`422`, `unknown-role` for a role).
 *
 * **Retries.** Only a `transient` answer (or a network error) is retried, on {@link Notifications.DEFAULT_RETRY_DELAYS_MS}
 * (a `Retry-After` is honoured up to the longest step), and every retry sends the SAME request -- the same
 * `idempotencyKey`, generated once per call when the options name none -- so the platform writes the notification
 * once. A `permanent` refusal of the platform is a {@link NotificationRejected} with the protocol's `reason`
 * (`unknown-key`, `undeclared-variable`, `not-a-member`, `not-live`, ...); a `transient` one that outlives the schedule is a
 * {@link KfException} of class `transient`.
 */
export class Notifications {
  /** The most recipients one request may name. */
  static readonly MAX_RECIPIENTS = 100;
  /** The most role names of `recipientRoles` (protocol 1.3). */
  static readonly MAX_ROLES = 10;
  /** The shape of a role name the SDK sends (the grantable system roles are upper case); the list is the platform's. */
  static readonly ROLE = /^[A-Z][A-Z0-9_]{0,63}$/;
  /** The manifest grammar of a notification key (without `m.<slug>.`). */
  static readonly KEY = /^[a-z][a-z0-9_-]{0,40}$/;
  /** The channels of the protocol. */
  static readonly CHANNELS: ReadonlySet<string> = new Set(['in_app', 'email']);
  /** The retry schedule of a `transient` answer, in milliseconds; its length is the number of retries. */
  static readonly DEFAULT_RETRY_DELAYS_MS: readonly number[] = [500, 1000, 2000];

  private retryDelaysMs: readonly number[] = Notifications.DEFAULT_RETRY_DELAYS_MS;

  /** @internal */
  constructor(private readonly kf: KfModule) {}

  /** Replaces the retry schedule of a `transient` answer (tests, a module with other needs), in milliseconds. */
  withRetryDelays(delaysMs: readonly number[]): this {
    if (delaysMs == null || delaysMs.some((d) => typeof d !== 'number' || !Number.isFinite(d) || d < 0)) {
      throw new TypeError('retry delays must be a list of non-negative durations');
    }
    this.retryDelaysMs = [...delaysMs];
    return this;
  }

  /**
   * Sends `key` to `recipients` of `tenantId`, answered `202`. Without `options`: every declared channel, no link, a
   * generated idempotency key.
   *
   * @throws KfException a local refusal, a network error or a `transient` answer that outlived the schedule
   * @throws NotificationRejected a `permanent` refusal of the platform
   */
  async send(tenantId: string, key: string, recipients: readonly string[], variables: Record<string, unknown> | null,
    options?: NotificationOptions | null): Promise<NotificationResult> {
    const opts: NotificationOptions = options ?? {};
    const errors: string[] = [];
    if (tenantId == null || String(tenantId).trim() === '') errors.push('tenantId is required');
    else if (!UUID.test(String(tenantId))) errors.push('tenantId must be a UUID');
    if (key == null || !Notifications.KEY.test(key)) {
      errors.push('key must match [a-z][a-z0-9_-]{0,40} (the key as the manifest declares it, without m.<slug>.)');
    }
    const roles = opts.recipientRoles ?? null;
    if (roles != null) {
      if (roles.length === 0) errors.push('recipientRoles names no role; omit it to send to recipients only');
      for (const role of roles) {
        if (role == null || !Notifications.ROLE.test(role)) {
          errors.push(`recipientRoles: '${role}' is not a role name (upper case, e.g. TENANT_ADMIN)`);
        }
      }
      if (new Set(roles).size > Notifications.MAX_ROLES) {
        errors.push(`at most ${Notifications.MAX_ROLES} recipientRoles per request`);
      }
    }
    const byRole = roles != null && roles.length > 0;
    if (recipients == null || recipients.length === 0) {
      if (!byRole) errors.push('recipients names no one (and no recipientRoles)');
    } else if (recipients.length > Notifications.MAX_RECIPIENTS) {
      errors.push(`at most ${Notifications.MAX_RECIPIENTS} recipients per request`);
    } else if (recipients.some((r) => r == null || String(r).trim() === '')) {
      errors.push('a recipient subject is blank');
    }
    const vars: Record<string, unknown> = {};
    for (const [name, value] of Object.entries(variables ?? {})) {
      if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') {
        errors.push(`the variable '${name}' must be a string, a number or a boolean`);
      }
      vars[name] = value;
    }
    const deepLink = opts.deepLink ?? null;
    if (deepLink != null && deepLink !== '' && !relative(deepLink)) {
      errors.push("deepLink must be a relative path starting with a single '/'");
    }
    const channels = opts.channels ?? null;
    if (channels != null) {
      if (channels.length === 0) errors.push('channels names no channel; omit it for every declared channel');
      for (const channel of channels) {
        if (!Notifications.CHANNELS.has(channel)) errors.push(`channel '${channel}' is not one of in_app, email`);
      }
    }
    if (this.kf.platformUrlValue() == null || this.kf.slug() == null || this.kf.tokenValue() == null) {
      errors.push('notifications need KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN');
    }
    if (errors.length > 0) throw new KfException(PlatformProblem.local(`invalid notification: ${errors.join('; ')}`));

    const body: Record<string, unknown> = {
      tenantId: String(tenantId),
      key,
      recipients: recipients == null ? [] : [...new Set(recipients)],
    };
    if (byRole) body.recipientRoles = [...new Set(roles)];
    body.variables = vars;
    if (deepLink != null && deepLink !== '') body.deepLink = deepLink;
    if (channels != null) body.channels = [...channels];
    body.idempotencyKey = opts.idempotencyKey != null ? String(opts.idempotencyKey) : randomUUID();
    const json = JSON.stringify(body);

    const delays = this.retryDelaysMs;
    const longest = delays.length === 0 ? 0 : Math.max(...delays);
    for (let attempt = 0; ; attempt++) {
      let problem: PlatformProblem;
      try {
        const response = await fetch(`${this.kf.platformUrlValue()}/api/v1/platform/modules/${this.kf.slug()}/notifications`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${this.kf.tokenValue()}`,
            Accept: 'application/json',
            'Content-Type': 'application/json',
          },
          body: json,
          redirect: 'manual',
          signal: AbortSignal.timeout(10_000),
        });
        const text = await response.text();
        if (response.status === 202) return result(text);
        problem = PlatformProblem.parse(response.status, text, response.headers.get('Retry-After'));
        if (!problem.retryable()) throw new NotificationRejected(problem, reason(text));
      } catch (e) {
        if (e instanceof KfException) throw e;
        problem = PlatformProblem.network(KfModule.describe(e));
      }
      if (attempt >= delays.length) throw new KfException(problem);
      let delay = delays[attempt];
      if (problem.retryAfterSeconds != null) {
        const asked = problem.retryAfterSeconds * 1000;
        delay = asked > longest ? longest : asked;
      }
      await new Promise((r) => setTimeout(r, delay));
    }
  }
}

/** A `permanent` refusal of the platform, with the protocol's `reason` (null when the body has none). Java: `Notifications.Rejected`. */
export class NotificationRejected extends KfException {
  constructor(problem: PlatformProblem, readonly reason: string | null) {
    super(problem);
    this.name = 'NotificationRejected';
  }
}

// ---------------------------------------------------------------------------------------------------------

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const MAX_DEEP_LINK = 2000;

function result(body: string): NotificationResult {
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const map = parsed as Record<string, unknown>;
      if (typeof map.accepted === 'number' && typeof map.duplicates === 'number') {
        return { accepted: Math.trunc(map.accepted), duplicates: Math.trunc(map.duplicates) };
      }
    }
  } catch {
    // falls through: a 202 the SDK cannot read
  }
  throw new KfException(new PlatformProblem(202, null, null, 'the notification answer has no accepted/duplicates',
    ErrorClass.PERMANENT, null));
}

function reason(body: string): string | null {
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const value = (parsed as Record<string, unknown>).reason;
      if (typeof value === 'string') return value;
    }
  } catch {
    // no reason
  }
  return null;
}

function relative(link: string): boolean {
  // eslint-disable-next-line no-control-regex
  return link.startsWith('/') && !link.startsWith('//') && link.length <= MAX_DEEP_LINK && !link.includes('\\')
    && !/[\u0000-\u001f\u007f-\u009f]/.test(link);
}
