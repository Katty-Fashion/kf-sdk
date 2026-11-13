<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.AfterEvent). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

use Kf\Sdk\HookEvents;
use Kf\Sdk\Json;
use Kf\Sdk\ModelSnapshot;

/** `model.after.create|update|delete`. `data` is the raw CloudEvent `data` as an associative array. */
final class AfterEvent
{
    public function __construct(
        public readonly string $event,
        public readonly ?string $id,
        public readonly ?ModelSnapshot $before,
        public readonly ?ModelSnapshot $after,
        public readonly array $changed,
        public readonly array $data,
        public readonly Envelope $envelope,
    ) {
    }

    /** @internal */
    public static function of(string $event, Envelope $envelope, \stdClass $data): self
    {
        return new self($event, Json::text(Json::get($data, 'id')), ModelSnapshot::fromMap(Json::get($data, 'before')),
            ModelSnapshot::fromMap(Json::get($data, 'after')), HookEvents::stringList(Json::get($data, 'changed')),
            Json::toArray($data), $envelope);
    }

    /** The `eventId` (`kfeventid`), the idempotency key of the event. */
    public function eventId(): ?string
    {
        return $this->envelope->eventId;
    }

    public function deliveryId(): ?string
    {
        return $this->envelope->deliveryId;
    }

    public function time(): ?string
    {
        return $this->envelope->time;
    }

    public function tenant(): ?string
    {
        return $this->envelope->tenant;
    }

    public function locale(): ?string
    {
        return $this->envelope->locale;
    }

    public function envelopeVersion(): ?string
    {
        return $this->envelope->envelopeVersion;
    }

    /** The context and proof of the delivery, for a background job (protocol 2.0). */
    public function deliveryContext(): ?DeliveryContext
    {
        return $this->envelope->deliveryContext();
    }

    /**
     * The version paths of `changed`, in order: a module's write on `model_version` (§5.1).
     *
     * @return list<VersionField>
     */
    public function changedVersionFields(): array
    {
        return HookEvents::versionFields($this->changed);
    }

    /**
     * The versions those paths touch, distinct, in order of first appearance.
     *
     * @return list<string>
     */
    public function changedVersionIds(): array
    {
        return HookEvents::versionIds($this->changedVersionFields());
    }
}
