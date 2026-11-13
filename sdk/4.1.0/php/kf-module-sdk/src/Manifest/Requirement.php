<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.Requirement). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One declared dependency. `optional` (protocol 2.0, `schemaVersion` 3): an optional dependency
 * blocks nothing in either direction -- absent, stopped or outside its range it counts as absent; a platform before
 * protocol 2.0 reads every dependency as mandatory. Absent or not `true` = mandatory (false = the shape before SDK
 * 0.10.0).
 */
final class Requirement
{
    public function __construct(
        public readonly ?string $slug,
        public readonly ?string $versionRange,
        public readonly bool $optional = false,
    ) {
    }
}
