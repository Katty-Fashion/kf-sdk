<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
