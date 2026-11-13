<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.SecretCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One secret of the module (protocol 1.1): the manifest names it, never its value. The
 * platform stores the value in its vault under the module's path and gives the module a `vault://` reference.
 *
 * - `name`: `^[a-z][a-z0-9-]{1,40}$`, unique
 * - `scope`: `platform` (one value) or `tenant` (one value per tenant)
 * - `writeRight`: the right of one of the module's routes; a `platform` secret only through a route with
 *   `admin: platform`
 * - `external`: the prefixes (`platform/aas/`) under which the module may keep an EXISTING reference
 *   `vault://<prefix>…` for this secret instead of a value it writes; the platform resolves it and audits every
 *   resolve (SDK 0.7.2); only on a `platform` secret; absent = `[]`
 */
final class SecretCapability
{
    /**
     * @param list<string> $external
     */
    public function __construct(
        public readonly ?string $name,
        public readonly ?string $scope,
        public readonly ?string $writeRight,
        public readonly array $external = [],
    ) {
    }
}
