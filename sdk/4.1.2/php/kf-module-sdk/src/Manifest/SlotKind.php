<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SlotKind). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * The kind of a slot (protocol 2.0, sdk-protocol.md §7.12): `A` UI panels, `B` data layers rendered by the owner,
 * `D` events and commands in the page, `R` catalog resources (data only). There is no kind C (foreign code in the
 * owner's WebGL context). The case value is the one of the manifest.
 */
enum SlotKind: string
{
    case A = 'A';
    case B = 'B';
    case D = 'D';
    case R = 'R';

    /**
     * The types a slot of this kind may declare.
     *
     * @return list<string>
     */
    public function types(): array
    {
        return match ($this) {
            self::A => ['extension', 'tab', 'action'],
            self::B => ['layer'],
            self::D => ['events', 'commands'],
            self::R => ['resource'],
        };
    }

    /**
     * The keys only this kind may carry.
     *
     * @return list<string>
     */
    public function ownKeys(): array
    {
        return match ($this) {
            self::A => ['publicMembers', 'actions'],
            self::B => ['layerKinds'],
            self::D => ['events', 'commands'],
            self::R => ['dataSchema'],
        };
    }

    /** The kind of a manifest value; null for a value that is not one (the check reports it). */
    public static function of(mixed $value): ?self
    {
        return is_string($value) ? self::tryFrom($value) : null;
    }
}
