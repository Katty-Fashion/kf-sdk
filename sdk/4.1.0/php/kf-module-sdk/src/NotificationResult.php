<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Notifications.java (Notifications.Result). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What the platform did: the rows it wrote (`accepted`, one per recipient and channel the tenant's matrix and the
 * recipient's preferences leave on) and the rows an earlier request with the same idempotency key already wrote
 * (`duplicates`). Java: `Notifications.Result`.
 */
final class NotificationResult
{
    public function __construct(public readonly int $accepted, public readonly int $duplicates)
    {
    }
}
