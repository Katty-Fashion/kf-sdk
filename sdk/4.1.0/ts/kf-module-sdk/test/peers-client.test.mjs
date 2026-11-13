// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PeersClientTest.java. Do not edit by hand.
//
// PeersClient against a platform stub (protocol 2.0, sdk-protocol.md §15.9): the token, the context and
// its proof go back unchanged; a command carries Idempotency-Key, refused locally when missing or invalid; the owner's
// answer passes unchanged; the platform's refusals (also 409, 422, 502, 503) are PlatformRefusals with their reason and
// class; an after delivery's context serves a background job within ±300 s.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { DeliveryContext, ErrorClass, KfException, KfModule, PeersClient, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'peers-secret';
const TOKEN = 'kfm_peers-token';
const PROOF = 'b'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000a';

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { requests: 0, status: 200, answer: '{}', seen: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.seen = { method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') };
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'caller', TOKEN, SECRET, null);
  return { server, state, kf };
}

function verified(kf, requestId) {
  const ts = Math.floor(Date.now() / 1000);
  const context = encode(ts, requestId, '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), PROOF);
}

/** The context the platform puts on an after delivery: system via hook, `ts` = the attempt. */
function deliveryHeader(ts, requestId) {
  return encode(ts, requestId, `"user":null,"actor":{"type":"system","via":"hook","hookId":"${requestId}"}`);
}

function encode(ts, requestId, userAndActor) {
  const json = `{"v":1,"tenant":"${TENANT}",${userAndActor},"locale":"en","rights":[],"method":"POST","path":"/_kf/hooks",`
    + `"query":null,"ts":${ts},"requestId":"${requestId}"}`;
  return Buffer.from(json, 'utf8').toString('base64url');
}

async function refusal(promise) {
  try {
    await promise;
  } catch (e) {
    assert.ok(e instanceof PlatformRefusal, String(e));
    return e;
  }
  assert.fail('expected a PlatformRefusal');
}

async function local(promise) {
  try {
    await promise;
  } catch (e) {
    assert.ok(e instanceof KfException, String(e));
    assert.ok(!(e instanceof PlatformRefusal), String(e));
    return e;
  }
  assert.fail('expected a KfException');
}

const problem = (status, reason, cls) => `{"type":"https://kf.ro/problems/${reason}","status":${status},"detail":"x","class":"${cls}"}`;

test('read: GET …/modules/caller/peers/kf-stock/reads/{port}?query with the token, the context and the proof → the owner\'s answer', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-1');
    state.answer = '{"sku":"KF-42","level":7.50}';
    const a = await kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', { sku: 'KF 42' });
    assert.equal(state.requests, 1);
    assert.equal(state.seen.method, 'GET');
    assert.equal(state.seen.url, '/api/v1/platform/modules/caller/peers/kf-stock/reads/m.kf-stock.level.v1?sku=KF+42');
    assert.equal(state.seen.headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.ok(state.seen.headers['x-kf-context'] != null);
    assert.equal(a.status, 200);
    assert.equal(a.object().sku, 'KF-42');
    assert.equal(a.object().level, 7.5);
  } finally {
    server.close();
  }
});

test('read: 503 module-unavailable transient → PlatformRefusal unavailable, class transient', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-2');
    state.status = 503;
    state.answer = '{"type":"https://kf.ro/problems/module-unavailable","title":"Module unavailable","status":503,'
      + '"detail":"The owner is not available now.","class":"transient"}';
    const e = await refusal(kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', {}));
    assert.equal(e.kind, 'unavailable');
    assert.equal(e.reason, PeersClient.MODULE_UNAVAILABLE);
    assert.equal(e.errorClass, ErrorClass.TRANSIENT);
    assert.ok(!e.message.includes(PROOF));
  } finally {
    server.close();
  }
});

test('read: 403 peer-not-required, 404 not-configured, 422 module-refused → refusals with the reason', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-3');
    for (const [status, reason, kind] of [[403, 'peer-not-required', 'forbidden'], [404, PeersClient.NOT_CONFIGURED, 'not-found'],
      [422, PeersClient.MODULE_REFUSED, 'refused'], [502, PeersClient.INVALID_ANSWER, 'invalid-answer']]) {
      state.status = status;
      state.answer = problem(status, reason, 'permanent');
      const e = await refusal(kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', {}));
      assert.equal(e.reason, reason);
      assert.equal(e.kind, kind);
      assert.equal(e.errorClass, ErrorClass.PERMANENT);
    }
  } finally {
    server.close();
  }
});

test('read: an owner or a port outside the grammar is refused locally, nothing sent', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-4');
    const a = await local(kf.peers().read(ctx, 'Kf_Stock', 'm.kf-stock.level.v1', null));
    const b = await local(kf.peers().read(ctx, 'kf-stock', 'm.other.level.v1', null));
    assert.equal(a.problem.status, 0);
    assert.ok(b.message.includes('m.kf-stock.{name}.v{N}'), b.message);
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('command: POST …/commands/{name} with Idempotency-Key and the JSON body; 201 passes unchanged', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-5');
    state.status = 201;
    state.answer = '{"reservationId":"r-1"}';
    const a = await kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 3 });
    assert.equal(state.seen.method, 'POST');
    assert.equal(state.seen.url, '/api/v1/platform/modules/caller/peers/kf-stock/commands/reserve');
    assert.equal(state.seen.headers['idempotency-key'], 'key-0001-abcdef');
    assert.equal(state.seen.headers['content-type'], 'application/json');
    assert.equal(state.seen.body, '{"qty":3}');
    assert.equal(a.status, 201);
    assert.equal(a.object().reservationId, 'r-1');
  } finally {
    server.close();
  }
});

test('command: a missing or invalid Idempotency-Key is refused locally, before the network', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-6');
    const missing = await local(kf.peers().command(ctx, 'kf-stock', 'reserve', null, {}));
    const shortKey = await local(kf.peers().command(ctx, 'kf-stock', 'reserve', 'short', {}));
    const badChars = await local(kf.peers().command(ctx, 'kf-stock', 'reserve', 'key with spaces', {}));
    for (const e of [missing, shortKey, badChars]) {
      assert.equal(e.problem.status, 0);
      assert.equal(e.errorClass, ErrorClass.PERMANENT);
      assert.ok(e.message.includes('Idempotency-Key'), e.message);
    }
    assert.equal(state.requests, 0);
    assert.ok(PeersClient.IDEMPOTENCY_KEY.test(PeersClient.newIdempotencyKey()));
  } finally {
    server.close();
  }
});

test('command: 409 idempotency-conflict permanent, idempotency-in-progress transient', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-7');
    state.status = 409;
    state.answer = problem(409, 'idempotency-conflict', 'permanent');
    const conflict = await refusal(kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 4 }));
    assert.equal(conflict.kind, 'conflict');
    assert.equal(conflict.reason, PeersClient.IDEMPOTENCY_CONFLICT);
    assert.equal(conflict.errorClass, ErrorClass.PERMANENT);
    state.answer = problem(409, 'idempotency-in-progress', 'transient');
    const running = await refusal(kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 4 }));
    assert.equal(running.errorClass, ErrorClass.TRANSIENT);
  } finally {
    server.close();
  }
});

test("the manifest's capabilities.peers must name the owner and the port or command (when known)", async () => {
  const { server, state, kf } = await platform();
  try {
    kf.withManifest('{"schemaVersion":3,"slug":"caller","requires":[{"slug":"kf-stock","versionRange":">=1.0.0"}],'
      + '"capabilities":{"peers":[{"owner":"kf-stock","reads":["m.kf-stock.level.v1"],"commands":["reserve"]}]}}');
    const ctx = verified(kf, 'req-8');
    const read = await local(kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.other.v1', null));
    const command = await local(kf.peers().command(ctx, 'kf-stock', 'release', 'key-0001-abcdef', {}));
    assert.ok(read.message.includes('capabilities.peers'), read.message);
    assert.ok(command.message.includes('capabilities.peers'), command.message);
    assert.equal(state.requests, 0);
    await kf.peers().read(ctx, 'kf-stock', 'm.kf-stock.level.v1', null);
    assert.equal(state.requests, 1);
  } finally {
    server.close();
  }
});

test(': a delivery\'s context serves a background job within ±300 s, refused locally after', async () => {
  const { server, state, kf } = await platform();
  try {
    const now = Math.floor(Date.now() / 1000);
    const header = deliveryHeader(now, '11111111-2222-3333-4444-555555555555');
    const fresh = DeliveryContext.of(header, PROOF);
    await kf.peers().read(fresh, 'kf-stock', 'm.kf-stock.level.v1', {});
    assert.equal(state.seen.headers['x-kf-context'], header);
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.equal(fresh.tenant(), TENANT);
    assert.equal(fresh.context.actor.type, 'system');
    assert.ok(!String(fresh).includes(PROOF));
    assert.ok(!JSON.stringify(fresh).includes(PROOF));

    const old = DeliveryContext.of(deliveryHeader(now - 301, 'x-old'), PROOF);
    const e = await local(kf.peers().command(old, 'kf-stock', 'reserve', 'key-0001-abcdef', {}));
    assert.ok(e.message.includes('300 s'), e.message);
    assert.equal(state.requests, 1);
    assert.throws(() => DeliveryContext.of(header, null), TypeError);
  } finally {
    server.close();
  }
});

test('command: an empty 2xx is an answer without a body', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-8');
    state.status = 204;
    state.answer = '';
    const a = await kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 3 });
    assert.equal(a.status, 204);
    assert.equal(a.body, null);
    assert.throws(() => a.object(), (e) => e instanceof KfException && e.errorClass === ErrorClass.PERMANENT);
  } finally {
    server.close();
  }
});

test('command: 409 idempotency-answer-not-stored is a permanent conflict refusal', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-9');
    state.status = 409;
    state.answer = '{"type":"https://kf.ro/problems/idempotency-answer-not-stored","status":409,"detail":"x","class":"permanent"}';
    const r = await refusal(kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 3 }));
    assert.equal(r.kind, 'conflict');
    assert.equal(r.reason, PeersClient.IDEMPOTENCY_ANSWER_NOT_STORED);
    assert.equal(r.errorClass, ErrorClass.PERMANENT);
  } finally {
    server.close();
  }
});

test('command: 502 invalid-answer permanent is an INVALID_ANSWER refusal', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-10');
    state.status = 502;
    state.answer = '{"type":"https://kf.ro/problems/invalid-answer","status":502,"detail":"x","class":"permanent"}';
    const r = await refusal(kf.peers().command(ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', { qty: 3 }));
    assert.equal(r.kind, 'invalid-answer');
    assert.equal(r.reason, PeersClient.INVALID_ANSWER);
    assert.equal(r.errorClass, ErrorClass.PERMANENT);
  } finally {
    server.close();
  }
});
