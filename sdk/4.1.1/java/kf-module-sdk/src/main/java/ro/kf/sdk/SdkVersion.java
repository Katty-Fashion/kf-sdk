package ro.kf.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.Set;

/**
 * What this SDK is and which protocol it speaks.
 *
 * <ul>
 *   <li>{@link #SDK_VERSION}: the artifact version from {@code pom.xml} (filtered into {@code sdk.properties}); the one
 *       semver every SDK carries.</li>
 *   <li>{@link #PROTOCOL_SCHEMA_VERSION}: the {@code schemaVersion} of {@code kf-module-conf.json} this SDK reads and
 *       checks locally ({@code 3} from 0.10.0: protocol 2.0, a strict superset of {@code 2}, which
 *       introduced and shaped; {@code 1} and {@code 2} stay readable).</li>
 * </ul>
 */
public final class SdkVersion {

    /** The SDK's own semver, e.g. {@code 0.7.0}. */
    public static final String SDK_VERSION = load();

    /** The manifest {@code schemaVersion} this SDK speaks; a newer manifest is refused locally. */
    public static final int PROTOCOL_SCHEMA_VERSION = 3;

    /**
     * The majors of the callback envelope ({@code kfenvelopeversion}, {@code envelopeVersion}) this SDK reads:
     * {@code {1}} ({@code DomainEventEnvelope.CURRENT_VERSION}, with {@code kfeventid}). A callback
     * with another major, or without the attribute, is answered {@code 422} without calling a handler.
     */
    public static final Set<Integer> SUPPORTED_ENVELOPE_MAJORS = Set.of(1);

    private SdkVersion() {
    }

    private static String load() {
        Properties properties = new Properties();
        try (InputStream in = SdkVersion.class.getResourceAsStream("sdk.properties")) {
            if (in == null) throw new IllegalStateException("ro/kf/sdk/sdk.properties is missing from the SDK jar");
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = properties.getProperty("sdk.version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("sdk.properties was not filtered by Maven: sdk.version=" + version);
        }
        return version.trim();
    }
}
