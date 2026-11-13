<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowEvents.java (FlowEvents.FlowEvent). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\FlowEvents;

use Kf\Sdk\HookEvents\DeliveryContext;
use Kf\Sdk\HookEvents\Envelope;

/** One event of the flow engine, as delivered: `data` as an associative array (unknown keys kept). */
final class FlowEvent
{
    /** @param array<string, mixed> $data */
    public function __construct(
        public readonly string $type,
        public readonly string $orderId,
        public readonly string $instanceId,
        public readonly ?string $flowKey,
        public readonly ?int $flowVersion,
        public readonly ?string $stepKey,
        public readonly array $data,
        public readonly Envelope $envelope,
    ) {
    }

    /** The `eventId` (`kfeventid`): the idempotency key of the event. */
    public function eventId(): ?string
    {
        return $this->envelope->eventId;
    }

    /** CloudEvents `id`: one per delivery, stable across retries. */
    public function deliveryId(): ?string
    {
        return $this->envelope->deliveryId;
    }

    /** The order's tenant (`kftenant`). */
    public function tenant(): ?string
    {
        return $this->envelope->tenant;
    }

    public function time(): ?string
    {
        return $this->envelope->time;
    }

    /** The context and proof of the delivery, for a background job, e.g. OrderServices. */
    public function deliveryContext(): ?DeliveryContext
    {
        return $this->envelope->deliveryContext();
    }
}
