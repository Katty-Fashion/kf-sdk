package ro.kf.sdk.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.PlatformRefusal;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.StorageClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakePlatform} serves the storage of protocol 1.2 (§15.7) with the platform's rules
 * ({@code ModuleStorageService}): the module asks for an upload URL, PUTs the bytes on it (an endpoint of the fake, no
 * S3), asks for a download URL and GETs the same bytes back. The key is the fake's, {@code kf-modules/{slug}/{tenant of
 * the context}/{uuid}.{ext}}; a type or a size the manifest does not declare is {@code 400}, a module without
 * {@code capabilities.storage} {@code 403 capability-not-declared}, a key of another tenant or with {@code ..}
 * {@code 403} (except for a context issued on an {@code admin: platform} route), an object that does not exist
 * {@code 404}.
 */
class FakePlatformStorageTest {

    private static final String SLUG = "qc-mod";
    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String MANIFEST = """
            {"schemaVersion":2,"slug":"qc-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"QC"}},
             "capabilities":{
               "storage":[{"contentTypes":["image/jpeg"],"maxBytes":1024}],
               "routes":[{"method":"POST","path":"/m/qc-mod/reports/{id}/entries","right":"api.v1.m.qc-mod.reports.post","roles":["QC"]},
                         {"method":"GET","path":"/m/qc-mod/admin/photos/{id}","right":"api.v1.m.qc-mod.admin.photos.get","admin":"platform"}]}}""";
    private static final String NO_STORAGE = """
            {"schemaVersion":2,"slug":"qc-mod","name":"n","version":"1.0.0",
             "i18n":{"en":{"n":"QC"}},
             "capabilities":{"routes":[{"method":"GET","path":"/m/qc-mod/x","right":"api.v1.m.qc-mod.x.get","roles":["QC"]}]}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private FakePlatform fake;
    private KfModule kf;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start(SLUG).manifest(MANIFEST);
        kf = fake.module();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    /** The context as the module's route sees it: verified by the module, so its clients forward it. */
    private static RequestContext verified(KfModule module, FakePlatform.SignedContext signed) {
        return module.verifyContext(signed.context(), signed.timestamp(), signed.signature(), signed.proof(), new byte[0]);
    }

    private RequestContext member(KfModule module, String tenant) {
        return verified(module, fake.signedContext(tenant, "sub-" + tenant.substring(35), List.of(), "POST",
                "/reports/7/entries"));
    }

    /** The module without its manifest: its client sends what it is given, so the fake's own checks answer. */
    private KfModule bare() {
        return KfModule.of(fake.url(), SLUG, fake.token(), fake.hookSecret(), null);
    }

    private HttpResponse<String> put(StorageClient.UploadUrl upload, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.url()));
        // Content-Length is set by the client from the body (a restricted header of java.net.http)
        upload.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Content-Length")) request.header(name, value);
        });
        request.method(upload.method(), HttpRequest.BodyPublishers.ofByteArray(bytes));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String uploaded(String tenant, byte[] bytes) throws Exception {
        StorageClient.UploadUrl upload = kf.storage().uploadUrl(member(kf, tenant), "image/jpeg", bytes.length);
        assertEquals(200, put(upload, bytes).statusCode());
        return upload.key();
    }

    @Test
    @DisplayName("tracer: upload-url → PUT 10 bytes on the fake → download-url → GET the same bytes; the key is the fake's")
    void uploadThenDownload_sameBytes() throws Exception {
        byte[] photo = "0123456789".getBytes(StandardCharsets.US_ASCII);
        RequestContext ctx = member(kf, TENANT_A);

        StorageClient.UploadUrl upload = kf.storage().uploadUrl(ctx, "image/jpeg", photo.length);

        assertTrue(upload.key().startsWith("kf-modules/qc-mod/" + TENANT_A + "/"), upload.key());
        assertTrue(upload.key().endsWith(".jpg"), upload.key());
        UUID.fromString(upload.key().substring(("kf-modules/qc-mod/" + TENANT_A + "/").length(), upload.key().length() - 4));
        assertEquals("PUT", upload.method());
        assertEquals(Map.of("Content-Type", "image/jpeg", "Content-Length", "10"), upload.headers());
        assertTrue(upload.url().startsWith(fake.url()), "the PUT goes to the fake, not to S3: " + upload.url());
        assertEquals(200, put(upload, photo).statusCode());

        StorageClient.DownloadUrl download = kf.storage().downloadUrl(ctx, upload.key());
        HttpResponse<byte[]> got = get(download.url());

        assertEquals(200, got.statusCode());
        assertArrayEquals(photo, got.body());
        assertEquals("image/jpeg", got.headers().firstValue("Content-Type").orElse(null));
        List<FakePlatform.Recorded> calls = fake.requests().stream()
                .filter(r -> r.path().startsWith("/api/v1/platform/modules/qc-mod/storage/")).toList();
        assertEquals(List.of("/api/v1/platform/modules/qc-mod/storage/upload-url",
                "/api/v1/platform/modules/qc-mod/storage/download-url"), calls.stream().map(FakePlatform.Recorded::path).toList());
        assertTrue(calls.stream().allMatch(r -> r.context() != null && r.proof() != null), "the three headers of §15");
    }

    @Test
    @DisplayName("a size above maxBytes or a type the manifest does not declare → 400 from the fake (validation, naming the field)")
    void undeclaredTypeOrSize_400() {
        KfModule module = bare();
        RequestContext ctx = member(module, TENANT_A);

        PlatformRefusal tooBig = assertThrows(PlatformRefusal.class, () -> module.storage().uploadUrl(ctx, "image/jpeg", 2048));
        PlatformRefusal png = assertThrows(PlatformRefusal.class, () -> module.storage().uploadUrl(ctx, "image/png", 10));

        assertEquals(PlatformRefusal.Kind.INVALID, tooBig.kind());
        assertTrue(tooBig.errors().containsKey("size"), tooBig.errors().toString());
        assertEquals(PlatformRefusal.Kind.INVALID, png.kind());
        assertTrue(png.errors().containsKey("contentType"), png.errors().toString());
    }

    @Test
    @DisplayName("a module whose manifest has no capabilities.storage → 403 capability-not-declared on both routes")
    void noStorageCapability_403() {
        fake.manifest(NO_STORAGE);
        KfModule module = bare();
        RequestContext ctx = verified(module, fake.signedContext(TENANT_A, "sub-a", List.of(), "GET", "/x"));

        PlatformRefusal up = assertThrows(PlatformRefusal.class, () -> module.storage().uploadUrl(ctx, "image/jpeg", 10));
        PlatformRefusal down = assertThrows(PlatformRefusal.class,
                () -> module.storage().downloadUrl(ctx, "kf-modules/qc-mod/" + TENANT_A + "/x.jpg"));

        assertEquals(403, up.problem().status());
        assertEquals("capability-not-declared", up.reason());
        assertEquals("capability-not-declared", down.reason());
    }

    @Test
    @DisplayName("a key of another tenant → 403; the same key from a context issued on an admin: platform route → 200, same bytes")
    void otherTenantKey_403_exceptPlatformAdminRoute() throws Exception {
        byte[] photo = "tenant-b".getBytes(StandardCharsets.US_ASCII);
        String keyOfB = uploaded(TENANT_B, photo);

        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.storage().downloadUrl(member(kf, TENANT_A), keyOfB));
        RequestContext triage = verified(kf, fake.signedContext(TENANT_A, "sub-admin", List.of(), "GET", "/admin/photos/7"));
        StorageClient.DownloadUrl allowed = kf.storage().downloadUrl(triage, keyOfB);

        assertEquals(PlatformRefusal.Kind.FORBIDDEN, refused.kind());
        assertEquals("forbidden", refused.reason());
        assertArrayEquals(photo, get(allowed.url()).body());
    }

    @Test
    @DisplayName("an admin: platform context reads only under kf-modules/{slug}/: another module's key → 403")
    void platformAdminRoute_onlyOwnModule() {
        RequestContext triage = verified(kf, fake.signedContext(TENANT_A, "sub-admin", List.of(), "GET", "/admin/photos/7"));

        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.storage().downloadUrl(triage, "kf-modules/other-mod/" + TENANT_A + "/x.jpg"));

        assertEquals(403, refused.problem().status());
    }

    @Test
    @DisplayName("a key that does not exist → 404, also for an upload URL issued but never PUT")
    void missingObject_404() {
        RequestContext ctx = member(kf, TENANT_A);
        StorageClient.UploadUrl neverPut = kf.storage().uploadUrl(ctx, "image/jpeg", 10);

        PlatformRefusal unknown = assertThrows(PlatformRefusal.class,
                () -> kf.storage().downloadUrl(ctx, "kf-modules/qc-mod/" + TENANT_A + "/" + UUID.randomUUID() + ".jpg"));
        PlatformRefusal notUploaded = assertThrows(PlatformRefusal.class, () -> kf.storage().downloadUrl(ctx, neverPut.key()));

        assertEquals(PlatformRefusal.Kind.NOT_FOUND, unknown.kind());
        assertEquals(PlatformRefusal.Kind.NOT_FOUND, notUploaded.kind());
    }

    @Test
    @DisplayName("a key with .. (out of the tenant's prefix) → 403, even if it would land on an existing object")
    void dotDotKey_403() throws Exception {
        String keyOfB = uploaded(TENANT_B, "b".getBytes(StandardCharsets.US_ASCII));
        String traversal = "kf-modules/qc-mod/" + TENANT_A + "/../" + TENANT_B + "/" + keyOfB.substring(keyOfB.lastIndexOf('/') + 1);

        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.storage().downloadUrl(member(kf, TENANT_A), traversal));

        assertEquals(403, refused.problem().status());
    }

    @Test
    @DisplayName("the presigned PUT is held to what was signed: another size, another type or an altered URL → 403, nothing stored")
    void presignedPut_heldToTheSignature() throws Exception {
        RequestContext ctx = member(kf, TENANT_A);
        StorageClient.UploadUrl upload = kf.storage().uploadUrl(ctx, "image/jpeg", 10);

        HttpResponse<String> otherSize = put(upload, new byte[11]);
        HttpResponse<String> otherType = http.send(HttpRequest.newBuilder(URI.create(upload.url()))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[10])).build(),
                HttpResponse.BodyHandlers.ofString());
        StorageClient.UploadUrl altered = new StorageClient.UploadUrl(upload.key(),
                upload.url().replace(upload.key(), "kf-modules/qc-mod/" + TENANT_B + "/" + UUID.randomUUID() + ".jpg"),
                upload.method(), upload.headers(), upload.expiresAt());
        HttpResponse<String> otherKey = put(altered, new byte[10]);

        assertEquals(403, otherSize.statusCode());
        assertEquals(403, otherType.statusCode());
        assertEquals(403, otherKey.statusCode());
        assertThrows(PlatformRefusal.class, () -> kf.storage().downloadUrl(ctx, upload.key()), "nothing was stored");
    }

    @Test
    @DisplayName("the module not live in the context's tenant → 403 module-not-live on upload-url")
    void notLive_403() {
        fake.notLive(TENANT_A);

        PlatformRefusal refused = assertThrows(PlatformRefusal.class,
                () -> kf.storage().uploadUrl(member(kf, TENANT_A), "image/jpeg", 10));

        assertEquals("module-not-live", refused.reason());
    }
}
