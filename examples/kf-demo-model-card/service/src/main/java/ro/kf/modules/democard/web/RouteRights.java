package ro.kf.modules.democard.web;

import ro.kf.modules.democard.ModuleIdentity;
import ro.kf.sdk.RequestContext;

/**
 * The rights of the module's routes, as the SOURCE manifest declares them, and the module's own checks of the caller.
 * At run time a right is moved under the slug of the run ({@link ModuleIdentity#right}), so the dev build
 * ({@code kf-demo-model-card-dev}) checks {@code api.v1.m.kf-demo-model-card-dev.…}.
 *
 * <ul>
 *   <li>{@code audience: member}: the platform forwards any member of the active tenant; the module asks for no right,
 *       only for a person's context ({@link #requireMember}).</li>
 *   <li>{@code admin: tenant} / {@code admin: platform}: the platform forwards only the holders of the right; the
 *       module checks it once more ({@link #require}).</li>
 * </ul>
 */
public final class RouteRights {

    public static final String PREFIX = "api.v1.m." + ModuleIdentity.SOURCE_SLUG + ".";
    public static final String ADMIN_PINS_DELETE = PREFIX + "admin.pins.delete";
    public static final String ADMIN_EXPORT_GET = PREFIX + "admin.export.get";
    public static final String ADMIN_IMPORT_POST = PREFIX + "admin.import.post";

    private RouteRights() {
    }

    /** 403 unless {@code ctx} is a person's context with a subject; the subject. A hook or the platform is no member. */
    public static String requireMember(RequestContext ctx) {
        if (ctx == null || ctx.actor() == null || ctx.actor().type() != RequestContext.Actor.Type.USER
                || ctx.user() == null || ctx.user().isBlank()) {
            throw Problem.forbidden("the route is called by a member of the tenant (a person's context)");
        }
        return ctx.user();
    }

    /** 403 unless {@code ctx} is a user's context holding {@code sourceRight} (under the slug of the run). */
    public static void require(RequestContext ctx, ModuleIdentity id, String sourceRight) {
        String right = id.right(sourceRight);
        if (ctx == null || ctx.actor() == null || ctx.actor().type() != RequestContext.Actor.Type.USER
                || !ctx.hasRight(right)) {
            throw Problem.forbidden("the context does not hold " + right);
        }
    }
}
