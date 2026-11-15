<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
