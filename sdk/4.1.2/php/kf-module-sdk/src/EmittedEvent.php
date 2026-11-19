<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
