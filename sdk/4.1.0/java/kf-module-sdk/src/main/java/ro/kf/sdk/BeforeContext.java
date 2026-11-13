package ro.kf.sdk;

import java.util.List;
import java.util.Map;

/**
 * Input of a before-hook ({@code model.before.create|update|delete}).
 * <ul>
 *   <li>{@link #before()}: stored model (null on create)</li>
 *   <li>{@link #after()}: model as it will be saved, including patches of modules with a lower priority (null on delete)</li>
 *   <li>{@link #data()}: the raw CloudEvent {@code data}</li>
 *   <li>{@link #changedVersionFields()}, {@link #changedVersionIds()}: a version's paths in the model's envelope (§5.1),
 *       their values through {@link ModelSnapshot#versionExtension(String, String, String)}</li>
 *   <li>{@link #tenant()}, {@link #locale()}, {@link #eventId()}, {@link #envelopeVersion()}: from the envelope
 *       ({@code kftenant}, {@code kflocale}, {@code kfeventid}, {@code kfenvelopeversion})</li>
 * </ul>
 * Return {@link #allow()}, {@link #reject(String, Map)} (a translation KEY of the manifest's {@code i18n} and its
 * parameters) or {@link #patch()} (null means allow).
 */
public final class BeforeContext {

    private final String slug;
    private final String event;
    private final HookEvents.Envelope envelope;
    private final Map<String, Object> data;
    private final ModelSnapshot before;
    private final ModelSnapshot after;
    private final List<String> changed;

    BeforeContext(String slug, String event, HookEvents.Envelope envelope, Map<String, Object> data) {
        this.slug = slug;
        this.event = event;
        this.envelope = envelope;
        this.data = data;
        this.before = ModelSnapshot.fromMap(data.get("before"));
        this.after = ModelSnapshot.fromMap(data.get("after"));
        this.changed = HookEvents.stringList(data.get("changed"));
    }

    /** e.g. {@code model.before.update} */
    public String event() {
        return event;
    }

    /** {@code kfeventid}: the per-mutation id of this before-hook call; null from an older platform. */
    public String eventId() {
        return envelope.eventId();
    }

    /** CloudEvents {@code id}: the id of this delivery. */
    public String deliveryId() {
        return envelope.deliveryId();
    }

    /** {@code kftenant}: the tenant the mutation happens in. */
    public String tenant() {
        return envelope.tenant();
    }

    /** {@code kflocale}: the language of the user who causes the mutation ({@code en} by default). */
    public String locale() {
        return envelope.locale();
    }

    /** {@code kfactor}: the OIDC subject of that user; null for the system. */
    public String actor() {
        return envelope.actor();
    }

    /** {@code kfenvelopeversion}, e.g. {@code "1"}. */
    public String envelopeVersion() {
        return envelope.envelopeVersion();
    }

    /** Every attribute of the envelope. */
    public HookEvents.Envelope envelope() {
        return envelope;
    }

    /** Model id; null on create. */
    public String id() {
        Object id = data.get("id");
        return id == null ? null : String.valueOf(id);
    }

    public Map<String, Object> data() {
        return data;
    }

    public ModelSnapshot before() {
        return before;
    }

    public ModelSnapshot after() {
        return after;
    }

    /** Changed field paths ({@code name}, {@code materialList}, {@code extensions.{slug}.{field}}). */
    public List<String> changed() {
        return changed;
    }

    /**
     * The version paths of {@link #changed()} ({@code versions.{versionId}.extensions.{slug}.{field}}), in order: a
     * module's write on {@code model_version} (§5.1). Read each value with
     * {@code after().versionExtension(f.versionId(), f.slug(), f.field())}.
     */
    public List<HookEvents.VersionField> changedVersionFields() {
        return HookEvents.versionFields(changed);
    }

    /** The versions those paths touch, distinct, in order of first appearance. */
    public List<String> changedVersionIds() {
        return HookEvents.versionIds(changedVersionFields());
    }

    /** This module's own value in {@link #after()} (or {@link #before()} on delete). */
    public Object ownExtension(String field) {
        ModelSnapshot s = after != null ? after : before;
        return s == null ? null : s.extension(slug, field);
    }

    public Decision allow() {
        return new Decision.Allow();
    }

    /** A veto with a translation key of the manifest's {@code i18n} block and no parameters. */
    public Decision reject(String key) {
        return Decision.reject(key, Map.of());
    }

    /**
     * A veto: {@code {"decision":"reject","key":…,"params":{…}}}. The platform shows the key's text on the
     * user's language, with the parameters interpolated (texts at most 500 characters, numbers or booleans; at most 20).
     */
    public Decision reject(String key, Map<String, Object> params) {
        return Decision.reject(key, params);
    }

    /** Starts a patch; chain {@code .name(..)}, {@code .set(..)}, {@code .extension(field, value)} and return it. */
    public Decision.Patch patch() {
        return new Decision.Patch(slug);
    }
}
