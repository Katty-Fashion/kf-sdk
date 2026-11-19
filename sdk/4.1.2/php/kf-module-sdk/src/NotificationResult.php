<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
