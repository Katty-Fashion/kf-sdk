<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.AuditCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One access to the platform's audit trail: `write` (rows in the request's tenant, attributed to the
 * module) or `read-own` (only the module's own rows). Declared in protocol 1.0, implemented by the platform (AuditClient).
 */
final class AuditCapability
{
    public function __construct(
        public readonly ?string $access,
    ) {
    }
}
