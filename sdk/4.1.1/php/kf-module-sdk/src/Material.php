<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/Material.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/** One line of a model's material list. */
final class Material
{
    public function __construct(
        public readonly ?string $material,
        public readonly int|float|null $quantity,
        public readonly ?string $unit,
    ) {
    }

    /** @internal */
    public static function fromMap(\stdClass $m): self
    {
        $q = Json::get($m, 'quantity');
        return new self(
            Json::text(Json::get($m, 'material')),
            $q === null ? null : (is_int($q) || is_float($q) ? $q : (float) $q),
            Json::text(Json::get($m, 'unit')),
        );
    }

    /** @internal */
    public function toMap(): array
    {
        return ['material' => $this->material, 'quantity' => $this->quantity, 'unit' => $this->unit];
    }
}
