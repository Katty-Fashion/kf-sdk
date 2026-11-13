<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.Activation). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * How a module is activated (protocol 1.2; the top-level `activation`): per tenant (`tenant`,
 * the default when absent or null) or on the whole platform (`platform`: live in every tenant, existing and new, while it
 * is installed with an active version, started, not tampered and not deactivated; no per-tenant activation is asked for
 * or possible). The case value is the one of the manifest (what a JSON view of the typed manifest shows).
 */
enum Activation: string
{
    case TENANT = 'tenant';
    case PLATFORM = 'platform';

    /** The value in the manifest. */
    public function wire(): string
    {
        return $this->value;
    }
}
