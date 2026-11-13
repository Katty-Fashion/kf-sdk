// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ReadsClientTest.java. Do not edit by hand.
//
// ReadsClient against a platform stub (protocol 1.1 §15.1): the module token, the context and the
// platform's proof of it go back unchanged; the list answer becomes a ReadPage; 404 on an object is null; the
// platform's refusals are PlatformRefusals with their reason; the local rules (port, paging, filters, the system
// context) refuse before any request.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { Actor, KfException, KfModule, PlatformRefusal, ReadsClient, RequestContext, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'reads-secret';
const TOKEN = 'kfm_reads-token';
const PROOF = 'a'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000a';

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { requests: 0, status: 200, answer: '{}', seen: null };
  const server = createServer((req, res) => {
    req.on('data', () => {});
    req.on('end', () => {
      state.requests++;
      state.seen = { method: req.method, url: req.url, headers: req.headers };
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'reads-mod', TOKEN, SECRET, null);
  return { server, state, kf };
}

/** A context as the platform forwards it, verified through the module (which keeps it with its proof). */
function verified(kf, requestId, proof, userAndActor = '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}') {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"${TENANT}",${userAndActor},"locale":"en","rights":[],"method":"GET","path":"/x",`
    + `"query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), proof);
}

function verifiedSystem(kf, requestId) {
  return verified(kf, requestId, PROOF,
    '"user":null,"actor":{"type":"system","via":"hook","hookId":"3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b0a"}');
}

/** The platform's own context (`system` via `platform`), as on an `admin/export` call (1.3). */
function verifiedPlatform(kf, requestId) {
  return verified(kf, requestId, PROOF, '"user":null,"actor":{"type":"system","via":"platform","hookId":null}');
}

async function rejects(promise) {
  try {
    await promise;
  } catch (e) {
    assert.ok(e instanceof KfException, String(e));
    return e;
  }
  assert.fail('must fail');
}

async function withPlatform(body) {
  const p = await platform();
  try {
    await body(p);
  } finally {
    p.server.close();
  }
}

test('list: GET …/reads/model.v1?filters&page&size with the token, the context and the proof unchanged → ReadPage', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-1', PROOF);
    state.answer = '{"items":[{"id":"42","name":"Rochie","price":12.50}],"page":0,"size":50,"total":51}';

    const page = await kf.reads().list(ctx, 'model.v1', { updatedSince: '2026-10-01T00:00:00Z' }, 0, 50);

    assert.equal(state.requests, 1);
    assert.equal(state.seen.method, 'GET');
    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/model.v1?updatedSince=2026-10-01T00%3A00%3A00Z&page=0&size=50');
    assert.equal(state.seen.headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.ok(Buffer.from(state.seen.headers['x-kf-context'], 'base64url').toString('utf8').includes('"requestId":"req-1"'));
    assert.equal(page.page, 0);
    assert.equal(page.size, 50);
    assert.equal(page.total, 51);
    assert.ok(page.hasNext());
    assert.equal(page.items[0].name, 'Rochie');
    assert.equal(page.items[0].price, 12.5, 'a JSON number (Java: BigDecimal 12.50)');
  }));

test('list without paging: page 0, size 20 (the protocol\'s default)', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-2', PROOF);
    state.answer = '{"items":[],"page":0,"size":20,"total":0}';

    const page = await kf.reads().list(ctx, 'technician.v1', {});

    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/technician.v1?page=0&size=20');
    assert.equal(page.items.length, 0);
    assert.ok(!page.hasNext());
  }));

test('get: GET …/reads/model.v1/42 → the object; with include → ?include=a,b; 404 → null', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-3', PROOF);
    state.answer = '{"id":"42","name":"Rochie","createdBySubject":"oidc-1"}';

    const model = await kf.reads().get(ctx, 'model.v1', '42');
    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/model.v1/42');
    assert.equal(model.name, 'Rochie');

    await kf.reads().get(ctx, 'model-version.v1', '7', ['grading', 'consumption']);
    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/model-version.v1/7?include=grading,consumption');

    state.status = 404;
    state.answer = '{"type":"https://kf.ro/problems/not-found","title":"Not found","status":404,"class":"permanent"}';
    assert.equal(await kf.reads().get(ctx, 'model.v1', '9 9'), null);
    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/model.v1/9%209');
  }));

test('403 → PlatformRefusal forbidden with the reason; 401 → unauthorized; 400 → invalid; 503 → transient', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-4', PROOF);
    state.status = 403;
    state.answer = '{"type":"https://kf.ro/problems/insufficient-rights","title":"Insufficient rights","status":403,'
      + '"detail":"The user does not hold api.v1.models.get","class":"permanent"}';
    const forbidden = await rejects(kf.reads().get(ctx, 'model.v1', '42'));
    assert.ok(forbidden instanceof PlatformRefusal);
    assert.equal(forbidden.kind, 'forbidden');
    assert.equal(forbidden.reason, 'insufficient-rights');
    assert.equal(forbidden.errorClass, 'permanent');
    assert.ok(forbidden.message.includes('api.v1.models.get'), forbidden.message);

    state.status = 401;
    state.answer = '{"type":"https://kf.ro/problems/invalid-context","title":"Invalid context","status":401,"class":"permanent"}';
    const unauthorized = await rejects(kf.reads().list(ctx, 'model.v1', {}));
    assert.ok(unauthorized instanceof PlatformRefusal);
    assert.equal(unauthorized.kind, 'unauthorized');
    assert.equal(unauthorized.reason, 'invalid-context');

    state.status = 400;
    state.answer = '{"type":"https://kf.ro/problems/validation","title":"Validation","status":400,'
      + '"detail":"unknown filter","class":"permanent","errors":{"colour":"unknown filter"}}';
    const invalid = await rejects(kf.reads().list(ctx, 'model.v1', { colour: 'red' }));
    assert.ok(invalid instanceof PlatformRefusal);
    assert.equal(invalid.kind, 'invalid');
    assert.deepStrictEqual({ ...invalid.errors }, { colour: 'unknown filter' });
    assert.ok(invalid.message.includes('colour'), invalid.message);

    state.status = 503;
    state.answer = '';
    const unavailable = await rejects(kf.reads().get(ctx, 'model.v1', '42'));
    assert.equal(unavailable.errorClass, 'transient');
    assert.ok(!(unavailable instanceof PlatformRefusal));
    assert.equal(state.requests, 4);
  }));

test('a context not verified by this module, or verified without the platform\'s proof: local refusal, no request', () =>
  withPlatform(async ({ state, kf }) => {
    const built = new RequestContext(1, TENANT, 'u', Actor.USER, 'en', [], 'GET', '/x', null,
      Math.floor(Date.now() / 1000), 'never-verified');
    const unknown = await rejects(kf.reads().get(built, 'model.v1', '42'));
    assert.ok(unknown.message.includes('was not verified by this module'), unknown.message);
    assert.equal(unknown.errorClass, 'permanent');

    const withoutProof = verified(kf, 'req-5', null);
    const noProof = await rejects(kf.reads().list(withoutProof, 'model.v1', {}));
    assert.ok(noProof.message.includes('X-KF-Context-Proof'), noProof.message);
    assert.equal(noProof.errorClass, 'permanent');
    assert.equal(state.requests, 0);
  }));

test('the local rules: the port grammar, size 1..200, page >= 0, at most 200 ids, the filter names, the include names', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-6', PROOF);
    const port = await rejects(kf.reads().get(ctx, 'Model', '1'));
    assert.ok(port.message.includes('port must match'), port.message);
    const size = await rejects(kf.reads().list(ctx, 'model.v1', {}, 0, 201));
    assert.ok(size.message.includes('size must be between 1 and 200'), size.message);
    const page = await rejects(kf.reads().list(ctx, 'model.v1', {}, -1, 20));
    assert.ok(page.message.includes('page must be at least 0'), page.message);
    const reserved = await rejects(kf.reads().list(ctx, 'model.v1', { page: '2' }));
    assert.ok(reserved.message.includes('filter page is reserved'), reserved.message);
    const include = await rejects(kf.reads().get(ctx, 'model-version.v1', '7', ['grading,consumption']));
    assert.ok(include.message.includes('include grading,consumption must match'), include.message);
    const id = await rejects(kf.reads().get(ctx, 'model.v1', ' '));
    assert.ok(id.message.includes('id is required'), id.message);
    const ids = await rejects(kf.reads().list(ctx, 'model.v1', { ids: idList(201) }));
    assert.ok(ids.message.includes('filter ids takes at most 200 values'), ids.message);
    for (const e of [port, size, page, reserved, include, id, ids]) {
      assert.equal(e.errorClass, 'permanent');
      assert.equal(e.problem.status, 0);
    }
    assert.equal(state.requests, 0);
  }));

test('the system context reads only objects, without include: a list or an include is refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    const system = verifiedSystem(kf, 'req-7');
    const list = await rejects(kf.reads().list(system, 'model.v1', {}));
    assert.ok(list.message.includes('the system context reads only objects'), list.message);
    const include = await rejects(kf.reads().get(system, 'model-version.v1', '7', ['grading']));
    assert.ok(include.message.includes('the system context reads only the base fields'), include.message);
    assert.equal(state.requests, 0);

    state.answer = '{"id":"7"}';
    assert.equal((await kf.reads().get(system, 'model-version.v1', '7')).id, '7');
    assert.equal(state.requests, 1);
  }));

test('protocol 1.3: the platform\'s own context lists tenant.v1 without ids; any other port, or a hook\'s context, still not', () =>
  withPlatform(async ({ state, kf }) => {
    const platformCtx = verifiedPlatform(kf, 'req-13');
    state.answer = `{"items":[{"id":"${TENANT}","name":"KF Dev","deletedAt":null}],"page":0,"size":200,"total":1}`;

    const page = await kf.reads().list(platformCtx, 'tenant.v1', {}, 0, 200);

    assert.equal(state.requests, 1);
    assert.equal(state.seen.url, '/api/v1/platform/modules/reads-mod/reads/tenant.v1?page=0&size=200');
    assert.equal(page.items[0].name, 'KF Dev');
    assert.ok('deletedAt' in page.items[0]);

    const model = await rejects(kf.reads().list(platformCtx, 'model.v1', {}));
    assert.ok(model.message.includes('the system context reads only objects'), model.message);
    const hook = verifiedSystem(kf, 'req-14');
    const hookList = await rejects(kf.reads().list(hook, 'tenant.v1', {}));
    assert.ok(hookList.message.includes('the system context reads only objects'), hookList.message);
    assert.equal(state.requests, 1, 'the two refusals are local');
  }));

test('with a manifest, a port the module does not declare in capabilities.reads is refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    kf.withManifest('{"schemaVersion":2,"slug":"reads-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"Reads"}},'
      + '"capabilities":{"reads":[{"port":"model.v1"}]}}');
    const ctx = verified(kf, 'req-8', PROOF);
    const e = await rejects(kf.reads().get(ctx, 'order.v1', '1'));
    assert.ok(e.message.includes('order.v1 is not declared in capabilities.reads'), e.message);
    assert.equal(state.requests, 0);

    state.answer = '{"id":"1"}';
    assert.notEqual(await kf.reads().get(ctx, 'model.v1', '1'), null);
  }));

test('the token and the proof never appear in an error', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-9', PROOF);
    state.status = 403;
    state.answer = '{"status":403,"class":"permanent"}';
    const e = await rejects(kf.reads().get(ctx, 'model.v1', '42'));
    assert.ok(!e.message.includes(TOKEN), e.message);
    assert.ok(!e.message.includes(PROOF), e.message);
  }));

test('ids at the limit: 200 values are sent as asked, comma-separated', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-10', PROOF);
    state.answer = '{"items":[],"page":0,"size":20,"total":0}';
    const page = await kf.reads().list(ctx, 'model.v1', { ids: idList(ReadsClient.MAX_IDS) });
    assert.equal(state.requests, 1);
    assert.ok(state.seen.url.startsWith('/api/v1/platform/modules/reads-mod/reads/model.v1?ids=1%2C2%2C'), state.seen.url);
    assert.ok(state.seen.url.endsWith('%2C200&page=0&size=20'), state.seen.url);
    assert.equal(page.total, 0);
  }));

/** The values 1..n, comma-separated, as a module writes the `ids` filter. */
function idList(n) {
  return Array.from({ length: n }, (_, i) => String(i + 1)).join(',');
}
