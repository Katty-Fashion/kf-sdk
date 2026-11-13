<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.PeerReadCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * A read this module OFFERS to other modules (protocol 2.0, `capabilities.peerReads[]`, sdk-protocol.md §15.9).
 *
 * - `port`: `m.{self}.{name}.v{N}`
 * - `path`: a `GET` route this manifest declares
 * - `visibility`: `internal` (the internal class only) or `public`
 */
final class PeerReadCapability
{
    public function __construct(
        public readonly ?string $port,
        public readonly ?string $path,
        public readonly ?string $visibility,
    ) {
    }
}
