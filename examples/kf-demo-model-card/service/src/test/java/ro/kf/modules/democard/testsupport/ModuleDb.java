package ro.kf.modules.democard.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The module's own database for the tests (the pattern of kf-feedback): ONE Postgres 16 container
 * per test JVM (Testcontainers), shared by every Spring context, with the roles of production — Flyway as the owner
 * (the container's superuser, {@code kfdemocard}), the application as {@code kfdemocard_app} (created by
 * {@code V1__baseline}, bound by RLS). The two passwords are the tests' own, never real ones.
 */
public final class ModuleDb {

    public static final String OWNER = "kfdemocard";
    public static final String OWNER_PASSWORD = "owner-test-password-261102";
    public static final String APP = "kfdemocard_app";
    public static final String APP_PASSWORD = "app-test-password-261102";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("kfdemocard")
            .withUsername(OWNER)
            .withPassword(OWNER_PASSWORD);

    private ModuleDb() {
    }

    /** The container, started on first use (and left to Testcontainers' reaper at the end of the JVM). */
    public static synchronized PostgreSQLContainer postgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    /** The datasource of the application (kfdemocard_app) and Flyway's (the owner), as in application.properties. */
    public static void register(DynamicPropertyRegistry registry) {
        PostgreSQLContainer db = postgres();
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.app_password", () -> APP_PASSWORD);
    }

    /** A connection as the application's role (after the migration ran): bound by RLS. */
    public static Connection app() throws SQLException {
        return DriverManager.getConnection(postgres().getJdbcUrl(), APP, APP_PASSWORD);
    }

    /** A connection as the owner (the superuser of the container: it bypasses RLS, for set-up and checks only). */
    public static Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres().getJdbcUrl(), OWNER, OWNER_PASSWORD);
    }

    /** {@code SELECT count(*) FROM model_pin WHERE tenant_id = ?}, as the owner (every row). */
    public static int pinsOf(String tenant) throws SQLException {
        try (Connection c = owner();
             var st = c.prepareStatement("SELECT count(*) FROM model_pin WHERE tenant_id = ?::uuid")) {
            st.setString(1, tenant);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
