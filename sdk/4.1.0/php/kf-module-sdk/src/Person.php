<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RequestContext.java (RequestContext.Person). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The caller as a person (protocol 1.2): what a module shows of the reporter of a ticket, for
 * instance. Sent only on the routes `audience: member`, from the platform user of the request.
 */
final class Person
{
    public function __construct(
        /** the caller's e-mail, or null */
        public readonly ?string $email,
        /** the caller's display name, or null */
        public readonly ?string $displayName,
    ) {
    }
}
