<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.ProvideCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One read the module serves to the platform (protocol 1.1): the platform calls `path` (a
 * GET route of the module) with the actor `{type: system, via: platform}`.
 *
 * - `port`: a port of the platform's catalog of provided reads (Manifest::PORT_ID), e.g. `factory.stations.v1`
 * - `path`: the path of a GET route this module declares
 */
final class ProvideCapability
{
    public function __construct(
        public readonly ?string $port,
        public readonly ?string $path,
    ) {
    }
}
