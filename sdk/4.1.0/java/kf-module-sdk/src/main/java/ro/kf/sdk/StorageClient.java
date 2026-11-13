package ro.kf.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The module's files through the platform: capability {@code storage} in {@code kf-module-conf.json}, protocol 1.2
 * §3.5. Obtained from {@link KfModule#storage()}.
 *
 * <pre>{@code
 * StorageClient.UploadUrl up = kf.storage().uploadUrl(ctx, "image/png", bytes.length);
 * // PUT the bytes to up.url() with up.headers() (Content-Type, Content-Length) before up.expiresAt(), keep up.key()
 * String url = kf.storage().downloadUrl(ctx, up.key()).url();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #uploadUrl}: {@code POST …/storage/upload-url} {@code {contentType, size}} → a presigned {@code PUT}
 *       (10 minutes) under a key the platform chooses, {@code kf-modules/{slug}/{tenant}/{uuid}.{ext}};</li>
 *   <li>{@link #downloadUrl}: {@code POST …/storage/download-url} {@code {key}} → a presigned {@code GET}
 *       (60 minutes).</li>
 * </ul>
 *
 * <p>Both calls carry the module token, the forwarded context and its proof (§15, like {@link SecretsClient}). Refused
 * locally, {@link ErrorClass#PERMANENT}, nothing sent: a missing content type, a {@code size} ≤ 0, an empty key, the
 * context rules of {@link ReadsClient}, and, when the manifest is known, a content type its
 * {@code capabilities.storage} does not declare (or no {@code storage} at all) and a {@code size} above its
 * {@code maxBytes}. The platform's refusals are {@link PlatformRefusal}s ({@code 400} validation, {@code 401},
 * {@code 403 capability-not-declared | module-not-live | forbidden}, {@code 404}); a {@code 5xx} is a
 * {@link KfException} with its class ({@code transient} without one). This client logs nothing: neither the token,
 * nor the context, nor the proof. The module never lists or deletes objects: when a tenant is deleted,
 * the platform purges {@code kf-modules/{slug}/{tenant}/}.
 */
public final class StorageClient {

    private final PlatformChannel channel;

    StorageClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "storage");
    }

    /**
     * A presigned upload: send the bytes with {@code method} to {@code url}, with {@code headers}, before
     * {@code expiresAt} (ISO-8601). The body must be exactly {@code size} bytes. A browser drops
     * {@code Content-Length} (a forbidden header) and sets it from the body itself.
     */
    public record UploadUrl(String key, String url, String method, Map<String, String> headers, String expiresAt) {
        public UploadUrl {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** A presigned download: {@code GET url} before {@code expiresAt} (ISO-8601). */
    public record DownloadUrl(String url, String expiresAt) {
    }

    /**
     * A presigned {@code PUT} for one object of {@code contentType} and exactly {@code size} bytes, in the tenant of
     * {@code context}.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 400}, {@code 401}, {@code 403})
     */
    public UploadUrl uploadUrl(RequestContext context, String contentType, long size) {
        if (contentType == null || contentType.isBlank()) throw PlatformChannel.local("invalid upload: the contentType is required");
        if (size <= 0) throw PlatformChannel.local("invalid upload: size must be greater than 0 (got " + size + ")");
        Manifest manifest = channel.kf().manifest();
        if (manifest != null) {
            Manifest.StorageCapability storage = manifest.capabilities().storage().stream().findFirst().orElse(null);
            if (storage == null) {
                throw PlatformChannel.local("the manifest does not declare capabilities.storage");
            }
            if (!storage.contentTypes().contains(contentType)) {
                throw PlatformChannel.local("content type " + contentType + " is not declared in capabilities.storage"
                        + " of the manifest " + storage.contentTypes());
            }
            if (storage.maxBytes() != null && size > storage.maxBytes()) {
                throw PlatformChannel.local("invalid upload: size " + size + " is above capabilities.storage maxBytes "
                        + storage.maxBytes());
            }
        }
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("contentType", contentType);
        request.put("size", size);
        PlatformChannel.Answer answer = channel.send("POST", forwarded, "/upload-url", "", request);
        if (answer.status() != 200) throw channel.failure(answer);
        Map<String, Object> body = channel.answerObject(answer, "an object {key, url, method, headers, expiresAt}");
        if (!(body.get("key") instanceof String key) || !(body.get("url") instanceof String url)) {
            throw unreadable("the storage answer has no key or no url");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (body.get("headers") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        }
        String method = body.get("method") instanceof String m ? m : "PUT";
        return new UploadUrl(key, url, method, headers, text(body.get("expiresAt")));
    }

    /**
     * A presigned {@code GET} for the object under {@code key}, a key the platform gave this module.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 400}, {@code 401}, {@code 403}, {@code 404})
     */
    public DownloadUrl downloadUrl(RequestContext context, String key) {
        if (key == null || key.isBlank()) throw PlatformChannel.local("invalid download: the key is required");
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        PlatformChannel.Answer answer = channel.send("POST", forwarded, "/download-url", "", Map.of("key", key));
        if (answer.status() != 200) throw channel.failure(answer);
        Map<String, Object> body = channel.answerObject(answer, "an object {url, expiresAt}");
        if (!(body.get("url") instanceof String url)) throw unreadable("the storage answer has no url");
        return new DownloadUrl(url, text(body.get("expiresAt")));
    }

    private static KfException unreadable(String detail) {
        return new KfException(new PlatformProblem(200, null, null, detail, ErrorClass.PERMANENT, null));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
