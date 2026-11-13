<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.FieldCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A field on an extensible entity (`model`, `order`, `customer`).
 *
 * `editable`: whether the HOST may render the field generically, when no live contribution of the module is mounted on
 * the form; absent = false. A rule of rendering, not of writing: a field that is not editable
 * is still written by the module's own contribution, a hook patch or the module's API.
 */
final class FieldCapability
{
    public function __construct(
        public readonly ?string $entity,
        public readonly ?string $name,
        public readonly ?string $type,
        public readonly ?string $table,
        public readonly ?string $label,
        public readonly bool $editable,
    ) {
    }
}
