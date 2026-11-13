<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SlotCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One slot the module defines for other modules (protocol 2.0, `schemaVersion` 3 only, sdk-protocol.md §7.12,
 *). A contributor declares `surfaces[].slug = "m.{owner}.{key}"`.
 */
final class SlotCapability
{
    public function __construct(
        /** the versioned key (Manifest::SLOT_KEY), the major as the last segment */
        public readonly ?string $key,
        /** A, B, D or R; null when absent or unknown (the check reports it) */
        public readonly ?SlotKind $kind,
        /** one of the kind's types (SlotKind::types()) */
        public readonly ?string $type,
        /** `internal` or `public`; null when absent (the check refuses it) */
        public readonly ?SlotVisibility $visibility,
        /** optional, a key of `i18n.en`; null when absent */
        public readonly ?string $label,
        /** @var list<string> A: the `ctx` members shown to the contributor; never null */
        public readonly array $publicMembers = [],
        /** @var list<string> A: the actions given to the contributor; never null */
        public readonly array $actions = [],
        /** @var list<string> B: the layer kinds the owner renders; never null */
        public readonly array $layerKinds = [],
        /** @var list<string> D (`type: events`): the event names; never null */
        public readonly array $events = [],
        /** @var list<string> D (`type: commands`): the command names; never null */
        public readonly array $commands = [],
        /**
         * R: the schema id `m.{owner}.{name}.v{N}` the owner validates the data against; null otherwise. Not `schema`:
         * that key is forbidden in a manifest (§3.3)
         */
        public readonly ?string $dataSchema = null,
    ) {
    }
}
