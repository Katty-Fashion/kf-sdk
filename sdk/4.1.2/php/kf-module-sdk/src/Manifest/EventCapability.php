<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.EventCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/** An event this module EMITS (`capabilities.events[]`, §6.3): the point `m.{self}.after.{name}`. */
final class EventCapability
{
    public function __construct(
        public readonly ?string $name,
        public readonly ?string $visibility,
    ) {
    }
}
