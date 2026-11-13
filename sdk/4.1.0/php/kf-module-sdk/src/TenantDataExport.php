<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantDataExport.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The tenant-data bundle a module answers on `GET /m/{slug}/admin/export?tenantId=&part=` (protocol 1.3 §15.8): the shape its own `POST /m/{slug}/admin/import?mode=dry-run|apply` takes back
 * unchanged. The platform calls the route (with its own context, `system` via `platform`) at the module's uninstall
 * and before a tenant's hard delete, and keeps the bundles in its bucket.
 *
 * ```php
 * $bundle = TenantDataExport::bundle('kf-feedback/tenant-data/v1', $tenantId, 1, 1,
 *     ['tickets' => count($tickets)], ['tickets' => $tickets]);
 * ```
 *
 * The common keys (COMMON_KEYS): `schema` (`<slug>/tenant-data/v<N>`, the value the manifest declares in
 * `tenantData.format`), `tenantId`, `exportedAt` (ISO-8601), `part` and `parts` (`1 <= part <= parts`), `counts` (the
 * module's tallies, which its import checks) and `contentSha256`. The module's own content sits at the top level beside
 * them, as in the `kf-<slug>/tenant-data/v1` bundles of today; it may also carry `source` and `warnings`, which are
 * informative.
 *
 * `contentSha256` is the SHA-256 (lowercase hex) of the **canonical JSON** of the bundle without NOT_HASHED
 * (`exportedAt`, `source`, `warnings`, `contentSha256`): object keys sorted at every level (by UTF-16 code unit, as
 * Java sorts them), compact, UTF-8 -- the form the modules' `TenantDataImport` recompute (a different hash is their
 * `422 bundle-altered`). A number keeps the form Jackson gives it (an integer as digits); a bundle that must hash the
 * same in another language carries its decimal numbers as strings. A bundle read back with `Json::decode` keeps `{}`
 * and `[]` apart; in a bundle built from PHP arrays an empty array is a list.
 */
final class TenantDataExport
{
    /** The module's route the platform calls, under `/m/{slug}` (`admin: platform`, a `GET`). */
    public const PATH = '/admin/export';
    /** The keys of every bundle, in the order bundle() writes them (the module's content goes before counts). */
    public const COMMON_KEYS = ['schema', 'tenantId', 'exportedAt', 'part', 'parts', 'counts', 'contentSha256'];
    /** The keys left out of `contentSha256`. */
    public const NOT_HASHED = ['exportedAt', 'source', 'warnings', 'contentSha256'];
    /** `<slug>/tenant-data/v<N>`: the module's slug, then the major version of its bundle. */
    public const SCHEMA = '#^[a-z0-9][a-z0-9-]{1,40}/tenant-data/v[1-9][0-9]{0,3}$#';

    private const UUID = '/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/';

    private function __construct()
    {
    }

    /**
     * One part of a module's export for `$tenantId`: the common keys, the module's `$content` at the top level,
     * `contentSha256` computed last. `$exportedAt` (Java's overload with an `Instant`): default now, written as Java's
     * `Instant.toString()` (`2026-10-30T18:00:00Z`).
     *
     * @param array<string, mixed>|\stdClass|null $counts
     * @param array<string, mixed>|\stdClass|null $content
     * @return array<string, mixed>
     * @throws \InvalidArgumentException a schema outside SCHEMA, no tenant, no counts, `$part` outside `1..$parts`, or
     *                                   content that carries a common key
     */
    public static function bundle(?string $schema, ?string $tenantId, int $part, int $parts, array|\stdClass|null $counts,
        array|\stdClass|null $content, \DateTimeInterface|string|null $exportedAt = null): array
    {
        $at = self::instant($exportedAt ?? new \DateTimeImmutable('now', new \DateTimeZone('UTC')));
        $errors = [];
        if ($schema === null || preg_match(self::SCHEMA, $schema) !== 1) {
            $errors[] = 'schema must be <slug>/tenant-data/v<N> (got ' . ($schema ?? 'null') . ')';
        }
        if ($tenantId === null || preg_match(self::UUID, $tenantId) !== 1) $errors[] = 'tenantId is required';
        if ($parts < 1 || $part < 1 || $part > $parts) {
            $errors[] = "part and parts must satisfy 1 <= part <= parts (got $part/$parts)";
        }
        if ($counts === null) $errors[] = "counts is required (the tallies the module's import checks)";
        $own = $content instanceof \stdClass ? get_object_vars($content) : ($content ?? []);
        foreach (array_keys($own) as $key) {
            if (in_array((string) $key, self::COMMON_KEYS, true)) $errors[] = "the content cannot carry the common key $key";
        }
        if ($errors !== []) {
            throw new \InvalidArgumentException('invalid tenant-data bundle: ' . implode('; ', $errors));
        }
        $bundle = [
            'schema' => $schema,
            'tenantId' => strtolower($tenantId),
            'exportedAt' => $at,
            'part' => $part,
            'parts' => $parts,
        ];
        foreach ($own as $key => $value) $bundle[(string) $key] = $value;
        $bundle['counts'] = $counts;
        $bundle['contentSha256'] = self::contentSha256($bundle);
        return $bundle;
    }

    /**
     * The `contentSha256` of `$bundle`: its canonical JSON without NOT_HASHED, SHA-256, hex.
     *
     * @param array<string, mixed>|\stdClass $bundle
     */
    public static function contentSha256(array|\stdClass $bundle): string
    {
        $content = $bundle instanceof \stdClass ? get_object_vars($bundle) : $bundle;
        foreach (self::NOT_HASHED as $key) unset($content[$key]);
        return hash('sha256', self::canonicalJson(Json::obj($content)));
    }

    /** The canonical JSON of `$value`: object keys sorted at every level, compact, arrays in order. */
    public static function canonicalJson(mixed $value): string
    {
        return Json::encode(self::sorted($value));
    }

    private static function sorted(mixed $node): mixed
    {
        if ($node instanceof \stdClass || (is_array($node) && $node !== [] && !array_is_list($node))) {
            $members = $node instanceof \stdClass ? get_object_vars($node) : $node;
            $names = array_map('strval', array_keys($members));
            usort($names, static fn (string $a, string $b): int => strcmp(self::utf16($a), self::utf16($b)));
            $out = new \stdClass();
            foreach ($names as $name) $out->{$name} = self::sorted($members[$name]);
            return $out;
        }
        if (is_array($node)) return array_map(self::sorted(...), $node);
        return $node;
    }

    /** UTF-16BE bytes of a UTF-8 text: comparing them byte-wise is Java's `String.compareTo`. */
    private static function utf16(string $text): string
    {
        $out = '';
        foreach (preg_split('//u', $text, -1, PREG_SPLIT_NO_EMPTY) ?: [] as $ch) {
            $b = array_values(unpack('C*', $ch));
            $cp = match (count($b)) {
                1 => $b[0],
                2 => (($b[0] & 0x1F) << 6) | ($b[1] & 0x3F),
                3 => (($b[0] & 0x0F) << 12) | (($b[1] & 0x3F) << 6) | ($b[2] & 0x3F),
                default => (($b[0] & 0x07) << 18) | (($b[1] & 0x3F) << 12) | (($b[2] & 0x3F) << 6) | ($b[3] & 0x3F),
            };
            if ($cp >= 0x10000) {
                $cp -= 0x10000;
                $out .= pack('n', 0xD800 | ($cp >> 10)) . pack('n', 0xDC00 | ($cp & 0x3FF));
            } else {
                $out .= pack('n', $cp);
            }
        }
        return $out;
    }

    /** Java's `Instant.toString()`: ISO-8601 in UTC, the fraction only when not zero. */
    private static function instant(\DateTimeInterface|string $at): string
    {
        if (is_string($at)) return $at;
        $utc = \DateTimeImmutable::createFromInterface($at)->setTimezone(new \DateTimeZone('UTC'));
        $micro = (int) $utc->format('u');
        if ($micro === 0) return $utc->format('Y-m-d\TH:i:s') . 'Z';
        $fraction = rtrim(sprintf('%06d', $micro), '0');
        $fraction = str_pad($fraction, strlen($fraction) <= 3 ? 3 : 6, '0');
        return $utc->format('Y-m-d\TH:i:s') . '.' . $fraction . 'Z';
    }
}
