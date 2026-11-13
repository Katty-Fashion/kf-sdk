<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SecretsClient.java (SecretsClient.Secret). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A resolved secret. `value()` is the only way to the value: `__toString()`, `json_encode` and `var_dump` /
 * `print_r` (`__debugInfo`) show the reference and `***`. Java: `SecretsClient.Secret`.
 */
final class Secret implements \JsonSerializable
{
    private const REDACTED = '***';

    /** @internal */
    public function __construct(public readonly string $ref, #[\SensitiveParameter] private readonly string $value)
    {
    }

    public function ref(): string
    {
        return $this->ref;
    }

    public function value(): string
    {
        return $this->value;
    }

    public function __toString(): string
    {
        return 'Secret[ref=' . $this->ref . ', value=' . self::REDACTED . ']';
    }

    /** @return array{ref: string, value: string} */
    public function jsonSerialize(): array
    {
        return ['ref' => $this->ref, 'value' => self::REDACTED];
    }

    /** @return array{ref: string, value: string} */
    public function __debugInfo(): array
    {
        return ['ref' => $this->ref, 'value' => self::REDACTED];
    }
}
