<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.Envelope). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

use Kf\Sdk\Json;

/**
 * The CloudEvents attributes of one callback (the envelope on the wire):
 * - `event`: the hook point, `type` without `kf.`, e.g. `model.before.update`
 * - `deliveryId`: CloudEvents `id`, one per delivery (per module and, for a field change, per path), stable across
 *   retries
 * - `eventId`: `kfeventid`, the `eventId` (`outbox_event.event_id`), the same on every copy of one event
 *   -- the idempotency key; null on a delivery queued by an older platform
 * - `time`: ISO-8601; `tenant`: `kftenant`; `locale`: `kflocale` (`en` by default); `actor`: `kfactor`, the OIDC subject
 *   of the user, null for the system; `aggregateType`: `kfaggregatetype`; `subject`: the aggregate's id as text (null on
 *   a before-create); `envelopeVersion`: `kfenvelopeversion`, e.g. "1"; `module`: `kfmodule`
 * - `delivery`: protocol 2.0, the context and proof the platform put on this after delivery; null on a before
 *   hook, from a platform before 2.0, or when the headers were not usable -- read it through deliveryContext()
 */
final class Envelope
{
    public function __construct(
        public readonly string $event,
        public readonly ?string $deliveryId,
        public readonly ?string $eventId,
        public readonly ?string $time,
        public readonly ?string $tenant,
        public readonly ?string $locale,
        public readonly ?string $actor,
        public readonly ?string $aggregateType,
        public readonly ?string $subject,
        public readonly ?string $envelopeVersion,
        public readonly ?string $module,
        public readonly ?DeliveryContext $delivery = null,
    ) {
    }

    /** @internal */
    public static function of(\stdClass $ce, ?DeliveryContext $delivery = null): self
    {
        $t = static fn (string $k): ?string => Json::text(Json::get($ce, $k));
        $type = $t('type');
        $event = $type === null ? '' : (str_starts_with($type, 'kf.') ? substr($type, 3) : $type);
        return new self($event, $t('id'), $t('kfeventid'), $t('time'), $t('kftenant'), $t('kflocale'), $t('kfactor'),
            $t('kfaggregatetype'), $t('subject'), $t('kfenvelopeversion'), $t('kfmodule'), $delivery);
    }

    /**
     * Protocol 2.0 (sdk-protocol.md §5.5): the context and proof of this after delivery, for a background job's
     * call to the platform (PeersClient, EventsClient) within ±300 s of the context's `ts`; null when the platform sent
     * none (a before hook, a platform before 2.0). Java: `Optional<DeliveryContext> deliveryContext()`.
     */
    public function deliveryContext(): ?DeliveryContext
    {
        return $this->delivery;
    }
}
