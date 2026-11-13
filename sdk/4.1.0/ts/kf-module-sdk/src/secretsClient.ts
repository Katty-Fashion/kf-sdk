// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SecretsClient.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import type { KfModule } from './kfModule.js';
import { local, PlatformChannel } from './platformChannel.js';
import { PlatformProblem } from './platformProblem.js';
import type { RequestContext } from './requestContext.js';

const MOUNT = /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;
const TENANT = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const REDACTED = '***';
/** The longest existing reference the platform resolves for a module. */
const MAX_EXTERNAL_REF = 512;
const REF_SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/;
const MODULES_SEGMENT = 'kf-modules';

/**
 * A resolved secret. {@link value} is the only way to the value: `toString()` (and `JSON.stringify`, and Node's
 * `util.inspect`) show the reference and `***`. Java: `SecretsClient.Secret`.
 */
export class Secret {
  readonly #value: string;

  /** @internal */
  constructor(readonly ref: string, value: string) {
    this.#value = value;
  }

  value(): string {
    return this.#value;
  }

  toString(): string {
    return `Secret[ref=${this.ref}, value=${REDACTED}]`;
  }

  toJSON(): Record<string, string> {
    return { ref: this.ref, value: REDACTED };
  }

  [Symbol.for('nodejs.util.inspect.custom')](): string {
    return this.toString();
  }
}

/**
 * The module's secrets: capability `secrets` in `kf-module-conf.json`, protocol 1.1 §15.3. Obtained from
 * `KfModule.secrets()`. The manifest names a secret, never its value; the platform keeps the value in its vault under
 * the module's path and gives the module a reference `vault://<mount>/kf-modules/<slug>/<platform | tenantId>/<name>`,
 * which the module may store and resolve when it needs the value.
 *
 *   const ref = await kf.secrets().write(ctx, 'r3-client-secret', value);   // in a route with the secret's writeRight
 *   const value = (await kf.secrets().resolve(ctx, ref)).value();
 *
 * - {@link write}: `PUT …/secrets/{name}` `{"value"}` → the reference;
 * - {@link resolve}: `POST …/secrets/resolve` `{"ref"}` → the value, as a {@link Secret} whose `toString` never shows it.
 *
 * **A value is never logged and never put in an error**: this client logs nothing, and the texts of a platform refusal
 * to a write pass through a redaction of the value. Refused locally, `permanent`, nothing sent: a name
 * outside `^[a-z][a-z0-9-]{1,40}$` (or not declared in `capabilities.secrets`, when the manifest is known), an empty
 * value, a reference outside the module's path (another slug, another shape) or of another tenant than the context's,
 * and the context rules of `ReadsClient`. The platform's refusals are `PlatformRefusal`s (`403` without the secret's
 * `writeRight`, or for a reference the module may not read).
 *
 * **An existing reference** (SDK 0.7.2): when a `platform` secret of the manifest declares
 * `external` prefixes (`platform/aas/`), {@link resolve} also sends a reference the platform already holds
 * (`vault://platform/aas/kf-r3/client-secret`) strictly under one of them; the platform resolves it and audits every
 * resolve. {@link externalRefProblem} says why a reference is not one; without a known manifest every reference outside
 * the module's path is refused locally.
 *
 * **The secret store**: `503 secret-store-unreachable` (`transient`: not configured, not reachable, not logged into) and
 * `503 secret-store-rejected` (`permanent`: it answered and its policy refuses the path) come back as a `KfException`
 * whose `problem` carries the type, for the module to pass on to its user.
 */
export class SecretsClient {
  /** A secret's name, as the manifest declares it. */
  static readonly NAME = /^[a-z][a-z0-9-]{1,40}$/;
  /** The scheme of a reference. */
  static readonly SCHEME = 'vault://';

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'secrets');
  }

  /**
   * Writes the value of the secret `name` (in the tenant of `context` for a `tenant` secret) and returns its reference.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
   */
  async write(context: RequestContext, name: string, value: string): Promise<string> {
    const errors: string[] = [];
    if (name == null || !SecretsClient.NAME.test(name)) errors.push('name must match ^[a-z][a-z0-9-]{1,40}$');
    if (value == null || value === '') errors.push('the value is required');
    if (errors.length > 0) throw local(`invalid secret write: ${errors.join('; ')}`);
    const manifest = this.channel.kf().manifest();
    if (manifest != null && !manifest.capabilities.secrets.some((s) => name === s.name)) {
      throw local(`secret ${name} is not declared in capabilities.secrets of the manifest`);
    }
    const forwarded = this.channel.forwarded(context);

    const answer = await this.channel.send('PUT', forwarded, `/${name}`, '', { value });
    if (answer.status !== 200) throw this.channel.failure(answer, (text) => text.split(value).join(REDACTED));
    const body = this.channel.answerObject(answer, 'an object {ref}');
    const ref = body.ref;
    if (typeof ref !== 'string' || this.refProblem(ref, context) != null) {
      throw new KfException(new PlatformProblem(200, null, null, 'the secrets answer has no reference of this module',
        ErrorClass.PERMANENT, null));
    }
    return ref;
  }

  /**
   * The value behind `ref`, a reference of this module (`platform`, or of the context's tenant), or an existing
   * reference strictly under a prefix a `platform` secret of the manifest declares in `external`.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`401`, `403`, `404`)
   */
  async resolve(context: RequestContext, ref: string): Promise<Secret> {
    let problem = this.refProblem(ref, context);
    if (problem != null && !ownFamily(ref)) problem = this.externalRefProblem(ref);
    if (problem != null) throw local(`invalid secret reference: ${problem}`);
    const forwarded = this.channel.forwarded(context);

    const answer = await this.channel.send('POST', forwarded, '/resolve', '', { ref });
    if (answer.status !== 200) throw this.channel.failure(answer);
    const body = this.channel.answerObject(answer, 'an object {value}');
    if (typeof body.value !== 'string') {
      throw new KfException(new PlatformProblem(200, null, null, 'the secrets answer has no value', ErrorClass.PERMANENT, null));
    }
    return new Secret(ref, body.value);
  }

  /**
   * Why `ref` is not an existing reference this module may keep and resolve, or null when it is (SDK 0.7.2): `vault://` + a path of at least two segments of `[A-Za-z0-9._-]` (none empty, none starting
   * with a dot), with no `kf-modules` segment, at most 512 characters, strictly under a prefix that a `platform` secret
   * of the manifest declares in `external`. The platform applies the same rules (`403 external-ref-not-declared`
   * otherwise).
   */
  externalRefProblem(ref: string | null | undefined): string | null {
    if (ref == null || !ref.startsWith(SecretsClient.SCHEME)) return `a reference starts with ${SecretsClient.SCHEME}`;
    if (ref.length > MAX_EXTERNAL_REF) return `a reference has at most ${MAX_EXTERNAL_REF} characters`;
    const path = ref.substring(SecretsClient.SCHEME.length);
    const segments = path.split('/');
    const shaped = segments.length >= 2 && segments.every((s) => REF_SEGMENT.test(s) && s !== MODULES_SEGMENT);
    if (!shaped) {
      return `${ref} is not an existing vault reference (vault://<mount>/<path>: segments of [A-Za-z0-9._-], `
        + 'none empty, none starting with a dot, no kf-modules)';
    }
    const manifest = this.channel.kf().manifest();
    if (manifest == null) {
      return `${ref} is outside the module's path, and without the manifest no capabilities.secrets[].external `
        + 'prefix is known';
    }
    const covered = manifest.capabilities.secrets
      .filter((secret) => secret.scope === 'platform')
      .flatMap((secret) => secret.external)
      .some((prefix) => path.startsWith(prefix) && path.length > prefix.length);
    return covered ? null : `${ref} is under no prefix declared in capabilities.secrets[].external`;
  }

  /**
   * @internal Why `ref` is not a reference this module may resolve in the tenant of `context`, or null when it is:
   * `vault://<mount>/kf-modules/<this slug>/<platform | the context's tenant>/<name>`.
   */
  refProblem(ref: string | null | undefined, context: RequestContext | null | undefined): string | null {
    const slug = this.channel.kf().slug();
    if (ref == null || !ref.startsWith(SecretsClient.SCHEME)) return `a reference starts with ${SecretsClient.SCHEME}`;
    const rest = ref.substring(SecretsClient.SCHEME.length);
    const at = rest.lastIndexOf('/kf-modules/');
    if (at <= 0 || !MOUNT.test(rest.substring(0, at))) {
      return `${ref} is not a reference of this module (vault://<mount>/kf-modules/${slug}/…)`;
    }
    const parts = rest.substring(at + '/kf-modules/'.length).split('/');
    if (parts.length !== 3 || parts[0] !== slug) {
      return `${ref} is not a reference of this module (vault://<mount>/kf-modules/${slug}/…)`;
    }
    if (!SecretsClient.NAME.test(parts[2])) return `${ref} does not end with a secret name`;
    if (parts[1] === 'platform') return null;
    if (!TENANT.test(parts[1])) return `${ref} names neither platform nor a tenant`;
    if (context != null && parts[1].toLowerCase() !== context.tenant.toLowerCase()) {
      return `${ref} is a reference of another tenant than the context's`;
    }
    return null;
  }
}

/** Whether `ref` names the modules' family (`…/kf-modules/…`): its problem is `refProblem`'s. */
function ownFamily(ref: string | null | undefined): boolean {
  return ref != null && ref.startsWith(SecretsClient.SCHEME)
    && ref.substring(SecretsClient.SCHEME.length).split('/').includes(MODULES_SEGMENT);
}
