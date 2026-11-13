<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
