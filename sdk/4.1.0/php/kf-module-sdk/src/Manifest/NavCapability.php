<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.NavCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One menu entry of the module (protocol 1.1): the platform composes it into the menu of
 * `surface` while the module is live there, under the public key `m.{slug}.{key}`.
 *
 * - `surface`: `staff` (the sidebar), `customer` (the portal) or `platform` (`/setup`)
 * - `group`: the key of a group of that menu (the platform's list, checked at upload); the SDK checks only the shape
 *   `^[a-z][a-z0-9-]{0,40}$`
 * - `key`: `^[a-z][a-z0-9-]{0,40}$`, unique per module and surface
 * - `label`: a key of `i18n.en`, resolved by the host in the user's language
 * - `url`: an in-app path: starts with `/`, without `//`, `..`, `%`, a backslash, whitespace, a scheme, a query or a
 *   fragment
 * - `icon`: optional, the name of an icon of the host's registry (PascalCase); null when absent
 * - `right`: optional, the right of one of the module's own routes or a code the platform declares; never on
 *   `customer`; null when absent. Presentation only: the target route keeps its own gate
 * - `order`: 0 to 10000, Manifest::NAV_DEFAULT_ORDER (100) when absent
 * - `flag`: optional, one of the module's `featureFlags`: the entry is shown only while it is on; null when absent
 */
final class NavCapability
{
    public function __construct(
        public readonly ?string $surface,
        public readonly ?string $group,
        public readonly ?string $key,
        public readonly ?string $label,
        public readonly ?string $url,
        public readonly ?string $icon,
        public readonly ?string $right,
        public readonly int $order,
        public readonly ?string $flag,
    ) {
    }
}
