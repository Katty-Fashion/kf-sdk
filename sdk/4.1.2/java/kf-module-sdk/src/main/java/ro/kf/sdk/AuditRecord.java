package ro.kf.sdk;

/**
 * One row a module writes to the platform's audit trail (capability {@code audit}, access {@code write},
 * implemented by the platform). The platform attributes the row to the module's slug and to the tenant of
 * the request ({@link RequestContext}); the rules below are the platform's, checked locally first by
 * {@link AuditClient#record}.
 *
 * @param action        what happened, an upper-case code {@code [A-Z][A-Z0-9_]{1,63}}, e.g. {@code NOTE_CREATED}
 * @param aggregateType the module's own type of the thing it happened to, {@code [A-Za-z][A-Za-z0-9_.-]{0,49}}, e.g.
 *                      {@code Note}; stored as {@code m.<slug>.<type>}
 * @param aggregateRef  the id of that thing, as text, at most 255 characters
 */
public record AuditRecord(String action, String aggregateType, String aggregateRef) {
}
