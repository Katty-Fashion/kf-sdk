<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
