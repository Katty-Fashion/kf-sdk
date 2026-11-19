<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.FeatureFlag). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One declared feature flag; `default` is false when omitted.
 */
final class FeatureFlag
{
    public function __construct(
        public readonly ?string $key,
        public readonly ?string $label,
        public readonly ?string $description,
        public readonly bool $defaultValue,
        /**
         * @var list<string> protocol 2.0 (`schemaVersion` 3): the flag is on for a tenant only while
         * each of these modules (declared in `requires`) is live there; never null ([] = the shape before SDK 0.10.0)
         */
        public readonly array $requiresModules = [],
    ) {
    }
}
