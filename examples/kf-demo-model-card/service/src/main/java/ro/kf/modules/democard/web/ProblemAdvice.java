package ro.kf.modules.democard.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.KfException;
import ro.kf.sdk.PlatformRefusal;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Map;

/**
 * Writes the module's problems (RFC 9457 with {@code class}, sdk-protocol.md §10): {@link Problem}; a refusal of the
 * platform to one of the module's calls ({@link PlatformRefusal}: 400/403/404 pass through with their reason, 401
 * means the module's own token: a 502); a failure of the platform ({@link KfException}: a 502 with its class); a body
 * that is not JSON. Never an upstream body, a secret or personal data in the text.
 */
@RestControllerAdvice
public class ProblemAdvice {

    public static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(Problem.class)
    public ResponseEntity<String> problem(Problem p) {
        return write(p.status(), p.type(), p.title(), p.getMessage(), p.errorClass(), p.errors(), p.extra());
    }

    @ExceptionHandler(PlatformRefusal.class)
    public ResponseEntity<String> platformRefusal(PlatformRefusal refusal) {
        int status = refusal.problem().status();
        if (status == 401) {
            return write(502, "https://kf.ro/problems/platform-refused-module", "Bad gateway",
                    "the platform refused the module's call", ErrorClass.PERMANENT, null, null);
        }
        String reason = refusal.reason() == null ? "platform-refused" : refusal.reason();
        return write(status, "https://kf.ro/problems/" + reason,
                refusal.problem().title() == null ? "Refused" : refusal.problem().title(),
                "the platform refused the module's call", ErrorClass.PERMANENT, refusal.errors(), null);
    }

    /**
     * A failure of the platform: a 502, except the platform's secret store, whose two {@code 503}s pass on with their
     * type (as KF-AAS-R3, quick 261024-secret-ref): {@code secret-store-unreachable} (transient) and
     * {@code secret-store-rejected} (permanent). The platform's detail carries no value (sdk-protocol.md §15.3).
     */
    @ExceptionHandler(KfException.class)
    public ResponseEntity<String> platformFailure(KfException failure) {
        String type = failure.problem().type();
        if (failure.problem().status() == 503 && type != null) {
            if (type.endsWith("/secret-store-unreachable")) {
                return write(503, "https://kf.ro/problems/secret-store-unreachable", "Secret store unreachable",
                        "the platform's secret store is unreachable", ErrorClass.TRANSIENT, null, null);
            }
            if (type.endsWith("/secret-store-rejected")) {
                return write(503, "https://kf.ro/problems/secret-store-rejected", "Secret store rejected",
                        "the platform's secret store refused the path", ErrorClass.PERMANENT, null, null);
            }
        }
        return write(502, "https://kf.ro/problems/platform-unavailable", "Bad gateway",
                "the platform did not answer the module's call", failure.errorClass(), null, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadable(HttpMessageNotReadableException e) {
        return write(400, "https://kf.ro/problems/validation", "Invalid request", "the body is not the expected JSON",
                ErrorClass.PERMANENT, null, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e) {
        return write(400, "https://kf.ro/problems/validation", "Invalid request",
                "the query parameter " + e.getParameterName() + " is required", ErrorClass.PERMANENT,
                Map.of(e.getParameterName(), "required"), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return write(400, "https://kf.ro/problems/validation", "Invalid request",
                "the parameter " + e.getName() + " is not valid", ErrorClass.PERMANENT, Map.of(e.getName(), "invalid"),
                null);
    }

    public static ResponseEntity<String> write(int status, String type, String title, String detail,
                                               ErrorClass errorClass, Map<String, String> errors,
                                               Map<String, Object> extra) {
        var body = JsonNodeFactory.instance.objectNode();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put(ErrorClass.PROPERTY, (errorClass == null ? ErrorClass.forStatus(status) : errorClass).wire());
        if (errors != null && !errors.isEmpty()) {
            var e = body.putObject("errors");
            errors.forEach(e::put);
        }
        if (extra != null) extra.forEach((k, v) -> body.put(k, v == null ? null : String.valueOf(v)));
        return ResponseEntity.status(status).contentType(PROBLEM).body(body.toString());
    }
}
