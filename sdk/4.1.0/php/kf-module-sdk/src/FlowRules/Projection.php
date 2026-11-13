<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowRules.java (FlowRules.Projection). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\FlowRules;

use Kf\Sdk\FlowRules;
use Kf\Sdk\PlatformChannel;

/**
 * The order as a rule sees it (`order.flow-rule.v1`); every value is the engine's text, null when the parameter is
 * absent (not applicable). `orderStages` is never null (empty = none ticked).
 */
final class Projection
{
    /** @var list<string> */
    public readonly array $orderStages;

    /** @param ?list<string> $orderStages */
    public function __construct(
        public readonly ?string $ruleKey,
        public readonly ?string $orderId,
        public readonly ?string $orderStatus,
        public readonly ?string $flowKey,
        public readonly ?string $flowVersion,
        public readonly ?string $instanceId,
        public readonly ?string $stepKey,
        public readonly ?string $stepTo,
        public readonly ?string $orderNumber,
        public readonly ?bool $orderInternal,
        public readonly ?string $orderCustomerId,
        public readonly ?string $stepBehavesLike,
        ?array $orderStages,
        public readonly ?string $lineId,
        public readonly ?string $lineModelId,
        public readonly ?string $lineModelVersionId,
        public readonly ?string $lineQuantity,
    ) {
        $this->orderStages = array_values($orderStages ?? []);
    }

    /** Whether the step is a step of a line (`line.id` present). */
    public function onLine(): bool
    {
        return $this->lineId !== null;
    }

    /** Whether `stage` is ticked on the order. */
    public function hasStage(string $stage): bool
    {
        return in_array($stage, $this->orderStages, true);
    }

    /**
     * Reads the raw query of the rule's request (`a=b&c=d`, URL-encoded, a leading `?` allowed; e.g.
     * `$_SERVER['QUERY_STRING']`). Unknown parameters are ignored.
     *
     * @throws \InvalidArgumentException `projection` absent or not `order.flow-rule.v1`, a required parameter missing or
     *                                   repeated, `order.internal` not `true|false`, or a query over 4096 bytes --
     *                                   answer `400`
     */
    public static function fromQuery(?string $rawQuery): self
    {
        $q = $rawQuery === null ? '' : (str_starts_with($rawQuery, '?') ? substr($rawQuery, 1) : $rawQuery);
        if (strlen($q) > FlowRules::MAX_QUERY_BYTES) {
            throw new \InvalidArgumentException(FlowRules::PROJECTION . ': the query is over ' . FlowRules::MAX_QUERY_BYTES . ' bytes');
        }
        $params = [];
        if ($q !== '') {
            foreach (explode('&', $q) as $pair) {
                if ($pair === '') continue;
                $eq = strpos($pair, '=');
                $name = urldecode($eq === false ? $pair : substr($pair, 0, $eq));
                $value = $eq === false ? '' : urldecode(substr($pair, $eq + 1));
                $params[$name][] = $value;
            }
        }
        return self::fromParams($params);
    }

    /**
     * Reads the parameters already decoded (`name => list of values`, as a web framework gives them); the same rules as
     * fromQuery().
     *
     * @param ?array<string, list<string>> $params
     */
    public static function fromParams(?array $params): self
    {
        $p = $params ?? [];
        $projection = self::single($p, 'projection');
        if ($projection === null) {
            throw new \InvalidArgumentException('the projection is missing (expected projection=' . FlowRules::PROJECTION . ')');
        }
        if ($projection !== FlowRules::PROJECTION) {
            throw new \InvalidArgumentException('the projection "' . $projection . '" is not known by this rule (expected '
                . FlowRules::PROJECTION . ')');
        }
        $missing = [];
        foreach (FlowRules::REQUIRED as $name) {
            if (self::single($p, $name) === null) $missing[] = $name;
        }
        if ($missing !== []) throw new \InvalidArgumentException(FlowRules::PROJECTION . ': missing ' . implode(', ', $missing));
        $internal = self::single($p, 'order.internal');
        if ($internal !== null && $internal !== 'true' && $internal !== 'false') {
            throw new \InvalidArgumentException(FlowRules::PROJECTION . ': order.internal must be true or false (got "'
                . $internal . '")');
        }
        $stages = [];
        foreach ($p['order.stages'] ?? [] as $stage) {
            if (is_string($stage) && trim($stage) !== '') $stages[] = $stage;
        }
        return new self(self::single($p, 'rule.key'), self::single($p, 'order.id'), self::single($p, 'order.status'),
            self::single($p, 'flow.key'), self::single($p, 'flow.version'), self::single($p, 'instance.id'),
            self::single($p, 'step.key'), self::single($p, 'step.to'), self::single($p, 'order.number'),
            $internal === null ? null : $internal === 'true', self::single($p, 'order.customerId'),
            self::single($p, 'step.behavesLike'), $stages, self::single($p, 'line.id'), self::single($p, 'line.modelId'),
            self::single($p, 'line.modelVersionId'), self::single($p, 'line.quantity'));
    }

    /**
     * The query the engine sends for this projection, in the order (absent parameters left out,
     * `order.stages` repeated): for a module's tests and the platform's fake.
     */
    public function toQuery(): string
    {
        $out = [];
        $add = static function (string $name, ?string $value) use (&$out): void {
            if ($value !== null && trim($value) !== '') $out[] = [$name, $value];
        };
        $add('projection', FlowRules::PROJECTION);
        $add('rule.key', $this->ruleKey);
        $add('order.id', $this->orderId);
        $add('order.status', $this->orderStatus);
        $add('flow.key', $this->flowKey);
        $add('flow.version', $this->flowVersion);
        $add('instance.id', $this->instanceId);
        $add('step.key', $this->stepKey);
        $add('step.to', $this->stepTo);
        $add('order.number', $this->orderNumber);
        $add('order.internal', $this->orderInternal === null ? null : ($this->orderInternal ? 'true' : 'false'));
        $add('order.customerId', $this->orderCustomerId);
        $add('step.behavesLike', $this->stepBehavesLike);
        $stages = $this->orderStages;
        sort($stages, SORT_STRING);
        foreach ($stages as $stage) $add('order.stages', $stage);
        $add('line.id', $this->lineId);
        $add('line.modelId', $this->lineModelId);
        $add('line.modelVersionId', $this->lineModelVersionId);
        $add('line.quantity', $this->lineQuantity);
        return implode('&', array_map(static fn (array $e): string => PlatformChannel::queryValue($e[0]) . '='
            . PlatformChannel::queryValue($e[1]), $out));
    }

    /** @param array<string, list<string>> $p */
    private static function single(array $p, string $name): ?string
    {
        $values = $p[$name] ?? null;
        if ($values === null || $values === []) return null;
        if (count($values) > 1) throw new \InvalidArgumentException(FlowRules::PROJECTION . ': ' . $name . ' is repeated');
        $v = $values[0];
        return $v === null || trim((string) $v) === '' ? null : (string) $v;
    }
}
