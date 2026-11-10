package ro.kf.modules.democard;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The slug the module runs under, read at run time (the pattern of KF-AAS-R3, plan 222-15): the same code runs
 * as {@code kf-demo-model-card} and as its dev build {@code kf-demo-model-card-dev} (built by {@code build.sh --dev-identity}, installed
 * unsigned on the development organization). Everything the platform addresses by slug follows it: the base of the
 * routes ({@code /m/<slug>}), the prefix of the route rights ({@code api.v1.m.<slug>.}), the UI bundle and stylesheet,
 * the host's tab keys ({@code <slug>.<key>}).
 *
 * <p>The source is {@code KF_MODULE_SLUG}, which the platform injects into the container (the generated
 * {@code docker-compose.kf.yml}, sdk-protocol §2) — the same value {@code KfModule.fromEnv} reads. Without it (the tests,
 * a local run from {@code service/}) the slug is the source manifest's, {@link #SOURCE_SLUG}: the only place the
 * module names its slug. A slug of another form stops the module at boot ({@link IllegalStateException}).
 *
 * <p>What does NOT follow the slug: the contracts of the code -- the format {@code kf-demo-model-card/tenant-data/v1},
 * the texts' keys. The route rights in {@code RouteRights} keep the codes of the source manifest; {@link #right(String)}
 * moves one under the slug of the run.
 */
public final class ModuleIdentity {

    /** The slug of the source manifest ({@code kf-module-conf.json}); the fallback without {@code KF_MODULE_SLUG}. */
    public static final String SOURCE_SLUG = "kf-demo-model-card";

    /** The environment variable of the platform (sdk-protocol §2). */
    public static final String ENV = "KF_MODULE_SLUG";

    /** The platform's slug ({@code ModuleSlug.PATTERN}): 2..41 characters, lower-case letters, digits and dashes. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");

    private static final String DEV_SUFFIX = "-dev";

    private static final ModuleIdentity SOURCE = new ModuleIdentity(SOURCE_SLUG);

    private final String slug;

    private ModuleIdentity(String slug) {
        this.slug = slug;
    }

    /** The identity of the source manifest. */
    public static ModuleIdentity source() {
        return SOURCE;
    }

    /**
     * The identity of {@code slug}; {@code null} or blank is the source one.
     *
     * @throws IllegalStateException when {@code slug} is not a platform slug
     */
    public static ModuleIdentity of(String slug) {
        if (slug == null || slug.isBlank()) return SOURCE;
        String value = slug.trim();
        if (!SLUG.matcher(value).matches()) {
            throw new IllegalStateException(ENV + "=" + slug + " is not a module slug (" + SLUG.pattern()
                    + "): KF Demo does not start under it");
        }
        return value.equals(SOURCE_SLUG) ? SOURCE : new ModuleIdentity(value);
    }

    /** The identity from an environment ({@value #ENV}). */
    public static ModuleIdentity fromEnv(Map<String, String> env) {
        return of(env == null ? null : env.get(ENV));
    }

    /** The identity of this process ({@value #ENV} of its environment), read once. */
    public static ModuleIdentity current() {
        return Current.IDENTITY;
    }

    private static final class Current {
        private static final ModuleIdentity IDENTITY = fromEnv(System.getenv());
    }

    public String slug() {
        return slug;
    }

    /** Whether this run is the dev build of a signed module ({@code <base>-dev}). */
    public boolean isDevBuild() {
        return slug.endsWith(DEV_SUFFIX) && slug.length() > DEV_SUFFIX.length();
    }

    /** {@code /m/<slug>}: where the platform's proxy exposes the module's routes. */
    public String routeBase() {
        return "/m/" + slug;
    }

    /** {@code /m/<slug>/<path>}. */
    public String route(String path) {
        String p = path == null ? "" : path.replaceFirst("^/+", "");
        return routeBase() + "/" + p;
    }

    /** {@code api.v1.m.<slug>.}: the prefix of every route right of this run. */
    public String rightPrefix() {
        return "api.v1.m." + slug + ".";
    }

    /**
     * The right {@code sourceCode} (a code of the source manifest, {@code api.v1.m.kf-demo-model-card.<suffix>}) under this run's
     * slug: {@code api.v1.m.<slug>.<suffix>}.
     *
     * @throws IllegalArgumentException when {@code sourceCode} is not one of the source manifest's route rights
     */
    public String right(String sourceCode) {
        String sourcePrefix = SOURCE.rightPrefix();
        if (sourceCode == null || !sourceCode.startsWith(sourcePrefix) || sourceCode.length() == sourcePrefix.length()) {
            throw new IllegalArgumentException(sourceCode + " is not a route right of the module (" + sourcePrefix + "…)");
        }
        return rightPrefix() + sourceCode.substring(sourcePrefix.length());
    }

    /** The UI bundle built by build.sh into static/ui/ and served by the SDK at /ui/** (behind /m/<slug>). */
    public String uiBundle() {
        return route("ui/surfaces.mjs");
    }

    /** The module's own Tailwind build (quick 261030-css-module), declared on every contribution. */
    public String uiCss() {
        return route("ui/surfaces.css");
    }

    /** How the host addresses a tab of the module in {@code ?tab=}: {@code <slug>.<key>} (261029-impact-notificare-dt). */
    public String tab(String key) {
        return slug + "." + key;
    }

    @Override
    public String toString() {
        return slug;
    }
}
