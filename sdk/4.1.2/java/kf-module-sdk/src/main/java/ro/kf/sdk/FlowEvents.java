package ro.kf.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): {@code workflow.flow-step-entered},
 * {@code workflow.flow-step-completed}, {@code workflow.flow-instance-completed}, {@code workflow.flow-line-promoted}
 * -- events of the engine's outbox (not hook points), delivered after the commit, at least once, signed, with
 * {@code kfeventid}. A module subscribes with {@code capabilities.hooks[{point}]} without {@code onFailure} and
 * {@code kf.flows().on(type, handler)}. Delivered by the platform with {@code FLOW_EVENTS}, from the third
 * source of subscribable points ({@code PublishedPlatformEventCatalog}), not from the hook catalog (§16.3); a module
 * relies on it only when the platform announces the feature. A platform before it refuses the subscription at upload,
 * with the text of the key (§16.7).
 *
 * <p>{@code data} is fixed; until then an object whose minimal named fields are {@code orderId},
 * {@code instanceId} and {@code stepKey} ({@code flowKey}, {@code flowVersion} too; unknown keys kept in
 * {@link FlowEvent#data()}). {@code stepKey} may be absent only on {@link #INSTANCE_COMPLETED}. The module deduplicates
 * on {@link FlowEvent#eventId()}; a flow that comes back to a step delivers {@link #STEP_ENTERED} again under another
 * {@code kfeventid}.
 */
public final class FlowEvents {

    public static final String STEP_ENTERED = "workflow.flow-step-entered";
    public static final String STEP_COMPLETED = "workflow.flow-step-completed";
    public static final String INSTANCE_COMPLETED = "workflow.flow-instance-completed";
    public static final String LINE_PROMOTED = "workflow.flow-line-promoted";

    /** The four types, in the documented order. */
    public static final List<String> TYPES = List.of(STEP_ENTERED, STEP_COMPLETED, INSTANCE_COMPLETED, LINE_PROMOTED);

    /** One event of the flow engine, as delivered. */
    public record FlowEvent(String type, String orderId, String instanceId, String flowKey, Integer flowVersion,
                            String stepKey, Map<String, Object> data, HookEvents.Envelope envelope) {

        /** The {@code eventId} ({@code kfeventid}): the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        /** CloudEvents {@code id}: one per delivery, stable across retries. */
        public String deliveryId() {
            return envelope.deliveryId();
        }

        /** The order's tenant ({@code kftenant}). */
        public String tenant() {
            return envelope.tenant();
        }

        public String time() {
            return envelope.time();
        }

        /** The context and proof of the delivery, for a background job, e.g. {@link OrderServices}. */
        public Optional<HookEvents.DeliveryContext> deliveryContext() {
            return envelope.deliveryContext();
        }
    }

    /** A handler of one type: exceptions become HTTP 500 {@code transient} (retried; the delivery is at least once). */
    @FunctionalInterface
    public interface FlowEventHandler {
        void handle(FlowEvent event) throws Exception;
    }

    /**
     * Reads one delivery of {@code type} ({@link HookEvents.Envelope#event()}).
     *
     * @throws IllegalArgumentException an unknown type, or {@code data} without {@code orderId} / {@code instanceId}
     *                                  (or {@code stepKey} on a type other than {@link #INSTANCE_COMPLETED})
     */
    public static FlowEvent parse(HookEvents.Envelope envelope, Map<String, Object> data) {
        Objects.requireNonNull(envelope, "envelope");
        String type = envelope.event();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("\"" + type + "\" is not an event of the flow engine (known: "
                    + String.join(", ", TYPES) + ")");
        }
        Map<String, Object> d = data == null ? Map.of() : data;
        List<String> missing = new ArrayList<>();
        String orderId = id(d.get("orderId"));
        String instanceId = id(d.get("instanceId"));
        String stepKey = d.get("stepKey") instanceof String s && !s.isBlank() ? s : null;
        if (orderId == null) missing.add("orderId");
        if (instanceId == null) missing.add("instanceId");
        if (stepKey == null && !INSTANCE_COMPLETED.equals(type)) missing.add("stepKey");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(type + " needs data." + String.join(", data.", missing));
        }
        String flowKey = d.get("flowKey") instanceof String s ? s : null;
        Integer flowVersion = d.get("flowVersion") instanceof Number n ? Integer.valueOf(n.intValue()) : null;
        return new FlowEvent(type, orderId, instanceId, flowKey, flowVersion, stepKey,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(d)), envelope);
    }

    /** An id as text: a non-blank string or a whole number; else null. */
    static String id(Object value) {
        if (value instanceof String s && !s.isBlank()) return s;
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof java.math.BigInteger) {
            return value.toString();
        }
        return null;
    }

    // ---- the subscriptions of a module ----------------------------------------------------------------------

    private final Map<String, List<FlowEventHandler>> handlers = new ConcurrentHashMap<>();

    FlowEvents() {
    }

    /**
     * Subscribes {@code handler} to {@code type} (one of {@link #TYPES}), sent with the registration as
     * {@code {"event": type}} (never with {@code onFailure}).
     *
     * @throws IllegalArgumentException a type that is not an event of the flow engine
     */
    public FlowEvents on(String type, FlowEventHandler handler) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("\"" + type + "\" is not an event of the flow engine (known: "
                    + String.join(", ", TYPES) + ")");
        }
        handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** The handlers of {@code type}; empty when none. */
    List<FlowEventHandler> handlers(String type) {
        return handlers.getOrDefault(type, List.of());
    }

    /** The {@code hooks} part of the registration body: one {@code {"event": type}} per subscribed type, in order. */
    List<Map<String, Object>> registration() {
        List<Map<String, Object>> hooks = new ArrayList<>();
        for (String type : TYPES) {
            if (!handlers.containsKey(type)) continue;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("event", type);
            hooks.add(h);
        }
        return hooks;
    }
}
