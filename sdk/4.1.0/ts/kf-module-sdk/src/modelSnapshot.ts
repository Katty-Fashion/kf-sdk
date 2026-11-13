// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelSnapshot.java. Do not edit by hand: fix Java, then re-derive.
import { Material } from './material.js';

/**
 * A model as the platform sends it: `{id, name, materialList, extensions: {slug: {field: value}}}`.
 * `id` is null in `model.before.create`.
 *
 * `versionExtensions`: a version's values in the model's envelope, `{versionId: {slug: {field: value}}}` read from
 * `versions.{versionId}.extensions` (protocol 1.1, §5.1: a module's write on `model_version` is an update of the
 * version's model and carries only the fields written); empty when the snapshot has no `versions`.
 */
export class ModelSnapshot {
  constructor(
    readonly id: string | null,
    readonly name: string | null,
    readonly materialList: readonly Material[],
    readonly extensions: Readonly<Record<string, Readonly<Record<string, unknown>>>>,
    /** Absent = none (the form before SDK 0.7.4). */
    readonly versionExtensions: Readonly<Record<string, Readonly<Record<string, Readonly<Record<string, unknown>>>>>> = Object.freeze({}),
  ) {}

  /** Value of `extensions.{slug}.{field}`, or null. */
  extension(slug: string, field: string): unknown {
    const fields = own(this.extensions, slug);
    return fields == null ? null : (own(fields, field) ?? null);
  }

  /**
   * Value of `versions.{versionId}.extensions.{slug}.{field}`, or null when the snapshot does not carry that version,
   * module or field (§5.1; SDK 0.7.4).
   */
  versionExtension(versionId: string, slug: string, field: string): unknown {
    const modules = own(this.versionExtensions, versionId);
    const fields = modules == null ? null : own(modules, slug);
    return fields == null ? null : (own(fields, field) ?? null);
  }

  /** @internal */
  static fromMap(raw: unknown): ModelSnapshot | null {
    if (!isObject(raw)) return null;
    const materials: Material[] = [];
    if (Array.isArray(raw.materialList)) {
      for (const o of raw.materialList) if (isObject(o)) materials.push(Material.fromMap(o));
    }
    const versions: Record<string, Readonly<Record<string, Readonly<Record<string, unknown>>>>> = {};
    if (isObject(raw.versions)) {
      for (const [versionId, version] of Object.entries(raw.versions)) {
        if (isObject(version)) {
          const ext = extensionsOf(version.extensions);
          if (Object.keys(ext).length > 0) versions[versionId] = ext;
        }
      }
    }
    return new ModelSnapshot(
      raw.id == null ? null : String(raw.id),
      raw.name == null ? null : String(raw.name),
      Object.freeze(materials),
      extensionsOf(raw.extensions),
      Object.freeze(versions),
    );
  }
}

/** `{slug: {field: value}}`; an entry that is not an object is skipped. */
function extensionsOf(raw: unknown): Readonly<Record<string, Readonly<Record<string, unknown>>>> {
  const ext: Record<string, Readonly<Record<string, unknown>>> = {};
  if (isObject(raw)) {
    for (const [slug, fields] of Object.entries(raw)) {
      if (isObject(fields)) ext[slug] = Object.freeze({ ...fields });
    }
  }
  return Object.freeze(ext);
}

/** An own key only (a Java map never answers `toString` or `__proto__`). */
function own<T>(o: Readonly<Record<string, T>>, key: string): T | undefined {
  return Object.prototype.hasOwnProperty.call(o, key) ? o[key] : undefined;
}

function isObject(o: unknown): o is Record<string, unknown> {
  return o !== null && typeof o === 'object' && !Array.isArray(o);
}
