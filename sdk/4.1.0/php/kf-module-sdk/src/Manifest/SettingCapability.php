<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SettingCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One setting of the module (protocol 1.1): a closed scalar the TENANT_ADMIN edits
 * per tenant in the module's area, read by the module with `GET .../settings`. Settings carry no data: `BOOLEAN`,
 * `NUMBER` or `ENUM` only (no free text, list, reference or secret); data lives on surfaces.
 *
 * - `key`: `^[a-z][a-z0-9-]{1,40}$`, unique
 * - `type`: `BOOLEAN`, `NUMBER` or `ENUM`
 * - `defaultValue`: the JSON `default`: a boolean, a number in `min..max`, or one of `values`
 * - `label`: a key of `i18n.en`
 * - `description`: optional, a key of `i18n.en`; null when absent
 * - `values`: the closed values of an `ENUM`; [] otherwise
 * - `min`: optional lower bound of a `NUMBER`; null when absent
 * - `max`: optional upper bound of a `NUMBER`; null when absent
 */
final class SettingCapability
{
    public function __construct(
        public readonly ?string $key,
        public readonly ?string $type,
        public readonly mixed $defaultValue,
        public readonly ?string $label,
        public readonly ?string $description,
        public readonly array $values,
        public readonly int|float|null $min,
        public readonly int|float|null $max,
    ) {
    }
}
