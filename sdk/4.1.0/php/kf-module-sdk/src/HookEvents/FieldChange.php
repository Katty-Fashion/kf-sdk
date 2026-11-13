<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.FieldChange). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

use Kf\Sdk\Json;
use Kf\Sdk\ModelSnapshot;

/** `model.field.changed`: one per changed field path. */
final class FieldChange
{
    public function __construct(
        public readonly ?string $id,
        public readonly string $field,
        public readonly mixed $oldValue,
        public readonly mixed $newValue,
        public readonly ?ModelSnapshot $before,
        public readonly ?ModelSnapshot $after,
        public readonly array $data,
        public readonly Envelope $envelope,
    ) {
    }

    /** @internal */
    public static function of(Envelope $envelope, \stdClass $data): self
    {
        return new self(Json::text(Json::get($data, 'id')), Json::text(Json::get($data, 'field')) ?? 'null',
            Json::toArray(Json::get($data, 'oldValue')), Json::toArray(Json::get($data, 'newValue')),
            ModelSnapshot::fromMap(Json::get($data, 'before')), ModelSnapshot::fromMap(Json::get($data, 'after')),
            Json::toArray($data), $envelope);
    }

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

    /** The context and proof of the delivery, for a background job (protocol 2.0). */
    public function deliveryContext(): ?DeliveryContext
    {
        return $this->envelope->deliveryContext();
    }

    /** The version path of `field` (`versions.{versionId}.extensions.{slug}.{field}`), or null. */
    public function versionField(): ?VersionField
    {
        return VersionField::parse($this->field);
    }
}
