// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/FlowEventsTest.java. Do not edit by hand.
//
// FlowEvents (protocol 2.0, sdk-protocol.md §16.3) and the order hooks (kf.orders(), §16.2): the four events of the flow
// engine read from their envelope and dispatched to the subscribed handlers (and sent with the registration, without
// onFailure); order.field.changed on the path status; a before hook on an order may patch only the module's own
// extensions.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Envelope, FlowEvents, HookEvents, KfModule, ORDER_POINTS, ORDER_STATUSES, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'flow-events-secret';
const TENANT = '00000000-0000-0000-0000-0000000000f1';

setSdkLogger({ info() {}, warn() {}, error() {} });

function envelope(point, eventId, data, aggregateType) {
  return {
    specversion: '1.0', id: `delivery-${eventId}`, kfeventid: eventId, type: `kf.${point}`, source: `/${aggregateType}/812`,
    subject: '812', time: '2026-11-09T10:00:00Z', datacontenttype: 'application/json', kfmodule: 'kf-step', kfflags: {},
    kftenant: TENANT, kflocale: 'en', kfactor: null, kfaggregatetype: aggregateType, kfenvelopeversion: '1', data,
  };
}

function flowData(stepKey) {
  const data = { tenantId: TENANT, orderId: 812, instanceId: 31, flowKey: 'g3-production', flowVersion: 4 };
  if (stepKey != null) data.stepKey = stepKey;
  data.iteration = 1;
  return data;
}

function send(kf, env) {
  const bytes = Buffer.from(JSON.stringify(env), 'utf8');
  const now = Math.floor(Date.now() / 1000);
  const ts = String(now);
  return kf.handle(bytes, ts, Signatures.sign(SECRET, ts, bytes), now);
}

function module() {
  return KfModule.of('http://127.0.0.1:1', 'kf-step', 'kfm_t', SECRET, 'http://kf-step:8080');
}

test('each of the four types is parsed', () => {
  for (const type of FlowEvents.TYPES) {
    const ce = envelope(type, `e-${type}`, flowData('cutting'), 'flow-instance');
    const ev = FlowEvents.parse(Envelope.of(ce), ce.data);
    assert.equal(ev.type, type);
    assert.equal(ev.orderId, '812');
    assert.equal(ev.instanceId, '31');
    assert.equal(ev.flowKey, 'g3-production');
    assert.equal(ev.flowVersion, 4);
    assert.equal(ev.stepKey, 'cutting');
    assert.equal(ev.eventId, `e-${type}`);
    assert.equal(ev.tenant, TENANT);
    assert.equal(ev.data.iteration, 1, 'unknown keys are kept in data');
  }
});

test('stepKey is optional only on the instance completion', () => {
  const completed = envelope(FlowEvents.INSTANCE_COMPLETED, 'e1', flowData(null), 'flow-instance');
  assert.equal(FlowEvents.parse(Envelope.of(completed), completed.data).stepKey, null);
  const entered = envelope(FlowEvents.STEP_ENTERED, 'e2', flowData(null), 'flow-instance');
  assert.throws(() => FlowEvents.parse(Envelope.of(entered), entered.data),
    (e) => e instanceof TypeError && e.message.includes('needs data.stepKey'));
  const noOrder = { ...flowData('a') };
  delete noOrder.orderId;
  const ce = envelope(FlowEvents.STEP_COMPLETED, 'e3', noOrder, 'flow-instance');
  assert.throws(() => FlowEvents.parse(Envelope.of(ce), noOrder), TypeError);
  const other = envelope('workflow.flow-started', 'e4', flowData('a'), 'flow-instance');
  assert.throws(() => FlowEvents.parse(Envelope.of(other), flowData('a')), TypeError);
});

test('a subscribed event reaches its handler and is registered without onFailure', async () => {
  const kf = module();
  const seen = [];
  kf.flows().on(FlowEvents.STEP_ENTERED, (e) => { seen.push(e); }).on(FlowEvents.LINE_PROMOTED, (e) => { seen.push(e); });
  assert.throws(() => kf.flows().on('order.after.update', () => {}), TypeError);

  const result = await send(kf, envelope(FlowEvents.STEP_ENTERED, 'e-1', flowData('cutting'), 'flow-instance'));
  assert.equal(result.status, 200, result.body);
  assert.equal(seen.length, 1);
  assert.equal(seen[0].stepKey, 'cutting');

  const notSubscribed = await send(kf, envelope(FlowEvents.STEP_COMPLETED, 'e-2', flowData('cutting'), 'flow-instance'));
  assert.equal(notSubscribed.status, 200);
  assert.ok(notSubscribed.body.includes('ignored'), notSubscribed.body);

  const invalid = await send(kf, envelope(FlowEvents.STEP_ENTERED, 'e-3', { orderId: 1 }, 'flow-instance'));
  assert.equal(invalid.status, 400, invalid.body);
  assert.equal(seen.length, 1);

  const hooks = kf.registrationBody().hooks;
  assert.ok(hooks.some((h) => h.event === FlowEvents.STEP_ENTERED && Object.keys(h).length === 1), JSON.stringify(hooks));
  assert.ok(hooks.some((h) => h.event === FlowEvents.LINE_PROMOTED && Object.keys(h).length === 1), JSON.stringify(hooks));
});

test('order.field.changed on status reaches the order watch', async () => {
  const kf = module();
  const changes = [];
  const modelChanges = [];
  kf.orders().onFieldChange(HookEvents.ORDER_PATH_STATUS, (c) => { changes.push(c); });
  kf.models().onFieldChange([], (c) => { modelChanges.push(c); });
  const data = { entity: 'order', id: '812', field: 'status', oldValue: HookEvents.ORDER_STATUS_SHIPPED,
    newValue: HookEvents.ORDER_STATUS_COMPLETED };
  const r = await send(kf, envelope(HookEvents.ORDER_FIELD_CHANGED, 'e-9', data, 'order'));
  assert.equal(r.status, 200, r.body);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].newValue, 'COMPLETED');
  assert.equal(modelChanges.length, 0, 'an order change never reaches the model\'s watches');

  const hooks = kf.registrationBody().hooks;
  assert.ok(hooks.some((h) => h.event === HookEvents.ORDER_FIELD_CHANGED && JSON.stringify(h.fields) === '["status"]'),
    JSON.stringify(hooks));
  assert.ok(ORDER_STATUSES.includes('COMPLETED'));
  assert.equal(ORDER_POINTS.length, 7);
});

test('an order before hook patches only its own extensions', async () => {
  const kf = module();
  kf.orders().beforeUpdate((ctx) => ctx.patch().extension('checked', true))
    .beforeCreate((ctx) => ctx.patch().set('status', 'CONFIRMED'))
    .beforeDelete((ctx) => ctx.reject('order.locked'));
  const data = { entity: 'order', id: '812', before: { status: 'DRAFT' }, after: { status: 'CONFIRMED' }, changed: ['status'] };

  const ok = await send(kf, envelope(HookEvents.ORDER_BEFORE_UPDATE, 'b-1', data, 'order'));
  assert.equal(ok.status, 200, ok.body);
  assert.ok(ok.body.includes('"kf-step":{"checked":true}'), ok.body);

  const core = await send(kf, envelope(HookEvents.ORDER_BEFORE_CREATE, 'b-2', data, 'order'));
  assert.equal(core.status, 500, core.body);
  assert.ok(core.body.includes('permanent') && core.body.includes('status'), core.body);

  const veto = await send(kf, envelope(HookEvents.ORDER_BEFORE_DELETE, 'b-3', data, 'order'));
  assert.equal(veto.status, 200);
  assert.ok(veto.body.includes('"reject"') && veto.body.includes('order.locked'), veto.body);

  const hooks = kf.registrationBody().hooks;
  assert.ok(hooks.some((h) => h.event === HookEvents.ORDER_BEFORE_UPDATE), JSON.stringify(hooks));
  assert.equal(kf.orders().entity(), 'order');
  assert.equal(kf.models().entity(), 'model');
});
