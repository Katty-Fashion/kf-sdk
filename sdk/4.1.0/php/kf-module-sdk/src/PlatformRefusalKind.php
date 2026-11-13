<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformRefusal.java (PlatformRefusal.Kind). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/** What the platform refused, by status. Java: `PlatformRefusal.Kind`. */
enum PlatformRefusalKind: string
{
    /** `400`: the request is invalid (PlatformRefusal::errors()). */
    case INVALID = 'invalid';
    /** `401`: the token or the context (`invalid-context`). */
    case UNAUTHORIZED = 'unauthorized';
    /** `403`: the capability, the module's state in the tenant, or the user's rights. */
    case FORBIDDEN = 'forbidden';
    /** `404`: nothing visible in the context's tenant. */
    case NOT_FOUND = 'not-found';
    /**
     * `409`, between modules only (protocol 2.0, §15.9): `idempotency-conflict` (permanent) or `idempotency-in-progress`
     * (transient).
     */
    case CONFLICT = 'conflict';
    /**
     * `422`, between modules only (§15.9, §6.3): the owner refused the call (`module-refused`) or the event is not
     * declared by the active version (`event-not-declared`).
     */
    case REFUSED = 'refused';
    /** `502`, between modules only (§15.9): the owner answered a `2xx` that is not JSON. */
    case INVALID_ANSWER = 'invalid-answer';
    /**
     * `503`, between modules only (§15.9): `module-unavailable` -- permanent when the owner is not installed or outside
     * the required range, transient when it is stopped or not activated for the tenant.
     */
    case UNAVAILABLE = 'unavailable';

    /**
     * `invalid`, `unauthorized`, `forbidden`, `not-found`, `conflict`, `refused`, `invalid-answer`, `unavailable`: the name
     * the fixtures use.
     */
    public function wire(): string
    {
        return $this->value;
    }

    /** @internal The kind of a status, or null when the status is not a refusal of this kind. */
    public static function of(int $status): ?self
    {
        return match ($status) {
            400 => self::INVALID,
            401 => self::UNAUTHORIZED,
            403 => self::FORBIDDEN,
            404 => self::NOT_FOUND,
            default => null,
        };
    }

    /**
     * @internal The kind of a status on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3), where the
     * platform answers `409`, `422`, `502` and `503` with a reason too; null otherwise.
     */
    public static function betweenModules(int $status): ?self
    {
        return match ($status) {
            409 => self::CONFLICT,
            422 => self::REFUSED,
            502 => self::INVALID_ANSWER,
            503 => self::UNAVAILABLE,
            default => self::of($status),
        };
    }

    /**
     * The kind of a status on a platform service (protocol 2.0, `order.services.v1`, §16.6): `422 not-delivered` is
     * REFUSED, `501 no-provider` UNAVAILABLE, and `409 order-service-line-owned-by-module` (a delete of a line another
     * module wrote) is CONFLICT; null otherwise.
     */
    public static function services(int $status): ?self
    {
        return match ($status) {
            409 => self::CONFLICT, // order-service-line-owned-by-module
            422 => self::REFUSED,
            501 => self::UNAVAILABLE,
            default => self::of($status),
        };
    }
}
