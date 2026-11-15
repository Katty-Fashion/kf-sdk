<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
