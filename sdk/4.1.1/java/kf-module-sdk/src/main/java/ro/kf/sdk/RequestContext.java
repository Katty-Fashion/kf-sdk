package ro.kf.sdk;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the platform tells the module about one request it forwards to the module's own routes:
 * sent INSTEAD of the user's token, in {@code X-KF-Context}, signed with the module's own key.
 *
 * <p>The JSON keys, in this order: {@code v, tenant, user, actor, locale, rights, method, path, query, ts, requestId}
 * ({@code actor} right after {@code user}), and from protocol 1.2 {@code person} last, only on the routes
 * {@code audience: member}. {@code v} stays {@code 1}: a key added to the context is not a change.
 *
 * @param v         the context version ({@code 1})
 * @param tenant    the caller's active tenant (UUID text); the module filters its data on it
 * @param user      the caller's OIDC subject (opaque, the same identifier as {@code kfactor} on events);
 *                  {@code null} when the platform calls as the system ({@link Actor.Type#SYSTEM})
 * @param actor     who calls: a user, or the system through a hook
 * @param locale    the caller's language, never null
 * @param rights    the caller's codes under {@code api.v1.m.{slug}.} in the active tenant, sorted
 * @param method    the HTTP method, upper case
 * @param path      the decoded path the module receives (without {@code /m/{slug}}), e.g. {@code /events}
 * @param query     the raw query string, or {@code null}
 * @param ts        epoch seconds, equal to {@code X-KF-Timestamp}
 * @param requestId the platform's id of this forward, for correlating the two logs
 * @param person    protocol 1.2: the caller's e-mail and display name at the time of the
 *                  request, sent only on the routes {@code audience: member}; {@code null} when the context carries
 *                  none (every other route, a system context, a platform before 1.2). Never a ground for authorization:
 *                  the identity stays {@code user}
 */
public record RequestContext(int v, String tenant, String user, Actor actor, String locale, List<String> rights,
                             String method, String path, String query, long ts, String requestId, Person person) {

    /** A context without {@code person} (the shape before SDK 0.8.0). */
    public RequestContext(int v, String tenant, String user, Actor actor, String locale, List<String> rights,
                          String method, String path, String query, long ts, String requestId) {
        this(v, tenant, user, actor, locale, rights, method, path, query, ts, requestId, null);
    }

    /** The context version this SDK reads. */
    public static final int CURRENT_VERSION = 1;

    public RequestContext {
        Objects.requireNonNull(actor, "actor");
        rights = rights == null ? List.of() : List.copyOf(rights);
    }

    /** Whether the caller holds {@code right} in the active tenant. */
    public boolean hasRight(String right) {
        return rights.contains(right);
    }

    /** The request headers the platform sends with the context. */
    public static final String CONTEXT_HEADER = "X-KF-Context";
    public static final String TIMESTAMP_HEADER = "X-KF-Timestamp";
    public static final String SIGNATURE_HEADER = "X-KF-Signature";
    /**
     * The platform's own proof over {@code X-KF-Context} for this module: opaque to the module, which keeps it
     * with the context and sends both back when it calls the platform on behalf of the request ({@link AuditClient}).
     * The module's key cannot produce it, so it is what proves to the PLATFORM that the context (and its tenant) is one
     * the platform issued. Absent from an older platform.
     */
    public static final String PROOF_HEADER = "X-KF-Context-Proof";

    /** The request attribute the Spring adapter stores the verified context under. */
    public static final String ATTRIBUTE = RequestContext.class.getName();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Verifies one forwarded request and returns its context:
     * <ol>
     *   <li>the three headers are present;</li>
     *   <li>{@code X-KF-Signature} = lowercase hex {@code HMAC-SHA256(secret, ts + "." + X-KF-Context + "." + body)},
     *       compared in constant time, with {@code ts} within ±300 s of {@code now} ({@link Signatures});</li>
     *   <li>{@code X-KF-Context} is base64url (no padding) of a JSON object of version {@code 1}, whose {@code ts} equals
     *       {@code X-KF-Timestamp}, with {@code tenant}, {@code locale}, {@code method}, {@code path},
     *       {@code requestId} as text and {@code rights} as a list of text;</li>
     *   <li>{@code actor} is coherent: {@code user} = a user ({@code via}, {@code hookId} null,
     *       {@code user} set); {@code system} = through a hook ({@code user} null, {@code via} = {@code hook},
     *       {@code hookId} set) or, from protocol 1.1, the platform itself calling a route the module
     *       declares in {@code provides} ({@code user} null, {@code via} = {@code platform}, {@code hookId} null,
     *       {@code rights} empty); any other type or combination is refused. A context WITHOUT {@code actor} (an older
     *       platform) reads as {@link Actor#USER}.</li>
     *   <li>{@code person} (protocol 1.2), when present and not {@code null}: an object whose
     *       {@code email} and {@code displayName} are text or {@code null}; never on a system context. Absent reads as
     *       {@code null}.</li>
     * </ol>
     *
     * @throws Rejected 401, {@link ErrorClass#PERMANENT}, naming the reason (never the secret)
     */
    public static RequestContext verify(String contextHeader, String timestampHeader, String signatureHeader, byte[] body,
                                        String secret, long nowSeconds) {
        if (contextHeader == null || contextHeader.isBlank()) throw new Rejected("missing " + CONTEXT_HEADER);
        byte[] payload = body == null ? new byte[0] : body;
        byte[] head = (contextHeader + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] signed = new byte[head.length + payload.length];
        System.arraycopy(head, 0, signed, 0, head.length);
        System.arraycopy(payload, 0, signed, head.length, payload.length);
        String invalid = Signatures.verify(secret, timestampHeader, signatureHeader, signed, nowSeconds);
        if (invalid != null) throw new Rejected(invalid);
        return decode(contextHeader, timestampHeader.trim());
    }

    /**
     * The context of an after delivery (protocol 2.0, sdk-protocol.md §5.5): {@code X-KF-Context} as the
     * platform put it on a delivery whose signature ({@code ts + "." + body}) the module already verified. The header
     * itself is not covered by that signature -- the platform's proof binds it, and the platform checks the proof when
     * the module sends both back -- so it is read with the same rules as {@link #verify}, without a signature and
     * without the match on {@code X-KF-Timestamp}. Not public: {@link HookEvents.DeliveryContext} is the API.
     *
     * @throws Rejected when the header is not a coherent context of version {@code 1}
     */
    static RequestContext decodeDelivered(String contextHeader) {
        if (contextHeader == null || contextHeader.isBlank()) throw new Rejected("missing " + CONTEXT_HEADER);
        return decode(contextHeader.trim(), null);
    }

    /** The checks of {@link #verify} after the signature; {@code timestamp} null = not matched against a header. */
    private static RequestContext decode(String contextHeader, String timestamp) {
        Map<String, Object> json;
        try {
            Object parsed = JSON.readValue(Base64.getUrlDecoder().decode(contextHeader), Object.class);
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("not an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            json = map;
        } catch (RuntimeException e) {
            throw new Rejected(CONTEXT_HEADER + " is not base64url of a JSON object");
        }
        if (!(json.get("v") instanceof Number v) || v.intValue() != CURRENT_VERSION) {
            throw new Rejected("context version " + json.get("v") + " is not supported (supported: " + CURRENT_VERSION + ")");
        }
        long ts = json.get("ts") instanceof Number n ? n.longValue() : Long.MIN_VALUE;
        if (timestamp == null && ts == Long.MIN_VALUE) throw new Rejected("context ts is required");
        if (timestamp != null && !String.valueOf(ts).equals(timestamp)) {
            throw new Rejected("the context ts does not match " + TIMESTAMP_HEADER);
        }
        String tenant = requiredText(json, "tenant");
        String locale = requiredText(json, "locale");
        String method = requiredText(json, "method");
        String path = requiredText(json, "path");
        String requestId = requiredText(json, "requestId");
        if (json.get("user") != null && !(json.get("user") instanceof String)) throw new Rejected("context user must be text");
        String user = (String) json.get("user");
        if (json.get("query") != null && !(json.get("query") instanceof String)) throw new Rejected("context query must be text");
        if (!(json.get("rights") instanceof List<?> rawRights) || rawRights.stream().anyMatch(r -> !(r instanceof String))) {
            throw new Rejected("context rights must be a list of text");
        }
        List<String> rights = rawRights.stream().map(String.class::cast).toList();
        Actor actor = actor(json, user, rights);
        Person person = person(json, actor);
        return new RequestContext(CURRENT_VERSION, tenant, user, actor, locale, rights, method, path,
                (String) json.get("query"), ts, requestId, person);
    }

    /**
     * Protocol 1.2: {@code person}, the last key, only on the routes {@code audience: member}; absent or
     * {@code null} = none. A system context never carries one.
     */
    private static Person person(Map<String, Object> json, Actor actor) {
        Object raw = json.get("person");
        if (raw == null) return null;
        if (actor.type() == Actor.Type.SYSTEM) throw new Rejected("a system context carries no person");
        if (!(raw instanceof Map<?, ?> person)) throw new Rejected("context person must be an object");
        Object email = person.get("email");
        Object displayName = person.get("displayName");
        if (email != null && !(email instanceof String)) throw new Rejected("context person.email must be text or null");
        if (displayName != null && !(displayName instanceof String)) {
            throw new Rejected("context person.displayName must be text or null");
        }
        return new Person((String) email, (String) displayName);
    }

    /**
     * the actor, coherent with {@code user}; absent = a user (an older platform). Protocol 1.1: {@code system} via {@code platform} has no user, no hookId and no rights.
     */
    private static Actor actor(Map<String, Object> json, String user, List<String> rights) {
        if (!json.containsKey("actor")) return Actor.USER;
        if (!(json.get("actor") instanceof Map<?, ?> raw)) throw new Rejected("context actor must be an object");
        Object type = raw.get("type");
        Object via = raw.get("via");
        Object hookId = raw.get("hookId");
        if ("user".equals(type)) {
            if (user == null || user.isBlank()) throw new Rejected("actor user requires a user");
            // Protocol 2.0: a user via a module -- a call between modules, through the platform (§15.9)
            if (via instanceof String v && v.startsWith(Actor.VIA_MODULE_PREFIX)) {
                if (hookId != null) throw new Rejected("actor user via a module has no hookId");
                return new Actor(Actor.Type.USER, viaModule(v), null);
            }
            if (via != null || hookId != null) throw new Rejected("actor user has no via and no hookId");
            return Actor.USER;
        }
        if ("system".equals(type)) {
            if (user != null) throw new Rejected("actor system has no user");
            // Protocol 2.0: a background job's call between modules, as the system via the caller
            if (via instanceof String v && v.startsWith(Actor.VIA_MODULE_PREFIX)) {
                if (hookId != null) throw new Rejected("actor system via a module has no hookId");
                if (!rights.isEmpty()) throw new Rejected("actor system via a module has no rights");
                return new Actor(Actor.Type.SYSTEM, viaModule(v), null);
            }
            if ("platform".equals(via)) {
                if (hookId != null) throw new Rejected("actor system via platform has no hookId");
                if (!rights.isEmpty()) throw new Rejected("actor system via platform has no rights");
                return Actor.PLATFORM;
            }
            if (!"hook".equals(via)) throw new Rejected("actor system requires via = hook or platform");
            if (!(hookId instanceof String id) || id.isBlank()) throw new Rejected("actor system requires a hookId");
            return new Actor(Actor.Type.SYSTEM, "hook", id);
        }
        throw new Rejected("actor type " + (type == null ? "null" : "\"" + type + "\"") + " is not user or system");
    }

    /** {@code module:{slug}} with the slug's grammar, or refused. */
    private static String viaModule(String via) {
        String slug = via.substring(Actor.VIA_MODULE_PREFIX.length());
        if (!Actor.MODULE_SLUG.matcher(slug).matches()) {
            throw new Rejected("actor via " + via + " is not module:{slug} (the slug must match " + Actor.MODULE_SLUG.pattern() + ")");
        }
        return via;
    }

    private static String requiredText(Map<String, Object> json, String key) {
        if (!(json.get(key) instanceof String s) || s.isBlank()) throw new Rejected("context " + key + " is required");
        return s;
    }

    /** A refused context: 401, {@link ErrorClass#PERMANENT}, the reason in {@code detail}. */
    public static final class Rejected extends KfException {

        public Rejected(String reason) {
            super(new PlatformProblem(401, "https://kf.ro/problems/invalid-context", "Invalid context",
                    "the request context was refused: " + reason, ErrorClass.PERMANENT, null));
        }

        /** The RFC 9457 body to answer with ({@code application/problem+json}). */
        public String problemJson() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", problem().type());
            body.put("title", problem().title());
            body.put("status", problem().status());
            body.put("detail", problem().detail());
            body.put(ErrorClass.PROPERTY, problem().errorClass().wire());
            return JSON.writeValueAsString(body);
        }

        /** The answer for an adapter. */
        public HookResult toResult() {
            return new HookResult(401, problemJson(), HookResult.PROBLEM_CONTENT_TYPE);
        }
    }

    /**
     * The caller as a person (protocol 1.2): what a module shows of the reporter of a ticket,
     * for instance. Sent only on the routes {@code audience: member}, from the platform user of the request.
     *
     * @param email       the caller's e-mail, or {@code null}
     * @param displayName the caller's display name, or {@code null}
     */
    public record Person(String email, String displayName) {
    }

    /**
     * Who calls the module's route: {@code {"type": "user" | "system", "via": "hook" | null,
     * "hookId": "<uuid>" | null}}.
     *
     * <ul>
     *   <li>{@link Type#USER}: a user's request; {@code user} is set, {@code via} and {@code hookId} are null.</li>
     *   <li>{@link Type#SYSTEM}: the platform calls a {@code hookable} route from a hook; {@code user} is null,
     *       {@code via} is {@code hook} and {@code hookId} names the hook.</li>
     *   <li>{@link Type#SYSTEM} via {@code platform} (protocol 1.1): the platform calls a route the module
     *       declares in {@code provides}; {@code user} and {@code hookId} are null and {@code rights} is
     *       empty. No user is ever deduced from a system context.</li>
     * </ul>
     * A context without {@code actor} (an older platform) reads as {@link #USER}.
     */
    public record Actor(Type type, String via, String hookId) {

        /** The actor of a context that carries none: a user, no hook. */
        public static final Actor USER = new Actor(Type.USER, null, null);

        /** The platform itself, calling a provided read (protocol 1.1): no user, no hook. */
        public static final Actor PLATFORM = new Actor(Type.SYSTEM, "platform", null);

        /** The prefix of {@code via} on a call between modules (protocol 2.0): {@code module:{slug}}. */
        public static final String VIA_MODULE_PREFIX = "module:";

        /** The grammar of the slug after {@link #VIA_MODULE_PREFIX}. */
        public static final java.util.regex.Pattern MODULE_SLUG = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");

        /** The calling module of a call between modules ({@code via = module:{slug}}), or {@code null}. */
        public String viaModule() {
            return via != null && via.startsWith(VIA_MODULE_PREFIX) ? via.substring(VIA_MODULE_PREFIX.length()) : null;
        }

        public Actor {
            Objects.requireNonNull(type, "type");
        }

        public enum Type {
            USER("user"),
            SYSTEM("system");

            private final String wire;

            Type(String wire) {
                this.wire = wire;
            }

            public String wire() {
                return wire;
            }
        }
    }
}
