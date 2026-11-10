package ro.kf.modules.democard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ro.kf.sdk.spring.EnableKfModule;

/**
 * kf-demo-model-card, the demo module of the tutorial "your first module" (quick 261102-module-tutorial). The platform
 * forwards {@code /m/<slug>/…} here without the prefix ({@code /m/kf-demo-model-card/pins/42} reaches {@code /pins/42}).
 * {@link EnableKfModule} mounts {@code POST /_kf/hooks}, serves {@code /ui/**}, verifies the signed context (X-23) on
 * every other route, hands it to the controllers as a {@code RequestContext} parameter and registers the module at boot.
 */
@SpringBootApplication
@EnableKfModule
public class KfDemoCardApplication {

    public static void main(String[] args) {
        SpringApplication.run(KfDemoCardApplication.class, args);
    }
}
