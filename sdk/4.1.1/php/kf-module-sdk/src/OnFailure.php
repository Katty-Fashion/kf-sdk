<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelHooks.java (ModelHooks.OnFailure). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What the platform does when this module's before hook fails (timeout, error, unreadable answer): `allow` lets the
 * mutation through (the default: a module that is down must not stop every tenant from saving), `block` refuses it.
 */
enum OnFailure: string
{
    case ALLOW = 'allow';
    case BLOCK = 'block';

    public function wire(): string
    {
        return $this->value;
    }
}
