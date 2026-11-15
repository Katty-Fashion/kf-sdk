<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.HookCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A hook point; `onFailure` `allow` (default) or `block`, only on before points.
 */
final class HookCapability
{
    public function __construct(
        public readonly ?string $point,
        public readonly ?string $onFailure,
    ) {
    }
}
