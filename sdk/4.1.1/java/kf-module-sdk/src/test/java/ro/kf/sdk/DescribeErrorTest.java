package ro.kf.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link KfModule#describe}: the one place a registration failure becomes a log line. */
class DescribeErrorTest {

    @Test
    @DisplayName("describe: a null message gives way to the cause type, duplicates collapsed")
    void nullMessageShowsTheCauseChain() {
        // the shape HttpClient.send produces on a refused connection (measured on JDK 21)
        Throwable refused = new ConnectException();
        refused.initCause(new ConnectException());
        refused.getCause().initCause(new ClosedChannelException());

        assertEquals("ConnectException (cause: ClosedChannelException)", KfModule.describe(refused));
    }

    @Test
    @DisplayName("describe: an unresolvable host is named as such")
    void unresolvableHost() {
        Throwable unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());

        assertEquals("ConnectException (cause: UnresolvedAddressException)", KfModule.describe(unresolved));
    }

    @Test
    @DisplayName("describe: cause messages are kept when they exist")
    void causeMessageIsKept() {
        Throwable e = new ConnectException();
        e.initCause(new ConnectException("Connection refused"));

        assertEquals("ConnectException (cause: ConnectException: Connection refused)", KfModule.describe(e));
    }

    @Test
    @DisplayName("describe: an exception with a message and no cause is unchanged")
    void messageWithoutCause() {
        assertEquals("HttpConnectTimeoutException: HTTP connect timed out",
                KfModule.describe(new HttpConnectTimeoutException("HTTP connect timed out")));
    }

    @Test
    @DisplayName("describe: a cyclic cause chain terminates")
    void cyclicChainTerminates() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);

        assertEquals("Exception: a (cause: Exception: b)", KfModule.describe(a));
    }
}
