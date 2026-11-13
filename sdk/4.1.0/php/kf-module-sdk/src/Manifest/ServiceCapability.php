<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
