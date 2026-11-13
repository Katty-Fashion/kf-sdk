// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowRulesTest.java. Do not edit by hand.
//
// FlowRules (protocol 2.0, sdk-protocol.md §16.4): the projection order.flow-rule.v1 read from the rule's
// query -- every field, order.stages repeated, unknown parameters ignored, an unknown projection or a
// missing required field refused (the module answers 400) -- and the rule's answer.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FlowRules } from '../dist/index.js';

/** The query of a step of a line, as the engine's OrderFlowRuleProjection emits it. */
const FULL = 'projection=order.flow-rule.v1&rule.key=item-ready&order.id=812&order.status=CONFIRMED'
  + '&flow.key=g3-production&flow.version=4&instance.id=31&step.key=cutting&step.to=sewing&order.number=PO+2026%2F7'
  + '&order.internal=false&order.customerId=55&step.behavesLike=IN_PRODUCTION&order.stages=production'
  + '&order.stages=sampling&line.id=4401&line.modelId=90&line.modelVersionId=901&line.quantity=120';

test('a full v1 query gives every field', () => {
  const p = FlowRules.Projection.fromQuery(`?${FULL}`);
  assert.equal(p.ruleKey, 'item-ready');
  assert.equal(p.orderId, '812');
  assert.equal(p.orderStatus, 'CONFIRMED');
  assert.equal(p.flowKey, 'g3-production');
  assert.equal(p.flowVersion, '4');
  assert.equal(p.instanceId, '31');
  assert.equal(p.stepKey, 'cutting');
  assert.equal(p.stepTo, 'sewing');
  assert.equal(p.orderNumber, 'PO 2026/7');
  assert.equal(p.orderInternal, false);
  assert.equal(p.orderCustomerId, '55');
  assert.equal(p.stepBehavesLike, 'IN_PRODUCTION');
  assert.deepEqual([...p.orderStages], ['production', 'sampling']);
  assert.ok(p.hasStage('sampling'));
  assert.ok(!p.hasStage('development'));
  assert.ok(p.onLine());
  assert.equal(p.lineId, '4401');
  assert.equal(p.lineModelId, '90');
  assert.equal(p.lineModelVersionId, '901');
  assert.equal(p.lineQuantity, '120');
});

test('an internal order without line has no customer and no line', () => {
  const p = FlowRules.Projection.fromQuery('projection=order.flow-rule.v1&rule.key=r&order.id=9'
    + '&order.status=COMPLETED&flow.key=f&flow.version=1&instance.id=2&step.key=a&step.to=b&order.internal=true');
  assert.equal(p.orderInternal, true);
  assert.equal(p.orderCustomerId, null);
  assert.equal(p.orderStatus, 'COMPLETED');
  assert.deepEqual([...p.orderStages], []);
  assert.ok(!p.onLine());
});

test('unknown parameters are ignored', () => {
  assert.equal(FlowRules.Projection.fromQuery(`${FULL}&order.priority=high&flow.extra=1`).ruleKey, 'item-ready');
});

test('an unknown or missing projection is refused', () => {
  assert.throws(() => FlowRules.Projection.fromQuery(FULL.replace('order.flow-rule.v1', 'order.flow-rule.v2')),
    (e) => e instanceof TypeError && e.message.includes('"order.flow-rule.v2" is not known'));
  assert.throws(() => FlowRules.Projection.fromQuery(FULL.replace('projection=order.flow-rule.v1&', '')),
    (e) => e instanceof TypeError && e.message.includes('the projection is missing'));
});

test('a missing required field is refused naming it', () => {
  assert.throws(() => FlowRules.Projection.fromQuery(FULL.replace('&step.to=sewing', '').replace('&instance.id=31', '')),
    (e) => e instanceof TypeError && e.message.includes('missing instance.id, step.to'));
});

test('a repeated single field, a bad boolean and an oversized query are refused', () => {
  assert.throws(() => FlowRules.Projection.fromQuery(`${FULL}&step.to=packing`), TypeError);
  assert.throws(() => FlowRules.Projection.fromQuery(FULL.replace('order.internal=false', 'order.internal=yes')), TypeError);
  assert.throws(() => FlowRules.Projection.fromQuery(`${FULL}&pad=${'x'.repeat(FlowRules.MAX_QUERY_BYTES)}`), TypeError);
});

test('the query round-trips in the engine\'s order', () => {
  const p = FlowRules.Projection.fromQuery(FULL);
  const query = p.toQuery();
  assert.ok(query.startsWith('projection=order.flow-rule.v1&rule.key=item-ready&order.id=812'), query);
  assert.ok(query.includes('order.stages=production&order.stages=sampling'), `the stages sorted: ${query}`);
  assert.deepEqual(FlowRules.Projection.fromQuery(query), p);
  const params = new Map([['projection', ['order.flow-rule.v1']]]);
  for (const name of ['rule.key', 'order.id', 'order.status', 'flow.key', 'flow.version', 'instance.id', 'step.key', 'step.to']) {
    params.set(name, ['x']);
  }
  assert.equal(FlowRules.Projection.fromParams(params).stepTo, 'x');
});

test('the decisions', () => {
  assert.deepEqual(FlowRules.Decision.pass().toJson(), { decision: 'pass' });
  assert.ok(FlowRules.Decision.pass().passes());
  const block = FlowRules.Decision.block('rule.blocked', { item: 'A-1' }).toJson();
  assert.equal(block.decision, 'block');
  assert.equal(block.key, 'rule.blocked');
  assert.deepEqual(block.params, { item: 'A-1' });
  assert.deepEqual(FlowRules.Decision.block('rule.blocked').toJson(), { decision: 'block', key: 'rule.blocked' });
  assert.ok(!FlowRules.Decision.block('rule.blocked').passes());
});

test('a block without key or with too many params is refused locally', () => {
  assert.throws(() => FlowRules.Decision.block(null, {}), TypeError);
  assert.throws(() => FlowRules.Decision.block(' ', {}), TypeError);
  assert.throws(() => FlowRules.Decision.block('Not A Key', {}), TypeError);
  const many = {};
  for (let i = 0; i < 21; i++) many[`p${i}`] = i;
  assert.throws(() => FlowRules.Decision.block('rule.blocked', many),
    (e) => e instanceof TypeError && e.message.includes('at most 20 params'));
  delete many.p20;
  assert.equal(Object.keys(FlowRules.Decision.block('rule.blocked', many).toJson().params).length, 20);
});

test('the projection key is the manifest one', () => {
  assert.equal(FlowRules.PROJECTION, 'order.flow-rule.v1');
  assert.deepEqual([...FlowRules.STAGES], ['development', 'sampling', 'production']);
});
