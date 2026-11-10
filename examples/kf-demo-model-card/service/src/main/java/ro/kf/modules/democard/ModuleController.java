package ro.kf.modules.democard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.SdkVersion;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /health}: public (an SDK public path), not declared, so the platform never forwards it from users. */
@RestController
public class ModuleController {

    private final KfModule kf;
    private final String version;

    public ModuleController(KfModule kf, @Value("${MODULE_VERSION:${module.version}}") String version) {
        this.kf = kf;
        this.version = version;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("module", kf.slug());
        body.put("version", version);
        body.put("sdk", SdkVersion.SDK_VERSION);
        body.put("registered", kf.isRegistered());
        return body;
    }
}
