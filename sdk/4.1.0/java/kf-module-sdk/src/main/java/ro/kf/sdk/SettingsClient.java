package ro.kf.sdk;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The module's settings in a tenant: capability {@code settings} in {@code kf-module-conf.json}, protocol 1.1 §15.2
 * ("settings carry no data": a setting is a closed scalar). Obtained from {@link KfModule#settings()}.
 *
 * <pre>{@code
 * Map<String, Object> s = kf.settings().effective(ctx);
 * boolean live = (Boolean) s.get("live");             // BOOLEAN -> Boolean
 * BigDecimal batch = (BigDecimal) s.get("batch-size"); // NUMBER  -> BigDecimal
 * String mode = (String) s.get("push-mode");           // ENUM    -> String, one of the declared values
 * }</pre>
 *
 * <p>{@link #effective} sends {@code GET …/settings} with the module token, the context and its proof, unchanged
 * ({@link PlatformChannel}); the platform answers every declared key of the context's tenant, defaults included. The
 * values are typed by the module's manifest ({@link Manifest.SettingCapability}): {@code BOOLEAN} → {@link Boolean},
 * {@code NUMBER} → {@link BigDecimal} (within {@code min..max} when declared), {@code ENUM} → {@link String} (one of
 * {@code values}). A declared key missing from the answer, or a value that does not match its declaration, takes the
 * declared default (with a WARN); a key the manifest does not declare is ignored (with a WARN). Without the manifest
 * the call is refused locally (nothing to type the values with). The same local refusals as {@link ReadsClient} for the
 * context; the platform's refusals are {@link PlatformRefusal}s.
 */
public final class SettingsClient {

    private static final System.Logger LOG = System.getLogger("ro.kf.sdk");

    private final PlatformChannel channel;

    SettingsClient(KfModule kf) {
        this.channel = new PlatformChannel(kf, "settings");
    }

    /**
     * The effective settings of the module in the tenant of {@code context}, typed by the manifest, in the order the
     * manifest declares them.
     *
     * @throws KfException     a local refusal (no manifest, unknown context, no proof), a transient error or an
     *                         unreadable answer
     * @throws PlatformRefusal the platform refused the call ({@code 401}, {@code 403})
     */
    public Map<String, Object> effective(RequestContext context) {
        Manifest manifest = channel.kf().manifest();
        if (manifest == null) {
            throw PlatformChannel.local("settings need the module manifest (KF_MODULE_MANIFEST) to type the values");
        }
        KfModule.ForwardedContext forwarded = channel.forwarded(context);
        PlatformChannel.Answer answer = channel.send("GET", forwarded, "", "", null);
        if (answer.status() != 200) throw channel.failure(answer);
        Map<String, Object> values = channel.answerObject(answer, "an object of values");

        Map<String, Object> result = new LinkedHashMap<>();
        for (Manifest.SettingCapability setting : manifest.capabilities().settings()) {
            Object fallback = typed(setting, setting.defaultValue());
            if (!values.containsKey(setting.key())) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: setting {0} is missing from the platform''s answer; "
                        + "the declared default applies", setting.key());
                result.put(setting.key(), fallback);
                continue;
            }
            Object value = typed(setting, values.get(setting.key()));
            if (value == null) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: setting {0} does not match its declaration ({1}); "
                        + "the declared default applies", setting.key(), setting.type());
                value = fallback;
            }
            result.put(setting.key(), value);
        }
        for (String key : values.keySet()) {
            if (!result.containsKey(key)) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: setting {0} is not declared in the manifest; ignored", key);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** The value typed by its declaration, or null when it does not match it. */
    static Object typed(Manifest.SettingCapability setting, Object value) {
        if (value == null || setting.type() == null) return null;
        return switch (setting.type()) {
            case "BOOLEAN" -> value instanceof Boolean b ? b : null;
            case "NUMBER" -> {
                if (!(value instanceof Number n)) yield null;
                BigDecimal number = decimal(n);
                if (setting.min() != null && number.compareTo(decimal(setting.min())) < 0) yield null;
                if (setting.max() != null && number.compareTo(decimal(setting.max())) > 0) yield null;
                yield number;
            }
            case "ENUM" -> value instanceof String s && setting.values().contains(s) ? s : null;
            default -> null;
        };
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }
}
