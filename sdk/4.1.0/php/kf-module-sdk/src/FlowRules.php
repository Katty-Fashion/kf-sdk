<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowRules.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The rules of a module step in the order flow (protocol 2.0, sdk-protocol.md §16.4): the engine
 * (business-core) calls `GET {flowRules[].path}?<order.flow-rule.v1>` with the context `system via platform`, before the
 * step advances; the module answers `200 {decision: pass}` or `200 {decision: block, key, params?}`. The engine is
 * fail-closed: a timeout, a non-2xx, a body that is not JSON or an unknown decision block the step; several rules on a
 * step are an AND and every reason is shown. Delivered by the platform with `FLOW_RULES`; a module relies on
 * it only when the platform announces the feature. A platform before it refuses `capabilities.flowRules` at upload,
 * with the text of the key (§16.7).
 *
 * A rule reads the order ONLY from the projection (FlowRules\Projection::fromQuery()); it never reads `order.v1` while it
 * evaluates (`order.v1` stays non-`system`). A projection it does not know, or a missing required parameter,
 * is answered `400` (\InvalidArgumentException here), which the engine reads as a block.
 */
final class FlowRules
{
    /** The projection of protocol 2.0: the major is in the key; a rename or a removal is `order.flow-rule.v2`. */
    public const PROJECTION = Manifest::FLOW_RULE_PROJECTION;

    /** The largest query the engine sends, in bytes (UTF-8, encoded). */
    public const MAX_QUERY_BYTES = 4096;

    /** The most `params` a `block` carries. */
    public const MAX_PARAMS = 20;

    /** The required parameters, in the order the engine emits them. */
    public const REQUIRED = ['projection', 'rule.key', 'order.id', 'order.status', 'flow.key', 'flow.version',
        'instance.id', 'step.key', 'step.to'];

    /** The optional parameters, in the order the engine emits them; `order.stages` is repeated. */
    public const OPTIONAL = ['order.number', 'order.internal', 'order.customerId', 'step.behavesLike', 'order.stages',
        'line.id', 'line.modelId', 'line.modelVersionId', 'line.quantity'];

    /** The stages an order may have ticked (`order.stages`). */
    public const STAGES = ['development', 'sampling', 'production'];

    private function __construct()
    {
    }
}
