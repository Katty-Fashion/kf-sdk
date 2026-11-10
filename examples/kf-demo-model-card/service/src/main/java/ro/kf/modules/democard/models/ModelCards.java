package ro.kf.modules.democard.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The card of a model, as the UI receives it on every route: the fields of {@code model.v1} the card shows (with
 * {@code modelOwner}, protocol 1.3.1), the garment image of a version ({@code image}: {@code {url, expiresAt}} or
 * {@code null}, then the UI shows the monogram) and the caller's pin.
 */
public final class ModelCards {

    private ModelCards() {
    }

    public static Map<String, Object> card(Map<String, Object> object, ModelReads.Image image, boolean pinned,
                                           String pinnedAt) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("modelId", object.get("id"));
        card.put("name", object.get("name"));
        card.put("styleNumber", object.get("styleNumber"));
        card.put("modelOwner", object.get("modelOwner"));
        card.put("createdAt", object.get("createdAt"));
        card.put("currentVersionId", object.get("currentVersionId"));
        card.put("versions", object.getOrDefault("versions", List.of()));
        card.put("image", image == null ? null : image.toJson());
        card.put("pinned", pinned);
        if (pinnedAt != null) card.put("pinnedAt", pinnedAt);
        return card;
    }

    /** The model's current version ({@code currentVersionId}), or {@code null}. */
    public static Long currentVersion(Map<String, Object> object) {
        Object id = object.get("currentVersionId");
        return id instanceof Number n ? n.longValue() : null;
    }

    /** Whether {@code versionId} is one of the model's {@code versions[]}. */
    public static boolean hasVersion(Map<String, Object> object, long versionId) {
        if (!(object.get("versions") instanceof List<?> versions)) return false;
        for (Object v : versions) {
            if (v instanceof Map<?, ?> version && version.get("id") instanceof Number n && n.longValue() == versionId) {
                return true;
            }
        }
        return false;
    }
}
