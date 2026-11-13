package ro.kf.sdk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The module events {@code m.{owner}.after.{name}} (protocol 2.0, sdk-protocol.md §6.3): emitting the
 * module's own, declared in {@code capabilities.events[]}, and subscribing to another module's, declared in
 * {@code capabilities.hooks[{point: "m.{owner}.after.{name}"}]} with the owner in {@code requires}. Obtained from
 * {@link KfModule#events()}.
 *
 * <pre>{@code
 * kf.events().emit(ctx, "level-changed", Map.of("sku", "KF-42", "level", 7));     // the owner, kf-stock
 * kf.events().on("kf-stock", "level-changed", ev -> jobs.submit(ev));               // a subscriber
 * }</pre>
 *
 * <ul>
 *   <li>{@link #emit}: {@code POST …/modules/{self}/events} {@code {"name", "data"}} with the module token and a proved
 *       context (the {@link RequestContext} of a request the module serves, or the {@link HookEvents.DeliveryContext}
 *       of an after delivery for a background job) → {@code 202 {"eventId", "type"}}; the platform records it
 *       in its outbox, in the context's tenant. Refused locally, {@link ErrorClass#PERMANENT}, nothing sent: a name
 *       outside {@code [a-z][a-z0-9-]{1,40}}, {@code data} null or over {@value #MAX_DATA_BYTES} bytes as JSON, an
 *       event the manifest does not declare in {@code capabilities.events} (when the manifest is known), a context not
 *       verified or without proof. The platform's refusals are {@link PlatformRefusal}s: {@code 401 invalid-context},
 *       {@code 403 module-not-live}, {@code 400 validation}, {@code 422 event-not-declared}.</li>
 *   <li>{@link #on}: the handler of one event of another module; registered as the hook {@code m.{owner}.after.{name}}
 *       (no {@code onFailure}). Delivered at least once: the handler is idempotent on {@link HookEvents.ModuleEvent#eventId()}.
 *       A subscription the owner does not offer now is stored inactive by the platform, with a warning.</li>
 * </ul>
 */
public final class EventsClient {

    /** The largest {@code data}, serialized as JSON (64 KiB, §6.3). */
    public static final int MAX_DATA_BYTES = 64 * 1024;
    /** The grammar of an event's name (and of an owner's slug, {@link #on}). */
    public static final Pattern NAME = ManifestCheck.PEER_NAME;
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");

    /** What the platform answered an emission: the event's {@code eventId} and its {@code type}. */
    public record Emitted(String eventId, String type) {
    }

    private final PlatformChannel channel;
    private final Map<String, List<HookEvents.ModuleEventHandler>> handlers = new ConcurrentHashMap<>();

    EventsClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "events");
    }

    /**
     * Emits the module's event {@code name} with {@code data}, in the tenant of {@code context}.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the event
     */
    public Emitted emit(RequestContext context, String name, Map<String, ?> data) {
        Map<String, Object> body = check(name, data);
        return emitted(channel.send("POST", channel.forwarded(context), "", "", body));
    }

    /** {@link #emit(RequestContext, String, Map)} for a background job, with an after delivery's context. */
    public Emitted emit(HookEvents.DeliveryContext delivery, String name, Map<String, ?> data) {
        Map<String, Object> body = check(name, data);
        return emitted(channel.send("POST", channel.forwarded(delivery), "", "", body));
    }

    /**
     * Subscribes {@code handler} to the event {@code name} of the module {@code owner}: the point
     * {@code m.{owner}.after.{name}}, sent with the registration.
     *
     * @throws IllegalArgumentException an owner or a name outside the grammar
     */
    public EventsClient on(String owner, String name, HookEvents.ModuleEventHandler handler) {
        if (owner == null || !SLUG.matcher(owner).matches()) {
            throw new IllegalArgumentException("owner must be a module slug (" + SLUG.pattern() + ")");
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("event name must match " + NAME.pattern());
        }
        handlers.computeIfAbsent(HookEvents.moduleEventPoint(owner, name), p -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** The handlers of a point {@code m.{owner}.after.{name}}; empty when none. */
    List<HookEvents.ModuleEventHandler> handlers(String point) {
        return handlers.getOrDefault(point, List.of());
    }

    /** The {@code hooks} part of the registration body: one {@code {"event": point}} per subscribed point, sorted. */
    List<Map<String, Object>> registration() {
        List<Map<String, Object>> hooks = new ArrayList<>();
        handlers.keySet().stream().sorted().forEach(point -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("event", point);
            hooks.add(h);
        });
        return hooks;
    }

    // ---------------------------------------------------------------------------------------------------------

    private Map<String, Object> check(String name, Map<String, ?> data) {
        List<String> errors = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) errors.add("name must match " + NAME.pattern());
        if (data == null) {
            errors.add("data must be an object");
        } else if (PlatformChannel.JSON.writeValueAsString(data).getBytes(StandardCharsets.UTF_8).length > MAX_DATA_BYTES) {
            errors.add("data is over " + MAX_DATA_BYTES + " bytes as JSON");
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid event: " + String.join("; ", errors));
        Manifest manifest = channel.kf().manifest();
        if (manifest != null && manifest.capabilities().events().stream().noneMatch(e -> name.equals(e.name()))) {
            throw PlatformChannel.local("the event " + name + " is not declared in capabilities.events of the manifest");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("data", data);
        return body;
    }

    private Emitted emitted(PlatformChannel.Answer answer) {
        if (answer.status() < 200 || answer.status() > 299) throw channel.betweenModulesFailure(answer);
        Map<String, Object> body = channel.answerObject(answer, "{eventId, type}");
        if (!(body.get("eventId") instanceof String eventId) || !(body.get("type") instanceof String type)) {
            throw new KfException(new PlatformProblem(answer.status(), null, null,
                    "the events answer is not {eventId, type}", ErrorClass.PERMANENT, null));
        }
        return new Emitted(eventId, type);
    }
}
