<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.ReadCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A read through a port the platform publishes.
 */
final class ReadCapability
{
    public function __construct(
        public readonly ?string $port,
        public readonly ?string $access,
    ) {
    }
}
