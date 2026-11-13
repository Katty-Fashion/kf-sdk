// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantHooksTest.java. Do not edit by hand.
//
// TenantHooks (protocol 1.2 §6.1): a module subscribes to tenant.after.delete and receives a
// TenantDeleted only after the signature and the envelope are verified; an envelope whose data.entity is not tenant, or
// without an id, is refused; a second delivery of the same kfeventid calls the handler again (the module is
// idempotent, the SDK does not deduplicate for it).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HookEvents, KfModule, Signatures } from '../dist/index.js';

const SECRET = 'tenant-hooks-secret';
const TENANT = '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b';

function module(received) {
  const kf = KfModule.of('http://127.0.0.1:1', 'fb-mod', 'kfm_t', SECRET, 'http://fb-mod:8080');
  kf.tenants().onDeleted((d) => { received.push(d); });
  return kf;
}

/** Protocol 1.3: a module that takes the tenant's whole lifecycle. */
function lifecycleModule(state) {
  const kf = module(state.received);
  kf.tenants().onSoftDelete((e) => { state.softDeleted.push(e); }).onRestore((e) => { state.restored.push(e); });
  return kf;
}

function lifecycle() {
  return { received: [], softDeleted: [], restored: [] };
}

function envelope(entity, id, point = 'tenant.after.delete') {
  const deleted = point === 'tenant.after.delete';
  return {
    specversion: '1.0',
    id: 'd1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6',
    kfeventid: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
    type: `kf.${point}`,
    source: `/tenant/${TENANT}`,
    subject: TENANT,
    time: '2026-10-29T00:55:01.123456Z',
    datacontenttype: 'application/json',
    kfmodule: 'fb-mod',
    kfflags: {},
    kftenant: TENANT,
    kflocale: 'ro',
    kfactor: null,
    kfaggregatetype: 'tenant',
    kfenvelopeversion: '1',
    data: { entity, id, before: id == null ? null : { id }, after: deleted || id == null ? null : { id }, changed: [] },
  };
}

function send(kf, env, validSignature) {
  const bytes = Buffer.from(JSON.stringify(env), 'utf8');
  const now = Math.floor(Date.now() / 1000);
  const ts = String(now);
  const sig = validSignature ? Signatures.sign(SECRET, ts, bytes) : '0'.repeat(64);
  return kf.handle(bytes, ts, sig, now);
}

test('a signed tenant.after.delete → the handler receives TenantDeleted(tenantId), acknowledged 200', async () => {
  const received = [];
  const kf = module(received);

  const result = await send(kf, envelope('tenant', TENANT), true);

  assert.equal(result.status, 200, result.body);
  assert.equal(received.length, 1);
  const deleted = received[0];
  assert.equal(deleted.tenantId, TENANT);
  assert.equal(deleted.eventId, '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d');
  assert.equal(deleted.deliveryId, 'd1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6');
  assert.equal(deleted.envelope.event, HookEvents.TENANT_AFTER_DELETE);
});

test('a wrong signature is refused 401 before the handler', async () => {
  const received = [];
  const kf = module(received);

  const result = await send(kf, envelope('tenant', TENANT), false);

  assert.equal(result.status, 401);
  assert.equal(received.length, 0);
});

test('data.entity = model on tenant.after.delete, or no id → refused 400 permanent, no handler', async () => {
  const received = [];
  const kf = module(received);

  const wrongEntity = await send(kf, envelope('model', TENANT), true);
  const noId = await send(kf, envelope('tenant', null), true);

  assert.equal(wrongEntity.status, 400, wrongEntity.body);
  assert.equal(JSON.parse(wrongEntity.body).class, 'permanent');
  assert.equal(noId.status, 400, noId.body);
  assert.equal(received.length, 0);
});

test('the same kfeventid twice → the handler is called twice (the SDK does not deduplicate)', async () => {
  const received = [];
  const kf = module(received);

  await send(kf, envelope('tenant', TENANT), true);
  await send(kf, envelope('tenant', TENANT), true);

  assert.equal(received.length, 2);
});

test('the subscription is in the registration body: hooks[{event: tenant.after.delete}]', () => {
  const kf = module([]);

  assert.deepEqual(kf.registrationBody().hooks, [{ event: 'tenant.after.delete' }]);
});

test('protocol 1.3: tenant.after.soft-delete → onSoftDelete receives TenantSoftDeleted(tenantId, time); nothing deleted', async () => {
  const state = lifecycle();
  const kf = lifecycleModule(state);

  const result = await send(kf, envelope('tenant', TENANT, 'tenant.after.soft-delete'), true);

  assert.equal(result.status, 200, result.body);
  assert.equal(state.softDeleted.length, 1);
  const event = state.softDeleted[0];
  assert.equal(event.tenantId, TENANT);
  assert.equal(event.time, '2026-10-29T00:55:01.123456Z');
  assert.equal(event.eventId, '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d');
  assert.equal(event.envelope.event, HookEvents.TENANT_AFTER_SOFT_DELETE);
  assert.equal(state.received.length, 0, 'a soft-delete is not the purge');
  assert.equal(state.restored.length, 0);
});

test('protocol 1.3: tenant.after.restore → onRestore receives TenantRestored(tenantId, time)', async () => {
  const state = lifecycle();
  const kf = lifecycleModule(state);

  const result = await send(kf, envelope('tenant', TENANT, 'tenant.after.restore'), true);

  assert.equal(result.status, 200, result.body);
  assert.equal(state.restored.length, 1);
  assert.equal(state.restored[0].tenantId, TENANT);
  assert.equal(state.restored[0].envelope.event, HookEvents.TENANT_AFTER_RESTORE);
  assert.equal(state.softDeleted.length, 0);
});

test('protocol 1.3: the soft-delete and restore envelopes are checked like tenant.after.delete (entity tenant, an id)', async () => {
  const state = lifecycle();
  const kf = lifecycleModule(state);

  const wrongEntity = await send(kf, envelope('model', TENANT, 'tenant.after.soft-delete'), true);
  const noId = await send(kf, envelope('tenant', null, 'tenant.after.restore'), true);

  assert.equal(wrongEntity.status, 400, wrongEntity.body);
  assert.ok(wrongEntity.body.includes('tenant.after.soft-delete'), wrongEntity.body);
  assert.equal(JSON.parse(wrongEntity.body).class, 'permanent');
  assert.equal(noId.status, 400, noId.body);
  assert.equal(state.softDeleted.length + state.restored.length, 0);
});

test('protocol 1.3: the registration subscribes to each point that has a handler, in the order delete, soft-delete, restore', () => {
  const kf = lifecycleModule(lifecycle());

  assert.deepEqual(kf.registrationBody().hooks, [{ event: 'tenant.after.delete' }, { event: 'tenant.after.soft-delete' },
    { event: 'tenant.after.restore' }]);
});

test('without a handler the event is acknowledged and ignored, like any point the module did not take', async () => {
  const kf = KfModule.of('http://127.0.0.1:1', 'fb-mod', 'kfm_t', SECRET, null);

  const result = await send(kf, envelope('tenant', TENANT), true);

  assert.equal(result.status, 200);
  assert.ok(result.body.includes('ignored'));
});
