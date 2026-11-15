<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
