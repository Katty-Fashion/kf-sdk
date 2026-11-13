// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/EventsClientTest.java. Do not edit by hand.
//
// EventsClient (protocol 2.0, sdk-protocol.md §6.3): the emission POST …/events {name, data} →
// 202 {eventId, type}, the local refusals (an undeclared event, data over 64 KiB), the platform's 422
// event-not-declared; the subscription m.{owner}.after.{name} in the registration and its delivery to the handler, with
// the delivery's context and proof.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { EventsClient, KfException, KfModule, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'events-secret';
const TOKEN = 'kfm_events-token';
const PROOF = 'c'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000b';

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { requests: 0, status: 202, answer: '{"eventId":"e-1","type":"m.kf-stock.after.level-changed"}', uri: null, body: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.uri = `${req.method} ${req.url}`;
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(state.status);
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'kf-stock', TOKEN, SECRET, null);
  return { server, state, kf };
}

function verified(kf, requestId) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"${TENANT}","user":"sub-1","actor":{"type":"user","via":null,"hookId":null},"locale":"en",`
    + `"rights":[],"method":"POST","path":"/x","query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), PROOF);
}

/** The context the platform puts on an after delivery (tenant …0a, as in PeersClientTest). */
function deliveryHeader(ts, requestId) {
  const json = `{"v":1,"tenant":"00000000-0000-0000-0000-00000000000a","user":null,"actor":{"type":"system","via":"hook",`
    + `"hookId":"${requestId}"},"locale":"en","rights":[],"method":"POST","path":"/_kf/hooks","query":null,"ts":${ts},`
    + `"requestId":"${requestId}"}`;
  return Buffer.from(json, 'utf8').toString('base64url');
}

async function failing(promise) {
  try {
    await promise;
  } catch (e) {
    assert.ok(e instanceof KfException, String(e));
    return e;
  }
  assert.fail('expected a KfException');
}

test('emit: POST …/modules/kf-stock/events {name, data} → 202 {eventId, type}', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-1');
    const emitted = await kf.events().emit(ctx, 'level-changed', { sku: 'KF-42' });
    assert.equal(state.uri, 'POST /api/v1/platform/modules/kf-stock/events');
    assert.equal(state.body, '{"name":"level-changed","data":{"sku":"KF-42"}}');
    assert.equal(emitted.eventId, 'e-1');
    assert.equal(emitted.type, 'm.kf-stock.after.level-changed');
  } finally {
    server.close();
  }
});

test('emit: an undeclared event, a bad name, data null or over 64 KiB are refused locally', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-2');
    kf.withManifest('{"schemaVersion":3,"slug":"kf-stock","capabilities":{"events":[{"name":"level-changed","visibility":"public"}]}}');
    const undeclared = await failing(kf.events().emit(ctx, 'other', {}));
    assert.ok(undeclared.message.includes('capabilities.events'), undeclared.message);
    await failing(kf.events().emit(ctx, 'Bad_Name', {}));
    await failing(kf.events().emit(ctx, 'level-changed', null));
    const big = await failing(kf.events().emit(ctx, 'level-changed', { x: 'y'.repeat(EventsClient.MAX_DATA_BYTES) }));
    assert.ok(big.message.includes('65536'), big.message);
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('emit: 422 event-not-declared → PlatformRefusal refused, permanent', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-3');
    state.status = 422;
    state.answer = '{"type":"https://kf.ro/problems/event-not-declared","status":422,"detail":"x","class":"permanent"}';
    const e = await failing(kf.events().emit(ctx, 'level-changed', {}));
    assert.ok(e instanceof PlatformRefusal);
    assert.equal(e.kind, 'refused');
    assert.equal(e.reason, 'event-not-declared');
  } finally {
    server.close();
  }
});

test('on: the subscription is a hook m.{owner}.after.{name}; the delivery reaches the handler with its context', async () => {
  const subscriber = KfModule.of('http://127.0.0.1:1', 'kf-orders', TOKEN, SECRET, 'http://127.0.0.1:9');
  let seen = null;
  subscriber.events().on('kf-stock', 'level-changed', (ev) => { seen = ev; });
  assert.deepStrictEqual(subscriber.registrationBody().hooks, [{ event: 'm.kf-stock.after.level-changed' }]);

  const now = Math.floor(Date.now() / 1000);
  const header = deliveryHeader(now, 'd-1');
  const ce = {
    specversion: '1.0', id: 'd-1', kfeventid: 'e-1', type: 'kf.m.kf-stock.after.level-changed', source: '/module-event/kf-stock',
    subject: 'kf-stock', kftenant: TENANT, kflocale: 'en', kfaggregatetype: 'module-event', kfenvelopeversion: '1',
    data: { sku: 'KF-42' },
  };
  const raw = Buffer.from(JSON.stringify(ce), 'utf8');
  const ts = String(now);
  const result = await subscriber.handle(raw, ts, Signatures.sign(SECRET, ts, raw), { contextHeader: header, proofHeader: PROOF });
  assert.equal(result.status, 200, result.body);
  assert.equal(seen.owner, 'kf-stock');
  assert.equal(seen.name, 'level-changed');
  assert.equal(seen.eventId, 'e-1');
  assert.deepStrictEqual({ ...seen.data }, { sku: 'KF-42' });
  const delivery = seen.deliveryContext();
  assert.equal(delivery.contextHeader, header);
  assert.equal(delivery.proof, PROOF);
  assert.equal(delivery.tenant(), '00000000-0000-0000-0000-00000000000a'); // the platform's context, as delivered

  // a platform before 2.0: no headers, no delivery context; an unsubscribed event is ignored
  await subscriber.handle(raw, ts, Signatures.sign(SECRET, ts, raw));
  assert.equal(seen.deliveryContext(), null);
  const other = Buffer.from(JSON.stringify({ ...ce, type: 'kf.m.kf-stock.after.other' }), 'utf8');
  const ignored = await subscriber.handle(other, ts, Signatures.sign(SECRET, ts, other));
  assert.ok(ignored.body.includes('"ignored"'), ignored.body);
});
