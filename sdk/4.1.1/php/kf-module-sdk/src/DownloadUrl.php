<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
