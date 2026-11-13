<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
