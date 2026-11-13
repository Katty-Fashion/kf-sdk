// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/BeforeContext.java. Do not edit by hand: fix Java, then re-derive.
import { Allow, Decision, Patch } from './decision.js';
import { type Envelope, stringList, type VersionField, versionFields, versionIds } from './hookEvents.js';
import { ModelSnapshot } from './modelSnapshot.js';

/**
 * Input of a before-hook (`model.before.create|update|delete`).
 * - `before`: stored model (null on create)
 * - `after`: model as it will be saved, including patches of modules with a lower priority (null on delete)
 * - `data`: the raw CloudEvent `data`
 * - `changedVersionFields()`, `changedVersionIds()`: a version's paths in the model's envelope (§5.1), their values
 *   through `ModelSnapshot.versionExtension(versionId, slug, field)`
 * - `tenant`, `locale`, `eventId`, `envelopeVersion`: from the envelope (`kftenant`, `kflocale`, `kfeventid`,
 *   `kfenvelopeversion`)
 *
 * Return `allow()`, `reject(key, params)` (a translation KEY of the manifest's `i18n` and its parameters) or
 * `patch()` (null means allow).
 */
export class BeforeContext {
  readonly before: ModelSnapshot | null;
  readonly after: ModelSnapshot | null;
  /** Changed field paths (`name`, `materialList`, `extensions.{slug}.{field}`). */
  readonly changed: readonly string[];

  /** @internal */
  constructor(
    private readonly slug: string,
    /** e.g. `model.before.update` */
    readonly event: string,
    /** Every attribute of the envelope. */
    readonly envelope: Envelope,
    readonly data: Readonly<Record<string, unknown>>,
  ) {
    this.before = ModelSnapshot.fromMap(data.before);
    this.after = ModelSnapshot.fromMap(data.after);
    this.changed = stringList(data.changed);
  }

  /** `kfeventid`: the per-mutation id of this before-hook call; null from an older platform. */
  get eventId(): string | null {
    return this.envelope.eventId;
  }

  /** CloudEvents `id`: the id of this delivery. */
  get deliveryId(): string | null {
    return this.envelope.deliveryId;
  }

  /** `kftenant`: the tenant the mutation happens in. */
  get tenant(): string | null {
    return this.envelope.tenant;
  }

  /** `kflocale`: the language of the user who causes the mutation (`en` by default). */
  get locale(): string | null {
    return this.envelope.locale;
  }

  /** `kfactor`: the OIDC subject of that user; null for the system. */
  get actor(): string | null {
    return this.envelope.actor;
  }

  /** `kfenvelopeversion`, e.g. `"1"`. */
  get envelopeVersion(): string | null {
    return this.envelope.envelopeVersion;
  }

  /** Model id; null on create. */
  get id(): string | null {
    const id = this.data.id;
    return id == null ? null : String(id);
  }

  /**
   * The version paths of `changed` (`versions.{versionId}.extensions.{slug}.{field}`), in order: a module's write on
   * `model_version` (§5.1). Read each value with `after.versionExtension(f.versionId, f.slug, f.field)`.
   */
  changedVersionFields(): readonly VersionField[] {
    return versionFields(this.changed);
  }

  /** The versions those paths touch, distinct, in order of first appearance. */
  changedVersionIds(): readonly string[] {
    return versionIds(this.changedVersionFields());
  }

  /** This module's own value in `after` (or `before` on delete). */
  ownExtension(field: string): unknown {
    const s = this.after ?? this.before;
    return s == null ? null : s.extension(this.slug, field);
  }

  allow(): Decision {
    return new Allow();
  }

  /**
   * A veto: `{"decision":"reject","key":…,"params":{…}}`. The platform shows the key's text on the user's
   * language, with the parameters interpolated (texts at most 500 characters, numbers or booleans; at most 20).
   */
  reject(key: string, params?: Record<string, unknown> | null): Decision {
    return Decision.reject(key, params ?? {});
  }

  /** Starts a patch; chain `.name(..)`, `.set(..)`, `.extension(field, value)` and return it. */
  patch(): Patch {
    return new Patch(this.slug);
  }
}
