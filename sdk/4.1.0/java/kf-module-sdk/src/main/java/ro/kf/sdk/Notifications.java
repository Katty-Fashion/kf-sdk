package ro.kf.sdk;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The module sends a notification it declared in {@code capabilities.notifications} of {@code kf-module-conf.json}
 * (sdk-protocol.md §11.1). Obtained from {@link KfModule#notifications()}.
 *
 * <pre>{@code
 * kf.notifications().send(tenantId, "author-changed", List.of(userSubject), Map.of("modelName", "Rochie"));
 * kf.notifications().send(tenantId, "author-changed", List.of(userSubject), Map.of("modelName", "Rochie"),
 *         NotificationOptions.builder().deepLink("/models/42").channels(List.of("in_app")).build());
 * }</pre>
 *
 * <p>{@code POST /api/v1/platform/modules/{slug}/notifications} with the module token. The module names its own key
 * ({@code author-changed}); the platform stores it as {@code m.<slug>.<key>}, renders the module's texts in each
 * recipient's language and writes the rows the recipients' bell and mail show. The recipients are OIDC subjects
 * (the {@code user} of a {@link RequestContext}, {@code kfactor} of a callback), members of the tenant; from protocol
 * 1.3 (SDK 0.9.0) the module may name roles instead ({@link NotificationOptions#recipientRoles()}): the platform adds
 * the role's active members of the tenant, each person once, and the module never sees who they are.
 *
 * <p>Checked locally first, with the platform's rules (each a {@link KfException} of class
 * {@link ErrorClass#PERMANENT}, nothing sent): a tenant, the key's grammar {@code [a-z][a-z0-9_-]{0,40}}, at most
 * {@value #MAX_RECIPIENTS} non-blank recipients, at least one recipient or one role, 1 to {@value #MAX_ROLES} role
 * names of the grammar {@code [A-Z][A-Z0-9_]{0,63}} (a repeated one counts once), variables whose values are strings,
 * numbers or booleans, a relative {@code deepLink}, channels among {@code in_app}/{@code email}. Whether the key, its
 * variables and its channels are DECLARED, and whether a role is one of the grantable system roles, is the platform's
 * answer ({@code 422}, {@code unknown-role} for a role).
 *
 * <p><strong>Retries.</strong> Only a {@code transient} answer (or a network error) is retried, on
 * {@link #DEFAULT_RETRY_DELAYS} (a {@code Retry-After} is honoured up to the longest step), and every retry sends the
 * SAME request -- the same {@code idempotencyKey}, generated once per call when the options name none -- so the platform
 * writes the notification once. A {@code permanent} refusal of the platform is a {@link Rejected} with the
 * protocol's {@code reason} ({@code unknown-key}, {@code undeclared-variable}, {@code not-a-member}, {@code not-live},
 * ...); a {@code transient} one that outlives the schedule is a {@link KfException} of class
 * {@link ErrorClass#TRANSIENT}.
 */
public final class Notifications {

    /** The most recipients one request may name. */
    public static final int MAX_RECIPIENTS = 100;
    /** The most role names of {@code recipientRoles} (protocol 1.3). */
    public static final int MAX_ROLES = 10;
    /** The shape of a role name the SDK sends (the grantable system roles are upper case); the list is the platform's. */
    public static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** The manifest grammar of a notification key (without {@code m.<slug>.}). */
    public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_-]{0,40}");
    /** The channels of the protocol. */
    public static final Set<String> CHANNELS = Set.of("in_app", "email");
    /** The retry schedule of a {@code transient} answer; its length is the number of retries. */
    public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofSeconds(2));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_DEEP_LINK = 2000;

    private final KfModule kf;
    private volatile List<Duration> retryDelays = DEFAULT_RETRY_DELAYS;
    private volatile HttpClient client;

    Notifications(KfModule kf) {
        this.kf = kf;
    }

    /**
     * What the platform did: the rows it wrote ({@code accepted}, one per recipient and channel the tenant's matrix and
     * the recipient's preferences leave on) and the rows an earlier request with the same idempotency key already
     * wrote ({@code duplicates}).
     */
    public record Result(int accepted, int duplicates) {
    }

    /** A {@code permanent} refusal of the platform, with the protocol's {@code reason} (null when the body has none). */
    public static final class Rejected extends KfException {
        private final String reason;

        Rejected(PlatformProblem problem, String reason) {
            super(problem);
            this.reason = reason;
        }

        /** The protocol's reason: {@code unknown-key}, {@code not-a-member}, {@code not-live}, ...; or null. */
        public String reason() {
            return reason;
        }
    }

    /** Replaces the retry schedule of a {@code transient} answer (tests, a module with other needs). */
    public Notifications withRetryDelays(List<Duration> delays) {
        if (delays == null || delays.stream().anyMatch(d -> d == null || d.isNegative())) {
            throw new IllegalArgumentException("retry delays must be a list of non-negative durations");
        }
        this.retryDelays = List.copyOf(delays);
        return this;
    }

    /** Sends {@code key} to {@code recipients} of {@code tenantId}, on every declared channel. */
    public Result send(UUID tenantId, String key, List<String> recipients, Map<String, ?> variables) {
        return send(tenantId, key, recipients, variables, NotificationOptions.none());
    }

    /**
     * Sends {@code key} to {@code recipients} of {@code tenantId}, answered {@code 202}.
     *
     * @throws KfException a local refusal, a network error or a {@code transient} answer that outlived the schedule
     * @throws Rejected    a {@code permanent} refusal of the platform
     */
    public Result send(UUID tenantId, String key, List<String> recipients, Map<String, ?> variables,
                       NotificationOptions options) {
        NotificationOptions opts = options == null ? NotificationOptions.none() : options;
        List<String> errors = new ArrayList<>();
        if (tenantId == null) errors.add("tenantId is required");
        if (key == null || !KEY.matcher(key).matches()) {
            errors.add("key must match [a-z][a-z0-9_-]{0,40} (the key as the manifest declares it, without m.<slug>.)");
        }
        List<String> roles = opts.recipientRoles();
        if (roles != null) {
            if (roles.isEmpty()) errors.add("recipientRoles names no role; omit it to send to recipients only");
            for (String role : roles) {
                if (role == null || !ROLE.matcher(role).matches()) {
                    errors.add("recipientRoles: '" + role + "' is not a role name (upper case, e.g. TENANT_ADMIN)");
                }
            }
            if (new LinkedHashSet<>(roles).size() > MAX_ROLES) {
                errors.add("at most " + MAX_ROLES + " recipientRoles per request");
            }
        }
        boolean byRole = roles != null && !roles.isEmpty();
        if (recipients == null || recipients.isEmpty()) {
            if (!byRole) errors.add("recipients names no one (and no recipientRoles)");
        } else if (recipients.size() > MAX_RECIPIENTS) {
            errors.add("at most " + MAX_RECIPIENTS + " recipients per request");
        } else if (recipients.stream().anyMatch(r -> r == null || r.isBlank())) {
            errors.add("a recipient subject is blank");
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        if (variables != null) {
            for (Map.Entry<String, ?> entry : variables.entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                    errors.add("the variable '" + entry.getKey() + "' must be a string, a number or a boolean");
                }
                vars.put(entry.getKey(), value);
            }
        }
        String deepLink = opts.deepLink();
        if (deepLink != null && !deepLink.isEmpty() && !relative(deepLink)) {
            errors.add("deepLink must be a relative path starting with a single '/'");
        }
        if (opts.channels() != null) {
            if (opts.channels().isEmpty()) errors.add("channels names no channel; omit it for every declared channel");
            for (String channel : opts.channels()) {
                if (!CHANNELS.contains(channel)) errors.add("channel '" + channel + "' is not one of in_app, email");
            }
        }
        if (kf.platformUrl() == null || kf.slug() == null || kf.token() == null) {
            errors.add("notifications need KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN");
        }
        if (!errors.isEmpty()) {
            throw new KfException(PlatformProblem.local("invalid notification: " + String.join("; ", errors)));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("key", key);
        body.put("recipients", recipients == null ? List.of() : List.copyOf(new LinkedHashSet<>(recipients)));
        if (byRole) body.put("recipientRoles", List.copyOf(new LinkedHashSet<>(roles)));
        body.put("variables", vars);
        if (deepLink != null && !deepLink.isEmpty()) body.put("deepLink", deepLink);
        if (opts.channels() != null) body.put("channels", opts.channels());
        UUID idempotencyKey = opts.idempotencyKey() != null ? opts.idempotencyKey() : UUID.randomUUID();
        body.put("idempotencyKey", idempotencyKey.toString());
        String json = JSON.writeValueAsString(body);

        List<Duration> delays = retryDelays;
        Duration longest = delays.stream().max(Duration::compareTo).orElse(Duration.ZERO);
        for (int attempt = 0; ; attempt++) {
            PlatformProblem problem;
            try {
                HttpResponse<String> response = client().send(request(json),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 202) return result(response.body());
                problem = PlatformProblem.parse(response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
                if (!problem.retryable()) throw new Rejected(problem, reason(response.body()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KfException(PlatformProblem.network(e));
            } catch (KfException e) {
                throw e;
            } catch (Exception e) {
                problem = PlatformProblem.network(e);
            }
            if (attempt >= delays.size()) throw new KfException(problem);
            Duration delay = delays.get(attempt);
            if (problem.retryAfterSeconds() != null) {
                Duration asked = Duration.ofSeconds(problem.retryAfterSeconds());
                delay = asked.compareTo(longest) > 0 ? longest : asked;
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KfException(problem);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private HttpRequest request(String json) {
        return HttpRequest.newBuilder(URI.create(kf.platformUrl() + "/api/v1/platform/modules/" + kf.slug()
                        + "/notifications"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + kf.token())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private static Result result(String body) {
        try {
            Object parsed = JSON.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("accepted") instanceof Number accepted
                    && map.get("duplicates") instanceof Number duplicates) {
                return new Result(accepted.intValue(), duplicates.intValue());
            }
        } catch (RuntimeException notJson) {
            // falls through: a 202 the SDK cannot read
        }
        throw new KfException(new PlatformProblem(202, null, null, "the notification answer has no accepted/duplicates",
                ErrorClass.PERMANENT, null));
    }

    private static String reason(String body) {
        try {
            Object parsed = JSON.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("reason") instanceof String reason) return reason;
        } catch (RuntimeException notJson) {
            // no reason
        }
        return null;
    }

    private static boolean relative(String link) {
        return link.startsWith("/") && !link.startsWith("//") && link.length() <= MAX_DEEP_LINK
                && link.indexOf('\\') < 0 && link.chars().noneMatch(Character::isISOControl);
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            client = c;
        }
        return c;
    }
}
