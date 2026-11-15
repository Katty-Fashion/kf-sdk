package ro.kf.sdk.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.kf.sdk.FlowEvents;
import ro.kf.sdk.FlowRules;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.OrderServices;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Manifest;
import ro.kf.sdk.RequestContext;
import ro.kf.sdk.Signatures;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * A platform for a module's tests, without the platform: an HTTP server in the test's process that
 * answers the module's calls of protocols 1.1 and 1.2 -- {@code /api/v1/platform/modules/{slug}/{reads, settings,
 * secrets, extensions, audit, notifications, registrations, flags, storage}} -- from data the test puts in, serves the
 * presigned URLs of {@link FakeStorage} itself (no S3), checks the module token and
 * the context proof the way the platform does, records every request, and issues signed contexts to call the module's
 * own routes as the platform would. Published in the SDK's {@code tests} artifact
 * ({@code ro.kf:kf-module-sdk:<version>:tests}, scope {@code test}); never in the SDK itself.
 *
 * <pre>{@code
 * try (FakePlatform fake = FakePlatform.start("kf-aas-r3")) {
 *     fake.manifest(Files.readString(Path.of("kf-module-conf.json")));
 *     fake.reads("model.v1").put(TENANT, "42", Map.of("id", "42", "name", "Rochie"));
 *     fake.settings(TENANT, "live", true);
 *     KfModule kf = fake.module();                            // the module, pointed at the fake
 *     FakePlatform.SignedContext ctx = fake.signedContext(TENANT, "sub-1", List.of(), "GET", "/x");
 *     HttpRequest call = ctx.applyTo(HttpRequest.newBuilder(URI.create(moduleUrl + "/x"))).GET().build();
 *     ... // the module's route reads model.v1/42 through kf.reads() and the fake answers
 *     assertEquals("X", fake.extensions(TENANT, "model_version", "17").get("aas_shell"));
 * }
 * }</pre>
 *
 * <p>What it checks, in the platform's order (§15): the token ({@code 401}) and the slug ({@code 403}); the capability,
 * when the test gave the manifest ({@code 403 capability-not-declared}); the context and its proof, {@code ts} within
 * ±300 s ({@code 401 invalid-context}); the module live in the context's tenant ({@code 403 module-not-live}, see
 * {@link #notLive}); then the service's own rules: the platform right of a read port held by the context's user
 * ({@link #userRights}; a user without an explicit list holds every right), the system context only on the object of a
 * port marked {@code system} and without {@code include}, paging (size 1–200), a secret's {@code writeRight} and path,
 * the declared extension fields. The proof is an HMAC under {@link #PROOF_KEY} over {@code <slug>.<X-KF-Context>}: a
 * context the fake did not issue is refused, as on the platform.
 *
 * <p>From SDK 0.8.1, what a module of protocol 1.2 needs besides: the storage of §15.7
 * ({@link #storage()}, {@link FakeStorage}); the caller's {@code person} in a member's context
 * ({@link #signedContext(String, String, List, String, String, RequestContext.Person)}); the platform's
 * {@code tenant.after.delete} ({@link #callbackBase}, {@link #deliverTenantDeleted}); a port's reads failing
 * ({@link #failReads}, {@link Refusal#UNAVAILABLE}); {@code tenant.v1} among {@link #DECIDED_PORTS}.
 *
 * <p>From SDK 0.8.1 also the read ports of protocol 1.2.1: {@code component.v1} and
 * {@code inventory-roll.v1} among {@link #DECIDED_PORTS}, their lists held to the platform's filter rules (
 * {@code 10-citirile.md} §3.16, §3.17) -- see {@link #reads(String)}.
 *
 * <p>From SDK 0.9.0, protocol 1.3: the tenant's members and their roles ({@link #member},
 * {@link #inactiveMember}) and {@code recipientRoles} resolved from them on a notification, as the platform does
 * (§11.1: only in the notification's tenant, the active members, joined with {@code recipients}, each person once;
 * {@code 422 unknown-role} outside {@link #GRANTABLE_ROLES}, {@code 422 too-many-recipients} over
 * {@value #MAX_RESOLVED_RECIPIENTS}); the people each notification reached, for the test ({@link #notificationRecipients});
 * the tenant's soft-delete and restore ({@link #deliverTenantSoftDeleted}, {@link #deliverTenantRestored}: only to a
 * module subscribed and live in the tenant, nothing purged); {@code tenant.v1} listed without {@code ids} only by the
 * platform's actor -- its own context ({@link #systemContext}) or a user on an {@code admin: platform} route -- every
 * tenant, each with {@code deletedAt} ({@code null} unless the test put one); anyone else gets the platform's
 * {@code 400 ids: required}.
 *
 * <p>From SDK 0.9.1 (protocol 1.3.1, delivered): {@code model.v1} listed without
 * {@code ids} by a user holding the port's right (the system context still reads only objects), its only filter
 * {@code ids}; the port {@code model-version.garment-image.v1} among {@link #DECIDED_PORTS}, its list with a mandatory
 * {@code ids} ({@code 400 ids: required}). The fake has no customer persona: the portal's {@code 400 ids: required}
 * on {@code model.v1} is the platform's alone.
 *
 * <p>From SDK 0.10.0 (protocol 2.0): the calls between modules of §15.9 and the module events of §6.3.
 * The module under test is the CALLER; the owners it calls are {@link #peer(String)}s whose reads and commands the test
 * answers ({@link PeerHandler}). The fake checks in the platform's order: {@code capabilities.peers} names the owner and
 * the port or command ({@code 403 capability-not-declared}), the context and its proof, the module live, a command's
 * {@code Idempotency-Key} ({@code 400 idempotency-key-required}); the owner in {@code requires}
 * ({@code 403 peer-not-required}); the owner installed ({@code 503 module-unavailable}, permanent) and running
 * ({@code 503}, transient, {@link Peer#stopped}); the port or command offered, an {@code internal} one only to a caller
 * of the internal class ({@link #internalClass}; otherwise {@code 404 peer-port-unknown} / {@code peer-command-unknown},
 * exactly as one that does not exist); the user's right of the owner's route ({@code 403 peer-forbidden}). The owner's
 * answer is mapped as the platform maps it ({@code 2xx} unchanged, {@code 404 not-configured}, another {@code 4xx}
 * {@code 422 module-refused}, {@code 5xx} or a thrown handler {@code 503 module-unavailable} transient), and a command's
 * key replays the stored answer for the same body (the owner is not called again), answers
 * {@code 409 idempotency-conflict} for another one, and is freed by a transient failure. The owner sees the caller's
 * context as {@code via module:{caller}} ({@link PeerCall}). The module's emissions ({@code POST …/events}) are checked
 * ({@code 400 validation}, {@code 422 event-not-declared}) and kept ({@link #emittedEvents}); an owner's event is
 * delivered to the module with {@link #deliverModuleEvent}. Every delivery carries the context
 * ({@code X-KF-Context}, {@code system via hook}, and its proof), accepted by the fake for ±300 s.
 */
public final class FakePlatform implements AutoCloseable {

    /** The fake's own proof key (the platform's is secret; this one is the same in every test). */
    public static final String PROOF_KEY = "kf-fake-platform-proof-key";

    /**
     * The roles a module may name in {@code recipientRoles} (protocol 1.3 §11.1): the platform's
     * {@code GrantableSystemRole}, without {@code SUPER_ADMIN}.
     */
    public static final List<String> GRANTABLE_ROLES = List.of("BRAND_MANAGER", "DESIGNER", "OPERATOR",
            "PRODUCTION_MANAGER", "QC", "TENANT_ADMIN", "CLO_STATION");

    /** The most people one notification may reach once {@code recipientRoles} is resolved (protocol 1.3). */
    public static final int MAX_RESOLVED_RECIPIENTS = 200;

    /** The platform right and the system flag of a read port. */
    public record PortRule(String right, boolean system) {
    }

    /**
     * The ports decided on 2026-10-21 (§15.1, {@code docs/modules/specifications/10-citirile.md}), {@code tenant.v1}
     * of protocol 1.2, and {@code component.v1} and {@code inventory-roll.v1} of protocol 1.2.1 (the
     * right of {@code inventory-roll.v1} is the derived code of {@code GET /api/inventory/stock/{componentId}/entries}),
     * and {@code model-version.garment-image.v1} of protocol 1.3.1 (delivered: the right
     * of the host's route {@code GET /api/v1/models/{id}/versions/{versionId}/garment-image/view-url}, not a system port).
     */
    public static final Map<String, PortRule> DECIDED_PORTS = Map.ofEntries(
            Map.entry("model.v1", new PortRule("api.v1.models.get", true)),
            Map.entry("model-version.v1", new PortRule("api.v1.models.get", true)),
            Map.entry("document.v1", new PortRule("api.v1.documents.get", false)),
            Map.entry("file.public-url.v1", new PortRule("api.v1.models.get", false)),
            Map.entry("order.v1", new PortRule("api.v1.orders.get", false)),
            Map.entry("order-costing.v1", new PortRule("api.v1.orders.get", false)),
            Map.entry("purchase-order.v1", new PortRule("api.v1.purchase-orders.get", false)),
            Map.entry("supplier.v1", new PortRule("api.v1.suppliers.get", false)),
            Map.entry("customer.v1", new PortRule("api.v1.customers.get", false)),
            Map.entry("production-batch.v1", new PortRule("api.v1.production.get", false)),
            Map.entry("waste-report.v1", new PortRule("api.v1.waste-reports.get", false)),
            Map.entry("tech-process.v1", new PortRule("api.v1.tech-process.get", false)),
            Map.entry("technician.v1", new PortRule("api.v1.technicians.get", false)),
            Map.entry("technician-shift.v1", new PortRule("api.v1.planner.get", false)),
            Map.entry("tenant.v1", new PortRule("api.v1.tenants.get", false)),
            Map.entry("component.v1", new PortRule("api.v1.components.get", false)),
            Map.entry("inventory-roll.v1", new PortRule("api.inventory.stock.get", false)),
            Map.entry("model-version.garment-image.v1", new PortRule("api.v1.models.get", false)));

    /**
     * A refusal the reads of one port answer with instead of the port's objects ({@link #failReads}): the status, the
     * protocol's reason, the {@code class} and the detail of the RFC 9457 body.
     */
    public record Refusal(int status, String reason, String errorClass, String detail) {

        /** The platform cannot answer the read now: {@code 503 unavailable}, {@code class: transient} (§15, §0). */
        public static final Refusal UNAVAILABLE = new Refusal(503, "unavailable", "transient",
                "The read could not be answered: its provider is unavailable.");
    }

    /**
     * One delivery of a callback to the module ({@link #deliverTenantDeleted}): the module's status and body, the event's
     * {@code kfeventid} (the same on a redelivery) and the delivery's CloudEvent {@code id} (new on every delivery).
     */
    public record Delivery(int status, String body, String eventId, String deliveryId, String context, String proof) {

        /** A delivery without the context (the shape before SDK 0.10.0). */
        public Delivery(int status, String body, String eventId, String deliveryId) {
            this(status, body, eventId, deliveryId, null, null);
        }
    }

    /**
     * One call of the module under test to an owner (§15.9), as the owner receives it: {@code kind} {@code read} or
     * {@code command}, the port or command {@code name}, the caller's tenant and user, the actor
     * ({@code user} or {@code system}) with {@code via = module:{caller}}, the query of a read, the body and the key
     * of a command.
     */
    public record PeerCall(String kind, String name, String tenant, String user, String actorType, String via,
                           Map<String, String> query, Object body, String idempotencyKey) {
    }

    /** The owner's answer to a {@link PeerCall}: its status and JSON body. */
    public record PeerReply(int status, Object body) {

        /** {@code 200} with {@code body}. */
        public static PeerReply ok(Object body) {
            return new PeerReply(200, body);
        }
    }

    /** The owner's handler of one read or command. */
    @FunctionalInterface
    public interface PeerHandler {
        PeerReply handle(PeerCall call);
    }

    /** One read or command an owner offers: its visibility, the right of its route (or none) and its handler. */
    private record Offer(String visibility, String right, PeerHandler handler) {
    }

    /**
     * An owner module the module under test calls (§15.9): the reads ({@code m.{owner}.{name}.v{N}}) and commands it
     * offers, and its state -- installed and running by default, {@link #stopped} (503 transient) or {@link #absent}
     * (503 permanent, as an absent optional dependency).
     */
    public static final class Peer {
        private final String slug;
        private final Map<String, Offer> reads = new ConcurrentHashMap<>();
        private final Map<String, Offer> commands = new ConcurrentHashMap<>();
        private final List<PeerCall> calls = new CopyOnWriteArrayList<>();
        private volatile boolean stopped;
        private volatile boolean absent;

        private Peer(String slug) {
            this.slug = slug;
        }

        public String slug() {
            return slug;
        }

        /** A {@code public} read. */
        public Peer read(String port, PeerHandler handler) {
            return read(port, "public", null, handler);
        }

        /**
         * A read with its visibility ({@code public} or {@code internal}) and the right of the owner's route
         * ({@code null} = none): a user's context must hold it ({@link FakePlatform#userRights}).
         */
        public Peer read(String port, String visibility, String right, PeerHandler handler) {
            reads.put(port, new Offer(visibility, right, java.util.Objects.requireNonNull(handler, "handler")));
            return this;
        }

        /** A {@code public} command. */
        public Peer command(String name, PeerHandler handler) {
            return command(name, "public", null, handler);
        }

        /** A command with its visibility and the right of the owner's route. */
        public Peer command(String name, String visibility, String right, PeerHandler handler) {
            commands.put(name, new Offer(visibility, right, java.util.Objects.requireNonNull(handler, "handler")));
            return this;
        }

        /** Installed, in range, but stopped (or not activated for the tenant): {@code 503 module-unavailable} transient. */
        public Peer stopped(boolean stopped) {
            this.stopped = stopped;
            return this;
        }

        /** Not installed, or outside the required range: {@code 503 module-unavailable} permanent. */
        public Peer absent(boolean absent) {
            this.absent = absent;
            return this;
        }

        /** The calls that reached this owner, in order (a replayed command does not reach it). */
        public List<PeerCall> calls() {
            return List.copyOf(calls);
        }
    }

    /** One request the module sent to the fake: the three headers of §15 (without the token's value) and the body. */
    public record Recorded(String method, String path, String query, String context, String proof, Object body) {
    }

    /**
     * A context signed as the platform signs it for a module route (§9.2): the four headers to put on the request.
     */
    public record SignedContext(String context, String timestamp, String signature, String proof) {

        /** {@code X-KF-Context}, {@code X-KF-Timestamp}, {@code X-KF-Signature}, {@code X-KF-Context-Proof}. */
        public Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(RequestContext.CONTEXT_HEADER, context);
            headers.put(RequestContext.TIMESTAMP_HEADER, timestamp);
            headers.put(RequestContext.SIGNATURE_HEADER, signature);
            headers.put(RequestContext.PROOF_HEADER, proof);
            return headers;
        }

        /** The four headers on {@code request}. */
        public HttpRequest.Builder applyTo(HttpRequest.Builder request) {
            headers().forEach(request::header);
            return request;
        }
    }

    /** The objects of one read port, per tenant (or in every tenant), and its rule. */
    public static final class Port {
        private static final String EVERY_TENANT = "*";
        private final Map<String, Map<String, Map<String, Object>>> objects = new ConcurrentHashMap<>();
        private volatile PortRule rule;

        private Port(PortRule rule) {
            this.rule = rule;
        }

        /** An object visible in every tenant. */
        public Port put(String id, Map<String, ?> object) {
            return put(EVERY_TENANT, id, object);
        }

        /** An object visible only in {@code tenant}. */
        public Port put(String tenant, String id, Map<String, ?> object) {
            objects.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).put(id, new LinkedHashMap<>(object));
            return this;
        }

        public Port remove(String id) {
            objects.values().forEach(m -> m.remove(id));
            return this;
        }

        /** The platform right a user must hold (null = none). Default: the decided port's. */
        public Port right(String right) {
            rule = new PortRule(right, rule.system());
            return this;
        }

        /** Whether the system context reads this port's objects. Default: the decided port's. */
        public Port system(boolean system) {
            rule = new PortRule(rule.right(), system);
            return this;
        }

        /** Every object of the port, in every tenant: what the platform's actor lists on {@code tenant.v1} (1.3). */
        private Map<String, Map<String, Object>> everywhere() {
            Map<String, Map<String, Object>> all = new LinkedHashMap<>();
            objects.values().forEach(all::putAll);
            return all;
        }

        private Map<String, Map<String, Object>> visible(String tenant) {
            Map<String, Map<String, Object>> visible = new LinkedHashMap<>(objects.getOrDefault(EVERY_TENANT, Map.of()));
            visible.putAll(objects.getOrDefault(tenant, Map.of()));
            return visible;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final String PREFIX = "/api/v1/platform/modules/";
    private static final List<String> ENTITIES = List.of("model", "order", "customer", "model_version");
    /** A {@code {param}} segment of a route's path (as {@code DeclaredModuleRoutes.PARAM}). */
    private static final Pattern PARAM = Pattern.compile("^\\{[A-Za-z][A-Za-z0-9_]*}$");

    private final String slug;
    private final String token;
    private final String hookSecret;
    private final HttpServer server;
    private final AtomicLong sequence = new AtomicLong();
    private volatile String manifestJson;
    private volatile Manifest manifest;
    private final Map<String, Port> ports = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> settings = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    /** The secret store's state: null = answering, else the problem reason it returns. */
    private volatile String secretStore;
    private final Map<String, Map<String, Object>> extensions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userRights = new ConcurrentHashMap<>();
    private final Set<String> notLive = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();
    private final Map<String, Refusal> readFailures = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> audit = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> notifications = new CopyOnWriteArrayList<>();
    private final List<List<String>> notificationRecipients = new CopyOnWriteArrayList<>();
    /** Per tenant, in declaration order: a member's subject, its roles and whether it is active (protocol 1.3). */
    private final Map<String, Map<String, Member>> members = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    /** A command's stored answers, by {@code owner|command|key}: the body's fingerprint and the answer (§15.9). */
    private final Map<String, Object[]> commandKeys = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> emittedEvents = new CopyOnWriteArrayList<>();
    private volatile boolean internalClass;
    /** The slots other modules declare (§7.12), by {@code m.{owner}.{key}}: kind, visibility, layer kinds. */
    private final Map<String, OwnerSlot> ownerSlots = new ConcurrentHashMap<>();
    private final FakeStorage storage;
    /** The module's callback base ({@code KF_CALLBACK_BASE}), where the fake delivers the callbacks; null = none. */
    private volatile String callbackBase;

    private FakePlatform(String slug) throws IOException {
        this.slug = slug;
        this.storage = new FakeStorage(slug, this::url);
        this.token = "kfm_fake-" + UUID.randomUUID();
        this.hookSecret = "fake-hook-secret-" + UUID.randomUUID();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Starts a fake platform for the module {@code slug} on a free local port. */
    public static FakePlatform start(String slug) throws IOException {
        return new FakePlatform(slug);
    }

    /** The base URL ({@code KF_PLATFORM_URL}). */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String slug() {
        return slug;
    }

    /** The module token the fake accepts ({@code KF_MODULE_TOKEN}). */
    public String token() {
        return token;
    }

    /** The module's hook secret ({@code KF_HOOK_SECRET}): the contexts of {@link #signedContext} verify under it. */
    public String hookSecret() {
        return hookSecret;
    }

    /**
     * The module's manifest: the fake then checks the capabilities (reads, settings, secrets, audit, the extension
     * fields), the settings get their defaults and the secrets their scope and {@code writeRight}. Without it, every
     * capability counts as declared.
     */
    public FakePlatform manifest(String json) {
        this.manifest = Manifest.parse(json);
        this.manifestJson = json;
        return this;
    }

    /** A module pointed at this fake: its URL, slug, token, hook secret, and the manifest when given. */
    public KfModule module() {
        KfModule kf = KfModule.of(url(), slug, token, hookSecret, null);
        return manifestJson == null ? kf : kf.withManifest(manifestJson);
    }

    /**
     * The module's storage (§15.7): the objects the module uploaded through the presigned URLs, and the
     * test's own ({@link FakeStorage#put}, {@link FakeStorage#remove}, {@link FakeStorage#purgeTenant}).
     */
    public FakeStorage storage() {
        return storage;
    }

    /** The module's callback base ({@code KF_CALLBACK_BASE}): {@link #deliverTenantDeleted} posts to its {@code /_kf/hooks}. */
    public FakePlatform callbackBase(String url) {
        this.callbackBase = url;
        return this;
    }

    /**
     * Every read of {@code port} ({@code GET …/reads/{port}[/{id}]}) answers {@code refusal} -- e.g.
     * {@link Refusal#UNAVAILABLE}, the platform's {@code 503} -- after the common checks (token, capability, context,
     * the module live, the port's right), instead of the port's objects; the other ports answer as before.
     * {@code null} = the port answers again.
     */
    public FakePlatform failReads(String port, Refusal refusal) {
        if (refusal == null) readFailures.remove(port);
        else readFailures.put(port, refusal);
        return this;
    }

    /**
     * The platform's deletion of {@code tenantId}, as the module sees it (protocol 1.2 §6.1, §15.7): the objects under
     * {@code kf-modules/{slug}/{tenantId}/} are purged ({@link FakeStorage#purgeTenant}), then {@code tenant.after.delete}
     * is delivered -- a CloudEvent {@code kf.tenant.after.delete} with {@code data = {entity: tenant, id, before: {id},
     * after: null, changed: []}}, signed under the hook secret, {@code POST {callbackBase}/_kf/hooks} -- with a new
     * {@code kfeventid}.
     *
     * @return the module's answer, with the event's and the delivery's ids
     * @throws IllegalStateException without {@link #callbackBase}, or when the manifest given does not subscribe to
     *                               {@code tenant.after.delete} (the platform would not deliver it)
     */
    public Delivery deliverTenantDeleted(String tenantId) {
        return deliverTenantDeleted(tenantId, UUID.randomUUID().toString());
    }

    /**
     * {@link #deliverTenantDeleted(String)} with the {@code kfeventid} {@code eventId}: a redelivery of the same event
     * (the platform delivers at least once), under a new delivery id.
     */
    public Delivery deliverTenantDeleted(String tenantId, String eventId) {
        requireSubscribed(HookEvents.TENANT_AFTER_DELETE);
        storage.purgeTenant(tenantId);
        return deliver(HookEvents.TENANT_AFTER_DELETE, tenantId, eventId, hookSecret);
    }

    /**
     * {@code tenant.after.delete} for {@code tenantId} signed under {@code secret} instead of the hook secret: a
     * delivery that did not come from the platform (the module answers {@code 401}). Nothing is purged.
     */
    public Delivery deliverTenantDeletedSignedWith(String tenantId, String secret) {
        requireSubscribed(HookEvents.TENANT_AFTER_DELETE);
        return deliver(HookEvents.TENANT_AFTER_DELETE, tenantId, UUID.randomUUID().toString(), secret);
    }

    /**
     * The platform's soft-delete of {@code tenantId}, as the module sees it (protocol 1.3 §6.1):
     * {@code tenant.after.soft-delete} -- a CloudEvent with {@code data = {entity: tenant, id, before: {id},
     * after: {id}, changed: []}}, signed under the hook secret, {@code POST {callbackBase}/_kf/hooks} -- with a new
     * {@code kfeventid}. Nothing is purged: the tenant's data stays, access is off and recoverable.
     *
     * @throws IllegalStateException without {@link #callbackBase}, when the manifest given does not subscribe to the
     *                               point, or when the module is not live in the tenant ({@link #notLive}): the
     *                               platform would not deliver it
     */
    public Delivery deliverTenantSoftDeleted(String tenantId) {
        return deliverTenantSoftDeleted(tenantId, UUID.randomUUID().toString());
    }

    /** {@link #deliverTenantSoftDeleted(String)} with the {@code kfeventid} {@code eventId} (a redelivery). */
    public Delivery deliverTenantSoftDeleted(String tenantId, String eventId) {
        requireSubscribed(HookEvents.TENANT_AFTER_SOFT_DELETE);
        requireLive(HookEvents.TENANT_AFTER_SOFT_DELETE, tenantId);
        return deliver(HookEvents.TENANT_AFTER_SOFT_DELETE, tenantId, eventId, hookSecret);
    }

    /**
     * The platform's restore of the soft-deleted {@code tenantId} (protocol 1.3 §6.1): {@code tenant.after.restore},
     * delivered as {@link #deliverTenantSoftDeleted(String)}, with the same envelope and the same conditions.
     */
    public Delivery deliverTenantRestored(String tenantId) {
        return deliverTenantRestored(tenantId, UUID.randomUUID().toString());
    }

    /** {@link #deliverTenantRestored(String)} with the {@code kfeventid} {@code eventId} (a redelivery). */
    public Delivery deliverTenantRestored(String tenantId, String eventId) {
        requireSubscribed(HookEvents.TENANT_AFTER_RESTORE);
        requireLive(HookEvents.TENANT_AFTER_RESTORE, tenantId);
        return deliver(HookEvents.TENANT_AFTER_RESTORE, tenantId, eventId, hookSecret);
    }

    /**
     * The event {@code name} of the module {@code owner}, delivered to the module under test as the platform does
     * (protocol 2.0, §6.3): a CloudEvent {@code kf.m.{owner}.after.{name}} with {@code source /module-event/{owner}},
     * {@code subject} = the owner, {@code kfaggregatetype module-event}, {@code data} unchanged, the context and its proof, signed under the hook secret, with a new {@code kfeventid}.
     *
     * @throws IllegalStateException without {@link #callbackBase}, when the manifest given does not subscribe to the
     *                               point or does not require the owner, or when the module is not live in the tenant
     */
    public Delivery deliverModuleEvent(String owner, String name, String tenantId, Map<String, ?> data) {
        return deliverModuleEvent(owner, name, tenantId, data, UUID.randomUUID().toString());
    }

    /** {@link #deliverModuleEvent(String, String, String, Map)} with a given {@code kfeventid} (a redelivery). */
    public Delivery deliverModuleEvent(String owner, String name, String tenantId, Map<String, ?> data, String eventId) {
        String point = HookEvents.moduleEventPoint(owner, name);
        requireSubscribed(point);
        if (manifest != null && manifest.requires().stream().noneMatch(r -> owner.equals(r.slug()))) {
            throw new IllegalStateException(slug + " does not require " + owner + ": the platform would not deliver "
                    + point + " to it");
        }
        requireLive(point, tenantId);
        Map<String, Object> ce = envelope(point, tenantId, eventId, "/module-event/" + owner, owner, "module-event");
        ce.put("data", data == null ? Map.of() : new LinkedHashMap<>(data));
        return post(ce, tenantId, eventId, hookSecret);
    }

    /**
     * The objects and the rule of a read port (created empty on first use). A list is filtered by equality on the
     * objects' fields, {@code ids} among the ids given -- except on the ports of protocol 1.2.1, which keep the
     * platform's rules: {@code component.v1} takes {@code ids} or {@code q} (one of them, never both;
     * {@code q} 2 to 100 characters, the name contains it, any case) and an optional single {@code componentType};
     * {@code inventory-roll.v1} takes {@code componentId} or {@code ids} (one of them; with both, the material's rolls
     * among the ids). Anything else is the platform's {@code 400} with its {@code errors}: an unknown filter, a missing
     * mandatory one ({@code "required (or ids)"}), a malformed id.
     */
    public Port reads(String port) {
        return ports.computeIfAbsent(port, p -> new Port(DECIDED_PORTS.getOrDefault(p, new PortRule(null, false))));
    }

    /** One setting of the module in {@code tenant} (over the manifest's default). */
    public FakePlatform settings(String tenant, String key, Object value) {
        settings.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).put(key, value);
        return this;
    }

    /** A stored secret, as if written earlier. */
    public FakePlatform secret(String ref, String value) {
        secrets.put(ref, value);
        return this;
    }

    /**
     * The platform's secret store unreachable: every secret write and resolve is then
     * {@code 503 secret-store-unreachable}, {@code class: transient}, until called again with {@code false}.
     */
    public FakePlatform secretStoreDown(boolean down) {
        this.secretStore = down ? "secret-store-unreachable" : null;
        return this;
    }

    /**
     * The platform's secret store answering but refusing the path, as a Vault {@code 403} of policy: every secret write and resolve is then {@code 503 secret-store-rejected},
     * {@code class: permanent}, until called again with {@code false}.
     */
    public FakePlatform secretStoreRejects(boolean rejects) {
        this.secretStore = rejects ? "secret-store-rejected" : null;
        return this;
    }

    /** The reference the fake gives a secret: {@code tenant} null for a {@code platform} secret. */
    public String secretRef(String tenant, String name) {
        return "vault://platform/kf-modules/" + slug + "/" + (tenant == null ? "platform" : tenant) + "/" + name;
    }

    /** The value stored behind {@code ref}, or null. */
    public String secretValue(String ref) {
        return secrets.get(ref);
    }

    /** The module's extension values on one object of {@code tenant} (a copy; empty when none). */
    public Map<String, Object> extensions(String tenant, String entity, String id) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(extensions.getOrDefault(key(tenant, entity, id), Map.of())));
    }

    /** The platform rights {@code user} holds in {@code tenant}; a user without such a list holds every right. */
    public FakePlatform userRights(String tenant, String user, String... rights) {
        userRights.put(tenant + "|" + user, Set.of(rights));
        return this;
    }

    /** The module is not live in {@code tenant}: every call with its context is {@code 403 module-not-live}. */
    public FakePlatform notLive(String tenant) {
        notLive.add(tenant);
        return this;
    }

    /**
     * The owner {@code slug} the module under test calls (§15.9), created on first use, installed and running, with
     * nothing offered.
     */
    public Peer peer(String slug) {
        return peers.computeIfAbsent(slug, Peer::new);
    }

    /**
     * Whether the module under test is of the internal class (§14.6: the class follows the signed SDK variant):
     * only then does an owner's {@code internal} read or command answer it. Public by default.
     */
    /**
     * A slot another module (the owner) declares in its installed version (protocol 2.0, §7.12): a contribution of the
     * module under test to {@code m.{owner}.{key}} is then stored active; any other — owner absent, slot or layer kind
     * undeclared, or an {@code internal} slot while the module is not of the {@link #internalClass} — is accepted with
     * the platform's warning, the same text for an internal slot as for a missing one.
     */
    public FakePlatform slot(String owner, String key, String kind, String visibility, String... layerKinds) {
        ownerSlots.put("m." + owner + "." + key, new OwnerSlot(kind, visibility, List.of(layerKinds)));
        return this;
    }

    /** A slot as its owner declares it (the fake's copy of the owner's approved manifest). */
    private record OwnerSlot(String kind, String visibility, List<String> layerKinds) {
    }

    /**
     * The 422 of {@code SurfaceValidator} for a contribution to a slot that exists and is visible to the module: the kind must fit the slot's kind (A react|wc, B layer, R data, D none) and a {@code layer}'s
     * {@code source} must be a GET route of the module's manifest (checked only when the test gave the manifest). A slot
     * the owner does not declare (or an internal one for a public module) is not refused: warns.
     */
    private void slotErrors(Map<String, Object> registration) {
        if (!(registration.get("surfaces") instanceof List<?> surfaces)) return;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < surfaces.size(); i++) {
            if (!(surfaces.get(i) instanceof Map<?, ?> surface) || !(surface.get("slug") instanceof String slotSlug)
                    || !slotSlug.startsWith("m.")) {
                continue;
            }
            String where = "surfaces[" + i + "]";
            String kind = String.valueOf(surface.get("kind"));
            if ("layer".equals(kind) && manifest != null) {
                Object source = surface.get("source");
                boolean declaredGet = source instanceof String path && manifest.capabilities().routes().stream()
                        .anyMatch(r -> "GET".equalsIgnoreCase(r.method()) && path.equals(r.path()));
                if (!declaredGet) {
                    errors.add(where + " kind=layer needs a \"source\" that is a GET route this module declares but was "
                            + (source == null ? "null" : "\"" + source + "\""));
                }
            }
            OwnerSlot declared = ownerSlots.get(slotSlug);
            if (declared == null || ("internal".equals(declared.visibility()) && !internalClass)) continue;
            boolean fits = switch (declared.kind()) {
                case "A" -> "react".equals(kind) || "wc".equals(kind);
                case "B" -> "layer".equals(kind);
                case "R" -> "data".equals(kind);
                default -> false;
            };
            if (!fits) {
                String expects = switch (declared.kind()) {
                    case "A" -> "expects react or wc";
                    case "B" -> "expects layer";
                    case "R" -> "expects data";
                    default -> "the bus in the page; nothing is registered on it";
                };
                errors.add(where + " kind=" + kind + " does not fit the slot \"" + slotSlug + "\" of kind "
                        + declared.kind() + " (" + expects + ")");
            }
        }
        if (!errors.isEmpty()) {
            throw new Refused(422, "validation", String.join("; ", errors));
        }
    }

    /** The platform's answer for the contributions to slots (SurfaceValidator): warnings, never a 422. */
    private List<String> slotWarnings(Map<String, Object> registration) {
        List<String> warnings = new ArrayList<>();
        if (!(registration.get("surfaces") instanceof List<?> surfaces)) return warnings;
        for (int i = 0; i < surfaces.size(); i++) {
            if (!(surfaces.get(i) instanceof Map<?, ?> surface) || !(surface.get("slug") instanceof String slotSlug)
                    || !slotSlug.startsWith("m.")) {
                continue;
            }
            String where = "surfaces[" + i + "]";
            OwnerSlot declared = ownerSlots.get(slotSlug);
            if (declared == null || ("internal".equals(declared.visibility()) && !internalClass)) {
                warnings.add(where + ".slug: \"" + slotSlug + "\" is not a slot the installed version of its owner "
                        + "declares; the contribution is stored inactive and is not served until the owner declares it");
            } else if ("layer".equals(surface.get("kind")) && !declared.layerKinds().contains(surface.get("layer"))) {
                warnings.add(where + ".layer \"" + surface.get("layer") + "\" is not a layer kind the installed version "
                        + "of the owner declares on \"" + slotSlug + "\"; the contribution is stored inactive and is not "
                        + "served until it does");
            }
        }
        return warnings;
    }

    public FakePlatform internalClass(boolean internal) {
        this.internalClass = internal;
        return this;
    }

    // ---- the order flow (protocol 2.0, sdk-protocol.md §16) ------------------------------------------

    /**
     * What the fake answers on {@code order.services.v1} (§16.6): {@link #DELIVERED} (the default, so that a module's
     * tests can write lines), {@link #NOT_DELIVERED} ({@code 422 not-delivered}, a platform before
     * {@code ORDER_SERVICES}) or {@link #NO_PROVIDER} ({@code 501 no-provider}, delivered without an adapter).
     */
    public enum ServicesState { DELIVERED, NOT_DELIVERED, NO_PROVIDER }

    /** A service line on an order (§16.6), keyed on {@code (tenant, orderId, source, externalRef)}. */
    public record ServiceLine(String tenant, String orderId, String entryId, String name, BigDecimal amount,
                              String currency, String externalRef, String source) {
    }

    /**
     * One rule's outcome in {@link #evaluateFlowRules}: {@code passed}, the module's {@code key} and {@code params} on a
     * {@code block}, or the platform's {@code failure} when the module's answer did not count (fail-closed):
     * {@code timeout}, {@code status-<n>} (a non-2xx, a {@code 400} for a projection the rule refused included),
     * {@code invalid-json}, {@code unknown-decision}, {@code block-without-key}, {@code module-unavailable}.
     */
    public record RuleOutcome(String rule, boolean passed, String key, Map<String, Object> params, String failure) {
    }

    /** The engine's verdict on an advance: every rule passed (AND), and every outcome -- all the reasons, not the first. */
    public record FlowAdvance(boolean passed, List<RuleOutcome> rules) {

        /** The outcomes that block the advance. */
        public List<RuleOutcome> reasons() {
            return rules.stream().filter(r -> !r.passed()).toList();
        }
    }

    private volatile ServicesState servicesState = ServicesState.DELIVERED;
    private final Map<String, ServiceLine> serviceLines = new ConcurrentHashMap<>();
    private volatile boolean moduleStopped;

    /** The answer of {@code order.services.v1} ({@link ServicesState}). */
    public FakePlatform orderServices(ServicesState state) {
        this.servicesState = state == null ? ServicesState.DELIVERED : state;
        return this;
    }

    /** A line already on an order: another module's ({@code source} = its slug) or a manual one. */
    public FakePlatform serviceLine(ServiceLine line) {
        serviceLines.put(lineKey(line.tenant(), line.orderId(), line.source(), line.externalRef()), line);
        return this;
    }

    /** The service lines of an order in a tenant, every source, sorted by source and key. */
    public List<ServiceLine> serviceLines(String tenant, String orderId) {
        return serviceLines.values().stream().filter(l -> l.tenant().equals(tenant) && l.orderId().equals(orderId))
                .sorted(Comparator.comparing(ServiceLine::source).thenComparing(ServiceLine::externalRef)).toList();
    }

    /** The module stopped (or not): its rules are then unavailable to the engine -- the step blocks (fail-closed). */
    public FakePlatform moduleStopped(boolean stopped) {
        this.moduleStopped = stopped;
        return this;
    }

    /**
     * The engine's evaluation of an advance (§16.4): for every {@code flowRules[]} of the manifest given, the
     * query {@code order.flow-rule.v1} of {@code projection} with {@code rule.key} = the rule's key, sent as
     * {@code GET {callbackBase}{path without /m/{slug}}?<query>} (the platform strips the prefix) with the context {@code system via platform} (no user, no rights), within
     * the rule's {@code timeoutSeconds} (2 s by default). AND over the rules, every outcome kept; fail-closed on a
     * timeout, a non-2xx, a body that is not a JSON object, an unknown decision, a block without a key, and a module
     * stopped ({@link #moduleStopped}) or not live for the tenant ({@link #notLive}). No rule = the advance passes.
     *
     * @throws IllegalStateException without {@link #callbackBase}, or a query over 4 KiB
     */
    public FlowAdvance evaluateFlowRules(String tenant, FlowRules.Projection projection) {
        if (callbackBase == null) {
            throw new IllegalStateException("callbackBase(url) first: the fake calls the rules at {callbackBase}{path}");
        }
        List<RuleOutcome> outcomes = new ArrayList<>();
        List<Manifest.FlowRuleCapability> rules = manifest == null ? List.of() : manifest.capabilities().flowRules();
        for (Manifest.FlowRuleCapability rule : rules) {
            outcomes.add(evaluate(tenant, rule, projection));
        }
        return new FlowAdvance(outcomes.stream().allMatch(RuleOutcome::passed), List.copyOf(outcomes));
    }

    private RuleOutcome evaluate(String tenant, Manifest.FlowRuleCapability rule, FlowRules.Projection projection) {
        if (moduleStopped || notLive.contains(tenant)) {
            return new RuleOutcome(rule.key(), false, null, Map.of(), "module-unavailable");
        }
        FlowRules.Projection p = new FlowRules.Projection(rule.key(), projection.orderId(), projection.orderStatus(),
                projection.flowKey(), projection.flowVersion(), projection.instanceId(), projection.stepKey(),
                projection.stepTo(), projection.orderNumber(), projection.orderInternal(), projection.orderCustomerId(),
                projection.stepBehavesLike(), projection.orderStages(), projection.lineId(), projection.lineModelId(),
                projection.lineModelVersionId(), projection.lineQuantity());
        String query = p.toQuery();
        if (query.getBytes(StandardCharsets.UTF_8).length > FlowRules.MAX_QUERY_BYTES) {
            throw new IllegalStateException(FlowRules.PROJECTION + " query exceeds " + FlowRules.MAX_QUERY_BYTES + " bytes");
        }
        // as the platform forwards to a module (ModuleRequestPaths): the prefix /m/{slug} is stripped, the context
        // signed for the path the module serves
        String prefix = "/m/" + slug;
        String path = rule.path().startsWith(prefix + "/") ? rule.path().substring(prefix.length()) : rule.path();
        SignedContext context = systemContext(tenant, "GET", path);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(callbackBase + path + "?" + query))
                .timeout(java.time.Duration.ofSeconds(rule.effectiveTimeoutSeconds())).GET();
        context.applyTo(request);
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return new RuleOutcome(rule.key(), false, null, Map.of(), "timeout");
        } catch (IOException e) {
            return new RuleOutcome(rule.key(), false, null, Map.of(), "module-unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RuleOutcome(rule.key(), false, null, Map.of(), "module-unavailable");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return new RuleOutcome(rule.key(), false, null, Map.of(), "status-" + response.statusCode());
        }
        Map<String, Object> answer;
        try {
            Object parsed = JSON.readValue(response.body(), Object.class);
            answer = parsed instanceof Map<?, ?> ? object(parsed) : null;
        } catch (RuntimeException e) {
            answer = null;
        }
        if (answer == null) return new RuleOutcome(rule.key(), false, null, Map.of(), "invalid-json");
        Object decision = answer.get("decision");
        if (FlowRules.Decision.PASS.equals(decision)) return new RuleOutcome(rule.key(), true, null, Map.of(), null);
        if (FlowRules.Decision.BLOCK.equals(decision)) {
            if (!(answer.get("key") instanceof String key) || key.isBlank()) {
                return new RuleOutcome(rule.key(), false, null, Map.of(), "block-without-key");
            }
            Map<String, Object> params = answer.get("params") instanceof Map<?, ?> m ? object(m) : Map.of();
            return new RuleOutcome(rule.key(), false, key, params, null);
        }
        return new RuleOutcome(rule.key(), false, null, Map.of(), "unknown-decision");
    }

    /**
     * An event of the flow engine delivered to the module as the platform does (§16.3): a CloudEvent
     * {@code kf.<type>}, {@code source /flow-instance/{instanceId}}, {@code subject} = the order, {@code data}
     * unchanged, signed under the hook secret, with the context and a new {@code kfeventid}.
     *
     * @throws IllegalStateException without {@link #callbackBase}, a type that is not of the engine, when the manifest
     *                               given does not subscribe to it, or when the module is not live in the tenant
     */
    public Delivery deliverFlowEvent(String type, String tenantId, Map<String, ?> data) {
        return deliverFlowEvent(type, tenantId, data, UUID.randomUUID().toString());
    }

    /** {@link #deliverFlowEvent(String, String, Map)} with a given {@code kfeventid} (a redelivery). */
    public Delivery deliverFlowEvent(String type, String tenantId, Map<String, ?> data, String eventId) {
        if (!FlowEvents.TYPES.contains(type)) throw new IllegalStateException(type + " is not an event of the flow engine");
        requireSubscribed(type);
        requireLive(type, tenantId);
        Object instance = data == null ? null : data.get("instanceId");
        Object order = data == null ? null : data.get("orderId");
        Map<String, Object> ce = envelope(type, tenantId, eventId, "/flow-instance/" + instance,
                order == null ? null : String.valueOf(order), "flow-instance");
        ce.put("data", data == null ? Map.of() : new LinkedHashMap<>(data));
        return post(ce, tenantId, eventId, hookSecret);
    }

    /**
     * {@code order.field.changed} on {@code field} of the order {@code orderId} (§16.2): {@code data = {entity: order,
     * id, before: {field: old}, after: {field: new}, changed: [field], field, oldValue, newValue}}, delivered like
     * {@link #deliverFlowEvent}.
     */
    public Delivery deliverOrderFieldChanged(String tenantId, String orderId, String field, Object oldValue,
                                             Object newValue) {
        return deliverOrderFieldChanged(tenantId, orderId, field, oldValue, newValue, UUID.randomUUID().toString());
    }

    /** {@link #deliverOrderFieldChanged(String, String, String, Object, Object)} with a given {@code kfeventid}. */
    public Delivery deliverOrderFieldChanged(String tenantId, String orderId, String field, Object oldValue,
                                             Object newValue, String eventId) {
        requireSubscribed(HookEvents.ORDER_FIELD_CHANGED);
        requireLive(HookEvents.ORDER_FIELD_CHANGED, tenantId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put(field, oldValue);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(field, newValue);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", "order");
        data.put("id", orderId);
        data.put("before", before);
        data.put("after", after);
        data.put("changed", List.of(field));
        data.put("field", field);
        data.put("oldValue", oldValue);
        data.put("newValue", newValue);
        Map<String, Object> ce = envelope(HookEvents.ORDER_FIELD_CHANGED, tenantId, eventId, "/api/v1/orders/" + orderId,
                orderId, "order");
        ce.put("data", data);
        return post(ce, tenantId, eventId, hookSecret);
    }

    private static String lineKey(String tenant, String orderId, String source, String externalRef) {
        return tenant + "|" + orderId + "|" + source + "|" + externalRef;
    }

    /**
     * {@code order.services.v1} as the platform answers it: the capability, the context and its proof, the
     * delivery ({@code 422 not-delivered}), the user's {@code api.v1.orders.put} (a system context writes no line), the
     * body ({@code 400 validation}, {@code errors} by path), the provider ({@code 501 no-provider}); the line keyed on
     * {@code (orderId, slug, externalRef)}, {@code source} = this module, never the body's. A delete removes only this
     * module's line (an absent one is not an error); a delete that names only another module's line is refused,
     * {@code 409 order-service-line-owned-by-module}, the line untouched (10 Nov 2026).
     */
    private Object[] servicesAnswer(String method, List<String> rest, Map<String, String> params, Object body,
                                    String context, String proof) {
        boolean upsert = "POST".equals(method) && rest.size() == 1;
        boolean delete = "DELETE".equals(method) && rest.size() == 2;
        if (!upsert && !delete) throw new Refused(404, "not-found", "no such services route");
        String port = rest.get(0);
        declared(manifest != null && manifest.capabilities().services().stream().anyMatch(s -> port.equals(s.port())),
                "capabilities.services " + port);
        Call call = call(context, proof);
        if (!OrderServices.PORT.equals(port)) throw new Refused(404, "not-found", "the platform publishes no service " + port);
        if (servicesState == ServicesState.NOT_DELIVERED) {
            throw new Refused(422, "not-delivered", port + " is part of protocol 2.0 but this platform does not deliver it "
                    + "yet (reserved)");
        }
        if (call.system()) throw new Refused(403, "insufficient-rights", "a system context writes no service line");
        Set<String> held = userRights.get(call.tenant() + "|" + call.user());
        if (held != null && !held.contains("api.v1.orders.put")) {
            throw new Refused(403, "insufficient-rights", "The user does not hold api.v1.orders.put in the tenant");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (upsert) {
            Map<String, Object> b = body instanceof Map<?, ?> ? object(body) : Map.of();
            String orderId = idText(b.get("orderId"));
            String entryId = idText(b.get("entryId"));
            String name = b.get("name") instanceof String n ? n : null;
            String externalRef = b.get("externalRef") instanceof String r ? r : null;
            if (orderId == null) errors.put("orderId", "is required");
            if (entryId == null) errors.put("entryId", "is required");
            if (name == null || name.isBlank() || name.length() > OrderServices.MAX_NAME) {
                errors.put("name", "is required, 1 to " + OrderServices.MAX_NAME + " characters");
            }
            BigDecimal amount = null;
            String currency = null;
            if (!(b.get("cost") instanceof Map<?, ?> rawCost)) {
                errors.put("cost", "is required: {amount, currency}");
            } else {
                Map<String, Object> cost = object(rawCost);
                if (cost.get("amount") instanceof Number number) amount = new BigDecimal(number.toString());
                if (amount == null || amount.signum() < 0) errors.put("cost.amount", "must be a number >= 0");
                currency = cost.get("currency") instanceof String c ? c : null;
                if (currency == null || !OrderServices.CURRENCY.matcher(currency).matches()) {
                    errors.put("cost.currency", "must be an ISO 4217 code (three upper-case letters)");
                }
            }
            if (externalRef == null || !OrderServices.EXTERNAL_REF.matcher(externalRef).matches()) {
                errors.put("externalRef", "must match " + OrderServices.EXTERNAL_REF.pattern());
            }
            if (!errors.isEmpty()) throw new Refused(400, "validation", "The service line is invalid.", errors);
            if (servicesState == ServicesState.NO_PROVIDER) {
                throw new Refused(501, "no-provider", "nothing provides " + port + " on this platform");
            }
            serviceLines.put(lineKey(call.tenant(), orderId, slug, externalRef),
                    new ServiceLine(call.tenant(), orderId, entryId, name, amount, currency, externalRef, slug));
            return new Object[]{204, null};
        }
        String externalRef = URLDecoder.decode(rest.get(1), StandardCharsets.UTF_8);
        String orderId = params.get("orderId");
        if (orderId == null || orderId.isBlank()) errors.put("orderId", "is required (query parameter)");
        if (!OrderServices.EXTERNAL_REF.matcher(externalRef).matches()) {
            errors.put("externalRef", "must match " + OrderServices.EXTERNAL_REF.pattern());
        }
        if (!errors.isEmpty()) throw new Refused(400, "validation", "The service line is invalid.", errors);
        if (servicesState == ServicesState.NO_PROVIDER) {
            throw new Refused(501, "no-provider", "nothing provides " + port + " on this platform");
        }
        if (serviceLines.remove(lineKey(call.tenant(), orderId, slug, externalRef)) == null
                && serviceLines.values().stream().anyMatch(l -> l.tenant().equals(call.tenant())
                && l.orderId().equals(orderId) && l.externalRef().equals(externalRef) && !l.source().equals(slug))) {
            throw new Refused(409, "order-service-line-owned-by-module", "The line " + externalRef + " of order "
                    + orderId + " was written by another module; a module removes only its own lines.");
        }
        return new Object[]{204, null};
    }

    /** An id as text: a non-blank string or a whole number. */
    private static String idText(Object value) {
        if (value instanceof String s && !s.isBlank()) return s;
        if (value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger) return value.toString();
        return null;
    }

    /** The events the module emitted and the fake accepted: {@code {tenant, name, data, eventId, type}}, in order. */
    public List<Map<String, Object>> emittedEvents() {
        return List.copyOf(emittedEvents);
    }

    /** A feature flag the fake answers on {@code GET …/flags}. */
    public FakePlatform flag(String key, boolean enabled) {
        flags.put(key, enabled);
        return this;
    }

    /** The audit rows the module wrote (each with {@code tenant}). */
    public List<Map<String, Object>> auditRows() {
        return List.copyOf(audit);
    }

    /** The notification bodies the module sent (every one the fake received, refused ones included). */
    public List<Map<String, Object>> notifications() {
        return List.copyOf(notifications);
    }

    /**
     * The people each ACCEPTED notification reached, in order -- the subjects of {@code recipients}, then the active
     * members of {@code recipientRoles} in the notification's tenant, each person once. The platform never tells the
     * module (§11.1); the fake tells the test.
     */
    public List<List<String>> notificationRecipients() {
        return List.copyOf(notificationRecipients);
    }

    /**
     * An active member of {@code tenant} holding {@code roles} (protocol 1.3): what {@code recipientRoles} resolves
     * to. Declaring the subject again replaces its roles.
     */
    public FakePlatform member(String tenant, String subject, String... roles) {
        members.computeIfAbsent(tenant, t -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(subject, new Member(Set.of(roles), true));
        return this;
    }

    /** A member of {@code tenant} who is not active (a deactivated user): never reached through a role. */
    public FakePlatform inactiveMember(String tenant, String subject, String... roles) {
        members.computeIfAbsent(tenant, t -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(subject, new Member(Set.of(roles), false));
        return this;
    }

    /** The registration bodies the module sent. */
    public List<Map<String, Object>> registrations() {
        return List.copyOf(registrations);
    }

    /** Every request the module sent, in order. */
    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    /** A user's context for {@code method path} without a query and with an empty body. */
    public SignedContext signedContext(String tenant, String user, List<String> rights, String method, String path) {
        return signedContext(tenant, user, rights, method, path, null, new byte[0]);
    }

    /** A user's context; {@code rights} are the user's codes under {@code api.v1.m.<slug>.}; signed over {@code body}. */
    public SignedContext signedContext(String tenant, String user, List<String> rights, String method, String path,
                                       String query, byte[] body) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "user");
        actor.put("via", null);
        actor.put("hookId", null);
        return sign(tenant, user, actor, rights, method, path, query, body, null);
    }

    /**
     * A member's context for {@code method path} without a query and with an empty body, carrying {@code person}
     * (protocol 1.2 §9.2).
     */
    public SignedContext signedContext(String tenant, String user, List<String> rights, String method, String path,
                                       RequestContext.Person person) {
        return signedContext(tenant, user, rights, method, path, null, new byte[0], person);
    }

    /**
     * A user's context carrying the caller's {@code person} (protocol 1.2 §9.2): the last key, after
     * {@code requestId}, {@code {email, displayName}} (a null value is sent as JSON null); {@code person} null = the
     * context of {@link #signedContext(String, String, List, String, String, String, byte[])}. The platform sends
     * {@code person} only on the routes {@code audience: "member"}: when the manifest given declares the route
     * {@code method path} lands on and it is not one, this is refused ({@link IllegalArgumentException}).
     */
    public SignedContext signedContext(String tenant, String user, List<String> rights, String method, String path,
                                       String query, byte[] body, RequestContext.Person person) {
        if (person != null) {
            route(method, path).filter(r -> !"member".equals(r.audience())).ifPresent(r -> {
                throw new IllegalArgumentException("the platform puts person only on the routes audience: member; "
                        + r.method() + " " + r.path() + " is not one");
            });
        }
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "user");
        actor.put("via", null);
        actor.put("hookId", null);
        return sign(tenant, user, actor, rights, method, path, query, body, person);
    }

    /** The platform's own context ({@code system} via {@code platform}), as for a provided read (§15.5). */
    public SignedContext systemContext(String tenant, String method, String path) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "system");
        actor.put("via", "platform");
        actor.put("hookId", null);
        return sign(tenant, null, actor, List.of(), method, path, null, new byte[0], null);
    }

    /** A hook's context ({@code system} via {@code hook}), as for a {@code hookable} route (§9.4). */
    public SignedContext hookContext(String tenant, String hookId, String method, String path, byte[] body) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "system");
        actor.put("via", "hook");
        actor.put("hookId", hookId);
        return sign(tenant, null, actor, List.of(), method, path, null, body, null);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- signing ------------------------------------------------------------------------------------------

    private SignedContext sign(String tenant, String user, Map<String, Object> actor, List<String> rights, String method,
                               String path, String query, byte[] body, RequestContext.Person person) {
        long ts = Instant.now().getEpochSecond();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v", 1);
        json.put("tenant", tenant);
        json.put("user", user);
        json.put("actor", actor);
        json.put("locale", "en");
        json.put("rights", rights == null ? List.of() : rights.stream().sorted().toList());
        json.put("method", method);
        json.put("path", path);
        json.put("query", query);
        json.put("ts", ts);
        json.put("requestId", "fake-" + sequence.incrementAndGet());
        if (person != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("email", person.email());
            p.put("displayName", person.displayName());
            json.put("person", p);       // the last key
        }
        String context = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
        byte[] prefix = (context + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return new SignedContext(context, String.valueOf(ts), Signatures.sign(hookSecret, String.valueOf(ts), signed),
                proof(context));
    }

    private String proof(String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PROOF_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((slug + "." + context).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the platform's callbacks ---------------------------------------------------------------------------

    private void requireSubscribed(String point) {
        if (callbackBase == null) {
            throw new IllegalStateException("callbackBase(url) first: the fake delivers to {callbackBase}/_kf/hooks");
        }
        if (manifest != null && manifest.capabilities().hooks().stream().noneMatch(h -> point.equals(h.point()))) {
            throw new IllegalStateException("the manifest of " + slug + " does not subscribe to " + point
                    + ": the platform would not deliver it");
        }
    }

    /** The soft-delete and the restore reach only the modules live for the tenant (protocol 1.3 §6.1). */
    private void requireLive(String point, String tenantId) {
        if (notLive.contains(tenantId)) {
            throw new IllegalStateException(slug + " is not live for " + tenantId + ": the platform would not deliver "
                    + point + " to it");
        }
    }

    /**
     * A tenant point's CloudEvent: {@code data.after} is {@code null} on {@code tenant.after.delete} (the tenant no
     * longer exists), {@code {id}} on the soft-delete and the restore (it does).
     */
    private Delivery deliver(String point, String tenantId, String eventId, String secret) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", "tenant");
        data.put("id", tenantId);
        data.put("before", Map.of("id", tenantId));
        data.put("after", HookEvents.TENANT_AFTER_DELETE.equals(point) ? null : Map.of("id", tenantId));
        data.put("changed", List.of());
        Map<String, Object> ce = envelope(point, tenantId, eventId, "/tenant/" + tenantId, tenantId, "tenant");
        ce.put("data", data);
        return post(ce, tenantId, eventId, secret);
    }

    /** The CloudEvent of one delivery without its {@code data}, a new delivery {@code id}. */
    private Map<String, Object> envelope(String point, String tenantId, String eventId, String source, String subject,
                                         String aggregateType) {
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", UUID.randomUUID().toString());
        ce.put("kfeventid", eventId);
        ce.put("type", "kf." + point);
        ce.put("source", source);
        ce.put("subject", subject);
        ce.put("time", Instant.now().toString());
        ce.put("datacontenttype", "application/json");
        ce.put("kfmodule", slug);
        ce.put("kfflags", Map.of());
        ce.put("kftenant", tenantId);
        ce.put("kflocale", "en");
        ce.put("kfactor", null);
        ce.put("kfaggregatetype", aggregateType);
        ce.put("kfenvelopeversion", "1");
        return ce;
    }

    /**
     * Posts one delivery to {@code {callbackBase}/_kf/hooks}, signed under {@code secret}, with the context: {@code system via hook} ({@code hookId} = {@code requestId} = the delivery's {@code id}), no user, no
     * rights, the event's tenant, {@code ts} = now, and its proof for this module.
     */
    private Delivery post(Map<String, Object> ce, String tenantId, String eventId, String secret) {
        String deliveryId = String.valueOf(ce.get("id"));
        byte[] bytes = JSON.writeValueAsBytes(ce);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "system");
        actor.put("via", "hook");
        actor.put("hookId", deliveryId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v", 1);
        json.put("tenant", tenantId);
        json.put("user", null);
        json.put("actor", actor);
        json.put("locale", "en");
        json.put("rights", List.of());
        json.put("method", "POST");
        json.put("path", KfModule.DEFAULT_HOOK_PATH);
        json.put("query", null);
        json.put("ts", Long.parseLong(ts));
        json.put("requestId", deliveryId);
        String context = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
        String proof = proof(context);
        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackBase + KfModule.DEFAULT_HOOK_PATH))
                .header("Content-Type", "application/cloudevents+json")
                .header(RequestContext.TIMESTAMP_HEADER, ts)
                .header(RequestContext.SIGNATURE_HEADER, Signatures.sign(secret, ts, bytes))
                .header(RequestContext.CONTEXT_HEADER, context)
                .header(RequestContext.PROOF_HEADER, proof)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Delivery(response.statusCode(), response.body(), eventId, deliveryId, context, proof);
        } catch (IOException e) {
            throw new IllegalStateException("the module did not answer the delivery: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- the platform's answers ---------------------------------------------------------------------------

    /** Thrown inside a handler (here and in {@link FakeStorage}): the problem to answer. */
    static final class Refused extends RuntimeException {
        final int status;
        final String reason;
        final Map<String, String> errors;
        final String errorClass;
        /** The problem {@code type} when it is not {@code …/problems/<reason>}; the body then carries {@code reason}. */
        String type;

        Refused(int status, String reason, String detail) {
            this(status, reason, detail, null);
        }

        Refused(int status, String reason, String detail, Map<String, String> errors) {
            this(status, reason, detail, errors, "permanent");
        }

        Refused(int status, String reason, String detail, Map<String, String> errors, String errorClass) {
            super(detail);
            this.status = status;
            this.reason = reason;
            this.errors = errors;
            this.errorClass = errorClass;
        }
    }

    /** A member of a tenant (protocol 1.3): its roles and whether it is active. */
    private record Member(Set<String> roles, boolean active) {
    }

    /** The checked context of a call; {@code method} and {@code path} are the route it was issued for. */
    private record Call(String tenant, String user, boolean system, String hookId, List<String> rights, String requestId,
                        String method, String path) {
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String rawPath = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            byte[] raw = exchange.getRequestBody().readAllBytes();
            if (rawPath.startsWith(FakeStorage.OBJECT_PATH)) {
                storage.serve(exchange, raw);   // a presigned PUT or GET: S3's place, not a call of §15
                return;
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            Object body = text.isBlank() ? null : JSON.readValue(text, Object.class);
            String context = exchange.getRequestHeaders().getFirst(RequestContext.CONTEXT_HEADER);
            String proof = exchange.getRequestHeaders().getFirst(RequestContext.PROOF_HEADER);
            requests.add(new Recorded(method, rawPath, query, context, proof, body));
            int status;
            Object answer;
            try {
                if (!rawPath.startsWith(PREFIX)) throw new Refused(404, "not-found", "no such route " + rawPath);
                List<String> parts = new ArrayList<>(List.of(rawPath.substring(PREFIX.length()).split("/", -1)));
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8))) {
                    throw new Refused(401, "invalid-token", "the module token is not valid");
                }
                if (parts.size() < 2 || !parts.get(0).equals(slug)) {
                    throw new Refused(403, "forbidden", "the token does not belong to " + (parts.isEmpty() ? "" : parts.get(0)));
                }
                String service = parts.get(1);
                List<String> rest = parts.subList(2, parts.size());
                Map<String, String> params = params(query);
                Object[] result = switch (service) {
                    case "registrations" -> {
                        Map<String, Object> registration = object(body);
                        registrations.add(registration);
                        slotErrors(registration);
                        yield new Object[]{200, Map.of("warnings", slotWarnings(registration))};
                    }
                    case "flags" -> {
                        List<Map<String, Object>> list = new ArrayList<>();
                        flags.forEach((k, v) -> list.add(Map.of("key", k, "enabled", v)));
                        yield new Object[]{200, list};
                    }
                    case "notifications" -> notificationsAnswer(object(body));
                    case "reads" -> reads(method, rest, params, context, proof);
                    case "settings" -> settingsAnswer(context, proof);
                    case "secrets" -> secretsAnswer(method, rest, object(body), context, proof);
                    case "extensions" -> extensionsAnswer(method, rest, object(body), context, proof);
                    case "audit" -> auditAnswer(method, params, object(body), context, proof);
                    case "storage" -> storageAnswer(method, rest, body, context, proof);
                    case "peers" -> peersAnswer(method, rest, params, body, text, context, proof,
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    case "events" -> eventsAnswer(method, rest, body, text, context, proof);
                    case "services" -> servicesAnswer(method, rest, params, body, context, proof);
                    default -> throw new Refused(404, "not-found", "no such service " + service);
                };
                status = (Integer) result[0];
                answer = result[1];
            } catch (Refused refused) {
                status = refused.status;
                Map<String, Object> problem = new LinkedHashMap<>();
                problem.put("type", "https://kf.ro/problems/" + refused.reason);
                problem.put("title", refused.reason);
                problem.put("status", refused.status);
                problem.put("detail", refused.getMessage());
                problem.put("class", refused.errorClass);
                if (refused.errors != null) problem.put("errors", refused.errors);
                if (refused.type != null) {
                    problem.put("type", refused.type);        // the platform's type, with the reason as a key (§11.1)
                    problem.put("reason", refused.reason);
                }
                answer = problem;
            }
            if (status == 204) {      // no body (order.services.v1)
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            byte[] bytes = JSON.writeValueAsString(answer).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** The context of a §15 call: the proof (an HMAC of the fake), {@code ts} within ±300 s, the module live there. */
    private Call call(String context, String proof) {
        return call(context, proof, false);
    }

    /**
     * {@link #call(String, String)}; with {@code platformRouteAccepted}, a user's context issued for an
     * {@code admin: platform} route is let in although the module is not live in its tenant ({@code ModuleCallGuard}).
     */
    private Call call(String context, String proof, boolean platformRouteAccepted) {
        if (context == null || proof == null || !MessageDigest.isEqual(proof(context).getBytes(StandardCharsets.UTF_8),
                proof.getBytes(StandardCharsets.UTF_8))) {
            throw new Refused(401, "invalid-context", "the context was not issued by the platform for " + slug);
        }
        Map<String, Object> json;
        try {
            json = object(JSON.readValue(new String(Base64.getUrlDecoder().decode(context), StandardCharsets.UTF_8), Object.class));
        } catch (RuntimeException e) {
            throw new Refused(401, "invalid-context", "the context is not base64url of a JSON object");
        }
        long ts = json.get("ts") instanceof Number n ? n.longValue() : 0;
        if (Math.abs(Instant.now().getEpochSecond() - ts) > Signatures.MAX_SKEW_SECONDS) {
            throw new Refused(401, "invalid-context", "the context is older than 300 s");
        }
        String tenant = String.valueOf(json.get("tenant"));
        Map<String, Object> actor = object(json.get("actor"));
        boolean system = actor != null && "system".equals(actor.get("type"));
        List<String> rights = new ArrayList<>();
        if (json.get("rights") instanceof List<?> list) list.forEach(r -> rights.add(String.valueOf(r)));
        Call call = new Call(tenant, json.get("user") == null ? null : String.valueOf(json.get("user")), system,
                actor == null || actor.get("hookId") == null ? null : String.valueOf(actor.get("hookId")), rights,
                String.valueOf(json.get("requestId")),
                json.get("method") instanceof String m ? m : null, json.get("path") instanceof String p ? p : null);
        if (notLive.contains(tenant) && !(platformRouteAccepted && isPlatformAdminRoute(call))) {
            throw new Refused(403, "module-not-live", slug + " is not live for the tenant");
        }
        return call;
    }

    /** A user's context whose {@code method} + {@code path} land on a route the manifest declares {@code admin: platform}. */
    private boolean isPlatformAdminRoute(Call call) {
        if (call.system() || call.method() == null || call.path() == null) return false;
        return route(call.method(), call.path()).map(r -> "platform".equals(r.admin())).orElse(false);
    }

    /**
     * The manifest's route a context's {@code method} + {@code path} land on, as {@code DeclaredModuleRoutes.match}:
     * the path after {@code /m/{slug}}, a {@code {param}} segment matching any non-empty segment, the route with the
     * most literal segments winning. Empty without a manifest.
     */
    private Optional<Manifest.RouteCapability> route(String method, String path) {
        if (manifest == null || method == null || path == null) return Optional.empty();
        String[] requested = segments(path);
        Manifest.RouteCapability best = null;
        int bestLiterals = -1;
        for (Manifest.RouteCapability route : manifest.capabilities().routes()) {
            if (route.method() == null || route.path() == null || !route.method().equalsIgnoreCase(method)) continue;
            String template = route.path();
            String own = "/m/" + slug;
            if (template.equals(own) || template.startsWith(own + "/")) template = template.substring(own.length());
            String[] expected = segments(template);
            if (expected.length != requested.length) continue;
            int literals = 0;
            boolean matches = true;
            for (int i = 0; i < requested.length && matches; i++) {
                if (PARAM.matcher(expected[i]).matches()) {
                    matches = !requested[i].isEmpty();
                } else {
                    matches = expected[i].equals(requested[i]);
                    literals++;
                }
            }
            if (matches && literals > bestLiterals) {
                best = route;
                bestLiterals = literals;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String[] segments(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/", -1);
    }

    private void declared(boolean declared, String capability) {
        if (manifest != null && !declared) {
            throw new Refused(403, "capability-not-declared", "The active version of " + slug + " does not declare "
                    + capability);
        }
    }

    private Object[] reads(String method, List<String> rest, Map<String, String> params, String context, String proof) {
        if (!"GET".equals(method) || rest.isEmpty() || rest.size() > 2) throw new Refused(404, "not-found", "no such read");
        String portId = rest.get(0);
        declared(manifest != null && manifest.capabilities().reads().stream().anyMatch(r -> portId.equals(r.port())),
                "capabilities.reads " + portId);
        Call call = call(context, proof);
        Port port = reads(portId);
        boolean object = rest.size() == 2;
        boolean tenantPort = TENANT_PORT.equals(portId);
        // protocol 1.3: the platform's actor -- its own context, or a user on an admin: platform route
        boolean platformActor = (call.system() && call.hookId() == null) || isPlatformAdminRoute(call);
        boolean platformList = tenantPort && !object && platformActor;
        if (call.system() && !platformList) {
            if (!port.rule.system()) throw new Refused(403, "insufficient-rights", "the system context is not accepted on " + portId);
            if (!object) throw new Refused(403, "insufficient-rights", "the system context reads only objects");
            if (params.containsKey("include")) throw new Refused(403, "insufficient-rights", "the system context reads only the base fields");
        } else if (port.rule.right() != null) {
            Set<String> held = userRights.get(call.tenant() + "|" + call.user());
            if (held != null && !held.contains(port.rule.right())) {
                throw new Refused(403, "insufficient-rights", "The user does not hold " + port.rule.right() + " in the tenant");
            }
        }
        Refusal failure = readFailures.get(portId);
        if (failure != null) {
            throw new Refused(failure.status(), failure.reason(), failure.detail(), null, failure.errorClass());
        }
        Map<String, Map<String, Object>> held = platformList ? port.everywhere() : port.visible(call.tenant());
        Map<String, Map<String, Object>> visible = tenantPort ? withDeletedAt(held) : held;
        if (tenantPort && !object && !platformActor && params.getOrDefault("ids", "").isBlank()) {
            invalidIfAny(Map.of("ids", "required"));   // the platform's TenantReadProvider, for anyone else
        }
        if (object) {
            Map<String, Object> found = visible.get(URLDecoder.decode(rest.get(1), StandardCharsets.UTF_8));
            if (found == null) throw new Refused(404, "not-found", portId + " " + rest.get(1) + " not found");
            return new Object[]{200, found};
        }
        int page = integer(params.getOrDefault("page", "0"), "page");
        int size = integer(params.getOrDefault("size", "20"), "size");
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must be at least 0");
        if (size < 1 || size > 200) errors.put("size", "must be between 1 and 200");
        if (!errors.isEmpty()) throw new Refused(400, "validation", "invalid paging", errors);
        BiPredicate<String, Map<String, Object>> keep =
                LIST_RULES.getOrDefault(portId, FakePlatform::equalityFilter).accept(filtersOf(params));
        List<Map<String, Object>> matching = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : visible.entrySet()) {
            if (keep.test(e.getKey(), e.getValue())) matching.add(e.getValue());
        }
        List<String> order = new ArrayList<>(visible.keySet());
        order.sort(FakePlatform::compareIds);
        matching.sort(Comparator.comparingInt(o -> order.indexOf(idOf(visible, o))));
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("items", matching.subList(from, to));
        answer.put("page", page);
        answer.put("size", size);
        answer.put("total", matching.size());
        return new Object[]{200, answer};
    }

    /**
     * The list rule of a read port: checks the port's filters (a {@code 400} on a refused one) and gives which visible
     * objects ({@code (id, object)}) the list keeps.
     */
    @FunctionalInterface
    private interface ListFilter {
        BiPredicate<String, Map<String, Object>> accept(Map<String, String> filters);
    }

    /** {@code tenant.v1} (protocol 1.2; listable by the platform's actor and carrying {@code deletedAt} from 1.3). */
    private static final String TENANT_PORT = "tenant.v1";

    /** The tenants with {@code deletedAt} (protocol 1.3): {@code null} unless the test put one. */
    private static Map<String, Map<String, Object>> withDeletedAt(Map<String, Map<String, Object>> tenants) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        tenants.forEach((id, tenant) -> {
            Map<String, Object> copy = new LinkedHashMap<>(tenant);
            copy.putIfAbsent("deletedAt", null);
            out.put(id, copy);
        });
        return out;
    }

    /** The ports whose lists keep the platform's own filter rules (protocol 1.2.1; 1.3.1, delivered). */
    private static final Map<String, ListFilter> LIST_RULES = Map.of(
            "component.v1", FakePlatform::componentFilter,
            "inventory-roll.v1", FakePlatform::rollFilter,
            "model.v1", FakePlatform::modelFilter,
            "model-version.garment-image.v1", FakePlatform::garmentImageFilter);
    /** The most values of {@code ids} (§15.1; the platform's {@code ModuleReadFilters.MAX_IDS}). */
    private static final int MAX_IDS = 200;
    private static final List<String> COMPONENT_TYPES = List.of("ACCESSORY", "MATERIAL", "PACKAGING");

    /** The filters of a list request: everything but the paging and {@code include}. */
    private static Map<String, String> filtersOf(Map<String, String> params) {
        Map<String, String> filters = new LinkedHashMap<>(params);
        filters.keySet().removeAll(List.of("page", "size", "include"));
        return filters;
    }

    /** Any port without its own rule: equality on the object's fields, {@code ids} among the ids given. */
    private static BiPredicate<String, Map<String, Object>> equalityFilter(Map<String, String> filters) {
        return (id, object) -> {
            for (Map.Entry<String, String> f : filters.entrySet()) {
                if (f.getKey().equals("ids")) {
                    if (!List.of(f.getValue().split(",")).contains(id)) return false;
                } else if (!f.getValue().equals(String.valueOf(object.get(f.getKey())))) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * {@code component.v1} (as the platform's {@code ComponentReadProvider}): {@code ids} or {@code q}, one of
     * them, never both; {@code q} trimmed, 2 to 100 characters, the name contains it (any case); {@code componentType}
     * one value among {@link #COMPONENT_TYPES}. The checks in the platform's order.
     */
    private static BiPredicate<String, Map<String, Object>> componentFilter(Map<String, String> filters) {
        Map<String, String> errors = unknownFilters(filters, "ids", "q", "componentType");
        Set<Long> ids = ids(filters.get("ids"), "ids", errors);
        List<String> types = new ArrayList<>();
        String rawTypes = filters.get("componentType");
        if (rawTypes != null && !rawTypes.isBlank()) {
            for (String part : rawTypes.split(",", -1)) {
                String code = part.trim().toUpperCase(Locale.ROOT);
                if (!COMPONENT_TYPES.contains(code)) {
                    errors.put("componentType", "comma-separated values among " + COMPONENT_TYPES);
                    types.clear();
                    break;
                }
                if (!types.contains(code)) types.add(code);
            }
        }
        invalidIfAny(errors);
        String q = filters.get("q");
        boolean hasIds = filters.containsKey("ids") && !filters.get("ids").isBlank();
        if (types.size() > 1) invalidIfAny(Map.of("componentType", "one value"));
        if (q != null && hasIds) invalidIfAny(Map.of("q", "not with ids: either the ids or the search"));
        if (q == null && !hasIds) invalidIfAny(Map.of("q", "required (or ids)"));
        String type = types.isEmpty() ? null : types.get(0);
        if (hasIds) {
            return (id, object) -> ids.contains(parseId(id)) && (type == null || type.equals(object.get("componentType")));
        }
        String text = q.trim();
        if (text.length() < 2 || text.length() > 100) invalidIfAny(Map.of("q", "between 2 and 100 characters"));
        String lower = text.toLowerCase(Locale.ROOT);
        return (id, object) -> String.valueOf(object.get("name")).toLowerCase(Locale.ROOT).contains(lower)
                && (type == null || type.equals(object.get("componentType")));
    }

    /**
     * {@code inventory-roll.v1} (as the platform's {@code InventoryRollReadProvider}): {@code componentId} or
     * {@code ids}, one of them; with both, the material's rolls among the ids.
     */
    private static BiPredicate<String, Map<String, Object>> rollFilter(Map<String, String> filters) {
        Map<String, String> errors = unknownFilters(filters, "componentId", "ids");
        Long componentId = null;
        String rawComponent = filters.get("componentId");
        if (rawComponent != null && !rawComponent.isBlank()) {
            componentId = parseId(rawComponent);
            if (componentId == null) errors.put("componentId", "a positive integer");
        }
        Set<Long> ids = ids(filters.get("ids"), "ids", errors);
        invalidIfAny(errors);
        if (componentId == null && ids.isEmpty()) invalidIfAny(Map.of("componentId", "required (or ids)"));
        Long material = componentId;
        return (id, object) -> (material == null || material.equals(parseId(String.valueOf(object.get("componentId")))))
                && (ids.isEmpty() || ids.contains(parseId(id)));
    }

    /**
     * {@code model.v1} (protocol 1.3.1, delivered): {@code ids} only; without it, every model visible in the tenant
     * (the system context never lists, checked before). A customer user's {@code 400 ids: required} is the platform's.
     */
    private static BiPredicate<String, Map<String, Object>> modelFilter(Map<String, String> filters) {
        Map<String, String> errors = unknownFilters(filters, "ids");
        Set<Long> ids = ids(filters.get("ids"), "ids", errors);
        invalidIfAny(errors);
        return (id, object) -> ids.isEmpty() || ids.contains(parseId(id));
    }

    /**
     * {@code model-version.garment-image.v1} (protocol 1.3.1, delivered): {@code ids} only, mandatory (at most
     * {@value #MAX_IDS}); the versions of other tenants are absent.
     */
    private static BiPredicate<String, Map<String, Object>> garmentImageFilter(Map<String, String> filters) {
        Map<String, String> errors = unknownFilters(filters, "ids");
        Set<Long> ids = ids(filters.get("ids"), "ids", errors);
        invalidIfAny(errors);
        if (ids.isEmpty()) invalidIfAny(Map.of("ids", "required"));
        return (id, object) -> ids.contains(parseId(id));
    }

    /** Every filter outside {@code accepted} as {@code "unknown filter"}, in the order given. */
    private static Map<String, String> unknownFilters(Map<String, String> filters, String... accepted) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (String name : filters.keySet()) {
            if (!List.of(accepted).contains(name)) errors.put(name, "unknown filter");
        }
        return errors;
    }

    /** {@code ids}: comma-separated positive integers, at most {@value #MAX_IDS}; empty when absent or blank. */
    private static Set<Long> ids(String raw, String name, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) return Set.of();
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_IDS) {
            errors.put(name, "at most " + MAX_IDS + " values, comma-separated (got " + parts.length + ")");
            return Set.of();
        }
        Set<Long> ids = new TreeSet<>();
        for (String part : parts) {
            Long id = parseId(part);
            if (id == null) {
                errors.put(name, "a comma-separated list of positive integers");
                return Set.of();
            }
            ids.add(id);
        }
        return ids;
    }

    /**
     * A positive integer id (as the platform's {@code ModuleReadFilters.parseId}), else null; a fixture's number
     * written as {@code 11.0} reads as 11.
     */
    private static Long parseId(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        if (text.isEmpty() || text.length() > 18 || !text.chars().allMatch(Character::isDigit)) return null;
        long value = Long.parseLong(text);
        return value > 0 ? value : null;
    }

    /** The platform's {@code 400} of the filters ({@code InvalidReadRequestException}) when {@code errors} has any. */
    private static void invalidIfAny(Map<String, String> errors) {
        if (errors.isEmpty()) return;
        StringBuilder detail = new StringBuilder("The read request is not valid (invalid filters): ");
        String separator = "";
        for (Map.Entry<String, String> e : errors.entrySet()) {
            detail.append(separator).append(e.getKey()).append(": ").append(e.getValue());
            separator = "; ";
        }
        throw new Refused(400, "validation", detail.append('.').toString(), new LinkedHashMap<>(errors));
    }

    /** {@code POST …/storage/{upload-url, download-url}} (§15.7): the common checks here, the rules in {@link FakeStorage}. */
    // ---- between modules (protocol 2.0, §15.9) and the module events (§6.3) ----------------------------------

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    /** {@code GET …/peers/{owner}/reads/{port}} and {@code POST …/peers/{owner}/commands/{name}}, in the platform's order. */
    private Object[] peersAnswer(String method, List<String> rest, Map<String, String> params, Object body, String text,
                                 String context, String proof, String key) {
        boolean read = rest.size() == 3 && "reads".equals(rest.get(1)) && "GET".equals(method);
        boolean command = rest.size() == 3 && "commands".equals(rest.get(1)) && "POST".equals(method);
        if (!read && !command) throw new Refused(404, "not-found", "no such call between modules");
        String owner = rest.get(0);
        String name = URLDecoder.decode(rest.get(2), StandardCharsets.UTF_8);
        // (1) the declaration, the context, the module live; for a command, the key
        declared(manifest != null && manifest.capabilities().peers().stream().anyMatch(p -> owner.equals(p.owner())
                && (read ? p.reads() : p.commands()).contains(name)), "capabilities.peers " + owner + " " + name);
        Call call = call(context, proof);
        if (command && (key == null || !IDEMPOTENCY_KEY.matcher(key).matches())) {
            throw new Refused(400, "idempotency-key-required", "Idempotency-Key is missing or does not match "
                    + IDEMPOTENCY_KEY.pattern());
        }
        // (2) the owner in requires
        if (manifest != null && manifest.requires().stream().noneMatch(r -> owner.equals(r.slug()))) {
            throw new Refused(403, "peer-not-required", owner + " is not in the requires of " + slug);
        }
        // (3) the owner installed, running
        Peer peer = peers.get(owner);
        if (peer == null || peer.absent) {
            throw new Refused(503, "module-unavailable", "The module " + owner + " is not installed in the required range.");
        }
        if (peer.stopped) {
            throw new Refused(503, "module-unavailable", "The module " + owner + " is not available now.", null, "transient");
        }
        // (4) offered to this caller: an internal one only to the internal class, else exactly as unknown
        Offer offer = (read ? peer.reads : peer.commands).get(name);
        if (offer == null || ("internal".equals(offer.visibility()) && !internalClass)) {
            throw read ? new Refused(404, "peer-port-unknown", "The owner does not offer this port.")
                    : new Refused(404, "peer-command-unknown", "The owner does not offer this command.");
        }
        // (5) a user's right of the owner's route
        if (!call.system() && offer.right() != null) {
            Set<String> held = userRights.get(call.tenant() + "|" + call.user());
            if (held != null && !held.contains(offer.right())) {
                throw new Refused(403, "peer-forbidden", "The user does not hold the right of the owner's route.");
            }
        }
        PeerCall peerCall = new PeerCall(read ? "read" : "command", name, call.tenant(), call.user(),
                call.system() ? "system" : "user", "module:" + slug, read ? Map.copyOf(params) : Map.of(),
                read ? null : body, read ? null : key);
        if (read) return forward(peer, offer, peerCall);
        if (text.isBlank() || body == null) throw new Refused(400, "validation", "The command body is not JSON.");
        String slot = call.tenant() + "|" + owner + "|" + name + "|" + key;
        String fingerprint = canonical(body);
        Object[] stored = commandKeys.get(slot);
        if (stored != null) {
            if (!fingerprint.equals(stored[0])) {
                throw new Refused(409, "idempotency-conflict", "The key was used with another body.");
            }
            if (stored[1] instanceof Refused refused) throw refused;   // a stored refusal is replayed as the same refusal
            return (Object[]) stored[1];
        }
        try {
            Object[] answer = forward(peer, offer, peerCall);
            commandKeys.put(slot, new Object[]{fingerprint, answer});
            return answer;
        } catch (Refused refused) {
            if (!"transient".equals(refused.errorClass)) commandKeys.put(slot, new Object[]{fingerprint, refused});
            throw refused;   // a transient failure frees the key: a retry executes
        }
    }

    /** Calls the owner's handler and maps its answer as the platform does (§15.9). */
    private static Object[] forward(Peer peer, Offer offer, PeerCall call) {
        PeerReply reply;
        try {
            peer.calls.add(call);
            reply = offer.handler().handle(call);
        } catch (RuntimeException e) {
            throw new Refused(503, "module-unavailable", "The module " + peer.slug + " did not answer.", null, "transient");
        }
        int status = reply == null ? 500 : reply.status();
        if (status >= 200 && status <= 299) return new Object[]{status, reply.body() == null ? Map.of() : reply.body()};
        if (status == 404) throw new Refused(404, "not-configured", "The owner has nothing configured for this call.");
        if (status >= 400 && status <= 499) throw new Refused(422, "module-refused", "The owner refused the call.");
        throw new Refused(503, "module-unavailable", "The module " + peer.slug + " is not available now.", null, "transient");
    }

    /** The body's canonical JSON (keys sorted at every level): its fingerprint for the idempotency key. */
    private static String canonical(Object body) {
        return JSON.writeValueAsString(sorted(body));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(FakePlatform::sorted).toList();
        return value;
    }

    /** {@code POST …/events {name, data}} → {@code 202 {eventId, type}} (§6.3). */
    private Object[] eventsAnswer(String method, List<String> rest, Object body, String text, String context, String proof) {
        if (!"POST".equals(method) || !(rest.isEmpty() || (rest.size() == 1 && rest.get(0).isEmpty()))) {
            throw new Refused(404, "not-found", "no such route");
        }
        Call call = call(context, proof);
        Map<String, Object> sent = body instanceof Map<?, ?> ? object(body) : null;
        if (sent == null || !(sent.get("name") instanceof String name) || !(sent.get("data") instanceof Map<?, ?>)) {
            throw new Refused(400, "validation", "The body must be {name: text, data: object}.");
        }
        if (JSON.writeValueAsString(sent.get("data")).getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new Refused(400, "validation", "data is over 64 KiB.");
        }
        if (manifest != null && manifest.capabilities().events().stream().noneMatch(e -> name.equals(e.name()))) {
            Refused refused = new Refused(422, "event-not-declared", "The active version of " + slug
                    + " does not declare the event " + name + ".");
            throw refused;
        }
        String eventId = UUID.randomUUID().toString();
        String type = HookEvents.moduleEventPoint(slug, name);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tenant", call.tenant());
        event.put("name", name);
        event.put("data", sent.get("data"));
        event.put("eventId", eventId);
        event.put("type", type);
        emittedEvents.add(event);
        return new Object[]{202, Map.of("eventId", eventId, "type", type)};
    }

    private Object[] storageAnswer(String method, List<String> rest, Object body, String context, String proof) {
        if (!"POST".equals(method) || rest.size() != 1
                || !(rest.get(0).equals("upload-url") || rest.get(0).equals("download-url"))) {
            throw new Refused(404, "not-found", "no such storage route");
        }
        Manifest.StorageCapability declared = manifest == null ? null
                : manifest.capabilities().storage().stream().findFirst().orElse(null);
        declared(declared != null, "capabilities.storage");
        if (rest.get(0).equals("upload-url")) {
            Call call = call(context, proof);
            return new Object[]{200, storage.uploadUrl(call.tenant(), declared, body)};
        }
        Call call = call(context, proof, true);
        return new Object[]{200, storage.downloadUrl(call.tenant(), isPlatformAdminRoute(call), body)};
    }

    /**
     * {@code POST …/notifications} (§11.1; protocol 1.3 for {@code recipientRoles}): the body recorded, then the
     * people resolved -- the subjects of {@code recipients} as given, then the active members of each role of
     * {@code recipientRoles} in {@code tenantId} ({@link #member}), each person once. {@code 422 unknown-role} on a
     * role outside {@link #GRANTABLE_ROLES}, {@code 422 no-recipients} when neither names anyone, {@code 422
     * too-many-recipients} over 100 subjects or over {@value #MAX_RESOLVED_RECIPIENTS} people. The answer counts
     * one row per person ({@code accepted}), and names no one.
     */
    private Object[] notificationsAnswer(Map<String, Object> sent) {
        notifications.add(sent == null ? Map.of() : sent);
        if (sent == null) throw notificationRefused("invalid-request", "the notification has no body");
        List<String> subjects = new ArrayList<>();
        if (sent.get("recipients") instanceof List<?> l) l.forEach(r -> subjects.add(String.valueOf(r)));
        List<String> roles = new ArrayList<>();
        if (sent.get("recipientRoles") instanceof List<?> l) l.forEach(r -> roles.add(String.valueOf(r)));
        for (String role : roles) {
            if (!GRANTABLE_ROLES.contains(role)) {
                throw notificationRefused("unknown-role", "'" + role + "' is not a role a module may address (one of "
                        + String.join(", ", GRANTABLE_ROLES) + ").");
            }
        }
        if (subjects.isEmpty() && roles.isEmpty()) throw notificationRefused("no-recipients", "recipients names no one.");
        if (subjects.size() > 100) throw notificationRefused("too-many-recipients", "at most 100 recipients.");
        Set<String> people = new LinkedHashSet<>(subjects);
        if (!roles.isEmpty()) {
            Map<String, Member> ofTenant = members.getOrDefault(String.valueOf(sent.get("tenantId")), Map.of());
            synchronized (ofTenant) {
                ofTenant.forEach((subject, member) -> {
                    if (member.active() && member.roles().stream().anyMatch(roles::contains)) people.add(subject);
                });
            }
        }
        if (people.size() > MAX_RESOLVED_RECIPIENTS) {
            throw notificationRefused("too-many-recipients", "the roles resolve to " + people.size()
                    + " people, at most " + MAX_RESOLVED_RECIPIENTS + ".");
        }
        notificationRecipients.add(List.copyOf(people));
        return new Object[]{202, Map.of("accepted", people.size(), "duplicates", 0)};
    }

    /** The platform's refusal of a notification: {@code 422}, type {@code notification-rejected}, the {@code reason}. */
    private static Refused notificationRefused(String reason, String detail) {
        Refused refused = new Refused(422, reason, detail);
        refused.type = "https://kf.ro/problems/notification-rejected";
        return refused;
    }

    private Object[] settingsAnswer(String context, String proof) {
        declared(manifest != null && !manifest.capabilities().settings().isEmpty(), "capabilities.settings");
        Call call = call(context, proof);
        Map<String, Object> values = new LinkedHashMap<>();
        if (manifest != null) manifest.capabilities().settings().forEach(s -> values.put(s.key(), s.defaultValue()));
        values.putAll(settings.getOrDefault(call.tenant(), Map.of()));
        return new Object[]{200, values};
    }

    private Object[] secretsAnswer(String method, List<String> rest, Map<String, Object> body, String context, String proof) {
        if (rest.size() != 1) throw new Refused(404, "not-found", "no such secret route");
        if ("POST".equals(method) && rest.get(0).equals("resolve")) {
            declared(manifest != null && !manifest.capabilities().secrets().isEmpty(), "capabilities.secrets");
            Call call = call(context, proof);
            String ref = body == null ? null : (String) body.get("ref");
            String own = "vault://platform/kf-modules/" + slug + "/";
            if (ref != null && !ref.startsWith(own) && externalDeclared(ref)) {
                // An existing reference under a declared secrets[].external prefix.
                secretStoreAnswering();
                String value = secrets.get(ref);
                if (value == null) throw new Refused(404, "not-found", "no secret behind the reference");
                return new Object[]{200, Map.of("value", value)};
            }
            if (ref != null && !ref.startsWith(own) && !ref.contains("/kf-modules/") && !ref.contains("/../")) {
                throw new Refused(403, "external-ref-not-declared", "the reference is under no declared prefix");
            }
            if (ref == null || !ref.startsWith(own)) throw new Refused(403, "forbidden", "not a reference of " + slug);
            String scope = ref.substring(own.length()).split("/")[0];
            if (!scope.equals("platform") && !scope.equals(call.tenant())) {
                throw new Refused(403, "forbidden", "a reference of another tenant");
            }
            secretStoreAnswering();
            String value = secrets.get(ref);
            if (value == null) throw new Refused(404, "not-found", "no secret behind the reference");
            return new Object[]{200, Map.of("value", value)};
        }
        if (!"PUT".equals(method)) throw new Refused(404, "not-found", "no such secret route");
        String name = rest.get(0);
        Manifest.SecretCapability declaration = manifest == null ? null
                : manifest.capabilities().secrets().stream().filter(s -> name.equals(s.name())).findFirst().orElse(null);
        declared(declaration != null, "capabilities.secrets " + name);
        Call call = call(context, proof);
        if (declaration != null && !call.rights().contains(declaration.writeRight())) {
            throw new Refused(403, "insufficient-rights", "the context does not hold " + declaration.writeRight());
        }
        if (body == null || !(body.get("value") instanceof String value) || value.isEmpty()) {
            throw new Refused(400, "validation", "the value is required", Map.of("value", "required"));
        }
        boolean platform = declaration != null && "platform".equals(declaration.scope());
        String ref = secretRef(platform ? null : call.tenant(), name);
        secretStoreAnswering();
        secrets.put(ref, value);
        return new Object[]{200, Map.of("ref", ref)};
    }

    /** {@code ref} strictly under a {@code secrets[].external} prefix of a platform secret of the manifest. */
    private boolean externalDeclared(String ref) {
        if (manifest == null || !ref.startsWith("vault://") || ref.contains("/../") || ref.indexOf("//", 8) >= 0) return false;
        String path = ref.substring("vault://".length());
        return manifest.capabilities().secrets().stream()
                .filter(secret -> "platform".equals(secret.scope()))
                .flatMap(secret -> secret.external().stream())
                .anyMatch(prefix -> path.startsWith(prefix) && path.length() > prefix.length());
    }

    /** Throws the secret store's 503 when {@link #secretStoreDown} or {@link #secretStoreRejects} is on. */
    private void secretStoreAnswering() {
        String reason = secretStore;
        if (reason == null) return;
        throw new Refused(503, reason, "secret-store-rejected".equals(reason)
                ? "The secret backend refused the path: its policy does not allow it."
                : "The secret backend did not answer.", null,
                "secret-store-rejected".equals(reason) ? "permanent" : "transient");
    }

    private Object[] extensionsAnswer(String method, List<String> rest, Map<String, Object> body, String context, String proof) {
        if (!"PATCH".equals(method) || rest.size() != 2 || !ENTITIES.contains(rest.get(0))) {
            throw new Refused(404, "not-found", "no such extension route");
        }
        String entity = rest.get(0);
        String id = URLDecoder.decode(rest.get(1), StandardCharsets.UTF_8);
        declared(manifest != null && manifest.capabilities().fields().stream().anyMatch(f -> entity.equals(f.entity())),
                "capabilities.fields for " + entity);
        Call call = call(context, proof);
        Map<String, Object> values = body == null ? Map.of() : body;
        Map<String, String> errors = new LinkedHashMap<>();
        if (manifest != null) {
            for (String field : values.keySet()) {
                boolean known = manifest.capabilities().fields().stream()
                        .anyMatch(f -> entity.equals(f.entity()) && field.equals(f.name()));
                if (!known) errors.put("extensions." + slug + "." + field, "not declared for " + entity);
            }
        }
        if (values.isEmpty()) errors.put("extensions", "at least one value is required");
        if (!errors.isEmpty()) throw new Refused(400, "validation", "invalid extension values", errors);
        Map<String, Object> stored = extensions.computeIfAbsent(key(call.tenant(), entity, id), k -> new LinkedHashMap<>());
        synchronized (stored) {
            values.forEach((field, value) -> {
                if (value == null) stored.remove(field);
                else stored.put(field, value);
            });
            return new Object[]{200, new LinkedHashMap<>(stored)};
        }
    }

    private Object[] auditAnswer(String method, Map<String, String> params, Map<String, Object> body, String context,
                                 String proof) {
        String access = "POST".equals(method) ? "write" : "read-own";
        declared(manifest != null && manifest.capabilities().audit().stream().anyMatch(a -> access.equals(a.access())),
                "capabilities.audit " + access);
        Call call = call(context, proof);
        if ("POST".equals(method)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", audit.size() + 1);
            row.put("tenant", call.tenant());
            row.put("aggregateType", body == null ? null : body.get("aggregateType"));
            row.put("aggregateRef", body == null ? null : body.get("aggregateRef"));
            row.put("action", body == null ? null : body.get("action"));
            row.put("actor", call.system() ? "system:hook:" + call.hookId() : call.user());
            row.put("performedAt", Instant.now().toString());
            row.put("requestId", call.requestId());
            audit.add(row);
            return new Object[]{201, row};
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : audit) {
            if (!call.tenant().equals(row.get("tenant"))) continue;
            if (!String.valueOf(row.get("aggregateType")).equals(params.get("aggregateType"))) continue;
            if (params.get("aggregateRef") != null && !params.get("aggregateRef").equals(row.get("aggregateRef"))) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("tenant");
            rows.add(0, copy);
        }
        return new Object[]{200, Map.of("rows", rows)};
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static String key(String tenant, String entity, String id) {
        return tenant + "|" + entity + "|" + id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o) {
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(name, value);
        }
        return params;
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new Refused(400, "validation", name + " is not a number", Map.of(name, "not a number"));
        }
    }

    private static int compareIds(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static String idOf(Map<String, Map<String, Object>> visible, Map<String, Object> object) {
        for (Map.Entry<String, Map<String, Object>> e : visible.entrySet()) {
            if (e.getValue() == object) return e.getKey();
        }
        return "";
    }
}
