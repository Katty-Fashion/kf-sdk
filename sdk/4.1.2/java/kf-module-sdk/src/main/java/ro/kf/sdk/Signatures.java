package ro.kf.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Callback signatures: {@code X-KF-Signature = hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + rawBody))},
 * {@code X-KF-Timestamp} in epoch seconds, accepted within ±300 s (like KF's SignedUrlVerifier).
 */
public final class Signatures {

    public static final long MAX_SKEW_SECONDS = 300;

    private Signatures() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return null when valid, otherwise the reason. */
    public static String verify(String secret, String timestamp, String signature, byte[] body, long nowSeconds) {
        if (secret == null || secret.isEmpty()) return "KF_HOOK_SECRET is not configured";
        if (timestamp == null || signature == null) return "missing X-KF-Timestamp / X-KF-Signature";
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return "invalid X-KF-Timestamp";
        }
        if (Math.abs(nowSeconds - ts) > MAX_SKEW_SECONDS) return "timestamp outside the allowed window (±300 s)";
        byte[] expected = sign(secret, timestamp.trim(), body).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? null : "bad signature";
    }
}
