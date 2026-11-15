package ro.kf.sdk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The hook points, the event payloads delivered to after / field-change handlers, and the handler interfaces.
 *
 * <p>The point ids are hand-written here, aligned with the vocabulary fixed ({@code HookEvent} /
 * {@code HookPointCatalog} of the platform) and, for {@link #TENANT_AFTER_DELETE}, with protocol 1.2, for
 * {@link #TENANT_AFTER_SOFT_DELETE} and {@link #TENANT_AFTER_RESTORE} with protocol 1.3; generating them
 * from the release catalog is deferred.
 */
public final class HookEvents {

    private HookEvents() {
    }

    public static final String BEFORE_CREATE = "model.before.create";
    public static final String BEFORE_UPDATE = "model.before.update";
    public static final String BEFORE_DELETE = "model.before.delete";
    public static final String AFTER_CREATE = "model.after.create";
    public static final String AFTER_UPDATE = "model.after.update";
    public static final String AFTER_DELETE = "model.after.delete";
    public static final String FIELD_CHANGED = "model.field.changed";

    /**
     * Protocol 1.2 (delivered by the platform, its SDK handler): the
     * after-commit point of a tenant's deletion, delivered at least once to every INSTALLED module subscribed to it
     * (liveness is not evaluated: the tenant no longer exists); the module deletes what it keeps for the tenant,
     * idempotently. Its envelope: {@code kftenant} = the deleted tenant, {@code data = {entity: "tenant", id,
     * before: {id}, after: null, changed: []}} (sdk-protocol.md §6.1).
     */
    public static final String TENANT_AFTER_DELETE = "tenant.after.delete";

    /**
     * Protocol 1.3 (delivered by the platform): the after-commit point of
     * a tenant's soft-delete -- access to the tenant is off and recoverable -- delivered at least once to every module
     * subscribed to it and LIVE for the tenant. The module stops its work for the tenant (jobs, synchronisations) and
     * deletes nothing: {@link #TENANT_AFTER_DELETE} stays the purge. Its envelope: {@code kftenant} = the tenant,
     * {@code data = {entity: "tenant", id, before: {id}, after: {id}, changed: []}} (sdk-protocol.md §6.1).
     */
    public static final String TENANT_AFTER_SOFT_DELETE = "tenant.after.soft-delete";

    /**
     * Protocol 1.3 (delivered by the platform): the after-commit point of
     * a soft-deleted tenant's restore, delivered like {@link #TENANT_AFTER_SOFT_DELETE}; the module resumes its work for
     * the tenant. The same envelope (sdk-protocol.md §6.1).
     */
    public static final String TENANT_AFTER_RESTORE = "tenant.after.restore";

    /**
     * Protocol 2.0 (sdk-protocol.md §6.1, §16.2): the order points, public since --
     * the model's shape on the order ({@code kf.orders()}, a {@link ModelHooks} on the entity {@code order}). A before
     * hook may veto, and patch only {@code extensions.{own slug}}: the order's core fields carry {@code patch: []}. The
     * {@code customer.*} points stay internal (not carried here).
     */
    public static final String ORDER_BEFORE_CREATE = "order.before.create";
    public static final String ORDER_BEFORE_UPDATE = "order.before.update";
    public static final String ORDER_BEFORE_DELETE = "order.before.delete";
    public static final String ORDER_AFTER_CREATE = "order.after.create";
    public static final String ORDER_AFTER_UPDATE = "order.after.update";
    public static final String ORDER_AFTER_DELETE = "order.after.delete";

    /**
     * {@code order.field.changed} ({@code fieldFilter: true}): one delivery per changed path, on
     * {@link #ORDER_PATH_STATUS}, {@link #ORDER_PATH_ENTRIES} and {@code extensions.{slug}.{field}}.
     */
    public static final String ORDER_FIELD_CHANGED = "order.field.changed";

    /** The seven order points, in the documented order. */
    public static final List<String> ORDER_POINTS = List.of(ORDER_BEFORE_CREATE, ORDER_BEFORE_UPDATE,
            ORDER_BEFORE_DELETE, ORDER_AFTER_CREATE, ORDER_AFTER_UPDATE, ORDER_AFTER_DELETE, ORDER_FIELD_CHANGED);

    /** The path of the order's status on {@link #ORDER_FIELD_CHANGED} (the intent {@code {status}}). */
    public static final String ORDER_PATH_STATUS = "status";

    /** The path of the order's lines on {@link #ORDER_FIELD_CHANGED}: {@code [{id, modelVersionId, quantity?}]}. */
    public static final String ORDER_PATH_ENTRIES = "entries";

    /** The statuses of an order (§16.2). {@link #ORDER_STATUS_COMPLETED} is terminal and reached only from a flow. */
    public static final String ORDER_STATUS_DRAFT = "DRAFT";
    public static final String ORDER_STATUS_CONFIRMED = "CONFIRMED";
    public static final String ORDER_STATUS_IN_PRODUCTION = "IN_PRODUCTION";
    public static final String ORDER_STATUS_SHIPPED = "SHIPPED";
    public static final String ORDER_STATUS_CANCELLED = "CANCELLED";
    public static final String ORDER_STATUS_COMPLETED = "COMPLETED";

    /** Every status of an order, in the order of the platform's {@code OrderStatus}. */
    public static final List<String> ORDER_STATUSES = List.of(ORDER_STATUS_DRAFT, ORDER_STATUS_CONFIRMED,
            ORDER_STATUS_IN_PRODUCTION, ORDER_STATUS_SHIPPED, ORDER_STATUS_CANCELLED, ORDER_STATUS_COMPLETED);

    /** The tenant points (protocols 1.2 and 1.3): the same envelope, checked the same way. */
    public static final List<String> TENANT_POINTS = List.of(TENANT_AFTER_DELETE, TENANT_AFTER_SOFT_DELETE,
            TENANT_AFTER_RESTORE);

    /**
     * Protocol 2.0 (sdk-protocol.md §6.3): the prefix of a module event's point,
     * {@code m.{owner}.after.{name}}. No platform point starts with it.
     */
    public static final String MODULE_EVENT_PREFIX = "m.";

    /** The grammar of a module event's point: {@code m.{owner}.after.{name}} (the owner's slug, the event's name). */
    public static final Pattern MODULE_EVENT_POINT =
            Pattern.compile("^m\\.([a-z0-9][a-z0-9-]{1,40})\\.after\\.([a-z][a-z0-9-]{1,40})$");

    /** The point of the event {@code name} of {@code owner}: {@code m.{owner}.after.{name}}. */
    public static String moduleEventPoint(String owner, String name) {
        return MODULE_EVENT_PREFIX + owner + ".after." + name;
    }

    @FunctionalInterface
    public interface BeforeHandler {
        /** Return a {@link Decision} (null = allow). Exceptions become HTTP 500 (the platform applies {@code onFailure}). */
        Decision handle(BeforeContext ctx) throws Exception;
    }

    @FunctionalInterface
    public interface AfterHandler {
        void handle(AfterEvent event) throws Exception;
    }

    @FunctionalInterface
    public interface FieldChangeHandler {
        void handle(FieldChange change) throws Exception;
    }

    /**
     * A module event {@code m.{owner}.after.{name}} (protocol 2.0, §6.3): exceptions become HTTP 500 {@code transient}
     * (retried; the delivery is at least once).
     */
    @FunctionalInterface
    public interface ModuleEventHandler {
        void handle(ModuleEvent event) throws Exception;
    }

    /** {@code tenant.after.delete} (protocol 1.2): exceptions become HTTP 500 {@code transient} (retried). */
    @FunctionalInterface
    public interface TenantDeletedHandler {
        void handle(TenantDeleted deleted) throws Exception;
    }

    /** {@code tenant.after.soft-delete} (protocol 1.3): exceptions become HTTP 500 {@code transient} (retried). */
    @FunctionalInterface
    public interface TenantSoftDeletedHandler {
        void handle(TenantSoftDeleted softDeleted) throws Exception;
    }

    /** {@code tenant.after.restore} (protocol 1.3): exceptions become HTTP 500 {@code transient} (retried). */
    @FunctionalInterface
    public interface TenantRestoredHandler {
        void handle(TenantRestored restored) throws Exception;
    }

    /**
     * {@code tenant.after.delete} (protocol 1.2 §6.1): the tenant {@code tenantId} ({@code data.id}) was
     * deleted; the module deletes what it keeps for it, idempotently (the same {@link #eventId()} may come again).
     */
    public record TenantDeleted(String tenantId, Envelope envelope) {

        /** The {@code eventId} ({@code kfeventid}), the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        /** CloudEvents {@code id}: one per delivery, stable across retries. */
        public String deliveryId() {
            return envelope.deliveryId();
        }

        /** {@code time}, ISO-8601. */
        public String time() {
            return envelope.time();
        }
    }

    /**
     * {@code tenant.after.soft-delete} (protocol 1.3 §6.1): the tenant {@code tenantId} ({@code data.id})
     * was soft-deleted at {@link #time()}; the module stops its work for it and keeps its data. The same
     * {@link #eventId()} may come again.
     */
    public record TenantSoftDeleted(String tenantId, Envelope envelope) {

        /** The {@code eventId} ({@code kfeventid}), the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        /** CloudEvents {@code id}: one per delivery, stable across retries. */
        public String deliveryId() {
            return envelope.deliveryId();
        }

        /** {@code time}, ISO-8601: when the tenant was soft-deleted. */
        public String time() {
            return envelope.time();
        }
    }

    /**
     * {@code tenant.after.restore} (protocol 1.3 §6.1): the soft-deleted tenant {@code tenantId} was
     * restored at {@link #time()}; the module resumes its work for it. The same {@link #eventId()} may come again.
     */
    public record TenantRestored(String tenantId, Envelope envelope) {

        /** The {@code eventId} ({@code kfeventid}), the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        /** CloudEvents {@code id}: one per delivery, stable across retries. */
        public String deliveryId() {
            return envelope.deliveryId();
        }

        /** {@code time}, ISO-8601: when the tenant was restored. */
        public String time() {
            return envelope.time();
        }
    }

    /**
     * The CloudEvents attributes of one callback (the envelope on the wire):
     *
     * @param event           the hook point, {@code type} without {@code kf.}, e.g. {@code model.before.update}
     * @param deliveryId      CloudEvents {@code id}: one per delivery (per module and, for a field change, per path),
     *                        stable across retries
     * @param eventId         {@code kfeventid}: the {@code eventId} ({@code outbox_event.event_id}), the same on every
     *                        copy of one event -- the idempotency key; null on a delivery queued
     * @param time            {@code time}, ISO-8601
     * @param tenant          {@code kftenant}: the tenant the event happened in
     * @param locale          {@code kflocale}: the language of the user whose request produced the event ({@code en} by
     *                        default)
     * @param actor           {@code kfactor}: the OIDC subject of that user; null for the system
     * @param aggregateType   {@code kfaggregatetype}, e.g. {@code model}
     * @param subject         {@code subject}: the aggregate's id as text; null on a before-create
     * @param envelopeVersion {@code kfenvelopeversion}, e.g. {@code "1"}
     * @param module          {@code kfmodule}: the module this copy is addressed to
     * @param delivery        protocol 2.0: the context and proof the platform put on this after delivery;
     *                        null on a before hook, from a platform before 2.0, or when the headers were not usable --
     *                        read it through {@link #deliveryContext()}
     */
    public record Envelope(String event, String deliveryId, String eventId, String time, String tenant, String locale,
                           String actor, String aggregateType, String subject, String envelopeVersion, String module,
                           DeliveryContext delivery) {

        /** An envelope without a delivery context (the shape before SDK 0.10.0). */
        public Envelope(String event, String deliveryId, String eventId, String time, String tenant, String locale,
                        String actor, String aggregateType, String subject, String envelopeVersion, String module) {
            this(event, deliveryId, eventId, time, tenant, locale, actor, aggregateType, subject, envelopeVersion, module,
                    null);
        }

        static Envelope of(Map<String, Object> ce) {
            return of(ce, null);
        }

        static Envelope of(Map<String, Object> ce, DeliveryContext delivery) {
            String type = text(ce.get("type"));
            String event = type == null ? "" : type.startsWith("kf.") ? type.substring(3) : type;
            return new Envelope(event, text(ce.get("id")), text(ce.get("kfeventid")), text(ce.get("time")),
                    text(ce.get("kftenant")), text(ce.get("kflocale")), text(ce.get("kfactor")),
                    text(ce.get("kfaggregatetype")), text(ce.get("subject")), text(ce.get("kfenvelopeversion")),
                    text(ce.get("kfmodule")), delivery);
        }

        /**
         * Protocol 2.0 (sdk-protocol.md §5.5): the context and proof of this after delivery, for a background
         * job's call to the platform ({@link PeersClient}, {@link EventsClient}) within ±300 s of the context's
         * {@code ts}; {@link Optional#empty()} when the platform sent none (a before hook, a platform before 2.0).
         */
        public Optional<DeliveryContext> deliveryContext() {
            return Optional.ofNullable(delivery);
        }
    }

    /**
     * The context of one after delivery (protocol 2.0, sdk-protocol.md §5.5): the platform's
     * {@code X-KF-Context} -- {@code actor {"type": "system", "via": "hook"}}, no user, no rights, the event's tenant
     * and locale -- and its proof {@code X-KF-Context-Proof}, signed by the platform for THIS module. A background job
     * started by the delivery passes it to {@link PeersClient} or {@link EventsClient} in place of a
     * {@link RequestContext}, within {@value #WINDOW_SECONDS} s of {@link #ts()}; the platform then calls the owner as
     * {@code system via module:{caller}}. The tenant always comes from the platform's context, never from the module.
     *
     * <p>{@link #contextHeader()} and {@link #proof()} are opaque; a job queued in another process can carry both and
     * rebuild the context with {@link #of(String, String)}. The proof is never printed ({@link #toString()}).
     *
     * @param context       the decoded context
     * @param contextHeader {@code X-KF-Context}, exactly as delivered
     * @param proof         {@code X-KF-Context-Proof}, exactly as delivered
     */
    public record DeliveryContext(RequestContext context, String contextHeader, String proof) {

        /** How long around its {@code ts} the platform accepts the context (±300 s, as every context). */
        public static final long WINDOW_SECONDS = Signatures.MAX_SKEW_SECONDS;

        public DeliveryContext {
            if (context == null || contextHeader == null || contextHeader.isBlank() || proof == null || proof.isBlank()) {
                throw new IllegalArgumentException("a delivery context needs the context, " + RequestContext.CONTEXT_HEADER
                        + " and " + RequestContext.PROOF_HEADER);
            }
        }

        /**
         * The context of {@code X-KF-Context} and {@code X-KF-Context-Proof} as delivered (e.g. carried by a job queue).
         *
         * @throws RequestContext.Rejected  when the header is not a coherent context
         * @throws IllegalArgumentException when the proof is missing
         */
        public static DeliveryContext of(String contextHeader, String proof) {
            if (proof == null || proof.isBlank()) {
                throw new IllegalArgumentException("a delivery context needs " + RequestContext.PROOF_HEADER);
            }
            return new DeliveryContext(RequestContext.decodeDelivered(contextHeader), contextHeader.trim(), proof.trim());
        }

        /** The tenant of the event, as the platform signed it. */
        public String tenant() {
            return context.tenant();
        }

        /** The context's {@code ts}, epoch seconds: the middle of the ±{@value #WINDOW_SECONDS} s window. */
        public long ts() {
            return context.ts();
        }

        /** Whether the platform would still accept the context at {@code nowSeconds}. */
        public boolean usableAt(long nowSeconds) {
            return Math.abs(nowSeconds - context.ts()) <= WINDOW_SECONDS;
        }

        /** Whether the platform would still accept the context now. */
        public boolean usable() {
            return usableAt(Instant.now().getEpochSecond());
        }

        @Override
        public String toString() {
            return "DeliveryContext[tenant=" + context.tenant() + ", requestId=" + context.requestId() + ", ts="
                    + context.ts() + ", proof=***]";
        }
    }

    /**
     * A module event {@code m.{owner}.after.{name}} (protocol 2.0, §6.3), delivered at least once to a module that
     * subscribed with {@link EventsClient#on}: {@link #data()} is the owner's object unchanged.
     */
    public record ModuleEvent(String owner, String name, Map<String, Object> data, Envelope envelope) {

        /** The {@code eventId} ({@code kfeventid}): the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        /** CloudEvents {@code id}: one per delivery, stable across retries. */
        public String deliveryId() {
            return envelope.deliveryId();
        }

        public String time() {
            return envelope.time();
        }

        /** The emitter's tenant ({@code kftenant}). */
        public String tenant() {
            return envelope.tenant();
        }

        public String locale() {
            return envelope.locale();
        }

        /** The point, {@code m.{owner}.after.{name}}. */
        public String point() {
            return moduleEventPoint(owner, name);
        }

        /** The context and proof of the delivery, for a background job. */
        public Optional<DeliveryContext> deliveryContext() {
            return envelope.deliveryContext();
        }
    }

    /** {@code model.after.create|update|delete}. */
    public record AfterEvent(String event, String id, ModelSnapshot before, ModelSnapshot after, List<String> changed,
                             Map<String, Object> data, Envelope envelope) {

        static AfterEvent of(String event, Envelope envelope, Map<String, Object> data) {
            return new AfterEvent(event, data.get("id") == null ? null : String.valueOf(data.get("id")),
                    ModelSnapshot.fromMap(data.get("before")), ModelSnapshot.fromMap(data.get("after")),
                    stringList(data.get("changed")), data, envelope);
        }

        /** The {@code eventId} ({@code kfeventid}), the idempotency key of the event. */
        public String eventId() {
            return envelope.eventId();
        }

        public String deliveryId() {
            return envelope.deliveryId();
        }

        public String time() {
            return envelope.time();
        }

        public String tenant() {
            return envelope.tenant();
        }

        public String locale() {
            return envelope.locale();
        }

        public String envelopeVersion() {
            return envelope.envelopeVersion();
        }

        /** The context and proof of the delivery, for a background job (protocol 2.0). */
        public Optional<DeliveryContext> deliveryContext() {
            return envelope.deliveryContext();
        }

        /** The version paths of {@link #changed()}, in order: a module's write on {@code model_version} (§5.1). */
        public List<VersionField> changedVersionFields() {
            return versionFields(changed);
        }

        /** The versions those paths touch, distinct, in order of first appearance. */
        public List<String> changedVersionIds() {
            return versionIds(changedVersionFields());
        }
    }

    /** {@code model.field.changed}: one per changed field path. */
    public record FieldChange(String id, String field, Object oldValue, Object newValue, ModelSnapshot before,
                              ModelSnapshot after, Map<String, Object> data, Envelope envelope) {

        static FieldChange of(Envelope envelope, Map<String, Object> data) {
            return new FieldChange(data.get("id") == null ? null : String.valueOf(data.get("id")),
                    String.valueOf(data.get("field")), data.get("oldValue"), data.get("newValue"),
                    ModelSnapshot.fromMap(data.get("before")), ModelSnapshot.fromMap(data.get("after")), data, envelope);
        }

        public String eventId() {
            return envelope.eventId();
        }

        public String deliveryId() {
            return envelope.deliveryId();
        }

        public String time() {
            return envelope.time();
        }

        public String tenant() {
            return envelope.tenant();
        }

        public String locale() {
            return envelope.locale();
        }

        /** The context and proof of the delivery, for a background job (protocol 2.0). */
        public Optional<DeliveryContext> deliveryContext() {
            return envelope.deliveryContext();
        }

        /** The version path of {@link #field()} ({@code versions.{versionId}.extensions.{slug}.{field}}), or null. */
        public VersionField versionField() {
            return VersionField.parse(field);
        }
    }

    /**
     * One path {@code versions.{versionId}.extensions.{slug}.{field}} of {@code changed} or of a field change's
     * {@code field}: a module's write of its own value on a version, delivered as an update of the version's model
     * (protocol 1.1, §5.1). Read the values with
     * {@link ModelSnapshot#versionExtension(String, String, String)} on {@code before} / {@code after} (SDK 0.7.4).
     */
    public record VersionField(String versionId, String slug, String field) {

        /** The path's first segment. */
        public static final String VERSIONS = "versions";

        /** The segment between the version id and the module's slug. */
        public static final String EXTENSIONS = "extensions";

        /**
         * The version field of a path; null when the path is not exactly
         * {@code versions.{versionId}.extensions.{slug}.{field}} with no empty segment (e.g. {@code name},
         * {@code extensions.{slug}.{field}}).
         */
        public static VersionField parse(String path) {
            if (path == null) return null;
            String[] parts = path.split("\\.", -1);
            if (parts.length != 5 || !VERSIONS.equals(parts[0]) || !EXTENSIONS.equals(parts[2])) return null;
            if (parts[1].isEmpty() || parts[3].isEmpty() || parts[4].isEmpty()) return null;
            return new VersionField(parts[1], parts[3], parts[4]);
        }

        /** {@code versions.{versionId}.extensions.{slug}.{field}} */
        public String path() {
            return VERSIONS + "." + versionId + "." + EXTENSIONS + "." + slug + "." + field;
        }
    }

    /**
     * What {@link KfModule#onCallback} observers see for every verified callback. {@code eventId} is {@code kfeventid}
     * (the delivery id when absent); {@code flags}: the module's effective feature flags sent with the callback
     * ({@code kfflags}).
     */
    public record CallbackRecord(String receivedAt, String type, String eventId, String entityId, List<String> changed,
                                 String field, int status, Object answer, Map<String, Boolean> flags, String tenant,
                                 String locale) {
    }

    /** The version paths of a {@code changed} list, in order; the model's own paths are left out. */
    static List<VersionField> versionFields(List<String> changed) {
        List<VersionField> out = new ArrayList<>();
        for (String path : changed) {
            VersionField f = VersionField.parse(path);
            if (f != null) out.add(f);
        }
        return List.copyOf(out);
    }

    /** The version ids of those paths, distinct, in order of first appearance. */
    static List<String> versionIds(List<VersionField> fields) {
        List<String> out = new ArrayList<>();
        for (VersionField f : fields) if (!out.contains(f.versionId())) out.add(f.versionId());
        return List.copyOf(out);
    }

    static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) l.forEach(o -> out.add(String.valueOf(o)));
        return List.copyOf(out);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
