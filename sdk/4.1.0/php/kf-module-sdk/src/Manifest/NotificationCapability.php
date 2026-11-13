<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.NotificationCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A notification the module may send.
 */
final class NotificationCapability
{
    public function __construct(
        public readonly ?string $key,
        public readonly array $variables,
        public readonly array $channels,
        public readonly ?string $subject,
        public readonly ?string $body,
    ) {
    }
}
