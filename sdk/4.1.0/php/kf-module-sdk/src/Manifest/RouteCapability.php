<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.RouteCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * An API route the module serves under `/m/{slug}/`.
 *
 * `roles`: global role names the right is proposed for; never null, absent = [].
 * `hookable`: whether a platform hook may call it as the system; absent = false.
 * `admin`: protocol 1.1: `tenant` (the platform also binds the right to TENANT_ADMIN in the tenants where
 * the module is active) or `platform` (`roles` empty: only the platform administrator holds it); never on a `hookable`
 * route; null when absent.
 * `timeoutSeconds`: SDK 0.7.3: how long the platform waits for the module's answer on this
 * route, 1 to Manifest::MAX_ROUTE_TIMEOUT_SECONDS seconds; null when absent (the platform's default, 5 s).
 */
final class RouteCapability
{
    public function __construct(
        public readonly ?string $method,
        public readonly ?string $path,
        public readonly ?string $right,
        public readonly array $roles,
        public readonly bool $hookable,
        public readonly ?string $admin,
        public readonly ?int $timeoutSeconds = null,
        /**
         * protocol 1.2: `member` -- any member of the active tenant calls the route, with any
         * role; never with `roles`, `admin` or `hookable`; null when absent (an ordinary route)
         */
        public readonly ?string $audience = null,
        /**
         * protocol 1.2: the route is also served on the customer portal, with the customer's tenant; only on
         * `audience: member`; absent = false
         */
        public readonly bool $portal = false,
    ) {
    }
}
