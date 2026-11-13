<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
