<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/OrderServices.java (OrderServices.Line). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\OrderServices;

/**
 * One service line: `orderId` and `entryId` the platform's ids (as text), the line's `name`, its cost (`amount` ≥ 0 in
 * `currency`; a number or a decimal text, Java's BigDecimal) and the module's own key `externalRef`.
 */
final class Line
{
    public function __construct(
        public readonly ?string $orderId,
        public readonly ?string $entryId,
        public readonly ?string $name,
        public readonly int|float|string|null $amount,
        public readonly ?string $currency,
        public readonly ?string $externalRef,
    ) {
    }

    /**
     * The JSON body of the upsert.
     *
     * @return array<string, mixed>
     */
    public function toJson(): array
    {
        $amount = $this->amount === null ? null : (is_string($this->amount) ? $this->amount + 0 : $this->amount);
        return [
            'orderId' => $this->orderId,
            'entryId' => $this->entryId,
            'name' => $this->name,
            'cost' => ['amount' => $amount, 'currency' => $this->currency],
            'externalRef' => $this->externalRef,
        ];
    }
}
