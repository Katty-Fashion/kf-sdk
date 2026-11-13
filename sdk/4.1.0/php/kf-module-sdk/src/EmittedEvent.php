<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/EventsClient.java (EventsClient.Emitted). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/** What the platform answered an emission: the event's `eventId` and its `type`. */
final class EmittedEvent
{
    public function __construct(
        public readonly string $eventId,
        public readonly string $type,
    ) {
    }
}
