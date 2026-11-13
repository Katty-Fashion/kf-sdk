package ro.kf.sdk;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The module's channel to one platform service of protocol 1.1 (§15: {@code reads}, {@code settings}, {@code secrets},
 * {@code extensions}), shared by {@link ReadsClient}, {@link SettingsClient}, {@link SecretsClient} and
 * {@link ExtensionsClient}. Not public: the clients are the API.
 *
 * <p>Every call goes to {@code /api/v1/platform/modules/{slug}/{service}…} with the module token and the context the
 * module received for the request -- {@code X-KF-Context} and the platform's proof of it, {@code X-KF-Context-Proof},
 * both exactly as they arrived (kept by {@link KfModule#verifyContext(String, String, String, String, byte[])}), as the
 * audit does (§11.2). A context this instance did not verify (or verified more than 300 s ago), or one that came
 * without the proof, is refused locally, {@link ErrorClass#PERMANENT}, and nothing is sent.
 *
 * <p>The platform's {@code 400}/{@code 401}/{@code 403}/{@code 404} are {@link PlatformRefusal}s; any other non-2xx
 * answer is a {@link KfException} with the class the body (or the status table) gives; a network error is
 * {@link ErrorClass#TRANSIENT}. Numbers with a fraction are read as {@link java.math.BigDecimal}, exactly as sent
 * ({@code 12.50} stays {@code 12.50}); whole numbers as {@link Integer} / {@link Long}.
 */
final class PlatformChannel {

    /** Reads numbers with a fraction as {@code BigDecimal}: the platform's values are never normalized (§15.1). */
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** One answer of the platform: the status, the body (never null) and its {@code Retry-After}, if any. */
    record Answer(int status, String body, String retryAfter) {
    }

    private final KfModule kf;
    private final String service;
    private volatile HttpClient client;

    PlatformChannel(KfModule kf, String service) {
        this.kf = kf;
        this.service = service;
    }

    KfModule kf() {
        return kf;
    }

    /**
     * Protocol 2.0: the context and proof of an after delivery, for a background job, or a local refusal
     * (nothing is sent) when the configuration is missing or the context is outside its ±300 s window.
     */
    KfModule.ForwardedContext forwarded(HookEvents.DeliveryContext delivery) {
        if (kf.platformUrl() == null || kf.slug() == null || kf.token() == null) {
            throw local(service + " needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN");
        }
        if (delivery == null) throw local("a delivery context is required");
        if (!delivery.usable()) {
            throw local("the delivery context is outside its " + HookEvents.DeliveryContext.WINDOW_SECONDS
                    + " s window (ts " + delivery.ts() + "); the platform would refuse it");
        }
        return new KfModule.ForwardedContext(delivery.contextHeader(), delivery.proof(), delivery.ts());
    }

    /** The raw context and proof of {@code context}, or a local refusal (nothing is sent). */
    KfModule.ForwardedContext forwarded(RequestContext context) {
        if (kf.platformUrl() == null || kf.slug() == null || kf.token() == null) {
            throw local(service + " needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN");
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

    /**
     * Sends one call: {@code method} on {@code …/{service}{path}{query}} ({@code path} and {@code query} already
     * encoded; {@code query} empty or starting with {@code ?}), {@code body} as JSON when not null.
     */
    Answer send(String method, KfModule.ForwardedContext forwarded, String path, String query, Object body) {
        return send(method, forwarded, path, query, body, Map.of());
    }

    /** {@link #send(String, KfModule.ForwardedContext, String, String, Object)} with more headers (e.g. {@code Idempotency-Key}). */
    Answer send(String method, KfModule.ForwardedContext forwarded, String path, String query, Object body,
                Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(kf.platformUrl() + "/api/v1/platform/modules/"
                        + kf.slug() + "/" + service + path + query))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + kf.token())
                .header("Accept", "application/json")
                .header(RequestContext.CONTEXT_HEADER, forwarded.contextHeader())
                .header(RequestContext.PROOF_HEADER, forwarded.proof());
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = client().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KfException(PlatformProblem.network(e));
        } catch (Exception e) {
            throw new KfException(PlatformProblem.network(e));
        }
        return new Answer(response.statusCode(), response.body() == null ? "" : response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /** The error of a non-2xx answer: a {@link PlatformRefusal} for 400/401/403/404, a {@link KfException} otherwise. */
    KfException failure(Answer answer) {
        return failure(answer, UnaryOperator.identity());
    }

    /**
     * {@link #failure(Answer)} with every text of the answer passed through {@code redact} first (the secrets client
     * removes a value the platform might echo).
     */
    KfException failure(Answer answer, UnaryOperator<String> redact) {
        return failure(answer, redact, PlatformRefusal.Kind.of(answer.status()));
    }

    /**
     * The error of a non-2xx answer on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3): also
     * {@code 409}, {@code 422}, {@code 502} and {@code 503} are {@link PlatformRefusal}s, with the reason of the body.
     */
    KfException betweenModulesFailure(Answer answer) {
        return failure(answer, UnaryOperator.identity(), PlatformRefusal.Kind.betweenModules(answer.status()));
    }

    /**
     * The error of a non-2xx answer of a platform service (protocol 2.0, {@code order.services.v1}, §16.6): also
     * {@code 422 not-delivered} ({@code refused}) and {@code 501 no-provider} ({@code unavailable}) are
     * {@link PlatformRefusal}s, with the reason of the body.
     */
    KfException servicesFailure(Answer answer) {
        return failure(answer, UnaryOperator.identity(), PlatformRefusal.Kind.services(answer.status()));
    }

    private KfException failure(Answer answer, UnaryOperator<String> redact, PlatformRefusal.Kind kind) {
        PlatformProblem parsed = PlatformProblem.parse(answer.status(), answer.body(), answer.retryAfter());
        Map<String, Object> body = object(answer.body());
        Map<String, String> errors = new LinkedHashMap<>();
        if (body != null) {
            if (body.get("errors") instanceof Map<?, ?> map) {
                map.forEach((k, v) -> errors.put(redact.apply(String.valueOf(k)), redact.apply(String.valueOf(v))));
            } else if (body.get("errors") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> e && e.get("field") != null) {
                        Object message = e.get("message") != null ? e.get("message") : e.get("code");
                        errors.put(redact.apply(String.valueOf(e.get("field"))), redact.apply(String.valueOf(message)));
                    }
                }
            }
        }
        String detail = parsed.detail() == null ? null : redact.apply(parsed.detail());
        if (!errors.isEmpty()) {
            StringBuilder named = new StringBuilder();
            errors.forEach((field, message) -> named.append(named.isEmpty() ? "" : "; ").append(field).append(": ").append(message));
            detail = (detail == null ? "" : detail + " ") + "(errors: " + named + ")";
        }
        PlatformProblem problem = new PlatformProblem(parsed.status(), parsed.type(),
                parsed.title() == null ? null : redact.apply(parsed.title()), detail, parsed.errorClass(),
                parsed.retryAfterSeconds());
        if (kind == null) return new KfException(problem);
        return new PlatformRefusal(problem, kind, reason(body, parsed.type()), errors);
    }

    /** The JSON value of a 2xx answer (object, array or scalar), or a permanent error when it is not JSON. */
    Object answerJson(Answer answer, String what) {
        try {
            if (answer.body().isBlank()) throw new IllegalArgumentException("empty");
            return JSON.readValue(answer.body(), Object.class);
        } catch (RuntimeException notJson) {
            throw new KfException(new PlatformProblem(answer.status(), null, null,
                    "the " + service + " answer is not " + what, ErrorClass.PERMANENT, null));
        }
    }

    /** The JSON object of a 2xx answer, or a permanent error naming what was expected. */
    Map<String, Object> answerObject(Answer answer, String what) {
        Map<String, Object> parsed = object(answer.body());
        if (parsed == null) throw new KfException(new PlatformProblem(answer.status(), null, null,
                "the " + service + " answer is not " + what, ErrorClass.PERMANENT, null));
        return parsed;
    }

    /** A refusal of the SDK itself, before any request: permanent, status {@code 0}. */
    static KfException local(String detail) {
        return new KfException(PlatformProblem.local(detail));
    }

    /** One path segment, percent-encoded ({@code 9 9} → {@code 9%209}). */
    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** One query value, form-encoded as the audit does ({@code 4 2} → {@code 4+2}). */
    static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A JSON text as an object with string keys (order kept), or null when it is not one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Object parsed = JSON.readValue(text, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String reason(Map<String, Object> body, String type) {
        if (body != null && body.get("reason") instanceof String reason && !reason.isBlank()) return reason;
        if (type == null || type.isBlank()) return null;
        String last = type.substring(type.lastIndexOf('/') + 1);
        return last.isBlank() ? null : last;
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
