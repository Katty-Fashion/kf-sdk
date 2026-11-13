<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
