<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.TenantRestored). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

/**
 * `tenant.after.restore` (protocol 1.3 §6.1): the soft-deleted tenant `tenantId` was restored at `time`;
 * the module resumes its work for it. The same `eventId` may come again. A handler that throws becomes HTTP 500 `transient` (retried).
 */
final class TenantRestored
{
    /** The `eventId` (`kfeventid`), the idempotency key of the event. */
    public readonly ?string $eventId;
    /** CloudEvents `id`: one per delivery, stable across retries. */
    public readonly ?string $deliveryId;
    /** `time`, ISO-8601: when the tenant was restored. */
    public readonly ?string $time;

    public function __construct(
        public readonly string $tenantId,
        public readonly Envelope $envelope,
    ) {
        $this->eventId = $envelope->eventId;
        $this->deliveryId = $envelope->deliveryId;
        $this->time = $envelope->time;
    }
}
