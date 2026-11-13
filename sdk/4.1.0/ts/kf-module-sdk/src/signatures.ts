// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Signatures.java. Do not edit by hand: fix Java, then re-derive.
import { createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Callback signatures: `X-KF-Signature = hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + rawBody))`,
 * `X-KF-Timestamp` in epoch seconds, accepted within ±300 s (like KF's SignedUrlVerifier).
 */
export const Signatures = {
  MAX_SKEW_SECONDS: 300,

  sign(secret: string, timestamp: string, body: Uint8Array | string): string {
    return createHmac('sha256', Buffer.from(secret, 'utf8'))
      .update(Buffer.from(`${timestamp}.`, 'utf8'))
      .update(typeof body === 'string' ? Buffer.from(body, 'utf8') : body)
      .digest('hex');
  },

  /** @returns null when valid, otherwise the reason. */
  verify(
    secret: string | null | undefined,
    timestamp: string | null | undefined,
    signature: string | null | undefined,
    body: Uint8Array | string,
    nowSeconds: number,
  ): string | null {
    if (secret == null || secret === '') return 'KF_HOOK_SECRET is not configured';
    if (timestamp == null || signature == null) return 'missing X-KF-Timestamp / X-KF-Signature';
    const trimmed = timestamp.trim();
    if (!/^[+-]?\d+$/.test(trimmed)) return 'invalid X-KF-Timestamp';
    const ts = Number(trimmed);
    if (!Number.isSafeInteger(ts)) return 'invalid X-KF-Timestamp';
    if (Math.abs(nowSeconds - ts) > Signatures.MAX_SKEW_SECONDS) return 'timestamp outside the allowed window (±300 s)';
    const expected = Buffer.from(Signatures.sign(secret, trimmed, body), 'ascii');
    const given = Buffer.from(signature.trim().toLowerCase(), 'ascii');
    return expected.length === given.length && timingSafeEqual(expected, given) ? null : 'bad signature';
  },
} as const;
