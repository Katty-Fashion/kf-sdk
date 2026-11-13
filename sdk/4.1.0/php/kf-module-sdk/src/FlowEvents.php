<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowEvents.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\FlowEvents\FlowEvent;
use Kf\Sdk\HookEvents\Envelope;

/**
 * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): `workflow.flow-step-entered`,
 * `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted` -- events of the
 * engine's outbox (not hook points), delivered after the commit, at least once, signed, with `kfeventid`. A module
 * subscribes with `capabilities.hooks[{point}]` without `onFailure` and `$kf->flows()->on($type, $handler)`. Delivered
 * by the platform with `FLOW_EVENTS`, from the third source of subscribable points
 * (`PublishedPlatformEventCatalog`), not from the hook catalog (§16.3); a module relies on it only when the platform
 * announces the feature. A platform before it refuses the subscription at upload, with the text of the key (§16.7).
 *
 * `data` is fixed; until then an object whose minimal named fields are `orderId`, `instanceId` and `stepKey`
 * (`flowKey`, `flowVersion` too; unknown keys kept in FlowEvent::$data). `stepKey` may be absent only on
 * INSTANCE_COMPLETED. The module deduplicates on FlowEvent::eventId(); a flow that comes back to a step delivers
 * STEP_ENTERED again under another `kfeventid`.
 */
final class FlowEvents
{
    public const STEP_ENTERED = 'workflow.flow-step-entered';
    public const STEP_COMPLETED = 'workflow.flow-step-completed';
    public const INSTANCE_COMPLETED = 'workflow.flow-instance-completed';
    public const LINE_PROMOTED = 'workflow.flow-line-promoted';

    /** The four types, in the documented order. */
    public const TYPES = [self::STEP_ENTERED, self::STEP_COMPLETED, self::INSTANCE_COMPLETED, self::LINE_PROMOTED];

    /** @var array<string, list<callable>> */
    private array $handlers = [];

    /**
     * Reads one delivery of `type` (Envelope::$event).
     *
     * @param ?array<string, mixed> $data
     * @throws \InvalidArgumentException an unknown type, or `data` without `orderId` / `instanceId` (or `stepKey` on a
     *                                   type other than INSTANCE_COMPLETED)
     */
    public static function parse(Envelope $envelope, ?array $data): FlowEvent
    {
        $type = $envelope->event;
        if (!in_array($type, self::TYPES, true)) {
            throw new \InvalidArgumentException('"' . $type . '" is not an event of the flow engine (known: '
                . implode(', ', self::TYPES) . ')');
        }
        $d = $data ?? [];
        $missing = [];
        $orderId = self::id($d['orderId'] ?? null);
        $instanceId = self::id($d['instanceId'] ?? null);
        $stepKey = is_string($d['stepKey'] ?? null) && trim($d['stepKey']) !== '' ? $d['stepKey'] : null;
        if ($orderId === null) $missing[] = 'orderId';
        if ($instanceId === null) $missing[] = 'instanceId';
        if ($stepKey === null && $type !== self::INSTANCE_COMPLETED) $missing[] = 'stepKey';
        if ($missing !== []) throw new \InvalidArgumentException($type . ' needs data.' . implode(', data.', $missing));
        $flowKey = is_string($d['flowKey'] ?? null) ? $d['flowKey'] : null;
        $v = $d['flowVersion'] ?? null;
        $flowVersion = is_int($v) || is_float($v) ? (int) $v : null;
        return new FlowEvent($type, $orderId, $instanceId, $flowKey, $flowVersion, $stepKey, $d, $envelope);
    }

    /** An id as text: a non-blank string or a whole number; else null. */
    public static function id(mixed $value): ?string
    {
        if (is_string($value) && trim($value) !== '') return $value;
        if (is_int($value)) return (string) $value;
        return null;
    }

    // ---- the subscriptions of a module ----------------------------------------------------------------------

    /**
     * Subscribes `handler` (`fn (FlowEvent $e)`, a throw becomes HTTP 500 transient) to `type` (one of TYPES), sent with
     * the registration as `{"event": type}` (never with `onFailure`).
     *
     * @throws \InvalidArgumentException a type that is not an event of the flow engine
     */
    public function on(string $type, callable $handler): self
    {
        if (!in_array($type, self::TYPES, true)) {
            throw new \InvalidArgumentException('"' . $type . '" is not an event of the flow engine (known: '
                . implode(', ', self::TYPES) . ')');
        }
        $this->handlers[$type][] = $handler;
        return $this;
    }

    /**
     * @internal The handlers of `type`; empty when none.
     *
     * @return list<callable>
     */
    public function handlers(string $type): array
    {
        return $this->handlers[$type] ?? [];
    }

    /**
     * @internal The `hooks` part of the registration body: one `{"event": type}` per subscribed type, in order.
     *
     * @return list<array{event: string}>
     */
    public function registration(): array
    {
        $hooks = [];
        foreach (self::TYPES as $type) {
            if (isset($this->handlers[$type])) $hooks[] = ['event' => $type];
        }
        return $hooks;
    }
}
