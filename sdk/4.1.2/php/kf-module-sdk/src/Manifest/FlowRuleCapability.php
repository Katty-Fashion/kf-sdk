<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.FlowRuleCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

use Kf\Sdk\Manifest;

/**
 * A rule of a module step in the order flow (protocol 2.0, `capabilities.flowRules[]`, sdk-protocol.md §16.4): the engine calls `GET {path}?<order.flow-rule.v1>` before the step advances.
 *
 * `key` `^[a-z][a-z0-9-]{1,40}$`, unique; `path` a `GET` route this manifest declares; `timeoutSeconds` 1..60, null =
 * the default 2 s; `projection` `order.flow-rule.v1`.
 */
final class FlowRuleCapability
{
    public function __construct(
        public readonly ?string $key,
        public readonly ?string $path,
        public readonly ?int $timeoutSeconds,
        public readonly ?string $projection,
    ) {
    }

    /** `timeoutSeconds`, or the default when absent. */
    public function effectiveTimeoutSeconds(): int
    {
        return $this->timeoutSeconds ?? Manifest::DEFAULT_FLOW_RULE_TIMEOUT_SECONDS;
    }
}
