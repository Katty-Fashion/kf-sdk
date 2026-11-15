<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
