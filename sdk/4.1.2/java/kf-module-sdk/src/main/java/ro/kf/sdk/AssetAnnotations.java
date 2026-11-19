package ro.kf.sdk;

import java.util.regex.Pattern;

/**
 * The platform's annotations on a model asset the module removed (protocol 2.3, {@code
 * collaboration.asset-annotations.v1}, sdk-protocol.md §15.10): when the module deletes an asset it owns, it
 * asks the platform to delete the annotations the platform keeps on it. {@code DELETE
 * …/services/collaboration.asset-annotations.v1/{assetId}} → {@code 204}, idempotent (an asset without annotations is
 * not an error). The context is a user's ({@link RequestContext}) or, for a background job, an after delivery's
 * ({@link HookEvents.DeliveryContext}: {@code system via hook}); the platform audits the call
 * ({@code MODULE_SERVICE_CALL}). Delivered by the platform.
 *
 * <pre>{@code
 * kf.assetAnnotations().delete(ctx, "c0ffee00-0000-4000-8000-000000000001");
 * }</pre>
 *
 * The local refusals ({@link KfException}, {@code class: permanent}, nothing sent): the port not declared in
 * {@code capabilities.services} of the manifest, an {@code assetId} outside {@code ^[A-Za-z0-9._:-]{1,128}$}. The
 * platform's refusals are {@link PlatformRefusal}s as on every service of §15.
 */
public final class AssetAnnotations {

    /** The port of protocol 2.3. */
    public static final String PORT = Manifest.ASSET_ANNOTATIONS_PORT;

    /** The module's own id of an asset: safe in a path segment, sent as it is. */
    public static final Pattern ASSET_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final PlatformChannel channel;

    AssetAnnotations(KfModule kf) {
        this.channel = new PlatformChannel(kf, "services");
    }

    /** Deletes the annotations of the asset {@code assetId}: {@code DELETE …/services/…/{assetId}} → {@code 204}. */
    public void delete(RequestContext context, String assetId) {
        String path = check(assetId);
        done(channel.send("DELETE", channel.forwarded(context), path, "", null));
    }

    /** {@link #delete(RequestContext, String)} for a background job, with an after delivery's context. */
    public void delete(HookEvents.DeliveryContext delivery, String assetId) {
        String path = check(assetId);
        done(channel.send("DELETE", channel.forwarded(delivery), path, "", null));
    }

    private String check(String assetId) {
        if (assetId == null || !ASSET_ID.matcher(assetId).matches()) {
            throw PlatformChannel.local("invalid asset annotations call: assetId must match " + ASSET_ID.pattern());
        }
        Manifest manifest = channel.kf().manifest();
        if (manifest != null && manifest.capabilities().services().stream().noneMatch(s -> PORT.equals(s.port()))) {
            throw PlatformChannel.local("the service " + PORT + " is not declared in capabilities.services of the "
                    + "manifest");
        }
        return "/" + PORT + "/" + assetId;
    }

    private void done(PlatformChannel.Answer answer) {
        if (answer.status() >= 200 && answer.status() <= 299) return;
        throw channel.servicesFailure(answer);
    }
}
