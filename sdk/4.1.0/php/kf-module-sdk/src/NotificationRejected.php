<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Notifications.java (Notifications.Rejected). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/** A `permanent` refusal of the platform, with the protocol's `reason` (null when the body has none). Java: `Notifications.Rejected`. */
final class NotificationRejected extends KfException
{
    public function __construct(PlatformProblem $problem, private readonly ?string $reason)
    {
        parent::__construct($problem);
    }

    /** The protocol's reason: `unknown-key`, `not-a-member`, `not-live`, ...; or null. */
    public function reason(): ?string
    {
        return $this->reason;
    }
}
