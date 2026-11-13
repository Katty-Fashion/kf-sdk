<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FeatureFlags.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's effective feature flags (declared in `kf-module-conf.json`, toggled by the platform admin). Fetched
 * after the registration (`GET /api/v1/platform/modules/{slug}/flags` with the module token) and refreshed from the
 * `kfflags` attribute of every callback, so a handler always sees the values the platform had when it sent the event.
 *
 * PHP keeps nothing between requests: outside a callback the flags come from the state the registration process wrote
 * (`StateStore`), through the loader `KfModule` gives this object.
 *
 *   $kf->models()->beforeUpdate(fn (BeforeContext $c) => $kf->flags()->isEnabled('autocalc') ? $c->patch()->extension('x', 1) : $c->allow());
 */
final class FeatureFlags
{
    /** @var array<string, bool> */
    private array $values = [];
    private bool $known = false;

    /** @param ?\Closure(): ?array $loader reads the flags kept by another process (null = none) */
    public function __construct(private readonly ?\Closure $loader = null)
    {
    }

    /** Effective value; an unknown key (or flags not received yet) is off. */
    public function isEnabled(string $key): bool
    {
        $this->load();
        return ($this->values[$key] ?? false) === true;
    }

    /** Every flag, sorted by key (read-only copy). */
    public function all(): array
    {
        $this->load();
        return $this->values;
    }

    /** True once the flags were fetched or received with a callback. */
    public function isKnown(): bool
    {
        $this->load();
        return $this->known;
    }

    /** @internal Replaces the set: a key absent from `$raw` is off, a value that is not `true` is off. */
    public function replace(array|\stdClass $raw): void
    {
        $next = [];
        foreach (($raw instanceof \stdClass ? get_object_vars($raw) : $raw) as $k => $v) $next[(string) $k] = $v === true;
        ksort($next, SORT_STRING);
        $this->values = $next;
        $this->known = true;
    }

    private function load(): void
    {
        if ($this->known || $this->loader === null) return;
        $values = ($this->loader)();
        if (is_array($values)) $this->replace($values);
    }
}
