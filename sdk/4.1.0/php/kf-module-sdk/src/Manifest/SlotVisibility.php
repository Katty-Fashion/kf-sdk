<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SlotVisibility). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * Who may contribute to a slot (protocol 2.0), chosen by the owner, without a default: `internal` only the modules
 * of the class `KF_RELEASE`, `public` any module that requires the owner. The case value is the one of the manifest
 * (what a JSON view of the typed manifest shows).
 */
enum SlotVisibility: string
{
    case INTERNAL = 'internal';
    case PUBLIC = 'public';

    /** The value in the manifest. */
    public function wire(): string
    {
        return $this->value;
    }

    /** The visibility of a manifest value; null for a value that is not one (the check reports it). */
    public static function of(mixed $value): ?self
    {
        return is_string($value) ? self::tryFrom($value) : null;
    }
}
