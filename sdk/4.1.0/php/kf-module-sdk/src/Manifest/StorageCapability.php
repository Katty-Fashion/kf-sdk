<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.StorageCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * The module's storage of files through the platform (protocol 1.2; sdk-protocol.md §15.7):
 * the platform presigns an upload under `kf-modules/{slug}/{tenant}/` and a download of a key there, and purges the
 * prefix when the tenant is deleted. At most Manifest::MAX_STORAGE_ELEMENTS element.
 */
final class StorageCapability
{
    public function __construct(
        /** @var list<string> 1 to Manifest::MAX_STORAGE_CONTENT_TYPES distinct `type/subtype`, never a wildcard */
        public readonly array $contentTypes,
        /** the largest object, 1 to Manifest::MAX_STORAGE_BYTES bytes; null when absent or not a whole number */
        public readonly ?int $maxBytes,
    ) {
    }
}
