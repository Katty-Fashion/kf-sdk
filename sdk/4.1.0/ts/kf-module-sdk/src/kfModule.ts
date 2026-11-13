// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfModule.java. Do not edit by hand: fix Java, then re-derive.
import { existsSync, readFileSync, statSync } from 'node:fs';
import { AuditClient } from './auditClient.js';
import { BeforeContext } from './beforeContext.js';
import { Allow, Patch, Reject } from './decision.js';
import { ErrorClass } from './errorClass.js';
import { ExtensionsClient } from './extensionsClient.js';
import { FeatureFlags } from './featureFlags.js';
import type { FieldType } from './fieldType.js';
import {
  AfterEvent, type CallbackRecord, DeliveryContext, Envelope, FieldChange, HookEvents, MODULE_EVENT_POINT,
  MODULE_EVENT_PREFIX, ModuleEvent, stringList, TENANT_POINTS, TenantDeleted, TenantRestored, TenantSoftDeleted,
} from './hookEvents.js';
import { EventsClient } from './eventsClient.js';
import { FlowEvent, FlowEvents } from './flowEvents.js';
import { OrderServices } from './orderServices.js';
import { PeersClient } from './peersClient.js';
import { HookResult } from './hookResult.js';
import { log } from './log.js';
import { Manifest } from './manifest.js';
import { ManifestCheck } from './manifestCheck.js';
import { ModelHooks } from './modelHooks.js';
import { TenantHooks } from './tenantHooks.js';
import { Notifications } from './notifications.js';
import { PlatformProblem } from './platformProblem.js';
import { ReadsClient } from './readsClient.js';
import { RequestContext } from './requestContext.js';
import { SdkVersion } from './sdkVersion.js';
import { SecretsClient } from './secretsClient.js';
import { StorageClient } from './storageClient.js';
import { SettingsClient } from './settingsClient.js';
import { Signatures } from './signatures.js';
import { UiContributions } from './uiContributions.js';

/** What {@link AuditClient} sends back for one verified context. Java: `KfModule.ForwardedContext`. */
export interface ForwardedContext {
  readonly contextHeader: string;
  readonly proof: string | null;
  readonly ts: number;
}

/**
 * The options of {@link KfModule.handle} (Java's overloads): the context the platform puts on an after delivery
 * (protocol 2.0, `X-KF-Context` and `X-KF-Context-Proof`) and the time to verify the signature at.
 */
export interface HandleOptions {
  readonly contextHeader?: string | null;
  readonly proofHeader?: string | null;
  /** Epoch seconds, for tests and replays; default now. */
  readonly nowSeconds?: number;
}

/** One field of the registration body. */
export interface FieldRegistration {
  entity: string;
  name: string;
  type: FieldType;
  table: string;
  label?: string;
}

/**
 * A patch on `model.before.delete` / `order.before.delete`, or a patch of an order's core field: the module's own bug,
 * answered 500 `permanent`.
 */
class PatchNotAllowedError extends Error {
  constructor(event: string, coreFields?: readonly string[]) {
    super(coreFields == null
      ? `a patch is not allowed on ${event}; answer allow or reject`
      : `a patch on ${event} may carry only extensions of this module (the order's core fields are patch: []); it names `
        + [...coreFields].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', '));
    this.name = 'PatchNotAllowedError';
  }
}

/**
 * Entry point of the KF module SDK.
 *
 *   const kf = KfModule.fromEnv();
 *   kf.models()
 *     .beforeCreate((ctx) => (ctx.after?.name?.includes('x') ? ctx.reject('veto.no-x') : ctx.allow()))
 *     .beforeUpdate((ctx) => ctx.allow(), { priority: 50, onFailure: 'block' })   // block needs the manifest capability
 *     .afterUpdate((ev) => console.info(`updated ${ev.id}`));
 *   kf.registerField('order', 'priority_code', 'STRING', 'orders_extra', 'field.priority'); // label = i18n key
 *   kf.ui().contribute('models.form.edit.fields.after',
 *     Surface.react('/m/my-mod/ui/surfaces.mjs', 'MyFields').css('/m/my-mod/ui/surfaces.css').order(10));
 *   http.createServer(nodeHandler(kf, { uiDir: './ui' })).listen(3000);   // POST /_kf/hooks, /ui/**, own routes
 *   kf.start();                               // checks kf-module-conf.json locally, then registers in the background,
 *                                             // retrying transient errors only
 *   kf.flags().isEnabled('my-flag');          // feature flags: fetched after registering, refreshed by every callback
 *   await kf.notifications().send(tenantId, 'author-changed', [subject], { modelName: 'X' }); // declared,
 *   await kf.reads().get(ctx, 'model.v1', '42');  // protocol 1.1: the platform's objects, in the context's tenant
 *   await kf.settings().effective(ctx);          // settings, secrets(), extensions(): the other services of protocol 1.1
 *
 * Environment (injected by the platform): `KF_PLATFORM_URL`, `KF_MODULE_SLUG`, `KF_MODULE_TOKEN`, `KF_HOOK_SECRET`, and
 * `KF_CALLBACK_BASE` for exposed services. `KF_MODULE_MANIFEST` (default `./kf-module-conf.json`) is the manifest the
 * SDK checks the registration against before sending it (`ManifestCheck`); without it the local check is skipped with
 * a warning.
 *
 * Version: `SdkVersion.SDK_VERSION`, manifest `schemaVersion` `SdkVersion.PROTOCOL_SCHEMA_VERSION`.
 */
export class KfModule {
  static readonly DEFAULT_HOOK_PATH = '/_kf/hooks';
  /** Where {@link fromEnv} looks for the manifest when `KF_MODULE_MANIFEST` is not set. */
  static readonly DEFAULT_MANIFEST = './kf-module-conf.json';
  /** The registration retry schedule in milliseconds (transient errors only); the last step repeats. */
  static readonly DEFAULT_RETRY_DELAYS: readonly number[] = Object.freeze([1000, 2000, 5000, 10000, 15000, 30000]);

  private readonly platformUrl: string | null;
  private readonly slugValue: string | null;
  private readonly token: string | null;
  private readonly hookSecret: string | null;
  private readonly callbackBase: string | null;
  private readonly hookPathValue: string;

  private readonly auditClient = new AuditClient(this);
  private readonly readsClient = new ReadsClient(this);
  private readonly settingsClient = new SettingsClient(this);
  private readonly secretsClient = new SecretsClient(this);
  private readonly extensionsClient = new ExtensionsClient(this);
  private readonly notificationsClient = new Notifications(this);
  private readonly modelHooks = new ModelHooks();
  private readonly orderHooks = new ModelHooks('order');
  private readonly flowEvents = new FlowEvents();
  private readonly orderServicesClient = new OrderServices(this);
  private readonly tenantHooks = new TenantHooks();
  private readonly storageClient = new StorageClient(this);
  private readonly peersClient = new PeersClient(this);
  private readonly eventsClient = new EventsClient(this);
  private readonly uiContributions = new UiContributions();
  private readonly featureFlags = new FeatureFlags();
  private readonly fields: FieldRegistration[] = [];
  private readonly observers: ((record: CallbackRecord) => void)[] = [];
  private readonly publicPaths = new Set<string>(['/health']);

  private retryDelays: readonly number[] = KfModule.DEFAULT_RETRY_DELAYS;
  private started = false;
  private stopped = false;
  private timer: NodeJS.Timeout | null = null;
  private wake: (() => void) | null = null;
  private registered = false;
  private registrationStopped = false;
  private lastProblem: PlatformProblem | null = null;
  private lastError: string | null = null;
  private lastSummary: Record<string, unknown> | null = null;
  private manifestJson: string | null = null;
  private manifestSource: string | null = null;

  /** How many verified contexts are kept for {@link AuditClient} (the oldest go first). */
  static readonly MAX_FORWARDED_CONTEXTS = 4096;

  /**
   * The raw `X-KF-Context` and the platform's `X-KF-Context-Proof` of every context this instance verified, by
   * `requestId`: {@link AuditClient} sends them back, unchanged, with the module token. Bounded; an entry is
   * used only while its `ts` is inside the ±300 s window.
   */
  private readonly forwardedContexts = new Map<string, ForwardedContext>();

  private constructor(platformUrl: string | null | undefined, slug: string | null | undefined, token: string | null | undefined,
    hookSecret: string | null | undefined, callbackBase: string | null | undefined, hookPath: string) {
    this.platformUrl = platformUrl == null || platformUrl === '' ? null : platformUrl.replace(/\/+$/, '');
    this.slugValue = slug == null || slug === '' ? null : slug;
    this.token = token == null || token === '' ? null : token;
    this.hookSecret = hookSecret == null || hookSecret === '' ? null : hookSecret;
    this.callbackBase = callbackBase == null || callbackBase === '' ? null : callbackBase.replace(/\/+$/, '');
    this.hookPathValue = hookPath;
  }

  /** Reads the `KF_*` environment variables injected by the platform (defaults to `process.env`). */
  static fromEnv(env: Record<string, string | undefined> = process.env): KfModule {
    const kf = new KfModule(env.KF_PLATFORM_URL, env.KF_MODULE_SLUG, env.KF_MODULE_TOKEN, env.KF_HOOK_SECRET,
      env.KF_CALLBACK_BASE, KfModule.DEFAULT_HOOK_PATH);
    const location = env.KF_MODULE_MANIFEST ?? KfModule.DEFAULT_MANIFEST;
    let isFile = false;
    try {
      isFile = existsSync(location) && statSync(location).isFile();
    } catch {
      isFile = false;
    }
    if (isFile) {
      try {
        kf.manifestJson = readFileSync(location, 'utf8');
        kf.manifestSource = location;
      } catch (e) {
        log().warn(`KF SDK: the manifest ${location} cannot be read (${KfModule.describe(e)}); the local check is skipped`);
      }
    } else {
      log().warn(`KF SDK: no manifest at ${location} (KF_MODULE_MANIFEST); the registration is not checked locally, only by the platform`);
    }
    return kf;
  }

  /** Explicit configuration (tests, local runs). */
  static of(platformUrl: string | null, slug: string | null, token: string | null, hookSecret: string | null,
    callbackBase: string | null): KfModule {
    return new KfModule(platformUrl, slug, token, hookSecret, callbackBase, KfModule.DEFAULT_HOOK_PATH);
  }

  /**
   * The manifest the registration is checked against before it is sent (`ManifestCheck`); replaces the one
   * {@link fromEnv} read. `null` disables the local check.
   */
  withManifest(manifestJson: string | null): this {
    this.manifestJson = manifestJson;
    this.manifestSource = manifestJson == null ? null : 'kf-module-conf.json (given)';
    return this;
  }

  /** The manifest the SDK read, typed, or null when there is none. Never throws: a broken manifest is null here. */
  manifest(): Manifest | null {
    if (this.manifestJson == null) return null;
    try {
      return Manifest.parse(this.manifestJson);
    } catch {
      return null;
    }
  }

  // ---- declaration ------------------------------------------------------------------------

  models(): ModelHooks {
    return this.modelHooks;
  }

  /**
   * Hooks on the platform's orders (protocol 2.0, sdk-protocol.md §16.2; the points `order.*`, public): the shape of {@link models} -- `kf.orders().beforeUpdate((ctx) => …)`,
   * `kf.orders().onFieldChange(HookEvents.ORDER_PATH_STATUS, (change) => …)`. A patch carries only the module's own
   * `extensions` (the order's core fields are `patch: []`): a patch that names a core field is answered
   * `500 permanent`, the module's bug.
   */
  orders(): ModelHooks {
    return this.orderHooks;
  }

  /**
   * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): `kf.flows().on(FlowEvents.STEP_ENTERED,
   * (e) => …)`; delivered by the platform with `FLOW_EVENTS`, used only when the platform announces it
   * (`FlowEvents`).
   */
  flows(): FlowEvents {
    return this.flowEvents;
  }

  /**
   * The module's service lines on an order (protocol 2.0, `order.services.v1`, sdk-protocol.md §16.6):
   * `await kf.orderServices().upsert(ctx, line)`, `delete(ctx, orderId, externalRef)`; delivered by the platform with
   * `ORDER_SERVICES`, used only when the platform announces it (`OrderServices`).
   */
  orderServices(): OrderServices {
    return this.orderServicesClient;
  }

  /** UI contributions to the host's frontend contact surfaces (sent with the registration). */
  ui(): UiContributions {
    return this.uiContributions;
  }

  /** The module's feature flags: `kf.flags().isEnabled('key')` (unknown keys are off). */
  flags(): FeatureFlags {
    return this.featureFlags;
  }

  /**
   * The module's access to the platform's audit trail (capability `audit`; implemented by the platform
   *): `await kf.audit().record(ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'))` inside a route
   * handler, with the {@link RequestContext} the adapter verified (`AuditClient`).
   */
  audit(): AuditClient {
    return this.auditClient;
  }

  /**
   * The module's reads of platform objects (capability `reads`, protocol 1.1 §15.1):
   * `await kf.reads().list(ctx, 'order.v1', { modelVersionId: '17' }, 0, 50)` and
   * `await kf.reads().get(ctx, 'model.v1', '42')` inside a route or hook handler, with the {@link RequestContext} the
   * adapter verified (`ReadsClient`).
   */
  reads(): ReadsClient {
    return this.readsClient;
  }

  /**
   * The module's settings in the context's tenant (capability `settings`, protocol 1.1 §15.2):
   * `await kf.settings().effective(ctx)`, typed by the manifest (`SettingsClient`).
   */
  settings(): SettingsClient {
    return this.settingsClient;
  }

  /**
   * The module's secrets (capability `secrets`, protocol 1.1 §15.3):
   * `await kf.secrets().write(ctx, name, value)` → a `vault://` reference, `await kf.secrets().resolve(ctx, ref)` → the
   * value; a value is never logged (`SecretsClient`).
   */
  secrets(): SecretsClient {
    return this.secretsClient;
  }

  /**
   * The module's files through the platform (protocol 1.2 §3.5, capability `storage`):
   * `await kf.storage().uploadUrl(ctx, contentType, size)` → a presigned `PUT` under a key the platform chooses,
   * `await kf.storage().downloadUrl(ctx, key)` → a presigned `GET` (`StorageClient`).
   */
  storage(): StorageClient {
    return this.storageClient;
  }

  /**
   * The module's reads and commands of another module, through the platform (protocol 2.0, sdk-protocol.md §15.9):
   * `await kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', {})` and
   * `await kf.peers().command(ctx, 'kf-stock', 'reserve', PeersClient.newIdempotencyKey(), body)`, with the
   * {@link RequestContext} the adapter verified or, for a background job, an after delivery's `DeliveryContext`
   * (`PeersClient`).
   */
  peers(): PeersClient {
    return this.peersClient;
  }

  /**
   * The module events `m.{owner}.after.{name}` (protocol 2.0, sdk-protocol.md §6.3):
   * `await kf.events().emit(ctx, 'level-changed', data)` for the module's own, `kf.events().on(owner, name, handler)`
   * to subscribe to another module's (`EventsClient`).
   */
  events(): EventsClient {
    return this.eventsClient;
  }

  /**
   * Hooks on the platform's tenants (protocol 1.2 §6.1): `kf.tenants().onDeleted((d) => …)` receives
   * `tenant.after.delete`; from protocol 1.3, `onSoftDelete` / `onRestore` receive `tenant.after.soft-delete` /
   * `tenant.after.restore` (`TenantHooks`).
   */
  tenants(): TenantHooks {
    return this.tenantHooks;
  }

  /**
   * The module's own extension values on platform objects (protocol 1.1 §15.4):
   * `await kf.extensions().write(ctx, 'model_version', '17', values)` sends exactly the given fields
   * (`ExtensionsClient`).
   */
  extensions(): ExtensionsClient {
    return this.extensionsClient;
  }

  /**
   * The notifications the module declared in `capabilities.notifications` (sdk-protocol.md §11.1):
   * `await kf.notifications().send(tenantId, 'author-changed', [subject], { modelName: 'X' })` ({@link Notifications}).
   */
  notifications(): Notifications {
    return this.notificationsClient;
  }

  /**
   * Declares an extension field of an extensible entity of the platform (`model`, `order`, `customer`),
   * stored in the real column `name` of the table `m_{slug}_{table}` (created by the platform). Values appear under
   * `extensions.{slug}.{name}` of that entity.
   *
   * The field must be declared in `capabilities.fields` of `kf-module-conf.json` with the same entity, name, type and
   * table; the label is a translation KEY of `i18n.en`, or null for the field's name.
   */
  registerField(entity: string, name: string, type: FieldType, table: string, labelKey: string | null = null): this {
    for (const [k, v] of Object.entries({ entity, name, type, table })) if (v == null) throw new TypeError(k);
    const f: FieldRegistration = { entity, name, type, table };
    if (labelKey != null) f.label = labelKey;
    this.fields.push(f);
    return this;
  }

  /** Observer called for every verified callback (e.g. to keep a debug log of what was received). */
  onCallback(observer: (record: CallbackRecord) => void): this {
    this.observers.push(observer);
    return this;
  }

  slug(): string | null {
    return this.slugValue;
  }

  hookPath(): string {
    return this.hookPathValue;
  }

  isRegistered(): boolean {
    return this.registered;
  }

  /** The last registration failure as one line (status, class, `detail`; never the token), or null. */
  lastRegistrationError(): string | null {
    return this.lastError;
  }

  /** The last registration failure as read from the platform's answer, or null. */
  lastRegistrationProblem(): PlatformProblem | null {
    return this.lastProblem;
  }

  /**
   * True once the registration stopped on a permanent error (a wrong token, a module in the wrong state, a refused
   * registration, a manifest the local check refuses): the SDK does not retry it; the module must be fixed and
   * restarted.
   */
  isRegistrationStopped(): boolean {
    return this.registrationStopped;
  }

  /**
   * Replaces the retry schedule of the registration in milliseconds (transient errors only; the last step repeats).
   * The default is {@link DEFAULT_RETRY_DELAYS}; a `Retry-After` of the platform is honoured up to the longest step.
   */
  withRetryDelays(delaysMs: readonly number[]): this {
    if (delaysMs == null || delaysMs.length === 0 || delaysMs.some((d) => d == null || !(d >= 0))) {
      throw new Error('retry delays must be a non-empty list of non-negative durations');
    }
    this.retryDelays = Object.freeze([...delaysMs]);
    return this;
  }

  lastRegistrationSummary(): Record<string, unknown> | null {
    return this.lastSummary;
  }

  /**
   * The warnings of the last successful registration (`warnings[]` of the platform's answer): among them for a
   * contribution to a slot its owner does not offer (yet) — stored inactive, not served until it does
   * (sdk-protocol.md §7.12). Empty before the first registration. Each one is also logged at WARNING.
   */
  registrationWarnings(): string[] {
    const summary = this.lastSummary;
    if (summary == null || !Array.isArray(summary.warnings)) return [];
    return summary.warnings.filter((w): w is string => typeof w === 'string');
  }

  /** The body of `PUT /api/v1/platform/modules/{slug}/registrations`. */
  registrationBody(): Record<string, unknown> {
    return {
      callbackUrl: this.callbackBase == null ? null : this.callbackBase + this.hookPathValue,
      hooks: [...this.modelHooks.registration(), ...this.orderHooks.registration(), ...this.flowEvents.registration(),
        ...this.tenantHooks.registration(), ...this.eventsClient.registration()],
      fields: this.fields.map((f) => ({ ...f })),
      surfaces: this.uiContributions.registration(),
    };
  }

  // ---- registration -----------------------------------------------------------------------

  /**
   * Starts the registration in the background. It retries (1 s … 30 s) only **transient** errors -- network errors,
   * `503` during the first install, any answer with `class: transient` (`ErrorClass`); a permanent error (`401`, `403`,
   * `409`, `422`, an unknown class) stops it with one ERROR log naming the status and the `detail`
   * ({@link isRegistrationStopped}, {@link lastRegistrationError}). Idempotent. Without
   * `KF_PLATFORM_URL`/`KF_MODULE_SLUG`/`KF_MODULE_TOKEN` it only logs a warning.
   */
  start(): this {
    if (this.started) return this;
    this.started = true;
    if (!this.checkManifest()) return this;
    if (this.platformUrl == null || this.slugValue == null || this.token == null) {
      log().warn('KF SDK: KF_PLATFORM_URL / KF_MODULE_SLUG / KF_MODULE_TOKEN not set, not registering (running outside the platform?)');
      return this;
    }
    void this.registerLoop();
    return this;
  }

  /**
   * The local check of the manifest and of the registration against it, before the first request. A violation is
   * permanent: nothing is sent and nothing is retried.
   */
  private checkManifest(): boolean {
    const json = this.manifestJson;
    if (json == null) return true;
    const result = ManifestCheck.check(json, this.registrationBody(), []);
    for (const warning of result.warnings) log().warn(`KF SDK: manifest ${this.manifestSource}: ${warning}`);
    if (result.ok()) return true;
    const problem = PlatformProblem.local(`the registration of ${this.slugValue} was refused by the local check of `
      + `${this.manifestSource}: ${result.errors.join('; ')}`);
    this.lastProblem = problem;
    this.lastError = problem.describe();
    this.registrationStopped = true;
    log().error(`KF SDK: ${this.lastError}; nothing was sent. Fix kf-module-conf.json or the registration and restart the module.`);
    return false;
  }

  /** Stops a registration still retrying (shutdown, tests). Idempotent; a finished registration is unaffected. */
  stop(): void {
    this.stopped = true;
    if (this.timer != null) clearTimeout(this.timer);
    this.timer = null;
    const wake = this.wake;
    this.wake = null;
    if (wake != null) wake();
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => {
      this.wake = resolve;
      this.timer = setTimeout(() => {
        this.timer = null;
        this.wake = null;
        resolve();
      }, ms);
      this.timer.unref();
    });
  }

  private async registerLoop(): Promise<void> {
    const url = `${this.platformUrl}/api/v1/platform/modules/${this.slugValue}/registrations`;
    const delays = this.retryDelays;
    let attempt = 0;
    while (!this.registered && !this.stopped) {
      let problem: PlatformProblem;
      try {
        const response = await fetch(url, {
          method: 'PUT',
          headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify(this.registrationBody()),
          signal: AbortSignal.timeout(10_000),
        });
        const text = await response.text();
        if (this.stopped) return;
        if (response.status === 200) {
          this.registered = true;
          this.lastError = null;
          let summary: Record<string, unknown> = {};
          try {
            const parsed: unknown = JSON.parse(text);
            if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) summary = parsed as Record<string, unknown>;
          } catch {
            /* a summary that is not JSON carries no warning */
          }
          this.lastSummary = summary;
          log().info(`KF SDK: module ${this.slugValue} registered: ${text}`);
          if (Array.isArray(summary.warnings)) {
            for (const w of summary.warnings) log().warn(`KF SDK: registration warning: ${String(w)}`);
          }
          await this.fetchFlags();
          return;
        }
        problem = PlatformProblem.parse(response.status, text, response.headers.get('Retry-After'));
      } catch (e) {
        if (this.stopped) return;
        // describe() walks the cause chain: fetch throws `TypeError: fetch failed` and keeps the reason (refused,
        // unresolvable host) on its causes.
        problem = PlatformProblem.network(KfModule.describe(e));
      }
      this.lastProblem = problem;
      this.lastError = problem.describe();
      if (!problem.retryable()) {
        // a permanent error does not fix itself; retrying it forever only hides it.
        this.registrationStopped = true;
        log().error(`KF SDK: registration of ${this.slugValue} refused permanently (${this.lastError}); not retrying. `
          + 'Fix the cause and restart the module.');
        return;
      }
      let delay = delays[Math.min(attempt++, delays.length - 1)];
      if (problem.retryAfterSeconds != null) {
        // Retry-After paces the retries (503 during the first install), never beyond the schedule's longest step
        const asked = problem.retryAfterSeconds * 1000;
        const longest = delays[delays.length - 1];
        if (asked > delay) delay = asked > longest ? longest : asked;
      }
      log().warn(`KF SDK: registration of ${this.slugValue} failed (${this.lastError}), retrying in ${delay} ms`);
      await this.sleep(delay);
    }
  }

  /**
   * One line for a failed platform call, cause chain included: `TypeError: fetch failed (cause: Error: connect
   * ECONNREFUSED 127.0.0.1:8080)` instead of a bare `fetch failed`.
   *
   * `fetch` reports a refused connection or an unresolvable host as `TypeError: fetch failed`, the reason sitting on
   * `cause`. Without it "platform down" and "wrong host" read the same in the module's log. An empty message gives way
   * to the error's `code` (Node's system errors) or to its name alone; a cause that renders like the previous one adds
   * nothing and is skipped; at most three causes, and a cyclic chain stops. Only the errors are read, never the request,
   * so neither the token nor the hook secret can reach the line.
   */
  static describe(error: unknown): string {
    const first = render(error);
    const seen = new Set<unknown>([error]);
    const causes: string[] = [];
    let previous = first;
    for (let cause = causeOf(error); cause != null && causes.length < 3 && !seen.has(cause); cause = causeOf(cause)) {
      seen.add(cause);
      const rendered = render(cause);
      if (rendered !== previous) causes.push(rendered);
      previous = rendered;
    }
    return causes.length === 0 ? first : `${first} (cause: ${causes.join('; ')})`;
  }

  /** `GET /api/v1/platform/modules/{slug}/flags` with the module token, once after registering (best effort). */
  private async fetchFlags(): Promise<void> {
    try {
      const response = await fetch(`${this.platformUrl}/api/v1/platform/modules/${this.slugValue}/flags`, {
        headers: { Authorization: `Bearer ${this.token}` },
        signal: AbortSignal.timeout(10_000),
      });
      if (response.status !== 200) {
        log().warn(`KF SDK: reading the feature flags failed: HTTP ${response.status}`);
        return;
      }
      const values: Record<string, unknown> = {};
      const list: unknown = await response.json();
      if (Array.isArray(list)) {
        for (const o of list) {
          if (o !== null && typeof o === 'object' && (o as Record<string, unknown>).key != null) {
            values[String((o as Record<string, unknown>).key)] = (o as Record<string, unknown>).enabled;
          }
        }
      }
      this.featureFlags.replace(values);
      log().info(`KF SDK: feature flags ${JSON.stringify(this.featureFlags.all())}`);
    } catch (e) {
      log().warn(`KF SDK: reading the feature flags failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  // ---- callbacks --------------------------------------------------------------------------

  /**
   * Handles one platform callback (`POST /_kf/hooks`) and returns what to answer. In this order:
   * 1. the signature on the raw body (Signatures, ±300 s): `401`;
   * 2. the body is a CloudEvent JSON object: `400`;
   * 3. the envelope version (`kfenvelopeversion`, rule): a major outside
   *    `SdkVersion.SUPPORTED_ENVELOPE_MAJORS`, a missing or a non-numeric value: `422`
   *    `https://kf.ro/problems/unsupported-envelope-version`, `class: permanent`, with `received` and `supported`; no
   *    handler is called;
   * 4. the handlers: `200` with the decision (before) or an ack (after / field change); `500` when a handler throws
   *    (`class: transient`) or answers a patch on delete (`class: permanent`).
   *
   * Every refusal is an RFC 9457 body (`application/problem+json`) with its `class`.
   *
   * With the context the platform puts on an after delivery (protocol 2.0, sdk-protocol.md §5.5) --
   * `X-KF-Context` and `X-KF-Context-Proof`, in `options` -- the handler sees it as `Envelope.deliveryContext()`, for a
   * background job. The adapters pass both. Both absent (a before hook, a platform before 2.0) = no delivery context;
   * a header the SDK cannot read is logged and left out, never a reason to refuse the delivery (the signature covers
   * `ts + "." + body`, not the headers).
   *
   * @param options the delivery's context headers and/or `nowSeconds` (the time to verify the signature at, epoch
   *                seconds, for tests and replays; default now); a number is `nowSeconds`
   */
  async handle(rawBody: Uint8Array | string, timestampHeader: string | null | undefined, signatureHeader: string | null | undefined,
    options: number | HandleOptions = {}): Promise<HookResult> {
    const opts: HandleOptions = typeof options === 'number' ? { nowSeconds: options } : options;
    const nowSeconds = opts.nowSeconds ?? Math.floor(Date.now() / 1000);
    const body = typeof rawBody === 'string' ? Buffer.from(rawBody, 'utf8') : Buffer.from(rawBody);
    const invalid = Signatures.verify(this.hookSecret, timestampHeader, signatureHeader, body, nowSeconds);
    if (invalid != null) {
      return problem(401, 'invalid-signature', 'Invalid signature', invalid, ErrorClass.PERMANENT, {});
    }

    let ce: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(body.toString('utf8'));
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
      ce = parsed as Record<string, unknown>;
    } catch {
      return problem(400, 'invalid-envelope', 'Invalid envelope', 'body is not a CloudEvent JSON object', ErrorClass.PERMANENT, {});
    }

    // the envelope version is read after the signature and the parsing, before any handler
    const received = ce.kfenvelopeversion;
    if (!KfModule.supportedEnvelope(received)) {
      const supported = [...SdkVersion.SUPPORTED_ENVELOPE_MAJORS].sort((a, b) => a - b);
      const receivedText = received == null ? null : typeof received === 'string' ? received : JSON.stringify(received);
      log().warn(`KF SDK: envelope version ${receivedText} is not supported by kf-module-sdk ${SdkVersion.SDK_VERSION} `
        + `(supported majors: [${supported.join(', ')}]); the handler was not called`);
      return problem(422, 'unsupported-envelope-version', 'Unsupported envelope version',
        `kfenvelopeversion ${received == null ? 'is missing' : `"${receivedText}" is not supported`}; this SDK supports the majors [${supported.join(', ')}]`,
        ErrorClass.PERMANENT, { received: receivedText, supported });
    }

    const data = ce.data !== null && typeof ce.data === 'object' && !Array.isArray(ce.data) ? (ce.data as Record<string, unknown>) : {};
    // the module's effective flags as the platform had them when it sent the event
    if (ce.kfflags !== null && typeof ce.kfflags === 'object' && !Array.isArray(ce.kfflags)) {
      this.featureFlags.replace(ce.kfflags as Record<string, unknown>);
    }
    const envelope = Envelope.of(ce, deliveryContextOf(opts.contextHeader, opts.proofHeader));
    const event = envelope.event;

    let result: HookResult;
    try {
      result = await this.dispatch(event, envelope, data);
    } catch (e) {
      log().error(`KF SDK: handler for ${event} failed`, e);
      const patchOnDelete = e instanceof PatchNotAllowedError;
      const message = e instanceof Error ? (e.message === '' ? e.name : e.message) : String(e);
      result = problem(500, 'handler-failed', 'Handler failed', `handler failed: ${message}`,
        patchOnDelete ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT, {});
    }
    this.notifyObservers(event, envelope, data, result);
    return result;
  }

  /** @internal Whether `kfenvelopeversion` (text, e.g. `"1"` or `"1.3"`) has a supported major. */
  static supportedEnvelope(version: unknown): boolean {
    if (typeof version !== 'string' || version.trim() === '') return false;
    const dot = version.indexOf('.');
    const major = dot < 0 ? version : version.substring(0, dot);
    if (major === '' || !/^[0-9]+$/.test(major) || major.length > 9) return false;
    return SdkVersion.SUPPORTED_ENVELOPE_MAJORS.includes(Number.parseInt(major, 10));
  }

  private async dispatch(event: string, envelope: Envelope, data: Record<string, unknown>): Promise<HookResult> {
    const hooks = event.startsWith('order.') ? this.orderHooks : this.modelHooks;
    if (event.startsWith('model.before.') || event.startsWith('order.before.')) {
      const ctx = new BeforeContext(this.slugValue ?? '', event, envelope, data);
      let patch: Patch | null = null;
      for (const b of hooks.before.get(event) ?? []) {
        const d = await b.handler(ctx);
        if (d instanceof Reject) return ok(d.toJson());
        if (d instanceof Patch && !d.isEmpty()) patch = patch == null ? d : patch.merge(d);
      }
      if (patch != null && event.endsWith('.before.delete')) throw new PatchNotAllowedError(event);
      if (patch != null && hooks === this.orderHooks) {
        // protocol 2.0 (§16.2): the order's core fields carry patch: [] -- only extensions.{own slug}
        const core = Object.keys(patch.patchValues()).filter((k) => k !== 'extensions');
        if (core.length > 0) throw new PatchNotAllowedError(event, core);
      }
      return ok(patch == null ? new Allow().toJson() : patch.toJson());
    }
    if (event === HookEvents.FIELD_CHANGED || event === HookEvents.ORDER_FIELD_CHANGED) {
      const change = FieldChange.of(envelope, data);
      for (const w of hooks.fieldWatches) {
        if (w.paths.length === 0 || w.paths.includes(change.field)) await w.handler(change);
      }
      return ok({ ok: true });
    }
    if (FlowEvents.TYPES.includes(event)) {
      // protocol 2.0 (§16.3): the events of the flow engine
      const subscribed = this.flowEvents.handlersOf(event);
      if (subscribed.length === 0) return ok({ ok: true, ignored: event });
      let ev: FlowEvent;
      try {
        ev = FlowEvents.parse(envelope, data);
      } catch (e) {
        return problem(400, 'invalid-envelope', 'Invalid envelope', (e as Error).message, ErrorClass.PERMANENT, {});
      }
      for (const h of subscribed) await h(ev);
      return ok({ ok: true });
    }
    if (TENANT_POINTS.includes(event)) {
      const entity = typeof data.entity === 'string' ? data.entity : null;
      const tenantId = data.id == null ? null : String(data.id);
      if (entity !== 'tenant' || tenantId == null || tenantId.trim() === '') {
        return problem(400, 'invalid-envelope', 'Invalid envelope', `${event}`
          + ' needs data.entity = tenant and data.id', ErrorClass.PERMANENT, {});
      }
      if (event === HookEvents.TENANT_AFTER_DELETE) {
        if (this.tenantHooks.deleted.length === 0) return ok({ ok: true, ignored: event });
        const deleted = new TenantDeleted(tenantId, envelope);
        for (const h of this.tenantHooks.deleted) await h(deleted);
      } else if (event === HookEvents.TENANT_AFTER_SOFT_DELETE) {
        if (this.tenantHooks.softDeleted.length === 0) return ok({ ok: true, ignored: event });
        const softDeleted = new TenantSoftDeleted(tenantId, envelope);
        for (const h of this.tenantHooks.softDeleted) await h(softDeleted);
      } else {
        if (this.tenantHooks.restored.length === 0) return ok({ ok: true, ignored: event });
        const restored = new TenantRestored(tenantId, envelope);
        for (const h of this.tenantHooks.restored) await h(restored);
      }
      return ok({ ok: true });
    }
    if (event.startsWith(MODULE_EVENT_PREFIX)) {
      const point = MODULE_EVENT_POINT.exec(event);
      const subscribed = point != null ? this.eventsClient.handlersOf(event) : [];
      if (point == null || subscribed.length === 0) return ok({ ok: true, ignored: event });
      const ev = new ModuleEvent(point[1], point[2], data, envelope);
      for (const h of subscribed) await h(ev);
      return ok({ ok: true });
    }
    const handlers = hooks.after.get(event);
    if (handlers != null) {
      const ev = AfterEvent.of(event, envelope, data);
      for (const h of handlers) await h(ev);
      return ok({ ok: true });
    }
    return ok({ ok: true, ignored: event });
  }

  // ---- the module's own routes -------------------------------------------------------

  /**
   * Verifies the signed context of a request the platform forwards to one of the module's own routes (`X-KF-Context`,
   * `X-KF-Timestamp`, `X-KF-Signature`) with this module's `KF_HOOK_SECRET`, now. The adapters (`nodeHandler`)
   * call it.
   *
   * @throws Rejected (401, `permanent`) for a missing, altered, expired or incoherent context
   */
  verifyContext(contextHeader: string | null | undefined, timestampHeader: string | null | undefined,
    signatureHeader: string | null | undefined, body: Uint8Array | string | null | undefined,
    proofHeader?: string | null): RequestContext {
    const ctx = RequestContext.verify(contextHeader, timestampHeader, signatureHeader, body, this.hookSecret,
      Math.floor(Date.now() / 1000));
    // Java: the overload with proofHeader -- keeps the raw context and the platform's proof for AuditClient.
    this.forwardedContexts.delete(ctx.requestId);
    this.forwardedContexts.set(ctx.requestId, {
      contextHeader: (contextHeader as string).trim(),
      proof: proofHeader == null || proofHeader.trim() === '' ? null : proofHeader.trim(),
      ts: ctx.ts,
    });
    while (this.forwardedContexts.size > KfModule.MAX_FORWARDED_CONTEXTS) {
      const eldest = this.forwardedContexts.keys().next().value as string;
      this.forwardedContexts.delete(eldest);
    }
    return ctx;
  }

  /** @internal The raw context and proof of a context this instance verified within ±300 s; null otherwise. */
  forwardedContext(ctx: RequestContext | null | undefined): ForwardedContext | null {
    if (ctx == null || ctx.requestId == null) return null;
    const forwarded = this.forwardedContexts.get(ctx.requestId);
    if (forwarded == null || Math.abs(Math.floor(Date.now() / 1000) - forwarded.ts) > Signatures.MAX_SKEW_SECONDS) return null;
    return forwarded;
  }

  /** @internal The platform base URL, for {@link AuditClient} and {@link Notifications} (never logged). */
  platformUrlValue(): string | null {
    return this.platformUrl;
  }

  /** @internal The module token, for {@link AuditClient} and {@link Notifications} (never logged). */
  tokenValue(): string | null {
    return this.token;
  }

  /**
   * Paths of the module served WITHOUT a platform context: the hook callback ({@link hookPath}), the UI assets
   * (`/ui/**`, fetched by the platform's asset proxy) and these prefixes (default `/health`).
   */
  publicPath(prefix: string): this {
    if (prefix == null) throw new TypeError('prefix');
    this.publicPaths.add(prefix);
    return this;
  }

  /** Whether a request path is served without a platform context (see {@link publicPath}). */
  isPublicPath(path: string | null | undefined): boolean {
    if (path == null) return false;
    if (path === this.hookPathValue || path.startsWith('/ui/')) return true;
    for (const prefix of this.publicPaths) {
      if (path === prefix || path.startsWith(prefix.endsWith('/') ? prefix : `${prefix}/`)) return true;
    }
    return false;
  }

  private notifyObservers(event: string, envelope: Envelope, data: Record<string, unknown>, result: HookResult): void {
    if (this.observers.length === 0) return;
    let answer: unknown;
    try {
      answer = JSON.parse(result.body);
    } catch {
      answer = result.body;
    }
    const record: CallbackRecord = {
      receivedAt: new Date().toISOString(),
      type: event,
      eventId: envelope.eventId ?? envelope.deliveryId,
      entityId: data.id == null ? null : String(data.id),
      changed: stringList(data.changed),
      field: data.field == null ? null : String(data.field),
      status: result.status,
      answer,
      flags: this.featureFlags.all(),
      tenant: envelope.tenant,
      locale: envelope.locale,
    };
    for (const o of this.observers) {
      try {
        o(record);
      } catch {
        // observers must not break callbacks
      }
    }
  }
}

function ok(body: Record<string, unknown>): HookResult {
  return new HookResult(200, JSON.stringify(body), HookResult.CONTENT_TYPE);
}

/** An RFC 9457 answer with its `class`. */
function problem(status: number, type: string, title: string, detail: string, errorClass: ErrorClass,
  extra: Record<string, unknown>): HookResult {
  const body: Record<string, unknown> = { type: `https://kf.ro/problems/${type}`, title, status, detail, [ErrorClass.PROPERTY]: errorClass, ...extra };
  return new HookResult(status, JSON.stringify(body), HookResult.PROBLEM_CONTENT_TYPE);
}

function causeOf(e: unknown): unknown {
  return e !== null && typeof e === 'object' && 'cause' in e ? (e as { cause?: unknown }).cause ?? null : null;
}

function render(t: unknown): string {
  if (!(t instanceof Error)) {
    if (t !== null && typeof t === 'object') {
      const { name, message, code } = t as { name?: unknown; message?: unknown; code?: unknown };
      const n = typeof name === 'string' && name !== '' ? name : 'Error';
      const m = typeof message === 'string' && message.trim() !== '' ? message : typeof code === 'string' ? code : '';
      return m === '' ? n : `${n}: ${m}`;
    }
    return String(t);
  }
  const code = (t as { code?: unknown }).code;
  const message = t.message != null && t.message.trim() !== '' ? t.message : typeof code === 'string' && code !== '' ? code : '';
  return message === '' ? t.name : `${t.name}: ${message}`;
}

/** The context of an after delivery, or null when absent or unreadable (logged, never refused). */
function deliveryContextOf(contextHeader: string | null | undefined, proofHeader: string | null | undefined): DeliveryContext | null {
  if (contextHeader == null || contextHeader.trim() === '') return null;
  if (proofHeader == null || proofHeader.trim() === '') {
    log().warn(`KF SDK: the delivery carried ${RequestContext.CONTEXT_HEADER} without ${RequestContext.PROOF_HEADER}; `
      + 'no delivery context for the handler');
    return null;
  }
  try {
    return DeliveryContext.of(contextHeader, proofHeader);
  } catch (e) {
    log().warn(`KF SDK: the delivery context cannot be read (${e instanceof Error ? e.message : String(e)}); no delivery `
      + 'context for the handler');
    return null;
  }
}
