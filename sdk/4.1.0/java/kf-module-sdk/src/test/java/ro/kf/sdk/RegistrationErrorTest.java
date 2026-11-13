package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration errors must name their cause.
 *
 * <p> with the platform unreachable the SDK logged {@code ConnectException: null}.
 * {@code java.net.http.HttpClient} throws a {@code ConnectException} with no message and keeps the reason
 * (refused, unresolvable host, ...) on the cause chain, which the SDK dropped. "Backend down" and "wrong host"
 * read the same.
 */
class RegistrationErrorTest {

    private static final String TOKEN = "kfm_0123456789abcdef-test-only";

    @Test
    @DisplayName("a refused connection names its cause instead of 'ConnectException: null'")
    void refusedConnectionNamesItsCause() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        KfModule kf = KfModule.fromEnv(Map.of(
                "KF_PLATFORM_URL", "http://127.0.0.1:" + port,
                "KF_MODULE_SLUG", "sdk-error-test",
                "KF_MODULE_TOKEN", TOKEN));

        kf.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (kf.lastRegistrationError() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        String error = kf.lastRegistrationError();
        assertNotNull(error, "the first attempt against a closed port must fail within the deadline");
        assertTrue(error.startsWith("ConnectException"), error);
        assertFalse(error.contains("null"), "a missing message must not be printed as 'null': " + error);
        assertTrue(error.contains("(cause: "), "the cause chain must be named: " + error);
        assertFalse(error.contains(TOKEN), "the module token must never reach an error message");
    }
}
