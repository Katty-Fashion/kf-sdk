// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantDataExport.java. Do not edit by hand: fix Java, then re-derive.
import { createHash } from 'node:crypto';

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** The options of {@link TenantDataExport.bundle}; Java's overload with an `Instant`. */
export interface TenantDataExportOptions {
  /** When the part was exported (default: now); written as Java's `Instant.toString()` (`2026-10-30T18:00:00Z`). */
  readonly exportedAt?: Date | string | null;
}

/**
 * The tenant-data bundle a module answers on `GET /m/{slug}/admin/export?tenantId=&part=` (protocol 1.3 §15.8): the shape its own `POST /m/{slug}/admin/import?mode=dry-run|apply` takes back
 * unchanged. The platform calls the route (with its own context, `system` via `platform`) at the module's uninstall
 * and before a tenant's hard delete, and keeps the bundles in its bucket.
 *
 *   const bundle = TenantDataExport.bundle('kf-feedback/tenant-data/v1', tenantId, 1, 1,
 *     { tickets: tickets.length }, { tickets });
 *
 * The common keys ({@link TenantDataExport.COMMON_KEYS}): `schema` (`<slug>/tenant-data/v<N>`, the value the manifest
 * declares in `tenantData.format`), `tenantId`, `exportedAt` (ISO-8601), `part` and `parts` (`1 <= part <= parts`),
 * `counts` (the module's tallies, which its import checks) and `contentSha256`. The module's own content sits at the
 * top level beside them, as in the `kf-<slug>/tenant-data/v1` bundles of today; it may also carry `source` and
 * `warnings`, which are informative.
 *
 * `contentSha256` is the SHA-256 (lowercase hex) of the **canonical JSON** of the bundle without
 * {@link TenantDataExport.NOT_HASHED} (`exportedAt`, `source`, `warnings`, `contentSha256`): object keys sorted at
 * every level, compact, UTF-8 -- the form the modules' `TenantDataImport` recompute (a different hash is their
 * `422 bundle-altered`). A number keeps the form Jackson gives it (an integer as digits); a bundle that must hash the
 * same in another language carries its decimal numbers as strings.
 */
export class TenantDataExport {
  /** The module's route the platform calls, under `/m/{slug}` (`admin: platform`, a `GET`). */
  static readonly PATH = '/admin/export';
  /** The keys of every bundle, in the order {@link bundle} writes them (the module's content goes before counts). */
  static readonly COMMON_KEYS: readonly string[] = Object.freeze(['schema', 'tenantId', 'exportedAt', 'part', 'parts',
    'counts', 'contentSha256']);
  /** The keys left out of `contentSha256`. */
  static readonly NOT_HASHED: readonly string[] = Object.freeze(['exportedAt', 'source', 'warnings', 'contentSha256']);
  /** `<slug>/tenant-data/v<N>`: the module's slug, then the major version of its bundle. */
  static readonly SCHEMA = /^[a-z0-9][a-z0-9-]{1,40}\/tenant-data\/v[1-9][0-9]{0,3}$/;

  private constructor() {}

  /**
   * One part of a module's export for `tenantId`: the common keys, the module's `content` at the top level,
   * `contentSha256` computed last.
   *
   * @throws TypeError a schema outside {@link SCHEMA}, no tenant, no counts, `part` outside `1..parts`, or content that
   *   carries a common key
   */
  static bundle(schema: string, tenantId: string, part: number, parts: number,
    counts: Record<string, unknown> | null, content: Record<string, unknown> | null,
    options?: TenantDataExportOptions | null): Record<string, unknown> {
    const exportedAt = instant(options?.exportedAt ?? new Date());
    const errors: string[] = [];
    if (schema == null || !TenantDataExport.SCHEMA.test(schema)) {
      errors.push(`schema must be <slug>/tenant-data/v<N> (got ${schema})`);
    }
    if (tenantId == null || !UUID.test(String(tenantId))) errors.push('tenantId is required');
    if (exportedAt == null) errors.push('exportedAt is required');
    if (!Number.isInteger(part) || !Number.isInteger(parts) || parts < 1 || part < 1 || part > parts) {
      errors.push(`part and parts must satisfy 1 <= part <= parts (got ${part}/${parts})`);
    }
    if (counts == null) errors.push("counts is required (the tallies the module's import checks)");
    const own = content ?? {};
    for (const key of Object.keys(own)) {
      if (TenantDataExport.COMMON_KEYS.includes(key)) errors.push(`the content cannot carry the common key ${key}`);
    }
    if (errors.length > 0) throw new TypeError(`invalid tenant-data bundle: ${errors.join('; ')}`);
    const bundle: Record<string, unknown> = {
      schema,
      tenantId: String(tenantId).toLowerCase(),
      exportedAt,
      part,
      parts,
    };
    for (const [key, value] of Object.entries(own)) bundle[key] = value;
    bundle.counts = counts;
    bundle.contentSha256 = TenantDataExport.contentSha256(bundle);
    return bundle;
  }

  /** The `contentSha256` of `bundle`: its canonical JSON without {@link NOT_HASHED}, SHA-256, hex. */
  static contentSha256(bundle: Record<string, unknown>): string {
    const content: Record<string, unknown> = { ...bundle };
    for (const key of TenantDataExport.NOT_HASHED) delete content[key];
    return createHash('sha256').update(TenantDataExport.canonicalJson(content), 'utf8').digest('hex');
  }

  /** The canonical JSON of `value`: object keys sorted at every level, compact, arrays in order. */
  static canonicalJson(value: unknown): string {
    return JSON.stringify(sorted(value === undefined ? null : value));
  }
}

function sorted(node: unknown): unknown {
  if (node == null) return null;
  if (Array.isArray(node)) return node.map((item) => sorted(item === undefined ? null : item));
  if (typeof node === 'object' && !(node instanceof Date)) {
    const out: Record<string, unknown> = {};
    for (const name of Object.keys(node).sort()) {
      const v = (node as Record<string, unknown>)[name];
      if (v !== undefined) out[name] = sorted(v);
    }
    return out;
  }
  return node;
}

/** Java's `Instant.toString()`: ISO-8601 in UTC, without a zero millisecond part. */
function instant(at: Date | string | null): string | null {
  if (at == null) return null;
  if (typeof at === 'string') return at;
  if (Number.isNaN(at.getTime())) return null;
  return at.toISOString().replace('.000Z', 'Z');
}
