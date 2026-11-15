package ro.kf.sdk;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The module's access to the platform's audit trail: capability {@code audit} in {@code kf-module-conf.json}
 * ({@code [{"access": "write"}, {"access": "read-own"}]}). Obtained from {@link KfModule#audit()}.
 *
 * <p><strong>Implemented by the platform.</strong> Both calls go to
 * {@code /api/v1/platform/modules/{slug}/audit} with the module token and the context the module received for the
 * request -- {@code X-KF-Context} and the platform's own proof of it, {@code X-KF-Context-Proof}, both exactly as they
 * arrived (kept by {@link KfModule#verifyContext(String, String, String, String, byte[])}, which the adapters call).
 * The platform takes the tenant and the actor from that context and from nowhere else.
 *
 * <ul>
 *   <li>{@link #record}: access {@code write} -- one row in the tenant of the request, attributed to the module's slug,
 *       its type stored as {@code m.<slug>.<type>} (from a hook context, {@code actor.type = system}, the row's actor
 *       is {@code system:hook:<hookId>});</li>
 *   <li>{@link #list}: access {@code read-own} -- only the module's own rows of that tenant, newest first.</li>
 * </ul>
 *
 * <p>The record is checked locally with the platform's rules before any request ({@link AuditRecord}); a context
 * this instance did not verify (or verified more than 300 s ago), or one that came without the platform's proof, is
 * refused locally too. Every local refusal is a {@link KfException} of class {@link ErrorClass#PERMANENT} and sends
 * nothing. The platform's refusals: {@code 401} → {@link Unauthorized}, {@code 403} → {@link Forbidden} (the
 * capability is not declared, or the module is not live for the tenant), anything else → {@link KfException}.
 */
public final class AuditClient {

    /** The action: an upper-case code, as the platform validates it. */
    public static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    /** The module's own type (the platform stores it as {@code m.<slug>.<type>}). */
    public static final Pattern AGGREGATE_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,49}");
    /** The longest aggregate reference. */
    public static final int MAX_AGGREGATE_REF = 255;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final KfModule kf;
    private volatile HttpClient client;

    AuditClient(KfModule kf) {
        this.kf = kf;
    }

    /**
     * Writes one audit row in the tenant of {@code context} ({@code POST …/audit}, answered {@code 201}).
     *
     * @throws KfException  a local refusal (invalid record, unknown context, no proof) or a platform error
     * @throws Unauthorized the platform refused the token or the context ({@code 401})
     * @throws Forbidden    the capability is not declared or the module is not live for the tenant ({@code 403})
     */
    public void record(RequestContext context, AuditRecord record) {
        List<String> errors = new ArrayList<>();
        if (record == null) {
            errors.add("the record is required");
        } else {
            checkCode(errors, "action", record.action(), ACTION, "must match [A-Z][A-Z0-9_]{1,63}");
            checkCode(errors, "aggregateType", record.aggregateType(), AGGREGATE_TYPE,
                    "must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)");
            checkRef(errors, record.aggregateRef(), true);
        }
        if (!errors.isEmpty()) throw local("invalid audit record: " + String.join("; ", errors));
        KfModule.ForwardedContext forwarded = forwarded(context);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", record.action());
        body.put("aggregateType", record.aggregateType());
        body.put("aggregateRef", record.aggregateRef());
        HttpRequest request = base(forwarded, "")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        send(request, 201);
    }

    /**
     * The module's own audit rows of one aggregate type (and, when {@code aggregateRef} is not null, of one aggregate)
     * of the tenant of {@code context}, newest first ({@code GET …/audit}). Each row: {@code id}, {@code aggregateType}
     * (the module's own type), {@code aggregateRef}, {@code action}, {@code actor}, {@code performedAt},
     * {@code requestId}.
     *
     * @param aggregateRef one aggregate, or null for every aggregate of the type
     * @throws KfException  a local refusal or a platform error
     * @throws Unauthorized the platform refused the token or the context ({@code 401})
     * @throws Forbidden    the capability is not declared or the module is not live for the tenant ({@code 403})
     */
    public List<Map<String, Object>> list(RequestContext context, String aggregateType, String aggregateRef) {
        List<String> errors = new ArrayList<>();
        checkCode(errors, "aggregateType", aggregateType, AGGREGATE_TYPE,
                "must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)");
        checkRef(errors, aggregateRef, false);
        if (!errors.isEmpty()) throw local("invalid audit query: " + String.join("; ", errors));
        KfModule.ForwardedContext forwarded = forwarded(context);

        String query = "?aggregateType=" + encode(aggregateType)
                + (aggregateRef == null || aggregateRef.isBlank() ? "" : "&aggregateRef=" + encode(aggregateRef));
        String answer = send(base(forwarded, query).GET().build(), 200);
        Object parsed = JSON.readValue(answer, Object.class);
        if (!(parsed instanceof Map<?, ?> page) || !(page.get("rows") instanceof List<?> rows)) {
            throw new KfException(new PlatformProblem(200, null, null, "the audit answer has no rows", ErrorClass.PERMANENT,
                    null));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                result.add(copy);
            }
        }
        return result;
    }

    /** The platform refused the module token or the context ({@code 401}, {@code permanent}). */
    public static final class Unauthorized extends KfException {
        Unauthorized(PlatformProblem problem) {
            super(problem);
        }
    }

    /** The capability {@code audit} is not declared, or the module is not live for the tenant ({@code 403}). */
    public static final class Forbidden extends KfException {
        Forbidden(PlatformProblem problem) {
            super(problem);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private KfModule.ForwardedContext forwarded(RequestContext context) {
        if (kf.platformUrl() == null || kf.slug() == null || kf.token() == null) {
            throw local("audit needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN");
        }
        KfModule.ForwardedContext forwarded = kf.forwardedContext(context);
        if (forwarded == null) {
            throw local("the context of this request was not verified by this module in the last 300 s "
                    + "(use the RequestContext the adapter gives the route handler)");
        }
        if (forwarded.proof() == null) {
            throw local("the context of this request came without " + RequestContext.PROOF_HEADER
                    + " (an older platform)");
        }
        return forwarded;
    }

    private HttpRequest.Builder base(KfModule.ForwardedContext forwarded, String query) {
        return HttpRequest.newBuilder(URI.create(kf.platformUrl() + "/api/v1/platform/modules/" + kf.slug() + "/audit"
                        + query))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + kf.token())
                .header("Accept", "application/json")
                .header(RequestContext.CONTEXT_HEADER, forwarded.contextHeader())
                .header(RequestContext.PROOF_HEADER, forwarded.proof());
    }

    private String send(HttpRequest request, int expected) {
        HttpResponse<String> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KfException(PlatformProblem.network(e));
        } catch (Exception e) {
            throw new KfException(PlatformProblem.network(e));
        }
        if (response.statusCode() == expected) return response.body();
        PlatformProblem problem = PlatformProblem.parse(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
        if (response.statusCode() == 401) throw new Unauthorized(problem);
        if (response.statusCode() == 403) throw new Forbidden(problem);
        throw new KfException(problem);
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

    private static void checkCode(List<String> errors, String name, String value, Pattern rule, String text) {
        if (value == null || value.isBlank()) errors.add(name + " is required");
        else if (!rule.matcher(value).matches()) errors.add(name + " " + text);
    }

    private static void checkRef(List<String> errors, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) errors.add("aggregateRef is required");
            return;
        }
        if (value.trim().length() > MAX_AGGREGATE_REF) errors.add("aggregateRef must be at most " + MAX_AGGREGATE_REF
                + " characters");
        else if (value.chars().anyMatch(Character::isISOControl)) errors.add("aggregateRef must not contain control characters");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static KfException local(String detail) {
        return new KfException(PlatformProblem.local(detail));
    }
}
