<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
