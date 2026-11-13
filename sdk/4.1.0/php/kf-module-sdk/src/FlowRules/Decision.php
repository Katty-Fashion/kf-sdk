<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowRules.java (FlowRules.Decision). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\FlowRules;

use Kf\Sdk\Decision as HookDecision;
use Kf\Sdk\FlowRules;
use Kf\Sdk\Json;

/**
 * The answer of a rule: `{"decision": "pass"}` or `{"decision": "block", "key": …, "params": …}`. The key is a key of the
 * manifest's `i18n.en` (the engine shows its text with the params).
 */
final class Decision
{
    public const PASS = 'pass';
    public const BLOCK = 'block';

    /** @param array<string, mixed> $params */
    private function __construct(
        public readonly string $decision,
        public readonly ?string $key,
        public readonly array $params,
    ) {
    }

    /** The step may advance, as far as this rule is concerned. */
    public static function pass(): self
    {
        return new self(self::PASS, null, []);
    }

    /**
     * The step may not advance, with the reason (Java's `block(key)` and `block(key, params)`).
     *
     * @param ?array<string, mixed> $params
     * @throws \InvalidArgumentException no key, a key outside `^[a-z][a-z0-9_.-]{0,119}$` or more than 20 params (the
     *                                   engine would block the step anyway, with its own reason instead of the module's)
     */
    public static function block(?string $key, ?array $params = null): self
    {
        if ($key === null || trim($key) === '') throw new \InvalidArgumentException('a block needs a key (a key of i18n.en)');
        if (preg_match(HookDecision::KEY, $key) !== 1) {
            throw new \InvalidArgumentException('the key "' . $key . '" must match ' . trim(HookDecision::KEY, '/'));
        }
        $p = $params ?? [];
        if (count($p) > FlowRules::MAX_PARAMS) {
            throw new \InvalidArgumentException('a block carries at most ' . FlowRules::MAX_PARAMS . ' params (got ' . count($p) . ')');
        }
        return new self(self::BLOCK, $key, $p);
    }

    /** Whether the step may advance. */
    public function passes(): bool
    {
        return $this->decision === self::PASS;
    }

    /**
     * The JSON body of the `200` answer.
     *
     * @return array<string, mixed>
     */
    public function toJson(): array
    {
        $m = ['decision' => $this->decision];
        if ($this->decision === self::BLOCK) {
            $m['key'] = $this->key;
            if ($this->params !== []) $m['params'] = Json::obj($this->params);
        }
        return $m;
    }
}
