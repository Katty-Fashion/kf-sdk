// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SdkVersion.java. Do not edit by hand: fix Java, then re-derive.
import { SDK_PACKAGE_VERSION } from './sdkVersion.generated.js';

/**
 * What this SDK is and which protocol it speaks.
 *
 * - {@link SDK_VERSION}: the package version from `package.json`, baked into the code at build time (the TS twin of the
 *   version Java filters from its `pom.xml`); the one semver every SDK carries, checked by `sdk-provenance-check`.
 * - {@link PROTOCOL_SCHEMA_VERSION}: the `schemaVersion` of `kf-module-conf.json` this SDK reads and checks locally
 *   (`3` from 0.10.0: protocol 2.0, a strict superset of `2`, which introduced
 *   shaped; `1` and `2` stay readable).
 */
export const SdkVersion = {
  /** The SDK's own semver, e.g. `0.7.0`. */
  SDK_VERSION: load(),

  /** The manifest `schemaVersion` this SDK speaks; a newer manifest is refused locally. */
  PROTOCOL_SCHEMA_VERSION: 3,

  /**
   * The majors of the callback envelope (`kfenvelopeversion`, `envelopeVersion`) this SDK reads: `[1]`
   * (`DomainEventEnvelope.CURRENT_VERSION`, with `kfeventid`). A callback with another major, or
   * without the attribute, is answered `422` without calling a handler.
   */
  SUPPORTED_ENVELOPE_MAJORS: Object.freeze([1]) as readonly number[],
} as const;

function load(): string {
  // Baked in by the build (scripts/gen-version.mjs writes sdkVersion.generated.ts from package.json, as Maven filters
  // sdk.properties from the pom): no file is read at run time, so a module may bundle the SDK.
  const version: unknown = SDK_PACKAGE_VERSION;
  if (typeof version !== 'string' || version.trim() === '') {
    throw new Error('kf-module-sdk was built without a version (scripts/gen-version.mjs)');
  }
  return version.trim();
}
