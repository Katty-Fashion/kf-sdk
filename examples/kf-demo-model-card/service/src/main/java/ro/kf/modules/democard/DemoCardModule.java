package ro.kf.modules.democard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.kf.modules.democard.hooks.ModelDeletedHandler;
import ro.kf.modules.democard.hooks.TenantDeletedHandler;
import ro.kf.sdk.HookEvents;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.Surface;
import ro.kf.sdk.Surfaces;

import java.util.HashMap;
import java.util.Map;

/**
 * What kf-demo-model-card registers with the platform at boot. Everything here MUST be declared in
 * {@code kf-module-conf.json}: the SDK checks it before the first request, the platform at registration.
 */
@Configuration(proxyBeanMethods = false)
public class DemoCardModule {

    /**
     * The surface of the card: the extension surface in the model's Overview tab (protocol 1.3.1, delivered by platform
     * 6.1 and in the pinned catalogue of SDK 0.9.1), {@code ctx} {@code {model, version, href}}, on staff and portal.
     * A platform before 6.1 refuses the registration ({@code unknown surface}), hence {@code "platform": ">=6.1"}.
     */
    public static final String OVERVIEW_CARDS = Surfaces.MODELS_DETAIL_OVERVIEW_CARDS;

    /** The handlers of the module's subscriptions (the beans, or test doubles). */
    public record Handlers(HookEvents.AfterHandler onModelDeleted, HookEvents.TenantDeletedHandler onTenantDeleted) {
    }

    /**
     * The module from the platform's environment ({@code KF_PLATFORM_URL}, {@code KF_MODULE_SLUG}, {@code KF_MODULE_TOKEN},
     * {@code KF_HOOK_SECRET}, {@code KF_CALLBACK_BASE}). {@code kf.module-manifest} overrides {@code KF_MODULE_MANIFEST}.
     */
    @Bean
    KfModule kfModule(@Value("${kf.module-manifest:}") String manifest, ModelDeletedHandler onModelDeleted,
                      TenantDeletedHandler onTenantDeleted) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (!manifest.isBlank()) env.put("KF_MODULE_MANIFEST", manifest);
        return configure(KfModule.fromEnv(env), ModuleIdentity.current(), new Handlers(onModelDeleted, onTenantDeleted));
    }

    /** The identity of this process ({@code KF_MODULE_SLUG}), as a bean for the controllers. */
    @Bean
    ModuleIdentity moduleIdentity() {
        return ModuleIdentity.current();
    }

    /**
     * The registrations, on any {@link KfModule}: the two hooks and the two UI contributions, addressed under the slug
     * of the run ({@link ModuleIdentity}: the same code runs as {@code kf-demo-model-card} and as its dev build).
     */
    public static KfModule configure(KfModule kf, ModuleIdentity id, Handlers handlers) {
        kf.models().afterDelete(handlers.onModelDeleted());
        kf.tenants().onDeleted(handlers.onTenantDeleted());
        kf.ui().contribute(OVERVIEW_CARDS,
                Surface.react(id.uiBundle(), "ModelCard").css(id.uiCss()).labelKey("surface.card").key("card")
                        .order(100).peer("react", "^19.2"));
        kf.ui().contribute(Surfaces.MODULE_PAGES,
                Surface.react(id.uiBundle(), "ModelCardsPage").css(id.uiCss()).labelKey("page.cards").key("cards")
                        .peer("react", "^19.2"));
        return kf;
    }
}
