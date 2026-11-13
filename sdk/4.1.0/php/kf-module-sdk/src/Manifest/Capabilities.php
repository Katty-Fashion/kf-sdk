<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.Capabilities). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * The `capabilities` block; every list is never null.
 */
final class Capabilities
{
    public function __construct(
        public readonly array $fields,
        public readonly array $hooks,
        public readonly array $routes,
        public readonly array $surfaces,
        public readonly array $notifications,
        public readonly array $reads,
        public readonly array $audit,
        public readonly array $nav,
        public readonly array $settings,
        public readonly array $secrets,
        public readonly array $provides,
        /** @var list<StorageCapability> protocol 1.2: at most one element */
        public readonly array $storage = [],
        /** @var list<SlotCapability> protocol 2.0: the slots the module defines; [] when absent */
        public readonly array $slots = [],
        /** @var list<PeerReadCapability> protocol 2.0: the reads offered to other modules; [] when absent */
        public readonly array $peerReads = [],
        /** @var list<CommandCapability> protocol 2.0: the commands offered to other modules; [] when absent */
        public readonly array $commands = [],
        /** @var list<EventCapability> protocol 2.0: the events this module emits; [] when absent */
        public readonly array $events = [],
        /** @var list<PeerUseCapability> protocol 2.0: what this module calls of other modules; [] when absent */
        public readonly array $peers = [],
        /** @var list<FlowRuleCapability> protocol 2.0 (§16.4): the rules of the module's steps; [] when absent */
        public readonly array $flowRules = [],
        /** @var list<ServiceCapability> protocol 2.0 (§16.6): the platform services the module calls; [] when absent */
        public readonly array $services = [],
        /** @var list<FlowCapability> protocol 2.1 (§17): the module's baseline flows; [] when absent */
        public readonly array $flows = [],
    ) {
    }
}
