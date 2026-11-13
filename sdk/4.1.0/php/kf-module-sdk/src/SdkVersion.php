<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SdkVersion.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What this SDK is and which protocol it speaks.
 *
 * - `sdkVersion()`: the package version from `composer.json` (the PHP twin of the version Java filters from its
 *   `pom.xml`); the one semver every SDK carries, checked by `sdk-provenance-check`.
 * - `PROTOCOL_SCHEMA_VERSION`: the `schemaVersion` of `kf-module-conf.json` this SDK reads and checks locally (`3`
 *   from 0.10.0: protocol 2.0, a strict superset of `2`, which introduced and shaped; `1`
 *   and `2` stay readable).
 */
final class SdkVersion
{
    /** The manifest `schemaVersion` this SDK speaks; a newer manifest is refused locally. */
    public const PROTOCOL_SCHEMA_VERSION = 3;

    /**
     * The majors of the callback envelope (`kfenvelopeversion`, `envelopeVersion`) this SDK reads: `[1]`
     * (`DomainEventEnvelope.CURRENT_VERSION`, with `kfeventid`). A callback with another major, or
     * without the attribute, is answered 422 without calling a handler.
     */
    public const SUPPORTED_ENVELOPE_MAJORS = [1];

    private static ?string $version = null;

    private function __construct()
    {
    }

    /** The SDK's own semver, e.g. `0.7.0`. */
    public static function sdkVersion(): string
    {
        if (self::$version === null) {
            $raw = @file_get_contents(__DIR__ . '/../composer.json');
            $version = $raw === false ? null : (json_decode($raw, true)['version'] ?? null);
            if (!is_string($version) || trim($version) === '') {
                throw new \LogicException('composer.json of kf/module-sdk has no "version"');
            }
            self::$version = trim($version);
        }
        return self::$version;
    }
}
