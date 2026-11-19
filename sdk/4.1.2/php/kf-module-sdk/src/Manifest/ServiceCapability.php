<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.ServiceCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/** A platform service this module calls (`capabilities.services[]`, §16.6): `order.services.v1`. */
final class ServiceCapability
{
    public function __construct(
        public readonly ?string $port,
    ) {
    }
}
