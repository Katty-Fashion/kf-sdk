<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk (System.getLogger("ro.kf.sdk")). Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Where the SDK logs: the PHP twin of the Java SDK's `System.getLogger("ro.kf.sdk")`. Defaults to `error_log` (stderr
 * of `php -S` and of the CLI); `Log::use(fn (string $level, string $line) => ...)` replaces it.
 */
final class Log
{
    /** @var ?\Closure(string, string): void */
    private static ?\Closure $sink = null;

    private function __construct()
    {
    }

    /** @param callable(string $level, string $line): void $sink */
    public static function use(callable $sink): void
    {
        self::$sink = \Closure::fromCallable($sink);
    }

    public static function info(string $line): void
    {
        self::write('INFO', $line);
    }

    public static function warn(string $line): void
    {
        self::write('WARNING', $line);
    }

    public static function error(string $line): void
    {
        self::write('ERROR', $line);
    }

    private static function write(string $level, string $line): void
    {
        if (self::$sink !== null) {
            (self::$sink)($level, $line);
            return;
        }
        error_log($level . ' ' . $line);
    }
}
