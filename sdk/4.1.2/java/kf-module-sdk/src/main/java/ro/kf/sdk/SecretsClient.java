package ro.kf.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The module's secrets: capability {@code secrets} in {@code kf-module-conf.json}, protocol 1.1 §15.3.
 * Obtained from {@link KfModule#secrets()}. The manifest names a secret, never its value; the platform keeps the value
 * in its vault under the module's path and gives the module a reference
 * {@code vault://<mount>/kf-modules/<slug>/<platform | tenantId>/<name>}, which the module may store and resolve when
 * it needs the value.
 *
 * <pre>{@code
 * String ref = kf.secrets().write(ctx, "r3-client-secret", value);   // in a route with the secret's writeRight
 * String value = kf.secrets().resolve(ctx, ref).value();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #write}: {@code PUT …/secrets/{name}} {@code {"value"}} → the reference;</li>
 *   <li>{@link #resolve}: {@code POST …/secrets/resolve} {@code {"ref"}} → the value, as a {@link Secret} whose
 *       {@code toString} never shows it.</li>
 * </ul>
 *
 * <p><strong>A value is never logged and never put in an error</strong>: this client logs nothing, and the texts of a
 * platform refusal to a write pass through a redaction of the value. Refused locally,
 * {@link ErrorClass#PERMANENT}, nothing sent: a name outside {@code ^[a-z][a-z0-9-]{1,40}$} (or not declared in
 * {@code capabilities.secrets}, when the manifest is known), an empty value, a reference outside the module's path
 * (another slug, another shape) or of another tenant than the context's, and the context rules of {@link ReadsClient}.
 * The platform's refusals are {@link PlatformRefusal}s ({@code 403} without the secret's {@code writeRight}, or for a
 * reference the module may not read).
 *
 * <p><strong>An existing reference</strong> (SDK 0.7.2): when a {@code platform} secret of the
 * manifest declares {@code external} prefixes ({@code platform/aas/}), {@link #resolve} also sends a reference the
 * platform already holds ({@code vault://platform/aas/kf-r3/client-secret}) strictly under one of them; the platform
 * resolves it and audits every resolve. {@link #externalRefProblem} says why a reference is not one; without a known
 * manifest every reference outside the module's path is refused locally.
 *
 * <p><strong>The secret store</strong>: {@code 503 secret-store-unreachable} ({@link ErrorClass#TRANSIENT}: not
 * configured, not reachable, not logged into) and {@code 503 secret-store-rejected} ({@link ErrorClass#PERMANENT}: it
 * answered and its policy refuses the path) come back as a {@link KfException} whose {@link KfException#problem()}
 * carries the type, for the module to pass on to its user.
 */
public final class SecretsClient {

    /** A secret's name, as the manifest declares it. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,40}");
    /** The scheme of a reference. */
    public static final String SCHEME = "vault://";

    private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern TENANT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String REDACTED = "***";
    /** The longest existing reference the platform resolves for a module. */
    static final int MAX_EXTERNAL_REF = 512;
    private static final Pattern REF_SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");
    private static final String MODULES_SEGMENT = "kf-modules";

    private final PlatformChannel channel;

    SecretsClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "secrets");
    }

    /**
     * A resolved secret. {@link #value()} is the only way to the value: {@link #toString()} shows the reference and
     * {@code ***}.
     */
    public static final class Secret {
        private final String ref;
        private final String value;

        Secret(String ref, String value) {
            this.ref = ref;
            this.value = value;
        }

        public String ref() {
            return ref;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return "Secret[ref=" + ref + ", value=" + REDACTED + "]";
        }
    }

    /**
     * Writes the value of the secret {@code name} (in the tenant of {@code context} for a {@code tenant} secret) and
     * returns its reference.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 400}, {@code 401}, {@code 403})
     */
    public String write(RequestContext context, String name, String value) {
        List<String> errors = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) errors.add("name must match ^[a-z][a-z0-9-]{1,40}$");
        if (value == null || value.isEmpty()) errors.add("the value is required");
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid secret write: " + String.join("; ", errors));
        Manifest manifest = channel.kf().manifest();
        if (manifest != null && manifest.capabilities().secrets().stream().noneMatch(s -> name.equals(s.name()))) {
            throw PlatformChannel.local("secret " + name + " is not declared in capabilities.secrets of the manifest");
        }
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        PlatformChannel.Answer answer = channel.send("PUT", forwarded, "/" + name, "", Map.of("value", value));
        if (answer.status() != 200) throw channel.failure(answer, text -> text.replace(value, REDACTED));
        Map<String, Object> body = channel.answerObject(answer, "an object {ref}");
        if (!(body.get("ref") instanceof String ref) || refProblem(ref, context) != null) {
            throw new KfException(new PlatformProblem(200, null, null,
                    "the secrets answer has no reference of this module", ErrorClass.PERMANENT, null));
        }
        return ref;
    }

    /**
     * The value behind {@code ref}, a reference of this module ({@code platform}, or of the context's tenant), or an
     * existing reference strictly under a prefix a {@code platform} secret of the manifest declares in
     * {@code external}.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 401}, {@code 403}, {@code 404})
     */
    public Secret resolve(RequestContext context, String ref) {
        String problem = refProblem(ref, context);
        if (problem != null && !ownFamily(ref)) problem = externalRefProblem(ref);
        if (problem != null) throw PlatformChannel.local("invalid secret reference: " + problem);
        KfModule.ForwardedContext forwarded = channel.forwarded(context);

        PlatformChannel.Answer answer = channel.send("POST", forwarded, "/resolve", "", Map.of("ref", ref));
        if (answer.status() != 200) throw channel.failure(answer);
        Map<String, Object> body = channel.answerObject(answer, "an object {value}");
        if (!(body.get("value") instanceof String value)) {
            throw new KfException(new PlatformProblem(200, null, null, "the secrets answer has no value",
                    ErrorClass.PERMANENT, null));
        }
        return new Secret(ref, value);
    }

    /**
     * Why {@code ref} is not an existing reference this module may keep and resolve, or null when it is (SDK 0.7.2): {@code vault://} + a path of at least two segments of {@code [A-Za-z0-9._-]} (none
     * empty, none starting with a dot), with no {@code kf-modules} segment, at most {@value #MAX_EXTERNAL_REF}
     * characters, strictly under a prefix that a {@code platform} secret of the manifest declares in {@code external}.
     * The platform applies the same rules ({@code 403 external-ref-not-declared} otherwise).
     */
    public String externalRefProblem(String ref) {
        if (ref == null || !ref.startsWith(SCHEME)) return "a reference starts with " + SCHEME;
        if (ref.length() > MAX_EXTERNAL_REF) return "a reference has at most " + MAX_EXTERNAL_REF + " characters";
        String path = ref.substring(SCHEME.length());
        String[] segments = path.split("/", -1);
        boolean shaped = segments.length >= 2;
        for (String segment : segments) {
            if (!REF_SEGMENT.matcher(segment).matches() || MODULES_SEGMENT.equals(segment)) shaped = false;
        }
        if (!shaped) {
            return ref + " is not an existing vault reference (vault://<mount>/<path>: segments of [A-Za-z0-9._-], "
                    + "none empty, none starting with a dot, no kf-modules)";
        }
        Manifest manifest = channel.kf().manifest();
        if (manifest == null) {
            return ref + " is outside the module's path, and without the manifest no capabilities.secrets[].external "
                    + "prefix is known";
        }
        boolean covered = manifest.capabilities().secrets().stream()
                .filter(secret -> "platform".equals(secret.scope()))
                .flatMap(secret -> secret.external().stream())
                .anyMatch(prefix -> path.startsWith(prefix) && path.length() > prefix.length());
        return covered ? null : ref + " is under no prefix declared in capabilities.secrets[].external";
    }

    /** Whether {@code ref} names the modules' family ({@code …/kf-modules/…}): its problem is {@link #refProblem}'s. */
    private static boolean ownFamily(String ref) {
        return ref != null && ref.startsWith(SCHEME)
                && List.of(ref.substring(SCHEME.length()).split("/", -1)).contains(MODULES_SEGMENT);
    }

    /**
     * Why {@code ref} is not a reference this module may resolve in the tenant of {@code context}, or null when it is:
     * {@code vault://<mount>/kf-modules/<this slug>/<platform | the context's tenant>/<name>}.
     */
    String refProblem(String ref, RequestContext context) {
        if (ref == null || !ref.startsWith(SCHEME)) return "a reference starts with " + SCHEME;
        String rest = ref.substring(SCHEME.length());
        int at = rest.lastIndexOf("/kf-modules/");
        if (at <= 0 || !MOUNT.matcher(rest.substring(0, at)).matches()) {
            return ref + " is not a reference of this module (vault://<mount>/kf-modules/" + channel.kf().slug() + "/…)";
        }
        String[] parts = rest.substring(at + "/kf-modules/".length()).split("/", -1);
        if (parts.length != 3 || !parts[0].equals(channel.kf().slug())) {
            return ref + " is not a reference of this module (vault://<mount>/kf-modules/" + channel.kf().slug() + "/…)";
        }
        if (!NAME.matcher(parts[2]).matches()) return ref + " does not end with a secret name";
        if (parts[1].equals("platform")) return null;
        if (!TENANT.matcher(parts[1]).matches()) return ref + " names neither platform nor a tenant";
        if (context != null && !parts[1].equalsIgnoreCase(context.tenant())) {
            return ref + " is a reference of another tenant than the context's";
        }
        return null;
    }
}
