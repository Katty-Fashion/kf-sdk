// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk (System.getLogger("ro.kf.sdk")). Do not edit by hand: fix Java, then re-derive.

/** Where the SDK logs: the TS twin of the Java SDK's `System.getLogger("ro.kf.sdk")`. Defaults to `console`. */
export interface SdkLogger {
  info(message: string): void;
  warn(message: string): void;
  error(message: string, error?: unknown): void;
}

let current: SdkLogger = {
  info: (m) => console.info(m),
  warn: (m) => console.warn(m),
  error: (m, e) => (e === undefined ? console.error(m) : console.error(m, e)),
};

/** Replaces the SDK's logger (every `KfModule` and the static helpers use it). */
export function setSdkLogger(logger: SdkLogger): void {
  current = logger;
}

/** @internal */
export function log(): SdkLogger {
  return current;
}
