<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The hook points; the payloads are `HookEvents\Envelope`, `HookEvents\AfterEvent`, `HookEvents\FieldChange`, and a
 * version path of `changed` is a `HookEvents\VersionField`.
 * Handlers are callables: before `fn (BeforeContext $ctx): ?Decision` (null = allow; a throw becomes HTTP 500 and the
 * platform applies `onFailure`), after `fn (HookEvents\AfterEvent $e): void`, field change
 * `fn (HookEvents\FieldChange $c): void`.
 *
 * The point ids are hand-written here, aligned with the vocabulary fixed (`HookEvent` / `HookPointCatalog`
 * of the platform) and, for TENANT_AFTER_DELETE, with protocol 1.2, for TENANT_AFTER_SOFT_DELETE and
 * TENANT_AFTER_RESTORE with protocol 1.3; generating them from the release catalog is deferred. The tenant payloads are `HookEvents\TenantDeleted`, `HookEvents\TenantSoftDeleted` and
 * `HookEvents\TenantRestored`.
 */
final class HookEvents
{
    public const BEFORE_CREATE = 'model.before.create';
    public const BEFORE_UPDATE = 'model.before.update';
    public const BEFORE_DELETE = 'model.before.delete';
    public const AFTER_CREATE = 'model.after.create';
    public const AFTER_UPDATE = 'model.after.update';
    public const AFTER_DELETE = 'model.after.delete';
    public const FIELD_CHANGED = 'model.field.changed';

    /**
     * Protocol 1.2 (delivered by the platform, its SDK handler): the
     * after-commit point of a tenant's deletion, delivered at least once to every INSTALLED module subscribed to it
     * (liveness is not evaluated: the tenant no longer exists); the module deletes what it keeps for the tenant,
     * idempotently. Its envelope: `kftenant` = the deleted tenant, `data = {entity: "tenant", id, before: {id}, after:
     * null, changed: []}` (sdk-protocol.md §6.1).
     */
    public const TENANT_AFTER_DELETE = 'tenant.after.delete';

    /**
     * Protocol 1.3 (delivered by the platform): the after-commit point of
     * a tenant's soft-delete -- access to the tenant is off and recoverable -- delivered at least once to every module
     * subscribed to it and LIVE for the tenant. The module stops its work for the tenant (jobs, synchronisations) and
     * deletes nothing: TENANT_AFTER_DELETE stays the purge. Its envelope: `kftenant` = the tenant,
     * `data = {entity: "tenant", id, before: {id}, after: {id}, changed: []}` (sdk-protocol.md §6.1).
     */
    public const TENANT_AFTER_SOFT_DELETE = 'tenant.after.soft-delete';

    /**
     * Protocol 1.3 (delivered by the platform): the after-commit point of
     * a soft-deleted tenant's restore, delivered like TENANT_AFTER_SOFT_DELETE; the module resumes its work for the
     * tenant. The same envelope (sdk-protocol.md §6.1).
     */
    public const TENANT_AFTER_RESTORE = 'tenant.after.restore';

    /** The tenant points (protocols 1.2 and 1.3): the same envelope, checked the same way. */
    public const TENANT_POINTS = [self::TENANT_AFTER_DELETE, self::TENANT_AFTER_SOFT_DELETE, self::TENANT_AFTER_RESTORE];

    /**
     * Protocol 2.0 (sdk-protocol.md §6.1, §16.2): the order points, public since -- the
     * model's shape on the order (`$kf->orders()`, a ModelHooks on the entity `order`). A before hook may veto, and patch
     * only `extensions.{own slug}`: the order's core fields carry `patch: []`. The `customer.*` points stay internal (not
     * carried here).
     */
    public const ORDER_BEFORE_CREATE = 'order.before.create';
    public const ORDER_BEFORE_UPDATE = 'order.before.update';
    public const ORDER_BEFORE_DELETE = 'order.before.delete';
    public const ORDER_AFTER_CREATE = 'order.after.create';
    public const ORDER_AFTER_UPDATE = 'order.after.update';
    public const ORDER_AFTER_DELETE = 'order.after.delete';

    /**
     * `order.field.changed` (`fieldFilter: true`): one delivery per changed path, on ORDER_PATH_STATUS,
     * ORDER_PATH_ENTRIES and `extensions.{slug}.{field}`.
     */
    public const ORDER_FIELD_CHANGED = 'order.field.changed';

    /** The seven order points, in the documented order. */
    public const ORDER_POINTS = [self::ORDER_BEFORE_CREATE, self::ORDER_BEFORE_UPDATE, self::ORDER_BEFORE_DELETE,
        self::ORDER_AFTER_CREATE, self::ORDER_AFTER_UPDATE, self::ORDER_AFTER_DELETE, self::ORDER_FIELD_CHANGED];

    /** The path of the order's status on ORDER_FIELD_CHANGED (the intent `{status}`). */
    public const ORDER_PATH_STATUS = 'status';

    /** The path of the order's lines on ORDER_FIELD_CHANGED: `[{id, modelVersionId, quantity?}]`. */
    public const ORDER_PATH_ENTRIES = 'entries';

    /** The statuses of an order (§16.2). ORDER_STATUS_COMPLETED is terminal and reached only from a flow. */
    public const ORDER_STATUS_DRAFT = 'DRAFT';
    public const ORDER_STATUS_CONFIRMED = 'CONFIRMED';
    public const ORDER_STATUS_IN_PRODUCTION = 'IN_PRODUCTION';
    public const ORDER_STATUS_SHIPPED = 'SHIPPED';
    public const ORDER_STATUS_CANCELLED = 'CANCELLED';
    public const ORDER_STATUS_COMPLETED = 'COMPLETED';

    /** Every status of an order, in the order of the platform's `OrderStatus`. */
    public const ORDER_STATUSES = [self::ORDER_STATUS_DRAFT, self::ORDER_STATUS_CONFIRMED, self::ORDER_STATUS_IN_PRODUCTION,
        self::ORDER_STATUS_SHIPPED, self::ORDER_STATUS_CANCELLED, self::ORDER_STATUS_COMPLETED];

    /**
     * Protocol 2.0 (sdk-protocol.md §6.3): the prefix of a module event's point, `m.{owner}.after.{name}`. No
     * platform point starts with it.
     */
    public const MODULE_EVENT_PREFIX = 'm.';

    /** The grammar of a module event's point: `m.{owner}.after.{name}` (the owner's slug, the event's name). */
    public const MODULE_EVENT_POINT = '^m\\.([a-z0-9][a-z0-9-]{1,40})\\.after\\.([a-z][a-z0-9-]{1,40})$';

    /** The point of the event `$name` of `$owner`: `m.{owner}.after.{name}`. */
    public static function moduleEventPoint(string $owner, string $name): string
    {
        return self::MODULE_EVENT_PREFIX . $owner . '.after.' . $name;
    }

    private function __construct()
    {
    }

    /**
     * @internal The version paths of a `changed` list, in order; the model's own paths are left out.
     *
     * @param list<string> $changed
     * @return list<HookEvents\VersionField>
     */
    public static function versionFields(array $changed): array
    {
        $out = [];
        foreach ($changed as $path) {
            $f = HookEvents\VersionField::parse($path);
            if ($f !== null) $out[] = $f;
        }
        return $out;
    }

    /**
     * @internal The version ids of those paths, distinct, in order of first appearance.
     *
     * @param list<HookEvents\VersionField> $fields
     * @return list<string>
     */
    public static function versionIds(array $fields): array
    {
        $out = [];
        foreach ($fields as $f) if (!in_array($f->versionId, $out, true)) $out[] = $f->versionId;
        return $out;
    }

    /** @internal */
    public static function stringList(mixed $raw): array
    {
        return is_array($raw) ? array_map(static fn (mixed $o): string => Json::text($o) ?? 'null', $raw) : [];
    }
}
