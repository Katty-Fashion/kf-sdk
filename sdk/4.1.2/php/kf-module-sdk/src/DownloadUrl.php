<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/StorageClient.java (StorageClient.DownloadUrl). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/** A presigned download: `GET url` before `expiresAt` (ISO-8601). */
final class DownloadUrl
{
    public function __construct(
        public readonly string $url,
        public readonly ?string $expiresAt,
    ) {
    }
}
