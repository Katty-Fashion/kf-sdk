<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Decision.java (Decision.Allow). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Decision;

use Kf\Sdk\Decision;

final class Allow extends Decision
{
    public function toJson(): array
    {
        return ['decision' => 'allow'];
    }
}
