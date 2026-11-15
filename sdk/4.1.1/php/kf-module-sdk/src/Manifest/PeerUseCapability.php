<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.PeerUseCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/** What this module CALLS of another one (`capabilities.peers[]`, §15.9); the owner is in `requires`. */
final class PeerUseCapability
{
    /**
     * @param list<string> $reads
     * @param list<string> $commands
     */
    public function __construct(
        public readonly ?string $owner,
        public readonly array $reads = [],
        public readonly array $commands = [],
    ) {
    }
}
