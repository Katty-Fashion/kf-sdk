<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelHooks.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Hooks on the platform's models (`$kf->models()`) and, from protocol 2.0, on its orders (`$kf->orders()`: the same
 * shape on the points `order.*`, public, sdk-protocol.md §16.2; a patch there carries only the module's own
 * `extensions`, the SDK refuses one on a core field). Several handlers for the same event run in declaration order inside the module; the
 * platform sees one hook per event:
 * - `priority`: the lowest declared (default DEFAULT_PRIORITY, lower runs first);
 * - `onFailure` (before hooks only): BLOCK if any handler of the event declares it, else ALLOW; always sent
 *   explicitly. `block` also needs `capabilities.hooks[].onFailure = "block"` in `kf-module-conf.json` (checked
 *   locally by ManifestCheck and by the platform).
 *
 * The Java overloads `beforeX(handler)`, `beforeX(priority, handler)`, `beforeX(priority, onFailure, handler)` are one
 * PHP method with named arguments: `beforeUpdate($handler, priority: 50, onFailure: OnFailure::BLOCK)`.
 * The hook points are hand-written constants of HookEvents until the catalog generates them.
 */
final class ModelHooks
{
    public const DEFAULT_PRIORITY = 100;

    /** @var array<string, list<array{priority: int, onFailure: OnFailure, handler: callable}>> */
    private array $before = [];
    /** @var array<string, list<callable>> */
    private array $after = [];
    /** @var list<array{paths: list<string>, handler: callable}> */
    private array $fieldWatches = [];

    /**
     * @internal The same hooks on the points `{entity}.before|after.*` and `{entity}.field.changed`: `model`
     * (`$kf->models()`) or `order` (`$kf->orders()`, §16.2).
     */
    public function __construct(private readonly string $entity = 'model')
    {
        if ($entity === '') throw new \InvalidArgumentException('entity');
    }

    /** The entity of these hooks' points (`model` or `order`). */
    public function entity(): string
    {
        return $this->entity;
    }

    /** @internal `{entity}.field.changed`. */
    public function fieldChanged(): string
    {
        return $this->entity . '.field.changed';
    }

    // ---- before -----------------------------------------------------------------------------

    public function beforeCreate(callable $handler, int $priority = self::DEFAULT_PRIORITY, OnFailure $onFailure = OnFailure::ALLOW): self
    {
        return $this->addBefore($this->entity . '.before.create', $priority, $onFailure, $handler);
    }

    public function beforeUpdate(callable $handler, int $priority = self::DEFAULT_PRIORITY, OnFailure $onFailure = OnFailure::ALLOW): self
    {
        return $this->addBefore($this->entity . '.before.update', $priority, $onFailure, $handler);
    }

    /** Patches are not allowed on delete: return allow or reject. */
    public function beforeDelete(callable $handler, int $priority = self::DEFAULT_PRIORITY, OnFailure $onFailure = OnFailure::ALLOW): self
    {
        return $this->addBefore($this->entity . '.before.delete', $priority, $onFailure, $handler);
    }

    // ---- after (asynchronous, retried by the platform until a 2xx) ---------------------------

    public function afterCreate(callable $handler): self
    {
        return $this->addAfter($this->entity . '.after.create', $handler);
    }

    public function afterUpdate(callable $handler): self
    {
        return $this->addAfter($this->entity . '.after.update', $handler);
    }

    public function afterDelete(callable $handler): self
    {
        return $this->addAfter($this->entity . '.after.delete', $handler);
    }

    // ---- field changes ----------------------------------------------------------------------

    /**
     * Called once per changed field among `$paths` (`name`, `materialList`, `extensions.{slug}.{field}`); an empty list
     * means every field.
     *
     * @param string|list<string> $paths
     */
    public function onFieldChange(string|array $paths, callable $handler): self
    {
        $this->fieldWatches[] = ['paths' => array_values(is_string($paths) ? [$paths] : $paths), 'handler' => $handler];
        return $this;
    }

    // ---- internals --------------------------------------------------------------------------

    private function addBefore(string $event, int $priority, OnFailure $onFailure, callable $handler): self
    {
        $this->before[$event][] = ['priority' => $priority, 'onFailure' => $onFailure, 'handler' => $handler];
        return $this;
    }

    private function addAfter(string $event, callable $handler): self
    {
        $this->after[$event][] = $handler;
        return $this;
    }

    /** @internal @return list<callable> */
    public function beforeHandlers(string $event): array
    {
        return array_map(static fn (array $b): callable => $b['handler'], $this->before[$event] ?? []);
    }

    /** @internal @return ?list<callable> null when the event has no handler */
    public function afterHandlers(string $event): ?array
    {
        return $this->after[$event] ?? null;
    }

    /** @internal @return list<callable> the field-change handlers watching `$path` */
    public function fieldHandlers(string $path): array
    {
        $out = [];
        foreach ($this->fieldWatches as $w) {
            if ($w['paths'] === [] || in_array($path, $w['paths'], true)) $out[] = $w['handler'];
        }
        return $out;
    }

    /** @internal The `hooks` part of the registration body. */
    public function registration(): array
    {
        $hooks = [];
        foreach ($this->before as $event => $handlers) {
            $block = false;
            foreach ($handlers as $b) $block = $block || $b['onFailure'] === OnFailure::BLOCK;
            $hooks[] = [
                'event' => $event,
                'priority' => $handlers === [] ? self::DEFAULT_PRIORITY : min(array_column($handlers, 'priority')),
                'onFailure' => ($block ? OnFailure::BLOCK : OnFailure::ALLOW)->wire(),
            ];
        }
        foreach (array_keys($this->after) as $event) $hooks[] = ['event' => $event];
        if ($this->fieldWatches !== []) {
            $all = false;
            $union = [];
            foreach ($this->fieldWatches as $w) {
                if ($w['paths'] === []) $all = true;
                foreach ($w['paths'] as $p) if (!in_array($p, $union, true)) $union[] = $p;
            }
            $hooks[] = ['event' => $this->fieldChanged(), 'fields' => $all ? [] : $union];
        }
        return $hooks;
    }
}
