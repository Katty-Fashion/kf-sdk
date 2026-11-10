package ro.kf.modules.democard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.modules.democard.testsupport.ModuleDb;
import ro.kf.modules.democard.testsupport.ModuleIT;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static ro.kf.modules.democard.testsupport.ModuleCalls.json;

/** The module boots on its database, answers /health and refuses an unsigned call. */
class ModuleBootTest extends ModuleIT {

    @Test
    @DisplayName("GET /health answers UP with the slug of the run")
    void health() throws Exception {
        var res = calls().unsigned("GET", "/health");
        assertEquals(200, res.statusCode());
        assertEquals("UP", json(res).get("status").asString());
        assertEquals(ModuleIdentity.SOURCE_SLUG, json(res).get("module").asString());
    }

    @Test
    @DisplayName("Flyway applied V1; the application's role is neither superuser nor BYPASSRLS, and RLS is forced on model_pin")
    void database() throws Exception {
        try (Connection c = ModuleDb.owner(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT r.rolsuper, r.rolbypassrls, c.relforcerowsecurity FROM pg_roles r, pg_class c "
                     + "WHERE r.rolname = 'kfdemocard_app' AND c.relname = 'model_pin'")) {
            rs.next();
            assertFalse(rs.getBoolean(1));
            assertFalse(rs.getBoolean(2));
            assertThat(rs.getBoolean(3)).isTrue();
        }
    }

    @Test
    @DisplayName("a route without the signed context is refused by the SDK: 401 invalid-context")
    void unsigned() throws Exception {
        var res = calls().unsigned("GET", "/cards");
        assertEquals(401, res.statusCode());
        assertThat(res.body()).contains("invalid-context");
    }
}
