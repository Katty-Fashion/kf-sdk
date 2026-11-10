package ro.kf.modules.democard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docker-compose.yml} of the module, read as YAML (plan 218-11, the checks of KF-AAS-R3 213-19 Task 3): ready for
 * a read-only root filesystem (Î-S7, phase 214) and with the module's database off the platform's network (Î-218-01 a).
 */
class ComposeReadOnlyRootfsTest {

    static final Path COMPOSE = Path.of("..", "docker-compose.yml");

    @SuppressWarnings("unchecked")
    static Map<String, Object> compose() throws Exception {
        return new Yaml().load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> services() throws Exception {
        return (Map<String, Map<String, Object>>) compose().get("services");
    }

    static boolean exposed(Map<String, Object> service) {
        return service.get("labels") instanceof Map<?, ?> labels && labels.containsKey("kf.expose");
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Map<String, Object> service, String key) {
        Object value = service.get(key);
        return value instanceof List<?> l ? (List<String>) l : List.of();
    }

    @Test
    @DisplayName("three services: db-init, db, api")
    void theServices() throws Exception {
        assertThat(services()).containsOnlyKeys("db-init", "db", "api");
    }

    @Test
    @DisplayName("every image: is pinned on a digest (@sha256:); a service without image: is built (build: .)")
    void imagesOnDigests() throws Exception {
        services().forEach((name, service) -> {
            if (service.containsKey("image")) {
                assertThat(String.valueOf(service.get("image"))).as(name).matches(".+@sha256:[0-9a-f]{64}");
            } else {
                assertThat(service).as(name + " has image or build").containsKey("build");
            }
        });
    }

    @Test
    @DisplayName("db: tmpfs for the Postgres socket (/var/run/postgresql) and /tmp; no kf.expose; no ports; the password from a file")
    void theDatabase() throws Exception {
        Map<String, Object> db = services().get("db");
        assertThat(db).isNotNull();
        assertThat(list(db, "tmpfs")).contains("/var/run/postgresql", "/tmp");
        assertThat(exposed(db)).as("db is on the platform's network").isFalse();
        assertThat(db).doesNotContainKey("ports").doesNotContainKey("networks");
        assertThat(String.valueOf(db.get("environment"))).doesNotContain("PASSWORD=").contains("POSTGRES_PASSWORD_FILE");
    }

    @Test
    @DisplayName("no service publishes ports; only api is exposed")
    void noPublishedPorts() throws Exception {
        services().forEach((name, service) -> {
            assertThat(service).as(name).doesNotContainKey("ports");
            assertThat(exposed(service)).as(name + " exposed").isEqualTo(name.equals("api"));
        });
    }

    @Test
    @DisplayName("the state lives only on named volumes (no bind mount); every service writes /tmp to a tmpfs")
    void stateOnNamedVolumes() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) compose().get("volumes");
        assertThat(declared).containsKeys("db-data", "db-credentials");
        services().forEach((name, service) -> {
            for (String mount : list(service, "volumes")) {
                String source = mount.split(":")[0];
                assertThat(declared).as(name + ": " + mount + " is a named volume").containsKey(source);
            }
            assertThat(list(service, "tmpfs")).as(name).contains("/tmp");
        });
        assertThat(list(services().get("db"), "volumes")).contains("db-data:/var/lib/postgresql/data");
        assertThat(list(services().get("api"), "volumes")).contains("db-credentials:/run/kfdemocard:ro");
    }

    @Test
    @DisplayName("no password, secret or KF_* value is written in the compose file")
    void noSecretsInTheFile() throws Exception {
        String text = Files.readString(COMPOSE, StandardCharsets.UTF_8);
        assertThat(text).doesNotContainPattern("(?m)^\\s*POSTGRES_PASSWORD\\s*:")
                .doesNotContainPattern("(?m)^\\s*SPRING_DATASOURCE_PASSWORD\\s*:")
                .doesNotContainPattern("(?m)^\\s*KF_[A-Z_]+\\s*:")
                .doesNotContainPattern("(?i)github[_-]?token");
    }
}
