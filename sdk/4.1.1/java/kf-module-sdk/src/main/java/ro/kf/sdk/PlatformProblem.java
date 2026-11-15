package ro.kf.sdk;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * A platform error as the SDK reads it: an RFC 9457 body ({@code type}, {@code title}, {@code status}, {@code detail},
 * {@code class}) or, without a readable body, just the status; or a network error.
 *
 * <p>The {@link #errorClass()} decides whether the SDK retries:
 * <ul>
 *   <li>{@code class} present and known: that class;</li>
 *   <li>{@code class} present but unknown: {@link ErrorClass#PERMANENT} -- an answer the SDK does not understand must
 *       never turn into an endless retry;</li>
 *   <li>{@code class} absent (an older platform, a filter that writes its own body, no body at all): the status table
 *       {@link ErrorClass#forStatus};</li>
 *   <li>a network error (refused, unresolvable host, timeout): {@link ErrorClass#TRANSIENT}.</li>
 * </ul>
 *
 * <p>{@link #describe()} is the one line the SDK logs and keeps as {@code lastRegistrationError}: the status, the class
 * and the {@code detail}. It is built from the answer only, never from the request, so the module token cannot reach
 * it.
 *
 * @param status            the HTTP status, {@code 0} for a network error
 * @param type              the problem type URI, or null
 * @param title             the problem title, or null
 * @param detail            the problem detail (or, for a network error, the cause chain), or null
 * @param errorClass        whether retrying can succeed
 * @param retryAfterSeconds the {@code Retry-After} the platform asked for, or null
 */
public record PlatformProblem(int status, String type, String title, String detail, ErrorClass errorClass,
                              Integer retryAfterSeconds) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_DETAIL = 500;

    /** Reads one non-2xx answer of the platform. Never throws: an unreadable body is a body without problem. */
    public static PlatformProblem parse(int status, String body, String retryAfterHeader) {
        String type = null;
        String title = null;
        String detail = null;
        ErrorClass errorClass = ErrorClass.forStatus(status);
        if (body != null && !body.isBlank()) {
            try {
                Object parsed = JSON.readValue(body, Object.class);
                if (parsed instanceof Map<?, ?> problem) {
                    type = text(problem.get("type"));
                    title = text(problem.get("title"));
                    detail = text(problem.get("detail"));
                    if (problem.containsKey(ErrorClass.PROPERTY)) {
                        errorClass = ErrorClass.fromWire(text(problem.get(ErrorClass.PROPERTY))).orElse(ErrorClass.PERMANENT);
                    }
                }
            } catch (RuntimeException notJson) {
                // a body that is not JSON carries no class: the status decides
            }
        }
        return new PlatformProblem(status, type, title, truncate(detail), errorClass, retryAfter(retryAfterHeader));
    }

    /** A network error: no answer at all. Transient. */
    public static PlatformProblem network(Throwable error) {
        return new PlatformProblem(0, null, null, KfModule.describe(error), ErrorClass.TRANSIENT, null);
    }

    /**
     * A refusal of the SDK itself, before any request (the local manifest check, a capability the platform does not
     * implement yet): permanent, status {@code 0}.
     */
    public static PlatformProblem local(String detail) {
        return new PlatformProblem(0, null, null, detail, ErrorClass.PERMANENT, null);
    }

    /** Whether the SDK retries the request. */
    public boolean retryable() {
        return errorClass == ErrorClass.TRANSIENT;
    }

    /**
     * One line for the log: {@code HTTP 409 (permanent): Module 'x' is FAILED; expected INSTALLED}, or the cause chain
     * of a network error. Never contains the token (only the answer is read).
     */
    public String describe() {
        if (status == 0) return detail + " (" + errorClass.wire() + ")"; // a network error or a local refusal
        String text = detail != null ? detail : title;
        return "HTTP " + status + " (" + errorClass.wire() + ")" + (text == null ? "" : ": " + text);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "…";
    }

    private static Integer retryAfter(String header) {
        if (header == null) return null;
        try {
            int seconds = Integer.parseInt(header.trim());
            return seconds < 0 ? null : Math.min(seconds, 300);
        } catch (NumberFormatException e) {
            return null; // an HTTP-date is not used by the platform
        }
    }
}
