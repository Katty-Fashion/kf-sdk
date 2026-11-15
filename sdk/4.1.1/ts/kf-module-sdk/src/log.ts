// kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
