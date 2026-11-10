package ro.kf.modules.democard.web;

import ro.kf.sdk.ErrorClass;

import java.util.Map;

/**
 * An RFC 9457 answer of the module's own routes (sdk-protocol.md §10): thrown by a controller or a service, written by
 * {@link ProblemAdvice} as {@code application/problem+json} with {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code class} and, for a validation, {@code errors} (an object field → message).
 *
 * <p>The detail is text for the operator: never a secret, never an upstream body.
 */
public final class Problem extends RuntimeException {

    private final int status;
    private final String type;
    private final String title;
    private final ErrorClass errorClass;
    private final Map<String, String> errors;
    private final Map<String, Object> extra;

    public Problem(int status, String type, String title, String detail, ErrorClass errorClass, Map<String, String> errors) {
        this(status, type, title, detail, errorClass, errors, null);
    }

    public Problem(int status, String type, String title, String detail, ErrorClass errorClass, Map<String, String> errors,
                   Map<String, Object> extra) {
        super(detail);
        this.status = status;
        this.type = "https://kf.ro/problems/" + type;
        this.title = title;
        this.errorClass = errorClass == null ? ErrorClass.forStatus(status) : errorClass;
        this.errors = errors == null ? Map.of() : Map.copyOf(errors);
        this.extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** 400: the request is invalid ({@code errors} names the fields). */
    public static Problem invalid(String detail, Map<String, String> errors) {
        return new Problem(400, "validation", "Invalid request", detail, ErrorClass.PERMANENT, errors);
    }

    /** 403: the context is not allowed on the route (the platform checks it first; the module checks again). */
    public static Problem forbidden(String detail) {
        return new Problem(403, "insufficient-rights", "Forbidden", detail, ErrorClass.PERMANENT, null);
    }

    public static Problem notFound(String type, String detail) {
        return new Problem(404, type, "Not found", detail, ErrorClass.PERMANENT, null);
    }

    public static Problem conflict(String type, String detail) {
        return new Problem(409, type, "Conflict", detail, ErrorClass.PERMANENT, null);
    }

    /** 502: an upstream (the platform, GitHub) did not answer as it should; transient when a retry can succeed. */
    public static Problem upstream(String type, String detail, boolean transientFailure) {
        return new Problem(502, type, "Bad gateway", detail,
                transientFailure ? ErrorClass.TRANSIENT : ErrorClass.PERMANENT, null);
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public Map<String, String> errors() {
        return errors;
    }

    /** More top-level members of the problem (RFC 9457 extensions), e.g. the ticket's {@code tenantId}. */
    public Map<String, Object> extra() {
        return extra;
    }
}
