<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.CommandCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/** A command this module OFFERS (`capabilities.commands[]`, §15.9): `method path` a declared route. */
final class CommandCapability
{
    public function __construct(
        public readonly ?string $name,
        public readonly ?string $path,
        public readonly ?string $method,
        public readonly ?string $visibility,
    ) {
    }
}
