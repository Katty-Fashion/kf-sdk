<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RequestContext.java (RequestContext.Actor). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Who calls the module's route: `{"type": "user" | "system", "via": "hook" | null, "hookId": "<uuid>" | null}`.
 * - `user`: a user's request; `user` is set, `via` and `hookId` are null.
 * - `system`: the platform calls a `hookable` route from a hook; `user` is null, `via` is `hook` and `hookId`
 *   names the hook.
 * - `system` via `platform` (protocol 1.1): the platform calls a route the module declares in `provides`; `user` and `hookId` are null and `rights` is empty. No user is ever deduced from a system context.
 *
 * A context without `actor` (an older platform) reads as `Actor::user()`.
 */
final class Actor
{
    public const USER = 'user';
    public const SYSTEM = 'system';

    /** The prefix of `via` on a call between modules (protocol 2.0): `module:{slug}`. */
    public const VIA_MODULE_PREFIX = 'module:';

    /** The grammar of the slug after VIA_MODULE_PREFIX. */
    public const MODULE_SLUG = '^[a-z0-9][a-z0-9-]{1,40}$';

    public function __construct(
        public readonly string $type,
        public readonly ?string $via,
        public readonly ?string $hookId,
    ) {
        if ($type !== self::USER && $type !== self::SYSTEM) throw new \InvalidArgumentException('type');
    }

    /** The actor of a context that carries none: a user, no hook. */
    public static function user(): self
    {
        return new self(self::USER, null, null);
    }

    /** The platform itself, calling a provided read (protocol 1.1): no user, no hook. */
    public static function platform(): self
    {
        return new self(self::SYSTEM, 'platform', null);
    }

    /** The calling module of a call between modules (`via = module:{slug}`), or null. */
    public function viaModule(): ?string
    {
        return $this->via !== null && str_starts_with($this->via, self::VIA_MODULE_PREFIX)
            ? substr($this->via, strlen(self::VIA_MODULE_PREFIX)) : null;
    }
}
