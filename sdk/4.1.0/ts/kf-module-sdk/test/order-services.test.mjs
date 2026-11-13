// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/OrderServicesTest.java. Do not edit by hand.
//
// OrderServices (protocol 2.0, order.services.v1, sdk-protocol.md §16.6): the upsert POST …/services/order.services.v1
// and the delete DELETE …/order.services.v1/{externalRef}?orderId= with the token, the context and its proof; the local
// refusals (nothing sent); the platform's 422 not-delivered and 501 no-provider as typed refusals.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { ErrorClass, KfException, KfModule, OrderServiceLine, OrderServices, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'services-secret';
const TOKEN = 'kfm_services-token';
const PROOF = 'd'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000c';
const MANIFEST = `{ "schemaVersion": 3, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
  "i18n": { "en": { "module.name": "Step" } },
  "capabilities": { "services": [ { "port": "order.services.v1" } ] } }`;

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { requests: 0, status: 204, answer: '', uri: null, body: null, proof: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.uri = `${req.method} ${req.url}`;
      state.proof = req.headers['x-kf-context-proof'] ?? null;
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(state.status);
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'kf-step', TOKEN, SECRET, null).withManifest(MANIFEST);
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

function line(amount, currency, externalRef) {
  return new OrderServiceLine('812', '4401', 'Embroidery', amount, currency, externalRef);
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

test('upsert posts the line with the context and its proof', async () => {
  const { server, state, kf } = await platform();
  try {
    await kf.orderServices().upsert(verified(kf, 'r-1'), line('12.50', 'EUR', 'embroidery:4401'));
    assert.equal(state.uri, 'POST /api/v1/platform/modules/kf-step/services/order.services.v1');
    assert.equal(state.proof, PROOF);
    const sent = JSON.parse(state.body);
    assert.equal(sent.orderId, '812');
    assert.equal(sent.entryId, '4401');
    assert.equal(sent.name, 'Embroidery');
    assert.equal(sent.externalRef, 'embroidery:4401');
    assert.equal(sent.cost.amount, 12.5);
    assert.equal(sent.cost.currency, 'EUR');
    assert.ok(!Object.hasOwn(sent, 'source'), "the source is the token's slug, never the body's");
  } finally {
    server.close();
  }
});

test('delete names the line and the order', async () => {
  const { server, state, kf } = await platform();
  try {
    await kf.orderServices().delete(verified(kf, 'r-2'), '812', 'embroidery:4401');
    assert.equal(state.uri, 'DELETE /api/v1/platform/modules/kf-step/services/order.services.v1/embroidery:4401?orderId=812');
  } finally {
    server.close();
  }
});

test('an invalid line is refused locally and nothing is sent', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'r-3');
    const negative = await failing(kf.orderServices().upsert(ctx, line('-1', 'EUR', 'x:1')));
    assert.ok(negative.message.includes('cost.amount must be a number >= 0'), negative.message);
    const currency = await failing(kf.orderServices().upsert(ctx, line('1', 'eur', 'x:1')));
    assert.ok(currency.message.includes('cost.currency must be an ISO 4217 code'), currency.message);
    const ref = await failing(kf.orderServices().upsert(ctx, line('1', 'EUR', 'has space')));
    assert.ok(ref.message.includes('externalRef must match'), ref.message);
    await failing(kf.orderServices().upsert(ctx, new OrderServiceLine('812', '4401', 'x'.repeat(201), 1, 'EUR', 'x:1')));
    await failing(kf.orderServices().delete(ctx, ' ', 'x:1'));
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('an undeclared service is refused locally', async () => {
  const { server, state, kf } = await platform();
  try {
    kf.withManifest(MANIFEST.replace('"services": [ { "port": "order.services.v1" } ]', '"routes": []'));
    const e = await failing(kf.orderServices().upsert(verified(kf, 'r-4'), line('1', 'EUR', 'x:1')));
    assert.ok(e.message.includes('not declared in capabilities.services'), e.message);
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('not-delivered and no-provider are typed refusals', async () => {
  const { server, state, kf } = await platform();
  try {
    state.status = 422;
    state.answer = '{"type":"https://kf.ro/problems/not-delivered","title":"Not delivered by this platform",'
      + '"status":422,"detail":"order.services.v1 is part of protocol 2.0 but this platform does not '
      + 'deliver it yet","class":"permanent"}';
    const notDelivered = await failing(kf.orderServices().upsert(verified(kf, 'r-5'), line('1', 'EUR', 'x:1')));
    assert.ok(notDelivered instanceof PlatformRefusal);
    assert.equal(notDelivered.kind, 'refused');
    assert.equal(notDelivered.reason, 'not-delivered');
    assert.equal(notDelivered.problem.errorClass, ErrorClass.PERMANENT);

    state.status = 501;
    state.answer = '{"type":"https://kf.ro/problems/no-provider","title":"No provider","status":501,"class":"permanent"}';
    const noProvider = await failing(kf.orderServices().delete(verified(kf, 'r-6'), '812', 'x:1'));
    assert.ok(noProvider instanceof PlatformRefusal);
    assert.equal(noProvider.kind, 'unavailable');
    assert.equal(noProvider.reason, 'no-provider');

    state.status = 400;
    state.answer = '{"type":"https://kf.ro/problems/validation","status":400,"class":"permanent","errors":{"entryId":"is required"}}';
    const invalid = await failing(kf.orderServices().upsert(verified(kf, 'r-7'), line('1', 'EUR', 'x:1')));
    assert.ok(invalid instanceof PlatformRefusal);
    assert.equal(invalid.kind, 'invalid');
    assert.equal(invalid.errors.entryId, 'is required');
  } finally {
    server.close();
  }
});

test('deleting the line of another module is a conflict refusal', async () => {
  const { server, state, kf } = await platform();
  try {
    state.status = 409;
    state.answer = '{"type":"https://kf.ro/problems/order-service-line-owned-by-module","status":409,'
      + '"reason":"order-service-line-owned-by-module","class":"permanent"}';
    const refusal = await failing(kf.orderServices().delete(verified(kf, 'r-8'), '812', 'x:1'));
    assert.ok(refusal instanceof PlatformRefusal);
    assert.equal(refusal.kind, 'conflict');
    assert.equal(refusal.reason, OrderServices.LINE_OWNED_BY_MODULE);
    assert.equal(refusal.problem.errorClass, ErrorClass.PERMANENT);
  } finally {
    server.close();
  }
});
