package ro.kf.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The module's own extension values on platform objects: protocol 1.1 §15.4. Obtained from
 * {@link KfModule#extensions()}. This is the only write a module makes on platform objects; {@link ReadsClient} never
 * writes.
 *
 * <pre>{@code
 * Map<String, Object> values = new LinkedHashMap<>();
 * values.put("aas_shell", "urn:kf:shell:17");
 * values.put("aas_last_push", null);                          // null clears the value
 * kf.extensions().write(ctx, "model_version", "17", values);  // PATCH …/extensions/model_version/17
 * }</pre>
 *
 * <p>{@link #write} sends {@code PATCH …/extensions/{entity}/{id}} with <strong>exactly the fields given</strong>
 * (a field not given is left as it is; {@code null} clears), the module token, the context and its proof, unchanged
 * ({@link PlatformChannel}); the answer is the module's values on that object. Only the fields the module declares in
 * {@code capabilities.fields[]} for {@code entity}, on an object visible in the context's tenant. Refused locally,
 * {@link ErrorClass#PERMANENT}, nothing sent: an entity other than {@value #ENTITY_LIST}, a blank id, no values, a field
 * name outside {@code ^[a-z][a-z0-9_]{0,40}$} (or not declared for the entity, when the manifest is known), and the
 * context rules of {@link ReadsClient}. The platform's refusals are {@link PlatformRefusal}s: {@code 400} with
 * {@code errors["extensions.<slug>.<field>"]} for a value of the wrong type, {@code 404} for an object not visible in the
 * tenant.
 */
public final class ExtensionsClient {

    /** The extensible entities of protocol 1.1 (§6.2). */
    public static final List<String> ENTITIES = List.of("model", "order", "customer", "model_version");
    static final String ENTITY_LIST = "model, order, customer, model_version";
    /** A field name, as the manifest declares it. */
    public static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]{0,40}");

    private final PlatformChannel channel;

    ExtensionsClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "extensions");
    }

    /**
     * Writes the module's values of the given fields on one object of {@code entity} in the tenant of
     * {@code context}; returns the module's values on that object after the write.
     *
     * @param values field → value ({@code null} clears); sent exactly, in this order
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 400}, {@code 401}, {@code 403}, {@code 404})
     */
    public Map<String, Object> write(RequestContext context, String entity, String id, Map<String, ?> values) {
        List<String> errors = new ArrayList<>();
        if (entity == null || !ENTITIES.contains(entity)) errors.add("entity must be one of " + ENTITY_LIST);
        if (id == null || id.isBlank()) errors.add("id is required");
        if (values == null || values.isEmpty()) {
            errors.add("at least one value is required");
        } else {
            for (String field : values.keySet()) {
                if (field == null || !FIELD.matcher(field).matches()) errors.add("field " + field + " must match ^[a-z][a-z0-9_]{0,40}$");
            }
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid extension write: " + String.join("; ", errors));
        Manifest manifest = channel.kf().manifest();
        if (manifest != null) {
            for (String field : values.keySet()) {
                boolean declared = manifest.capabilities().fields().stream()
                        .anyMatch(f -> entity.equals(f.entity()) && field.equals(f.name()));
                if (!declared) {
                    throw PlatformChannel.local(field + " is not declared in capabilities.fields for " + entity
                            + " in the manifest");
                }
            }
        }
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        Map<String, Object> body = new LinkedHashMap<>(values);
        PlatformChannel.Answer answer = channel.send("PATCH", forwarded, "/" + entity + "/" + PlatformChannel.segment(id),
                "", body);
        if (answer.status() != 200) throw channel.failure(answer);
        return Collections.unmodifiableMap(new LinkedHashMap<>(channel.answerObject(answer, "an object of values")));
    }
}
