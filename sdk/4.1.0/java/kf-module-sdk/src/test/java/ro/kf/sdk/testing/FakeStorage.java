package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpExchange;
import ro.kf.sdk.Manifest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The platform's storage for a module's tests (protocol 1.2 §15.7), served by {@link FakePlatform}: the
 * objects in memory, the keys made by the fake as by the platform, {@code kf-modules/{slug}/{tenant}/{uuid}.{ext}}, and
 * the presigned URLs pointing at the fake itself (no S3). Obtained from {@link FakePlatform#storage()}.
 *
 * <p>The rules are {@code ModuleStorageService}'s, in its order (the common checks of §15 — token, capability,
 * context, the module live — are the fake's, before these):
 * <ul>
 *   <li>{@code upload-url}: {@code contentType} required and declared, {@code 0 < size <= maxBytes} (without a manifest
 *       any type and the protocol's ceiling, 10 MiB), else {@code 400 validation} naming the field; the PUT is signed
 *       for {@link #PUT_TTL} with {@code Content-Type} and {@code Content-Length = size}: another type, another length,
 *       an altered URL or an expired one is {@code 403} and nothing is stored;</li>
 *   <li>{@code download-url}: {@code key} required ({@code 400}); under {@code kf-modules/{slug}/{tenant of the
 *       context}/} — or under {@code kf-modules/{slug}/} when the context was issued for an {@code admin: platform}
 *       route —, without {@code ..}, {@code .}, {@code //}, a backslash, a leading slash or a control character, else
 *       {@code 403 forbidden}; the object must exist ({@code 404 not-found}); the GET is signed for
 *       {@link #GET_TTL}.</li>
 * </ul>
 * As on the platform, nothing lists or deletes an object through the protocol; the test sees and changes the objects
 * here ({@link #put}, {@link #putAt}, {@link #object}, {@link #keys}, {@link #remove}, {@link #purgeTenant}).
 */
public final class FakeStorage {

    /** The life of an upload URL (§15.7). */
    public static final Duration PUT_TTL = Duration.ofMinutes(10);

    /** The life of a download URL (§15.7). */
    public static final Duration GET_TTL = Duration.ofMinutes(60);

    /** The protocol's ceiling of {@code maxBytes} (§3.2), the limit when the test gave no manifest. */
    public static final long MAX_BYTES = 10_485_760L;

    /** The fake's path of the presigned URLs. */
    static final String OBJECT_PATH = "/_fake/storage/objects/";

    private static final String ROOT = "kf-modules/";

    /** The extension the platform gives a declared type ({@code ModuleStorageService}); anything else is {@code bin}. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/svg+xml", "svg"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/tiff", "tiff"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/json", "json"),
            Map.entry("application/zip", "zip"),
            Map.entry("application/xml", "xml"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/csv", "csv"),
            Map.entry("text/xml", "xml"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("audio/mpeg", "mp3"));

    /** One stored object: its key, its type and its bytes (a copy). */
    public record StoredObject(String key, String contentType, byte[] bytes) {
        public StoredObject {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private final String slug;
    private final Supplier<String> baseUrl;
    private final byte[] signingKey = new byte[32];
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

    FakeStorage(String slug, Supplier<String> baseUrl) {
        this.slug = slug;
        this.baseUrl = baseUrl;
        new SecureRandom().nextBytes(signingKey);
    }

    // ---- what the test sees and changes ----------------------------------------------------------------------

    /** {@code kf-modules/{slug}/}: every key of the module starts with it. */
    public String prefix() {
        return ROOT + slug + "/";
    }

    /** {@code kf-modules/{slug}/{tenant}/}. */
    public String tenantPrefix(String tenant) {
        return prefix() + tenant + "/";
    }

    /** An object of {@code tenant} as if the module had uploaded it earlier, under a key the fake makes; its key. */
    public String put(String tenant, String contentType, byte[] bytes) {
        String key = tenantPrefix(tenant) + UUID.randomUUID() + "." + extensionOf(contentType);
        objects.put(key, new StoredObject(key, contentType, bytes));
        return key;
    }

    /** An object under {@code key}, which must be under {@link #prefix()} (e.g. a file copied by a migration). */
    public FakeStorage putAt(String key, String contentType, byte[] bytes) {
        if (key == null || !key.startsWith(prefix()) || !isClean(key)) {
            throw new IllegalArgumentException("a key of " + slug + " is under " + prefix() + " (got " + key + ")");
        }
        objects.put(key, new StoredObject(key, contentType, bytes));
        return this;
    }

    /** The object under {@code key}. */
    public Optional<StoredObject> object(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    /** The bytes under {@code key}. */
    public Optional<byte[]> bytes(String key) {
        return object(key).map(StoredObject::bytes);
    }

    /** Every key, sorted. */
    public Set<String> keys() {
        return new TreeSet<>(objects.keySet());
    }

    /** The keys of {@code tenant}, sorted. */
    public Set<String> keys(String tenant) {
        Set<String> keys = new TreeSet<>();
        objects.keySet().stream().filter(k -> k.startsWith(tenantPrefix(tenant))).forEach(keys::add);
        return keys;
    }

    /** The object under {@code key} disappears (as if purged): its {@code download-url} is then {@code 404}. */
    public FakeStorage remove(String key) {
        objects.remove(key);
        return this;
    }

    /**
     * What the platform does when a tenant is deleted (§15.7, {@code ModuleStorageTenantTeardown}): every object
     * under {@code kf-modules/{slug}/{tenant}/} is removed. Returns how many.
     */
    public int purgeTenant(String tenant) {
        int[] removed = {0};
        objects.keySet().removeIf(k -> {
            boolean purge = k.startsWith(tenantPrefix(tenant));
            if (purge) removed[0]++;
            return purge;
        });
        return removed[0];
    }

    // ---- the platform's answers (called by FakePlatform after the common checks) -----------------------------

    /** {@code POST …/storage/upload-url}: {@code declared} null when the test gave no manifest. */
    Map<String, Object> uploadUrl(String tenant, Manifest.StorageCapability declared, Object rawBody) {
        Map<String, Object> body = bodyObject(rawBody);
        String contentType = text(body, "contentType");
        Long size = integer(body, "size");
        Map<String, String> errors = new LinkedHashMap<>();
        if (contentType == null || contentType.isBlank()) {
            errors.put("contentType", "is required");
        } else if (declared != null && !declared.contentTypes().contains(contentType)) {
            errors.put("contentType", "is not one of the declared contentTypes");
        }
        long max = declared == null || declared.maxBytes() == null ? MAX_BYTES : declared.maxBytes();
        if (size == null) {
            errors.put("size", "is required");
        } else if (size <= 0) {
            errors.put("size", "must be positive");
        } else if (size > max) {
            errors.put("size", "exceeds the declared maxBytes (" + max + ")");
        }
        if (!errors.isEmpty()) {
            throw new FakePlatform.Refused(400, "validation", "The upload request was refused.", errors);
        }
        String key = tenantPrefix(tenant) + UUID.randomUUID() + "." + extensionOf(contentType);
        Instant expires = Instant.now().plus(PUT_TTL);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", Long.toString(size));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("key", key);
        answer.put("url", presigned("PUT", key, expires, contentType, size));
        answer.put("method", "PUT");
        answer.put("headers", headers);
        answer.put("expiresAt", expires.toString());
        return answer;
    }

    /** {@code POST …/storage/download-url}: {@code platformAdminRoute} = the context was issued for such a route. */
    Map<String, Object> downloadUrl(String tenant, boolean platformAdminRoute, Object rawBody) {
        String key = text(bodyObject(rawBody), "key");
        if (key == null || key.isBlank()) {
            throw new FakePlatform.Refused(400, "validation", "The key is required.", Map.of("key", "is required"));
        }
        String allowed = platformAdminRoute ? prefix() : tenantPrefix(tenant);
        if (!isClean(key) || !key.startsWith(allowed) || key.length() == allowed.length()) {
            throw new FakePlatform.Refused(403, "forbidden", platformAdminRoute
                    ? "The key is not under the storage of " + slug + "."
                    : "The key is not under the storage of " + slug + " for the tenant of this context.");
        }
        StoredObject stored = objects.get(key);
        if (stored == null || stored.bytes.length == 0) {
            throw new FakePlatform.Refused(404, "not-found", "No object behind this key.");
        }
        Instant expires = Instant.now().plus(GET_TTL);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("url", presigned("GET", key, expires, "", 0));
        answer.put("expiresAt", expires.toString());
        return answer;
    }

    /** A request on a presigned URL ({@link #OBJECT_PATH}): the PUT of an upload, the GET of a download. */
    void serve(HttpExchange exchange, byte[] body) throws IOException {
        String method = exchange.getRequestMethod();
        String key = URLDecoder.decode(exchange.getRequestURI().getRawPath().substring(OBJECT_PATH.length()),
                StandardCharsets.UTF_8);
        Map<String, String> params = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        String signedMethod = params.getOrDefault("X-Fake-Method", "");
        String type = params.getOrDefault("X-Fake-Content-Type", "");
        long size;
        long expires;
        try {
            size = Long.parseLong(params.getOrDefault("X-Fake-Size", "0"));
            expires = Long.parseLong(params.getOrDefault("X-Fake-Expires", "0"));
        } catch (NumberFormatException e) {
            s3Error(exchange, 403, "AccessDenied");
            return;
        }
        String signature = params.getOrDefault("X-Fake-Signature", "");
        if (!MessageDigest.isEqual(signature(signedMethod, key, expires, type, size).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII)) || !signedMethod.equals(method)) {
            s3Error(exchange, 403, "SignatureDoesNotMatch");
            return;
        }
        if (Instant.now().getEpochSecond() > expires) {
            s3Error(exchange, 403, "AccessDenied");
            return;
        }
        if ("PUT".equals(method)) {
            String sentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!type.equals(sentType) || body.length != size) {
                s3Error(exchange, 403, "SignatureDoesNotMatch");
                return;
            }
            objects.put(key, new StoredObject(key, type, body));
            exchange.getResponseHeaders().add("ETag", "\"" + HexFormat.of().formatHex(sha256(body)).substring(0, 32) + "\"");
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        StoredObject stored = objects.get(key);
        if (stored == null) {
            s3Error(exchange, 404, "NoSuchKey");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", stored.contentType());
        exchange.sendResponseHeaders(200, stored.bytes.length == 0 ? -1 : stored.bytes.length);
        if (stored.bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(stored.bytes);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** The extension the platform gives an object of {@code contentType}. */
    static String extensionOf(String contentType) {
        return EXTENSIONS.getOrDefault(contentType, "bin");
    }

    /** No traversal, no empty segment, no backslash, no leading slash, no control character. */
    static boolean isClean(String key) {
        if (key.startsWith("/") || key.contains("//") || key.contains("\\")) {
            return false;
        }
        for (String segment : key.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return key.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    private String presigned(String method, String key, Instant expires, String contentType, long size) {
        long at = expires.getEpochSecond();
        return baseUrl.get() + OBJECT_PATH + key
                + "?X-Fake-Method=" + method
                + "&X-Fake-Expires=" + at
                + "&X-Fake-Content-Type=" + URLEncoder.encode(contentType, StandardCharsets.UTF_8)
                + "&X-Fake-Size=" + size
                + "&X-Fake-Signature=" + signature(method, key, at, contentType, size);
    }

    private String signature(String method, String key, long expires, String contentType, long size) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String signed = method + "\n" + key + "\n" + expires + "\n" + contentType + "\n" + size;
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void s3Error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] bytes = ("<Error><Code>" + code + "</Code></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyObject(Object body) {
        if (body == null) return Map.of();
        if (!(body instanceof Map<?, ?> map)) {
            throw new FakePlatform.Refused(400, "validation", "The body is not a JSON object.",
                    Map.of("body", "must be a JSON object"));
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (!(value instanceof String s)) {
            throw new FakePlatform.Refused(400, "validation", "The " + field + " must be a text.",
                    Map.of(field, "must be a text"));
        }
        return s;
    }

    private static Long integer(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new FakePlatform.Refused(400, "validation", "The " + field + " must be an integer.",
                Map.of(field, "must be an integer"));
    }
}
