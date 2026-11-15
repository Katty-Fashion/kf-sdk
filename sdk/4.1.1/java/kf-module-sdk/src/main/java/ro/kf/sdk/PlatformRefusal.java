package ro.kf.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal of the platform to one of the module's calls of protocol 1.1 (§15: {@link ReadsClient},
 * {@link SettingsClient}, {@link SecretsClient}, {@link ExtensionsClient}): {@code 400}, {@code 401}, {@code 403} or
 * {@code 404}, always {@link ErrorClass#PERMANENT} unless the body says otherwise. Any other failure (a {@code 5xx}, a
 * network error, an answer the SDK cannot read) is a plain {@link KfException}; a local refusal (before any request)
 * is a plain {@link KfException} of status {@code 0}.
 *
 * <p>{@link #reason()} is the protocol's reason: the {@code reason} member of the RFC 9457 body when present,
 * otherwise the last segment of its {@code type} ({@code https://kf.ro/problems/insufficient-rights} →
 * {@code insufficient-rights}); null when the body has neither. The reasons of §15: {@code capability-not-declared},
 * {@code module-not-live}, {@code insufficient-rights} ({@code 403}), {@code invalid-context} ({@code 401}),
 * {@code validation} ({@code 400}), {@code not-found} ({@code 404}). Between modules (protocol 2.0, §15.9, §6.3,
 * {@link PeersClient}, {@link EventsClient}) also {@code 409}, {@code 422}, {@code 502} and {@code 503}, with the reasons
 * {@code peer-not-required}, {@code peer-port-unknown}, {@code peer-command-unknown}, {@code peer-forbidden},
 * {@code idempotency-key-required}, {@code idempotency-conflict}, {@code idempotency-in-progress},
 * {@code not-configured}, {@code module-refused}, {@code module-unavailable}, {@code invalid-answer} and
 * {@code event-not-declared}; their class is the body's ({@code idempotency-in-progress} and a stopped owner's
 * {@code module-unavailable} are transient).
 *
 * <p>{@link #errors()} are the field errors of a {@code 400} ({@code errors} of the body, field → message; e.g.
 * {@code extensions.<slug>.<field>} on an extension write), empty otherwise; the message of the exception names them.
 */
public final class PlatformRefusal extends KfException {

    /** What the platform refused, by status. */
    public enum Kind {
        /** {@code 400}: the request is invalid ({@link #errors()}). */
        INVALID("invalid"),
        /** {@code 401}: the token or the context ({@code invalid-context}). */
        UNAUTHORIZED("unauthorized"),
        /** {@code 403}: the capability, the module's state in the tenant, or the user's rights. */
        FORBIDDEN("forbidden"),
        /** {@code 404}: nothing visible in the context's tenant. */
        NOT_FOUND("not-found"),
        /**
         * {@code 409}, between modules only (protocol 2.0, §15.9): {@code idempotency-conflict} (permanent) or
         * {@code idempotency-in-progress} (transient).
         */
        CONFLICT("conflict"),
        /**
         * {@code 422}, between modules only (§15.9, §6.3): the owner refused the call ({@code module-refused}) or the
         * event is not declared by the active version ({@code event-not-declared}).
         */
        REFUSED("refused"),
        /** {@code 502}, between modules only (§15.9): the owner answered a {@code 2xx} that is not JSON. */
        INVALID_ANSWER("invalid-answer"),
        /**
         * {@code 503}, between modules only (§15.9): {@code module-unavailable} -- permanent when the owner is not
         * installed or outside the required range, transient when it is stopped or not activated for the tenant.
         */
        UNAVAILABLE("unavailable");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /**
         * {@code invalid}, {@code unauthorized}, {@code forbidden}, {@code not-found}, {@code conflict},
         * {@code refused}, {@code invalid-answer}, {@code unavailable}: the name the fixtures use.
         */
        public String wire() {
            return wire;
        }

        /** The kind of a status, or null when the status is not a refusal of this kind. */
        static Kind of(int status) {
            return switch (status) {
                case 400 -> INVALID;
                case 401 -> UNAUTHORIZED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                default -> null;
            };
        }

        /**
         * The kind of a status on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3), where the
         * platform answers {@code 409}, {@code 422}, {@code 502} and {@code 503} with a reason too; null otherwise.
         */
        static Kind betweenModules(int status) {
            return switch (status) {
                case 409 -> CONFLICT;
                case 422 -> REFUSED;
                case 502 -> INVALID_ANSWER;
                case 503 -> UNAVAILABLE;
                default -> of(status);
            };
        }

        /**
         * The kind of a status on a platform service (protocol 2.0, {@code order.services.v1}, §16.6):
         * {@code 409 order-service-line-owned-by-module} (a delete of a line another module wrote) is
         * {@link #CONFLICT}, {@code 422 not-delivered} is {@link #REFUSED}, {@code 501 no-provider}
         * {@link #UNAVAILABLE}; null otherwise.
         */
        static Kind services(int status) {
            return switch (status) {
                case 409 -> CONFLICT; // order-service-line-owned-by-module
                case 422 -> REFUSED;
                case 501 -> UNAVAILABLE;
                default -> of(status);
            };
        }
    }

    private final Kind kind;
    private final String reason;
    private final Map<String, String> errors;

    PlatformRefusal(PlatformProblem problem, Kind kind, String reason, Map<String, String> errors) {
        super(problem);
        this.kind = kind;
        this.reason = reason;
        this.errors = errors == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Kind kind() {
        return kind;
    }

    /** The protocol's reason ({@code insufficient-rights}, {@code capability-not-declared}, …), or null. */
    public String reason() {
        return reason;
    }

    /** The field errors of a {@code 400}, field → message; empty otherwise. */
    public Map<String, String> errors() {
        return errors;
    }
}
