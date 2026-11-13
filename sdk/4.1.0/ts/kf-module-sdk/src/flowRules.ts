// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowRules.java. Do not edit by hand: fix Java, then re-derive.
import { Decision as HookDecision } from './decision.js';
import { Manifest } from './manifest.js';
import { queryValue } from './platformChannel.js';

const PROJECTION = Manifest.FLOW_RULE_PROJECTION;
const MAX_QUERY_BYTES = 4096;
const MAX_PARAMS = 20;
const REQUIRED: readonly string[] = Object.freeze(['projection', 'rule.key', 'order.id', 'order.status', 'flow.key',
  'flow.version', 'instance.id', 'step.key', 'step.to']);
const OPTIONAL: readonly string[] = Object.freeze(['order.number', 'order.internal', 'order.customerId',
  'step.behavesLike', 'order.stages', 'line.id', 'line.modelId', 'line.modelVersionId', 'line.quantity']);
const STAGES: readonly string[] = Object.freeze(['development', 'sampling', 'production']);

/** The fields of a {@link FlowRuleProjection} (Java's record components). */
export interface FlowRuleProjectionFields {
  ruleKey: string | null;
  orderId: string | null;
  orderStatus: string | null;
  flowKey: string | null;
  flowVersion: string | null;
  instanceId: string | null;
  stepKey: string | null;
  stepTo: string | null;
  orderNumber: string | null;
  orderInternal: boolean | null;
  orderCustomerId: string | null;
  stepBehavesLike: string | null;
  orderStages: readonly string[] | null;
  lineId: string | null;
  lineModelId: string | null;
  lineModelVersionId: string | null;
  lineQuantity: string | null;
}

/**
 * The order as a rule sees it (`order.flow-rule.v1`); every value is the engine's text, `null` when the parameter is
 * absent (not applicable). `orderStages` is never null (empty = none ticked). Java: `FlowRules.Projection`.
 */
export class FlowRuleProjection {
  readonly ruleKey: string | null;
  readonly orderId: string | null;
  readonly orderStatus: string | null;
  readonly flowKey: string | null;
  readonly flowVersion: string | null;
  readonly instanceId: string | null;
  readonly stepKey: string | null;
  readonly stepTo: string | null;
  readonly orderNumber: string | null;
  readonly orderInternal: boolean | null;
  readonly orderCustomerId: string | null;
  readonly stepBehavesLike: string | null;
  readonly orderStages: readonly string[];
  readonly lineId: string | null;
  readonly lineModelId: string | null;
  readonly lineModelVersionId: string | null;
  readonly lineQuantity: string | null;

  constructor(f: FlowRuleProjectionFields) {
    this.ruleKey = f.ruleKey;
    this.orderId = f.orderId;
    this.orderStatus = f.orderStatus;
    this.flowKey = f.flowKey;
    this.flowVersion = f.flowVersion;
    this.instanceId = f.instanceId;
    this.stepKey = f.stepKey;
    this.stepTo = f.stepTo;
    this.orderNumber = f.orderNumber;
    this.orderInternal = f.orderInternal;
    this.orderCustomerId = f.orderCustomerId;
    this.stepBehavesLike = f.stepBehavesLike;
    this.orderStages = Object.freeze([...(f.orderStages ?? [])]);
    this.lineId = f.lineId;
    this.lineModelId = f.lineModelId;
    this.lineModelVersionId = f.lineModelVersionId;
    this.lineQuantity = f.lineQuantity;
  }

  /** Whether the step is a step of a line (`line.id` present). */
  onLine(): boolean {
    return this.lineId != null;
  }

  /** Whether `stage` is ticked on the order. */
  hasStage(stage: string): boolean {
    return this.orderStages.includes(stage);
  }

  /**
   * Reads the raw query of the rule's request (`a=b&c=d`, URL-encoded, a leading `?` allowed). Unknown parameters are
   * ignored.
   *
   * @throws TypeError `projection` absent or not `order.flow-rule.v1`, a required parameter missing or repeated,
   *         `order.internal` not `true|false`, or a query over 4096 bytes -- answer `400` (Java:
   *         `IllegalArgumentException`)
   */
  static fromQuery(rawQuery: string | null | undefined): FlowRuleProjection {
    const q = rawQuery == null ? '' : rawQuery.startsWith('?') ? rawQuery.substring(1) : rawQuery;
    if (Buffer.byteLength(q, 'utf8') > MAX_QUERY_BYTES) {
      throw new TypeError(`${PROJECTION}: the query is over ${MAX_QUERY_BYTES} bytes`);
    }
    const params = new Map<string, string[]>();
    if (q !== '') {
      for (const pair of q.split('&')) {
        if (pair === '') continue;
        const eq = pair.indexOf('=');
        const name = decode(eq < 0 ? pair : pair.substring(0, eq));
        const value = eq < 0 ? '' : decode(pair.substring(eq + 1));
        const list = params.get(name) ?? [];
        list.push(value);
        params.set(name, list);
      }
    }
    return FlowRuleProjection.fromParams(params);
  }

  /**
   * Reads the parameters already decoded (`name -> values`, as a web framework gives them); the same rules as
   * {@link FlowRuleProjection.fromQuery}.
   */
  static fromParams(params: ReadonlyMap<string, readonly string[]> | Readonly<Record<string, readonly string[]>> | null
    | undefined): FlowRuleProjection {
    const p: ReadonlyMap<string, readonly string[]> = params == null ? new Map()
      : params instanceof Map ? params : new Map(Object.entries(params as Record<string, readonly string[]>));
    const projection = single(p, 'projection');
    if (projection == null) {
      throw new TypeError(`the projection is missing (expected projection=${PROJECTION})`);
    }
    if (projection !== PROJECTION) {
      throw new TypeError(`the projection "${projection}" is not known by this rule (expected ${PROJECTION})`);
    }
    const missing = REQUIRED.filter((name) => single(p, name) == null);
    if (missing.length > 0) throw new TypeError(`${PROJECTION}: missing ${missing.join(', ')}`);
    const internal = single(p, 'order.internal');
    if (internal != null && internal !== 'true' && internal !== 'false') {
      throw new TypeError(`${PROJECTION}: order.internal must be true or false (got "${internal}")`);
    }
    const stages = (p.get('order.stages') ?? []).filter((s) => s != null && s.trim() !== '');
    return new FlowRuleProjection({
      ruleKey: single(p, 'rule.key'), orderId: single(p, 'order.id'), orderStatus: single(p, 'order.status'),
      flowKey: single(p, 'flow.key'), flowVersion: single(p, 'flow.version'), instanceId: single(p, 'instance.id'),
      stepKey: single(p, 'step.key'), stepTo: single(p, 'step.to'), orderNumber: single(p, 'order.number'),
      orderInternal: internal == null ? null : internal === 'true', orderCustomerId: single(p, 'order.customerId'),
      stepBehavesLike: single(p, 'step.behavesLike'), orderStages: stages, lineId: single(p, 'line.id'),
      lineModelId: single(p, 'line.modelId'), lineModelVersionId: single(p, 'line.modelVersionId'),
      lineQuantity: single(p, 'line.quantity'),
    });
  }

  /**
   * The query the engine sends for this projection, in the order (absent parameters left out,
   * `order.stages` repeated): for a module's tests and the platform's fake.
   */
  toQuery(): string {
    const out: Array<[string, string]> = [];
    const add = (name: string, value: string | null): void => {
      if (value != null && value.trim() !== '') out.push([name, value]);
    };
    add('projection', PROJECTION);
    add('rule.key', this.ruleKey);
    add('order.id', this.orderId);
    add('order.status', this.orderStatus);
    add('flow.key', this.flowKey);
    add('flow.version', this.flowVersion);
    add('instance.id', this.instanceId);
    add('step.key', this.stepKey);
    add('step.to', this.stepTo);
    add('order.number', this.orderNumber);
    add('order.internal', this.orderInternal == null ? null : String(this.orderInternal));
    add('order.customerId', this.orderCustomerId);
    add('step.behavesLike', this.stepBehavesLike);
    for (const stage of [...this.orderStages].sort(compareUtf16)) add('order.stages', stage);
    add('line.id', this.lineId);
    add('line.modelId', this.lineModelId);
    add('line.modelVersionId', this.lineModelVersionId);
    add('line.quantity', this.lineQuantity);
    return out.map(([k, v]) => `${queryValue(k)}=${queryValue(v)}`).join('&');
  }
}

/**
 * The answer of a rule: `{"decision": "pass"}` or `{"decision": "block", "key": …, "params": …}`. The key is a key of
 * the manifest's `i18n.en` (the engine shows its text with the params). Java: `FlowRules.Decision`.
 */
export class FlowRuleDecision {
  static readonly PASS = 'pass';
  static readonly BLOCK = 'block';

  readonly params: Readonly<Record<string, unknown>>;

  private constructor(readonly decision: string, readonly key: string | null,
    params: Readonly<Record<string, unknown>> | null | undefined) {
    this.params = Object.freeze({ ...(params ?? {}) });
  }

  /** The step may advance, as far as this rule is concerned. */
  static pass(): FlowRuleDecision {
    return new FlowRuleDecision(FlowRuleDecision.PASS, null, {});
  }

  /**
   * The step may not advance, with the reason (Java's `block(key)` and `block(key, params)`).
   *
   * @throws TypeError no key, a key outside `^[a-z][a-z0-9_.-]{0,119}$` or more than 20 params (the engine would block
   *         the step anyway, with its own reason instead of the module's) (Java: `IllegalArgumentException`)
   */
  static block(key: string | null | undefined, params?: Readonly<Record<string, unknown>> | null): FlowRuleDecision {
    if (key == null || key.trim() === '') throw new TypeError('a block needs a key (a key of i18n.en)');
    if (!HookDecision.KEY.test(key)) throw new TypeError(`the key "${key}" must match ${HookDecision.KEY.source}`);
    const p = { ...(params ?? {}) };
    const size = Object.keys(p).length;
    if (size > MAX_PARAMS) throw new TypeError(`a block carries at most ${MAX_PARAMS} params (got ${size})`);
    return new FlowRuleDecision(FlowRuleDecision.BLOCK, key, p);
  }

  /** Whether the step may advance. */
  passes(): boolean {
    return this.decision === FlowRuleDecision.PASS;
  }

  /** The JSON body of the `200` answer. */
  toJson(): Record<string, unknown> {
    const m: Record<string, unknown> = { decision: this.decision };
    if (this.decision === FlowRuleDecision.BLOCK) {
      m.key = this.key;
      if (Object.keys(this.params).length > 0) m.params = { ...this.params };
    }
    return m;
  }
}

/**
 * The rules of a module step in the order flow (protocol 2.0, sdk-protocol.md §16.4): the engine
 * (business-core) calls `GET {flowRules[].path}?<order.flow-rule.v1>` with the context `system via platform`, before the
 * step advances; the module answers `200 {decision: pass}` or `200 {decision: block, key, params?}`. The engine is
 * fail-closed: a timeout, a non-2xx, a body that is not JSON or an unknown decision block the step; several rules on a
 * step are an AND and every reason is shown. Delivered by the platform with `FLOW_RULES`; a module relies on
 * it only when the platform announces the feature. A platform before it refuses `capabilities.flowRules` at upload,
 * with the text of the key (§16.7).
 *
 * A rule reads the order ONLY from the projection ({@link FlowRuleProjection.fromQuery}); it never reads `order.v1`
 * while it evaluates (`order.v1` stays non-`system`). A projection it does not know, or a missing required
 * parameter, is answered `400` (a `TypeError` here), which the engine reads as a block.
 */
export const FlowRules = Object.freeze({
  /** The projection of protocol 2.0: the major is in the key; a rename or a removal is `order.flow-rule.v2`. */
  PROJECTION,
  /** The largest query the engine sends, in bytes (UTF-8, encoded). */
  MAX_QUERY_BYTES,
  /** The most `params` a `block` carries. */
  MAX_PARAMS,
  /** The required parameters, in the order the engine emits them. */
  REQUIRED,
  /** The optional parameters, in the order the engine emits them; `order.stages` is repeated. */
  OPTIONAL,
  /** The stages an order may have ticked (`order.stages`). */
  STAGES,
  Projection: FlowRuleProjection,
  Decision: FlowRuleDecision,
});

function single(p: ReadonlyMap<string, readonly string[]>, name: string): string | null {
  const values = p.get(name);
  if (values == null || values.length === 0) return null;
  if (values.length > 1) throw new TypeError(`${PROJECTION}: ${name} is repeated`);
  const v = values[0];
  return v == null || v.trim() === '' ? null : v;
}

/** Java's `URLDecoder.decode(text, UTF_8)`: `+` is a space. */
function decode(text: string): string {
  try {
    return decodeURIComponent(text.replace(/\+/g, ' '));
  } catch (e) {
    throw new TypeError(`${PROJECTION}: the query is not URL-encoded (${(e as Error).message})`);
  }
}

/** Java's `String.compareTo` order (UTF-16 code units). */
function compareUtf16(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
