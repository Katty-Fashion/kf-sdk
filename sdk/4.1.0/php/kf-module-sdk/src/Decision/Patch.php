<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Decision.java (Decision.Patch). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Decision;

use Kf\Sdk\Decision;
use Kf\Sdk\Material;

/** Builder-style patch; every setter returns $this, so it can be returned directly from a handler. */
final class Patch extends Decision
{
    private array $values = [];
    private array $ownExtensions = [];

    /** @internal */
    public function __construct(private readonly string $slug)
    {
    }

    public function name(string $name): self
    {
        $this->values['name'] = $name;
        return $this;
    }

    /** @param list<Material> $materials */
    public function materialList(array $materials): self
    {
        $this->values['materialList'] = array_map(static fn (Material $m): array => $m->toMap(), $materials);
        return $this;
    }

    /**
     * Sets one own field of the entity (the platform's patch guard decides which are patchable).
     * `extensions` is not set here: use `extension()`.
     */
    public function set(string $field, mixed $value): self
    {
        if ($field === 'extensions') throw new \InvalidArgumentException('use extension(field, value)');
        $this->values[$field] = $value;
        return $this;
    }

    /** Sets `extensions.{own slug}.{field}`. */
    public function extension(string $field, mixed $value): self
    {
        $this->ownExtensions[$field] = $value;
        return $this;
    }

    public function isEmpty(): bool
    {
        return $this->values === [] && $this->ownExtensions === [];
    }

    /** @internal Merges another patch into this one (later wins). */
    public function merge(self $other): self
    {
        foreach ($other->values as $k => $v) $this->values[$k] = $v;
        foreach ($other->ownExtensions as $k => $v) $this->ownExtensions[$k] = $v;
        return $this;
    }

    public function values(): array
    {
        $patch = $this->values;
        if ($this->ownExtensions !== []) $patch['extensions'] = [$this->slug => $this->ownExtensions];
        return $patch;
    }

    public function toJson(): array
    {
        return ['decision' => 'patch', 'patch' => \Kf\Sdk\Json::obj($this->values())];
    }
}
