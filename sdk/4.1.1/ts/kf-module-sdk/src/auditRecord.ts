// kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/AuditRecord.java. Do not edit by hand: fix Java, then re-derive.

/**
 * One row a module writes to the platform's audit trail (capability `audit`, access `write`, implemented
 * by the platform). The platform attributes the row to the module's slug and to the tenant of the request
 * (`RequestContext`); the rules below are the platform's, checked locally first by `AuditClient.record`.
 * - `action`: what happened, an upper-case code `[A-Z][A-Z0-9_]{1,63}`, e.g. `NOTE_CREATED`
 * - `aggregateType`: the module's own type of the thing it happened to, `[A-Za-z][A-Za-z0-9_.-]{0,49}`, e.g. `Note`;
 *   stored as `m.<slug>.<type>`
 * - `aggregateRef`: the id of that thing, as text, at most 255 characters
 */
export class AuditRecord {
  constructor(
    readonly action: string,
    readonly aggregateType: string,
    readonly aggregateRef: string,
  ) {}
}
