package ro.kf.sdk;

import java.util.Optional;

/**
 * The class of a platform error: whether repeating the same request can succeed.
 *
 * <p>The platform writes it as {@code "class"} in every RFC 9457 body of the module protocol
 * ({@code /api/v1/platform/**}, {@code /m/**}). When the body carries none, the SDK derives it from the status with the
 * platform's own table ({@link #forStatus}): {@code 408}, {@code 425}, {@code 429} and every {@code 5xx} are
 * {@link #TRANSIENT}; every other status is {@link #PERMANENT}.
 *
 * <p>The SDK retries <strong>only</strong> transient errors (and network errors, which are transient by nature). A
 * permanent error stops the retry and names its cause: a wrong token, a module in the wrong state or a
 * refused registration does not fix itself, and retrying it forever only hides it.
 */
public enum ErrorClass {

    PERMANENT("permanent"),
    TRANSIENT("transient");

    /** The RFC 9457 extension member that carries the class. */
    public static final String PROPERTY = "class";

    private final String wire;

    ErrorClass(String wire) {
        this.wire = wire;
    }

    /** {@code permanent} or {@code transient}, as on the wire. */
    public String wire() {
        return wire;
    }

    /** Empty for anything else, including {@code null} (the caller decides what an unknown class means). */
    public static Optional<ErrorClass> fromWire(String wire) {
        if (wire == null) return Optional.empty();
        for (ErrorClass c : values()) {
            if (c.wire.equals(wire)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** The platform's table ({@code ProblemClass.forStatus}): 408, 425, 429 and 5xx are transient. */
    public static ErrorClass forStatus(int status) {
        return status == 408 || status == 425 || status == 429 || (status >= 500 && status <= 599) ? TRANSIENT : PERMANENT;
    }
}
