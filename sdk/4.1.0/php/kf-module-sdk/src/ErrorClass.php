<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ErrorClass.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The class of a platform error: whether repeating the same request can succeed.
 *
 * The platform writes it as `"class"` in every RFC 9457 body of the module protocol (`/api/v1/platform/**`, `/m/**`).
 * When the body carries none, the SDK derives it from the status with the platform's own table (`forStatus`): `408`,
 * `425`, `429` and every `5xx` are TRANSIENT; every other status is PERMANENT.
 *
 * The SDK retries **only** transient errors (and network errors, which are transient by nature). A permanent error
 * stops the retry and names its cause: a wrong token, a module in the wrong state or a refused
 * registration does not fix itself, and retrying it forever only hides it.
 */
enum ErrorClass: string
{
    case PERMANENT = 'permanent';
    case TRANSIENT = 'transient';

    /** The RFC 9457 extension member that carries the class. */
    public const PROPERTY = 'class';

    /** `permanent` or `transient`, as on the wire. */
    public function wire(): string
    {
        return $this->value;
    }

    /** Null for anything else, including null (the caller decides what an unknown class means). */
    public static function fromWire(mixed $wire): ?self
    {
        return is_string($wire) ? self::tryFrom($wire) : null;
    }

    /** The platform's table (`ProblemClass.forStatus`): 408, 425, 429 and 5xx are transient. */
    public static function forStatus(int $status): self
    {
        return $status === 408 || $status === 425 || $status === 429 || ($status >= 500 && $status <= 599)
            ? self::TRANSIENT : self::PERMANENT;
    }
}
