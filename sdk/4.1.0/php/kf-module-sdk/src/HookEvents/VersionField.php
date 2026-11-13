<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.VersionField). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

/**
 * One path `versions.{versionId}.extensions.{slug}.{field}` of `changed` or of a field change's `field`: a module's
 * write of its own value on a version, delivered as an update of the version's model (protocol 1.1, §5.1). Read the values with `ModelSnapshot::versionExtension($versionId, $slug, $field)` on `before` /
 * `after` (SDK 0.7.4).
 */
final class VersionField
{
    /** The path's first segment. */
    public const VERSIONS = 'versions';

    /** The segment between the version id and the module's slug. */
    public const EXTENSIONS = 'extensions';

    public function __construct(
        public readonly string $versionId,
        public readonly string $slug,
        public readonly string $field,
    ) {
    }

    /**
     * The version field of a path; null when the path is not exactly `versions.{versionId}.extensions.{slug}.{field}`
     * with no empty segment (e.g. `name`, `extensions.{slug}.{field}`).
     */
    public static function parse(?string $path): ?self
    {
        if ($path === null) return null;
        $parts = explode('.', $path);
        if (count($parts) !== 5 || $parts[0] !== self::VERSIONS || $parts[2] !== self::EXTENSIONS) return null;
        if ($parts[1] === '' || $parts[3] === '' || $parts[4] === '') return null;
        return new self($parts[1], $parts[3], $parts[4]);
    }

    /** `versions.{versionId}.extensions.{slug}.{field}` */
    public function path(): string
    {
        return self::VERSIONS . '.' . $this->versionId . '.' . self::EXTENSIONS . '.' . $this->slug . '.' . $this->field;
    }
}
