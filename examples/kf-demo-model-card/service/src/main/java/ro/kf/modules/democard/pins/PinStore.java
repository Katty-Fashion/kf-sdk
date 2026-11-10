package ro.kf.modules.democard.pins;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The pins, in the module's own database. Every method runs inside {@link TenantScope#inTenant}: RLS keeps it to the
 * tenant of the scope, so the reads take no tenant filter of their own; the inserts name the tenant (RLS checks it).
 */
@Repository
public class PinStore {

    /** A pin. {@code pinnedName} is the model's name at the last pin (for the notification on its deletion). */
    public record Pin(long modelId, String pinnedBy, String pinnedName, Instant pinnedAt) {
    }

    private static final String COLUMNS = "SELECT model_id, pinned_by, pinned_name, pinned_at FROM model_pin ";

    private final JdbcClient jdbc;

    public PinStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Pins {@code modelId} for {@code user}; idempotent (a second pin refreshes the name and keeps the date). */
    public void pin(UUID tenant, long modelId, String user, String name) {
        jdbc.sql("""
                INSERT INTO model_pin (tenant_id, model_id, pinned_by, pinned_name) VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, model_id, pinned_by) DO UPDATE SET pinned_name = EXCLUDED.pinned_name
                """).params(tenant, modelId, user, name).update();
    }

    public boolean unpin(long modelId, String user) {
        return jdbc.sql("DELETE FROM model_pin WHERE model_id = ? AND pinned_by = ?").params(modelId, user).update() > 0;
    }

    public boolean isPinned(long modelId, String user) {
        return jdbc.sql("SELECT count(*) FROM model_pin WHERE model_id = ? AND pinned_by = ?").params(modelId, user)
                .query(Long.class).single() > 0;
    }

    /** The user's pins, the newest first. */
    public List<Pin> ofUser(String user) {
        return jdbc.sql(COLUMNS + "WHERE pinned_by = ? ORDER BY pinned_at DESC, id DESC").param(user).query(PinStore::row).list();
    }

    /** Every pin of the model (every user), deleted: the model is gone. Returns what was deleted. */
    public List<Pin> removeModel(long modelId) {
        List<Pin> pins = jdbc.sql(COLUMNS + "WHERE model_id = ? ORDER BY id").param(modelId).query(PinStore::row).list();
        jdbc.sql("DELETE FROM model_pin WHERE model_id = ?").param(modelId).update();
        return pins;
    }

    /** Every pin of the scope's tenant, in a stable order (the export). */
    public List<Pin> all() {
        return jdbc.sql(COLUMNS + "ORDER BY model_id, pinned_by").query(PinStore::row).list();
    }

    /** Inserts an imported pin unless it exists; true when written. */
    public boolean importPin(UUID tenant, Pin pin) {
        return jdbc.sql("""
                INSERT INTO model_pin (tenant_id, model_id, pinned_by, pinned_name, pinned_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, model_id, pinned_by) DO NOTHING
                """).params(tenant, pin.modelId(), pin.pinnedBy(), pin.pinnedName(),
                OffsetDateTime.ofInstant(pin.pinnedAt(), ZoneOffset.UTC)).update() > 0;
    }

    /** Deletes every pin of the scope's tenant; the number deleted. */
    public int deleteAll() {
        return jdbc.sql("DELETE FROM model_pin").update();
    }

    private static Pin row(ResultSet rs, int n) throws SQLException {
        return new Pin(rs.getLong("model_id"), rs.getString("pinned_by"), rs.getString("pinned_name"),
                rs.getObject("pinned_at", OffsetDateTime.class).toInstant());
    }
}
