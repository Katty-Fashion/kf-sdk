package ro.kf.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The module's reads of platform objects: capability {@code reads} in {@code kf-module-conf.json}
 * ({@code [{"port": "model.v1"}]}), protocol 1.1 §15.1 (the ports decided on 2026-10-21 in
 * {@code docs/modules/specifications/10-citirile.md}). Obtained from {@link KfModule#reads()}.
 *
 * <pre>{@code
 * ReadPage page = kf.reads().list(ctx, "order.v1", Map.of("modelVersionId", "17"), 0, 50);
 * Optional<Map<String, Object>> version = kf.reads().get(ctx, "model-version.v1", "17", List.of("grading"));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #list}: {@code GET …/reads/{port}?<filters>&page=<n>&size=<n>} → {@link ReadPage}; {@code page} from 0,
 *       {@code size} 1–{@value #MAX_SIZE} (default {@value #DEFAULT_SIZE}); the filters in the order given; the list
 *       filter {@code ids} takes at most {@value #MAX_IDS} values, comma-separated;</li>
 *   <li>{@link #get}: {@code GET …/reads/{port}/{id}[?include=a,b]} → the object, or {@link Optional#empty()} when
 *       the platform answers {@code 404} (not visible in the context's tenant).</li>
 * </ul>
 *
 * <p>Both calls carry the module token, the context and its proof, unchanged ({@link PlatformChannel}); the platform
 * reads in the context's tenant, with the right of the port held by the context's user. Refused locally,
 * {@link ErrorClass#PERMANENT}, nothing sent: a port outside the grammar ({@link Manifest#PORT_ID}), a port the
 * module's manifest does not declare (when the manifest is known), {@code page < 0}, {@code size} outside
 * 1–{@value #MAX_SIZE}, more than {@value #MAX_IDS} values in {@code ids}, a filter or include name outside
 * {@code [a-z][A-Za-z0-9]{0,40}} (or a reserved name), a blank id, a context not verified or without proof, and the two rules of the system context: it reads <em>only objects</em>
 * and <em>only the base fields</em> (no {@code include}). The one exception (protocol 1.3): the platform's own context ({@code system} via {@code platform}) lists the ports of
 * {@link #PLATFORM_LISTS} -- {@code tenant.v1}, without {@code ids}. The platform's refusals are
 * {@link PlatformRefusal}s ({@code 403} with {@code insufficient-rights}, {@code capability-not-declared} or
 * {@code module-not-live}; {@code 401} {@code invalid-context}; {@code 400} on the filters); a {@code 503} is a
 * transient {@link KfException}.
 */
public final class ReadsClient {

    /** The default page size of the protocol. */
    public static final int DEFAULT_SIZE = 20;
    /** The largest page the protocol serves. */
    public static final int MAX_SIZE = 200;
    /** The most values the list filter {@code ids} takes. */
    public static final int MAX_IDS = 200;
    /** The list filter by ids, its values comma-separated. */
    public static final String IDS = "ids";
    /** A filter name or an {@code include} name. */
    public static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,40}");
    /** Query names the client writes itself. */
    public static final List<String> RESERVED = List.of("page", "size", "include");
    /**
     * The ports the platform's own context ({@code system} via {@code platform}) may list (protocol 1.3 §15.1): {@code tenant.v1}, every tenant with {@code deletedAt}. Any other list from a system context is
     * refused locally.
     */
    public static final List<String> PLATFORM_LISTS = List.of("tenant.v1");

    private static final Pattern PORT = Pattern.compile(Manifest.PORT_ID);

    private final PlatformChannel channel;

    ReadsClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "reads");
    }

    /** {@link #list(RequestContext, String, Map, int, int)} on page 0 with the default size. */
    public ReadPage list(RequestContext context, String port, Map<String, String> filters) {
        return list(context, port, filters, 0, DEFAULT_SIZE);
    }

    /**
     * One page of the port's list in the tenant of {@code context}.
     *
     * @param filters the port's filters (name → value, sent in this order); a list filter such as {@code ids} takes
     *                its values comma-separated, {@code ids} at most {@value #MAX_IDS} of them; empty or null for none
     * @throws KfException      a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal  the platform refused the call ({@code 400}, {@code 401}, {@code 403}, {@code 404})
     */
    public ReadPage list(RequestContext context, String port, Map<String, String> filters, int page, int size) {
        List<String> errors = new ArrayList<>();
        checkPort(errors, port);
        if (page < 0) errors.add("page must be at least 0");
        if (size < 1 || size > MAX_SIZE) errors.add("size must be between 1 and " + MAX_SIZE);
        Map<String, String> given = filters == null ? Map.of() : filters;
        for (Map.Entry<String, String> f : given.entrySet()) {
            String name = f.getKey();
            if (name == null || !NAME.matcher(name).matches()) errors.add("filter " + name + " must match [a-z][A-Za-z0-9]{0,40}");
            else if (RESERVED.contains(name)) errors.add("filter " + name + " is reserved");
            else if (f.getValue() == null) errors.add("filter " + name + " has no value");
            else if (IDS.equals(name) && f.getValue().split(",", -1).length > MAX_IDS) {
                errors.add("filter " + IDS + " takes at most " + MAX_IDS + " values");
            }
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid read: " + String.join("; ", errors));
        if (context != null && context.actor().type() == RequestContext.Actor.Type.SYSTEM
                && !(RequestContext.Actor.PLATFORM.equals(context.actor()) && PLATFORM_LISTS.contains(port))) {
            throw PlatformChannel.local("the system context reads only objects (" + port + " list refused)");
        }
        checkDeclared(port);
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> f : given.entrySet()) {
            query.append(query.isEmpty() ? "?" : "&").append(f.getKey()).append('=')
                    .append(PlatformChannel.queryValue(f.getValue()));
        }
        query.append(query.isEmpty() ? "?" : "&").append("page=").append(page).append("&size=").append(size);
        PlatformChannel.Answer answer = channel.send("GET", forwarded, "/" + port, query.toString(), null);
        if (answer.status() != 200) throw channel.failure(answer);

        Map<String, Object> body = channel.answerObject(answer, "a page {items, page, size, total}");
        if (!(body.get("items") instanceof List<?> rawItems) || !(body.get("page") instanceof Number p)
                || !(body.get("size") instanceof Number s) || !(body.get("total") instanceof Number t)) {
            throw new KfException(new PlatformProblem(200, null, null,
                    "the reads answer is not a page {items, page, size, total}", ErrorClass.PERMANENT, null));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : rawItems) {
            if (item instanceof Map<?, ?> map) items.add(copy(map));
        }
        return new ReadPage(items, p.intValue(), s.intValue(), t.longValue());
    }

    /** {@link #get(RequestContext, String, String, List)} without {@code include}: the base fields. */
    public Optional<Map<String, Object>> get(RequestContext context, String port, String id) {
        return get(context, port, id, List.of());
    }

    /**
     * One object of the port in the tenant of {@code context}, with the heavy sub-collections named in
     * {@code include}; {@link Optional#empty()} when the platform answers {@code 404}.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 400}, {@code 401}, {@code 403})
     */
    public Optional<Map<String, Object>> get(RequestContext context, String port, String id, List<String> include) {
        List<String> errors = new ArrayList<>();
        checkPort(errors, port);
        if (id == null || id.isBlank()) errors.add("id is required");
        List<String> names = include == null ? List.of() : include;
        for (String name : names) {
            if (name == null || !NAME.matcher(name).matches()) errors.add("include " + name + " must match [a-z][A-Za-z0-9]{0,40}");
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid read: " + String.join("; ", errors));
        if (context != null && context.actor().type() == RequestContext.Actor.Type.SYSTEM && !names.isEmpty()) {
            throw PlatformChannel.local("the system context reads only the base fields (include " + String.join(",", names)
                    + " refused)");
        }
        checkDeclared(port);
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        String query = names.isEmpty() ? "" : "?include=" + String.join(",", names);
        PlatformChannel.Answer answer = channel.send("GET", forwarded, "/" + port + "/" + PlatformChannel.segment(id), query,
                null);
        if (answer.status() == 404) return Optional.empty();
        if (answer.status() != 200) throw channel.failure(answer);
        return Optional.of(channel.answerObject(answer, "an object"));
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void checkPort(List<String> errors, String port) {
        if (port == null || !PORT.matcher(port).matches()) {
            errors.add("port must match " + Manifest.PORT_ID);
        }
    }

    /** With the manifest known, the port must be one the module declares (the platform would answer 403). */
    private void checkDeclared(String port) {
        Manifest manifest = channel.kf().manifest();
        if (manifest == null) return;
        boolean declared = manifest.capabilities().reads().stream().anyMatch(r -> port.equals(r.port()));
        if (!declared) throw PlatformChannel.local(port + " is not declared in capabilities.reads of the manifest");
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }
}
