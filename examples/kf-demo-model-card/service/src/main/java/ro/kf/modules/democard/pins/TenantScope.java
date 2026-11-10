package ro.kf.modules.democard.pins;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * One transaction of a tenant: its FIRST statement is {@code set_config('kfdemocard.tenant', <tenant>, true)}, the GUC
 * the RLS policy of {@code model_pin} reads. {@code true} = local to the transaction, so a pooled connection never carries
 * a tenant into the next request. The tenant comes only from a verified message: the signed context of a request, or the
 * signed envelope of a callback. A statement outside a scope sees no row (fail-closed).
 */
@Component
public class TenantScope {

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public TenantScope(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    public <T> T inTenant(String tenant, Supplier<T> work) {
        UUID id = tenant(tenant);
        return tx.execute(status -> {
            jdbc.sql("SELECT set_config('kfdemocard.tenant', ?, true)").param(id.toString()).query(String.class).single();
            return work.get();
        });
    }

    /** The tenant as a UUID; a blank or malformed tenant is refused before the database. */
    public static UUID tenant(String tenant) {
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("a tenant transaction needs a tenant");
        try {
            return UUID.fromString(tenant.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the tenant is not a UUID");
        }
    }
}
