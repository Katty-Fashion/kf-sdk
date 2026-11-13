// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
