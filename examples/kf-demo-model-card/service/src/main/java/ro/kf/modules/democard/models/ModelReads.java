package ro.kf.modules.democard.models;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.kf.modules.democard.web.Problem;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.ReadPage;
import ro.kf.sdk.ReadsClient;
import ro.kf.sdk.RequestContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The module's reads of the platform, the two ports of {@code capabilities.reads}, always with the context of the
 * request: the platform answers with the visibility and the right ({@code api.v1.models.get}) of the caller in the
 * caller's tenant (sdk-protocol.md §15.1).
 *
 * <ul>
 *   <li>{@code model.v1}: one model, models by {@code ids}, and (protocol 1.3.1) a page of the tenant's models without
 *       {@code ids}, with {@code modelOwner}. A refusal of the platform (403 without the right, 404 for a model the
 *       caller cannot see) is thrown as it is and {@code ProblemAdvice} passes it on.</li>
 *   <li>{@code model-version.garment-image.v1} (protocol 1.3.1): the presigned URL of a version's garment image, valid
 *       10 minutes, made for the user's browser. The image is an extra of the card: when the platform does not give it
 *       (no image, a failure), the card shows the monogram, so a failure here is logged and never fails the card.</li>
 * </ul>
 */
@Component
public class ModelReads {

    static final String MODEL_PORT = "model.v1";
    static final String IMAGE_PORT = "model-version.garment-image.v1";

    private static final Logger LOG = LoggerFactory.getLogger(ModelReads.class);

    /** The garment image of a version: a presigned GET URL and the instant it expires (both null without an image). */
    public record Image(String url, String expiresAt) {

        Map<String, Object> toJson() {
            Map<String, Object> out = new HashMap<>();
            out.put("url", url);
            out.put("expiresAt", expiresAt);
            return out;
        }
    }

    private final KfModule kf;

    public ModelReads(KfModule kf) {
        this.kf = kf;
    }

    /** One model; the module's 404 when the platform answers that the caller cannot see it. */
    public Map<String, Object> model(RequestContext ctx, long modelId) {
        Optional<Map<String, Object>> object = kf.reads().get(ctx, MODEL_PORT, String.valueOf(modelId));
        return object.orElseThrow(() -> Problem.notFound("not-found", "the model is not visible in the tenant"));
    }

    /** A page of the tenant's models, by id (protocol 1.3.1: {@code model.v1} without {@code ids}, staff only). */
    public ReadPage page(RequestContext ctx, int page, int size) {
        return kf.reads().list(ctx, MODEL_PORT, Map.of(), page, size);
    }

    /** The visible models among {@code ids}, by id; the invisible ones are absent. In batches of 200. */
    public Map<Long, Map<String, Object>> models(RequestContext ctx, Collection<Long> ids) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (List<Long> batch : batches(ids)) {
            ReadPage page = kf.reads().list(ctx, MODEL_PORT, Map.of(ReadsClient.IDS, joined(batch)), 0, ReadsClient.MAX_SIZE);
            for (Map<String, Object> item : page.items()) out.put(id(item), item);
        }
        return out;
    }

    /**
     * The garment images of {@code versionIds} (one list read per 200 versions); a version without an image or not
     * visible is absent. Empty when the platform does not answer: the cards show their monograms.
     */
    public Map<Long, Image> images(RequestContext ctx, Collection<Long> versionIds) {
        Map<Long, Image> out = new HashMap<>();
        try {
            for (List<Long> batch : batches(versionIds)) {
                ReadPage page = kf.reads().list(ctx, IMAGE_PORT, Map.of(ReadsClient.IDS, joined(batch)), 0,
                        ReadsClient.MAX_SIZE);
                for (Map<String, Object> item : page.items()) image(item).ifPresent(image -> out.put(id(item), image));
            }
        } catch (KfException e) {
            LOG.warn("the garment images were not read ({} {}): the cards show their monograms",
                    e.problem().status(), e.problem().type());
            return Map.of();
        }
        return out;
    }

    /** The garment image of one version, or empty (no image, not visible, the platform did not answer). */
    public Optional<Image> image(RequestContext ctx, long versionId) {
        try {
            return kf.reads().get(ctx, IMAGE_PORT, String.valueOf(versionId)).flatMap(ModelReads::image);
        } catch (KfException e) {
            LOG.warn("the garment image was not read ({} {}): the card shows its monogram",
                    e.problem().status(), e.problem().type());
            return Optional.empty();
        }
    }

    private static Optional<Image> image(Map<String, Object> item) {
        Object url = item.get("url");
        if (url == null) return Optional.empty();
        Object expiresAt = item.get("expiresAt");
        return Optional.of(new Image(String.valueOf(url), expiresAt == null ? null : String.valueOf(expiresAt)));
    }

    static long id(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }

    private static List<List<Long>> batches(Collection<Long> ids) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<List<Long>> out = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += ReadsClient.MAX_IDS) {
            out.add(distinct.subList(from, Math.min(distinct.size(), from + ReadsClient.MAX_IDS)));
        }
        return out;
    }

    private static String joined(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
