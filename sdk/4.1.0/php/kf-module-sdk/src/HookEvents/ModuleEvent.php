<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.ModuleEvent). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

use Kf\Sdk\HookEvents;

/**
 * A module event `m.{owner}.after.{name}` (protocol 2.0, §6.3), delivered at least once to a module that subscribed with
 * EventsClient::on(): `data` is the owner's object unchanged, as an associative array.
 */
final class ModuleEvent
{
    /** @param array<string, mixed> $data */
    public function __construct(
        public readonly string $owner,
        public readonly string $name,
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

    public function time(): ?string
    {
        return $this->envelope->time;
    }

    /** The emitter's tenant (`kftenant`). */
    public function tenant(): ?string
    {
        return $this->envelope->tenant;
    }

    public function locale(): ?string
    {
        return $this->envelope->locale;
    }

    /** The point, `m.{owner}.after.{name}`. */
    public function point(): string
    {
        return HookEvents::moduleEventPoint($this->owner, $this->name);
    }

    /** The context and proof of the delivery, for a background job. */
    public function deliveryContext(): ?DeliveryContext
    {
        return $this->envelope->deliveryContext();
    }
}
