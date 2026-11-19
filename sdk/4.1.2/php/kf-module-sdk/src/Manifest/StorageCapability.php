<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.StorageCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * The module's storage of files through the platform (protocol 1.2; sdk-protocol.md §15.7):
 * the platform presigns an upload under `kf-modules/{slug}/{tenant}/` and a download of a key there, and purges the
 * prefix when the tenant is deleted. At most Manifest::MAX_STORAGE_ELEMENTS elements (2.3), a content type in one of them only.
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

    /** The most bytes an object may have: the declared `maxBytes`, never above Manifest::storageCeiling. */
    public function effectiveMaxBytes(): int
    {
        $ceiling = \Kf\Sdk\Manifest::storageCeiling($this->contentTypes);
        return $this->maxBytes === null ? $ceiling : min($this->maxBytes, $ceiling);
    }
}
