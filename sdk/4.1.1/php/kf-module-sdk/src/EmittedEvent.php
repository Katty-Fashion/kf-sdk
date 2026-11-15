<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
