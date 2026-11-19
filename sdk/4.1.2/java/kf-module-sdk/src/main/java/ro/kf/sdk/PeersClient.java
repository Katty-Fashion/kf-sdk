package ro.kf.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The module's reads and commands of ANOTHER module, through the platform (protocol 2.0, sdk-protocol.md §15.9): the owner offers {@code capabilities.peerReads[]} / {@code capabilities.commands[]}, this module
 * declares {@code capabilities.peers[] {owner, reads, commands}} with the owner in its {@code requires}. Obtained from
 * {@link KfModule#peers()}.
 *
 * <pre>{@code
 * PeersClient.Answer stock = kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", Map.of("sku", "KF-42"));
 * PeersClient.Answer reserved = kf.peers().command(ctx, "kf-stock", "reserve", PeersClient.newIdempotencyKey(),
 *         Map.of("sku", "KF-42", "qty", 3));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #read}: {@code GET …/modules/{self}/peers/{owner}/reads/{port}[?query]} → the owner's status and JSON
 *       body, unchanged;</li>
 *   <li>{@link #command}: {@code POST …/modules/{self}/peers/{owner}/commands/{name}} with the JSON body and the
 *       mandatory {@value #IDEMPOTENCY_HEADER}: the same key with the same body replays the stored answer (the owner is
 *       not called again), another body is {@code 409 idempotency-conflict}; a key is kept 7 days. Use one key per
 *       intended effect ({@link #newIdempotencyKey()}) and the SAME key when retrying it.</li>
 * </ul>
 *
 * <p>Both calls carry the module token and a proved context, unchanged ({@link PlatformChannel}): the
 * {@link RequestContext} of the request the module serves, or -- for a background job -- the
 * {@link HookEvents.DeliveryContext} of an after delivery, within ±300 s of its {@code ts}; the platform then
 * calls the owner as {@code system via module:{self}}. Refused locally, {@link ErrorClass#PERMANENT}, nothing sent: an
 * owner outside the slug grammar, a port outside {@code m.{owner}.{name}.v{N}}, a command name outside
 * {@code [a-z][a-z0-9-]{1,40}}, a blank query name or a null query value, a missing or invalid idempotency key
 * ({@link #IDEMPOTENCY_KEY}), a port or command the manifest's {@code capabilities.peers} does not name (when the
 * manifest is known), a context not verified or without proof, a delivery context outside its window.
 *
 * <p>The platform's refusals are {@link PlatformRefusal}s with the reason of §15.9: {@code peer-not-required},
 * {@code capability-not-declared}, {@code peer-forbidden} ({@code 403}); {@code peer-port-unknown},
 * {@code peer-command-unknown}, {@link #NOT_CONFIGURED} ({@code 404}); {@code idempotency-key-required},
 * {@code validation} ({@code 400}); {@link #IDEMPOTENCY_CONFLICT}, {@link #IDEMPOTENCY_IN_PROGRESS} and {@link #IDEMPOTENCY_ANSWER_NOT_STORED}
 * ({@code 409}; the last is permanent: use a new key to run the command again);
 * {@link #MODULE_REFUSED} ({@code 422}); {@link #INVALID_ANSWER} ({@code 502}); {@link #MODULE_UNAVAILABLE}
 * ({@code 503}, transient when the owner is stopped, permanent when it is absent -- an absent optional dependency
 * included). The class is the body's ({@link KfException#errorClass()}).
 */
public final class PeersClient {

    /** The header of a command's idempotency key. */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** The grammar of an idempotency key (§15.9). */
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    /** The owner answered {@code 404} ({@code NOT_CONFIGURED}). */
    public static final String NOT_CONFIGURED = "not-configured";
    /** The owner answered another {@code 4xx} ({@code MODULE_REFUSED}, {@code 422}). */
    public static final String MODULE_REFUSED = "module-refused";
    /** The owner is absent, stopped, failing or unreachable ({@code MODULE_UNAVAILABLE}, {@code 503}). */
    public static final String MODULE_UNAVAILABLE = "module-unavailable";
    /** The owner answered a {@code 2xx} that is not JSON ({@code 502}). */
    public static final String INVALID_ANSWER = "invalid-answer";
    /** The owner is not in this module's {@code requires} ({@code 403}). */
    public static final String PEER_NOT_REQUIRED = "peer-not-required";
    /** The key is missing or outside {@link #IDEMPOTENCY_KEY} ({@code 400}). */
    public static final String IDEMPOTENCY_KEY_REQUIRED = "idempotency-key-required";
    /** The same key with another body ({@code 409}, permanent). */
    public static final String IDEMPOTENCY_CONFLICT = "idempotency-conflict";
    /**
     * The same key again, the first {@code 2xx} too large to store ({@code 409}, permanent): use a new key to run the
     * command again.
     */
    public static final String IDEMPOTENCY_ANSWER_NOT_STORED = "idempotency-answer-not-stored";
    /** The same key, still running ({@code 409}, transient). */
    public static final String IDEMPOTENCY_IN_PROGRESS = "idempotency-in-progress";

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");

    /**
     * The owner's answer, passed on unchanged by the platform: its {@code 2xx} status and its JSON body (an object, an
     * array or a scalar, read as {@link PlatformChannel} reads every answer); {@code null} when the owner answered a
     * {@code 2xx} without a body, in which case {@link #object()} is a permanent error.
     */
    public record Answer(int status, Object body) {

        /** The body as an object; a permanent {@link KfException} when it is not one. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> object() {
            if (body instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new KfException(new PlatformProblem(status, null, null, "the owner's answer is not a JSON object",
                    ErrorClass.PERMANENT, null));
        }
    }

    private final PlatformChannel channel;

    PeersClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "peers");
    }

    /** A fresh idempotency key ({@code UUID}, 36 characters, inside {@link #IDEMPOTENCY_KEY}). */
    public static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Reads {@code port} of {@code owner} in the context's tenant, on behalf of the request the module serves.
     *
     * @param query the port's query parameters (name → value, sent in this order); empty or null for none
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call
     */
    public Answer read(RequestContext context, String owner, String port, Map<String, String> query) {
        String q = checkRead(owner, port, query);
        return answer(channel.send("GET", channel.forwarded(context), readPath(owner, port), q, null));
    }

    /** {@link #read(RequestContext, String, String, Map)} for a background job, with an after delivery's context. */
    public Answer read(HookEvents.DeliveryContext delivery, String owner, String port, Map<String, String> query) {
        String q = checkRead(owner, port, query);
        return answer(channel.send("GET", channel.forwarded(delivery), readPath(owner, port), q, null));
    }

    /**
     * Runs the command {@code name} of {@code owner} in the context's tenant, once per {@code idempotencyKey}.
     *
     * @param idempotencyKey mandatory, {@link #IDEMPOTENCY_KEY}; the same one when retrying the same effect
     * @param body           the command's JSON body; null sends {@code {}}
     * @throws KfException     a local refusal (a missing or invalid key included), a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call
     */
    public Answer command(RequestContext context, String owner, String name, String idempotencyKey, Object body) {
        checkCommand(owner, name, idempotencyKey);
        return answer(channel.send("POST", channel.forwarded(context), commandPath(owner, name), "",
                body == null ? Map.of() : body, Map.of(IDEMPOTENCY_HEADER, idempotencyKey)));
    }

    /** {@link #command(RequestContext, String, String, String, Object)} for a background job. */
    public Answer command(HookEvents.DeliveryContext delivery, String owner, String name, String idempotencyKey,
                          Object body) {
        checkCommand(owner, name, idempotencyKey);
        return answer(channel.send("POST", channel.forwarded(delivery), commandPath(owner, name), "",
                body == null ? Map.of() : body, Map.of(IDEMPOTENCY_HEADER, idempotencyKey)));
    }

    // ---------------------------------------------------------------------------------------------------------

    private Answer answer(PlatformChannel.Answer answer) {
        if (answer.status() < 200 || answer.status() > 299) throw channel.betweenModulesFailure(answer);
        // an owner's 2xx without a body (204) is passed on as its status and no body
        if (answer.body() == null || answer.body().isBlank()) return new Answer(answer.status(), null);
        return new Answer(answer.status(), channel.answerJson(answer, "JSON"));
    }

    private String checkRead(String owner, String port, Map<String, String> query) {
        List<String> errors = new ArrayList<>();
        boolean ownerOk = checkOwner(errors, owner);
        if (port == null || (ownerOk && !ManifestCheck.peerPort(owner).matcher(port).matches())) {
            errors.add("port must be m." + (ownerOk ? owner : "{owner}") + ".{name}.v{N}");
        }
        Map<String, String> given = query == null ? Map.of() : query;
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) errors.add("a query name is blank");
            else if (e.getValue() == null) errors.add("query " + e.getKey() + " has no value");
            else q.append(q.isEmpty() ? "?" : "&").append(PlatformChannel.queryValue(e.getKey())).append('=')
                        .append(PlatformChannel.queryValue(e.getValue()));
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid peer read: " + String.join("; ", errors));
        checkDeclared(owner, port, true);
        return q.toString();
    }

    private void checkCommand(String owner, String name, String idempotencyKey) {
        List<String> errors = new ArrayList<>();
        checkOwner(errors, owner);
        if (name == null || !ManifestCheck.PEER_NAME.matcher(name).matches()) {
            errors.add("command must match " + ManifestCheck.PEER_NAME.pattern());
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add(IDEMPOTENCY_HEADER + " is required (PeersClient.newIdempotencyKey(), the same one on a retry)");
        } else if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            errors.add(IDEMPOTENCY_HEADER + " must match " + IDEMPOTENCY_KEY.pattern());
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid peer command: " + String.join("; ", errors));
        checkDeclared(owner, name, false);
    }

    private static boolean checkOwner(List<String> errors, String owner) {
        if (owner == null || !SLUG.matcher(owner).matches()) {
            errors.add("owner must be a module slug (" + SLUG.pattern() + ")");
            return false;
        }
        return true;
    }

    /** With the manifest known, {@code capabilities.peers} must name the owner and the port or command (else 403). */
    private void checkDeclared(String owner, String what, boolean read) {
        Manifest manifest = channel.kf().manifest();
        if (manifest == null) return;
        boolean declared = manifest.capabilities().peers().stream()
                .anyMatch(p -> owner.equals(p.owner()) && (read ? p.reads() : p.commands()).contains(what));
        if (!declared) {
            throw PlatformChannel.local((read ? "the read " : "the command ") + what + " of " + owner
                    + " is not declared in capabilities.peers of the manifest");
        }
    }

    private static String readPath(String owner, String port) {
        return "/" + owner + "/reads/" + PlatformChannel.segment(port);
    }

    private static String commandPath(String owner, String name) {
        return "/" + owner + "/commands/" + name;
    }
}
