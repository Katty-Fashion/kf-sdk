<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestCheck.java (ManifestCheck.Result). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What the check found.
 * - `errors`: blocking findings, nothing is sent
 * - `warnings`: non-blocking findings, logged
 */
final class ManifestCheckResult
{
    /**
     * @param list<string> $errors
     * @param list<string> $warnings
     */
    public function __construct(public readonly array $errors, public readonly array $warnings)
    {
    }

    public function ok(): bool
    {
        return $this->errors === [];
    }
}
