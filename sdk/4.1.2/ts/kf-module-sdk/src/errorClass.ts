// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/ErrorClass.java. Do not edit by hand: fix Java, then re-derive.

/**
 * The class of a platform error: whether repeating the same request can succeed.
 *
 * The platform writes it as `"class"` in every RFC 9457 body of the module protocol (`/api/v1/platform/**`, `/m/**`).
 * When the body carries none, the SDK derives it from the status with the platform's own table ({@link forStatus}):
 * `408`, `425`, `429` and every `5xx` are `transient`; every other status is `permanent`.
 *
 * The SDK retries **only** transient errors (and network errors, which are transient by nature). A permanent error
 * stops the retry and names its cause: a wrong token, a module in the wrong state or a refused
 * registration does not fix itself, and retrying it forever only hides it.
 */
export type ErrorClass = 'permanent' | 'transient';

export const ErrorClass = {
  PERMANENT: 'permanent' as ErrorClass,
  TRANSIENT: 'transient' as ErrorClass,

  /** The RFC 9457 extension member that carries the class. */
  PROPERTY: 'class',

  /** `null` for anything else, including `null` (the caller decides what an unknown class means). */
  fromWire(wire: unknown): ErrorClass | null {
    return wire === 'permanent' || wire === 'transient' ? wire : null;
  },

  /** The platform's table (`ProblemClass.forStatus`): 408, 425, 429 and 5xx are transient. */
  forStatus(status: number): ErrorClass {
    return status === 408 || status === 425 || status === 429 || (status >= 500 && status <= 599) ? 'transient' : 'permanent';
  },
} as const;
