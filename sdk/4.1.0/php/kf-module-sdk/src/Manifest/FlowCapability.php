<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.FlowCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A baseline flow this module brings (protocol 2.1, `capabilities.flows[]`, sdk-protocol.md §17): a BPMN
 * file in the pack that the platform compiles at upload and approval and publishes as a global flow (`origin = module`)
 * at install and upgrade, visible to the tenants where the module is active.
 *
 * `key` `^[a-z][a-z0-9-]{2,62}$`, unique; the key of the global definition (the platform does not prefix it, so it
 * SHOULD start with the module's slug); `file` `flows/<name>.bpmn`, directly under `flows/`, at most 262144 bytes;
 * `name` a key of `i18n.en`: the flow's name.
 */
final class FlowCapability
{
    public function __construct(
        public readonly ?string $key,
        public readonly ?string $file,
        public readonly ?string $name,
    ) {
    }
}
