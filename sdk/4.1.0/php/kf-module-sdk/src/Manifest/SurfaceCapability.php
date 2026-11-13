<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SurfaceCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A UI surface the module contributes to.
 *
 * `page`: protocol 1.1: on a surface of type `page` (the host's catalog), the module page opened
 * at `/m/{slug}/{page}`, `^[a-z][a-z0-9-]{0,40}$`; null when absent.
 *
 * `key`: protocol 2.0 (§16.5): on `orderflow.step`, the key of the step the contribution renders (mandatory there); null
 * when absent.
 */
final class SurfaceCapability
{
    public function __construct(
        public readonly ?string $slug,
        public readonly ?string $kind,
        public readonly ?string $label,
        public readonly ?string $page,
        public readonly ?string $key = null,
    ) {
    }
}
