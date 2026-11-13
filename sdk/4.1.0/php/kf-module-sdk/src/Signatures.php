<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Signatures.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Callback signatures: `X-KF-Signature = hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + rawBody))`,
 * `X-KF-Timestamp` in epoch seconds, accepted within ±300 s (like KF's SignedUrlVerifier).
 */
final class Signatures
{
    public const MAX_SKEW_SECONDS = 300;

    private function __construct()
    {
    }

    public static function sign(string $secret, string $timestamp, string $body): string
    {
        return hash_hmac('sha256', $timestamp . '.' . $body, $secret);
    }

    /** @return ?string null when valid, otherwise the reason. */
    public static function verify(?string $secret, ?string $timestamp, ?string $signature, string $body, int $nowSeconds): ?string
    {
        if ($secret === null || $secret === '') return 'KF_HOOK_SECRET is not configured';
        if ($timestamp === null || $signature === null) return 'missing X-KF-Timestamp / X-KF-Signature';
        $trimmed = trim($timestamp);
        if (preg_match('/^[+-]?\d{1,18}$/', $trimmed) !== 1) return 'invalid X-KF-Timestamp';
        $ts = (int) $trimmed;
        if (abs($nowSeconds - $ts) > self::MAX_SKEW_SECONDS) return 'timestamp outside the allowed window (±300 s)';
        $expected = self::sign($secret, $trimmed, $body);
        return hash_equals($expected, strtolower(trim($signature))) ? null : 'bad signature';
    }
}
