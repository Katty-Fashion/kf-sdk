<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
