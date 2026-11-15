package ro.kf.sdk;

/**
 * What to answer to a platform callback or to a refused request: HTTP status, body and its content type
 * ({@code application/json} for a decision or an ack, {@code application/problem+json} for an RFC 9457 refusal).
 */
public record HookResult(int status, String body, String contentType) {

    public static final String CONTENT_TYPE = "application/json";
    public static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

    /** A JSON answer. */
    public HookResult(int status, String body) {
        this(status, body, CONTENT_TYPE);
    }
}
