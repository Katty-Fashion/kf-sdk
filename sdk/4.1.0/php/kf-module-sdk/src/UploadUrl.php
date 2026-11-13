<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/StorageClient.java (StorageClient.UploadUrl). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A presigned upload: send the bytes with `method` to `url`, with `headers`, before `expiresAt` (ISO-8601). The body
 * must be exactly `size` bytes. A browser drops `Content-Length` (a forbidden header) and sets it from the body itself.
 */
final class UploadUrl
{
    /** @param array<string, string> $headers */
    public function __construct(
        public readonly string $key,
        public readonly string $url,
        public readonly string $method,
        public readonly array $headers,
        public readonly ?string $expiresAt,
    ) {
    }
}
