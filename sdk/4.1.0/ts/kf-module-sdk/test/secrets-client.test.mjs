// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SecretsClientTest.java. Do not edit by hand.
//
// SecretsClient against a platform stub (protocol 1.1 §15.3): write → the reference, resolve → the value;
// a reference outside the module's path or of another tenant is refused locally; the value is in no log (captured at
// every level), no toString and no error.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { inspect } from 'node:util';
import { KfException, KfModule, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'secrets-secret';
const TOKEN = 'kfm_secrets-token';
const PROOF = 'c'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000a';
const VALUE = 'r3-Pa55-never-logged';
const REF = `vault://platform/kf-modules/sec-mod/${TENANT}/r3-client-secret`;

const logged = [];
setSdkLogger({
  info: (m) => logged.push(m),
  warn: (m) => logged.push(m),
  error: (m, e) => logged.push(`${m} ${e === undefined ? '' : String(e)}`),
});

async function withPlatform(body) {
  const state = { requests: 0, status: 200, answer: '{}', line: null, body: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.line = `${req.method} ${req.url}`;
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'sec-mod', TOKEN, SECRET, null);
  logged.length = 0;
  try {
    await body({ state, kf });
  } finally {
    server.close();
    assert.ok(!logged.some((l) => l.includes(VALUE)), `the value in a log line: ${logged}`);
  }
}

function verified(kf, requestId, proof) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"${TENANT}","user":"sub-1","locale":"en","rights":["api.v1.m.sec-mod.admin-r3.post"],`
    + `"method":"POST","path":"/admin/r3","query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), proof);
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

test('write: PUT …/secrets/{name} {value} → the reference; resolve: POST …/secrets/resolve {ref} → the value', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-1', PROOF);
    state.answer = `{"ref":"${REF}"}`;

    const ref = await kf.secrets().write(ctx, 'r3-client-secret', VALUE);

    assert.equal(ref, REF);
    assert.equal(state.line, 'PUT /api/v1/platform/modules/sec-mod/secrets/r3-client-secret');
    assert.deepStrictEqual(JSON.parse(state.body), { value: VALUE });

    state.answer = `{"value":"${VALUE}"}`;
    const secret = await kf.secrets().resolve(ctx, ref);

    assert.equal(state.line, 'POST /api/v1/platform/modules/sec-mod/secrets/resolve');
    assert.deepStrictEqual(JSON.parse(state.body), { ref: REF });
    assert.equal(secret.value(), VALUE);
    assert.ok(!String(secret).includes(VALUE), String(secret));
    assert.ok(String(secret).includes(REF), String(secret));
    assert.ok(!JSON.stringify(secret).includes(VALUE), JSON.stringify(secret));
    assert.ok(!inspect(secret).includes(VALUE), inspect(secret));
    assert.ok(!String(kf.secrets()).includes(VALUE));
  }));

test('a reference outside the module\'s path, of another tenant, or malformed: local refusal, no request', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-2', PROOF);
    for (const ref of [
      `vault://platform/kf-modules/other-mod/${TENANT}/r3-client-secret`,
      'vault://platform/kf-modules/sec-mod/00000000-0000-0000-0000-00000000000b/r3-client-secret',
      `vault://platform/kf-modules/sec-mod/${TENANT}/r3-client-secret/../x`,
      'https://platform/kf-modules/sec-mod/platform/r3-client-secret',
      'vault:///kf-modules/sec-mod/platform/r3-client-secret',
    ]) {
      const e = await rejects(kf.secrets().resolve(ctx, ref));
      assert.equal(e.errorClass, 'permanent', ref);
      assert.equal(e.problem.status, 0, ref);
    }
    assert.equal(state.requests, 0);

    state.answer = `{"value":"${VALUE}"}`;
    assert.equal((await kf.secrets().resolve(ctx, 'vault://kv/platform/kf-modules/sec-mod/platform/r3-client-secret')).value(), VALUE);
  }));

test('the value is in no error: a refusal that echoes it is redacted; the local checks never name it', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-3', PROOF);
    state.status = 400;
    state.answer = `{"type":"https://kf.ro/problems/validation","title":"bad ${VALUE}","status":400,`
      + `"detail":"value ${VALUE} is too long","class":"permanent","errors":{"value":"${VALUE}"}}`;

    const refusal = await rejects(kf.secrets().write(ctx, 'r3-client-secret', VALUE));
    assert.ok(refusal instanceof PlatformRefusal);
    assert.ok(!refusal.message.includes(VALUE), refusal.message);
    assert.ok(refusal.message.includes('value *** is too long'), refusal.message);
    assert.ok(!refusal.problem.title.includes(VALUE));
    assert.ok(!JSON.stringify(refusal.errors).includes(VALUE));

    const badName = await rejects(kf.secrets().write(ctx, 'R3 secret', VALUE));
    assert.ok(!badName.message.includes(VALUE), badName.message);
    const empty = await rejects(kf.secrets().write(ctx, 'r3-client-secret', ''));
    assert.ok(empty.message.includes('the value is required'), empty.message);
  }));

test('403 without the writeRight → forbidden; with a manifest, an undeclared secret is refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-4', PROOF);
    state.status = 403;
    state.answer = '{"type":"https://kf.ro/problems/insufficient-rights","status":403,"class":"permanent"}';
    const refusal = await rejects(kf.secrets().write(ctx, 'r3-client-secret', VALUE));
    assert.ok(refusal instanceof PlatformRefusal);
    assert.equal(refusal.kind, 'forbidden');
    assert.equal(refusal.reason, 'insufficient-rights');

    kf.withManifest('{"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},'
      + '"capabilities":{"secrets":[{"name":"r3-client-secret","scope":"tenant","writeRight":"api.v1.m.sec-mod.admin-r3.post"}]}}');
    const before = state.requests;
    const undeclared = await rejects(kf.secrets().write(ctx, 'nc-token', VALUE));
    assert.ok(undeclared.message.includes('secret nc-token is not declared in capabilities.secrets'), undeclared.message);
    assert.equal(state.requests, before);
  }));

test('an answer without a reference of this module is an error, never a silent success', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-5', PROOF);
    state.answer = '{"ref":"vault://platform/kf-modules/other-mod/platform/x1"}';
    const e = await rejects(kf.secrets().write(ctx, 'r3-client-secret', VALUE));
    assert.ok(e.message.includes('no reference of this module'), e.message);
  }));

test('an existing reference outside the module\'s path: sent only when a secrets[].external prefix of the manifest '
  + 'covers it; otherwise (no manifest, another prefix, a traversal) refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-6', PROOF);
    const external = 'vault://platform/aas/kf-r3/client-secret';

    const noManifest = await rejects(kf.secrets().resolve(ctx, external));
    assert.equal(noManifest.problem.status, 0);
    assert.equal(state.requests, 0);

    kf.withManifest('{"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},'
      + '"capabilities":{"secrets":[{"name":"r3-client-secret","scope":"platform",'
      + '"writeRight":"api.v1.m.sec-mod.admin-r3.post","external":["platform/aas/"]}]}}');
    for (const ref of ['vault://platform/idp/keycloak/client-secret',
      'vault://platform/aas/../idp/keycloak/client-secret',
      'vault://platform/aas/',
      'vault://platform/aas//x']) {
      const e = await rejects(kf.secrets().resolve(ctx, ref));
      assert.equal(e.problem.status, 0, ref);
      assert.equal(e.errorClass, 'permanent', ref);
    }
    const undeclared = await rejects(kf.secrets().resolve(ctx, 'vault://platform/idp/keycloak/client-secret'));
    assert.ok(undeclared.message.includes('is under no prefix declared in capabilities.secrets[].external'),
      undeclared.message);
    assert.equal(state.requests, 0);

    state.answer = `{"value":"${VALUE}"}`;
    const secret = await kf.secrets().resolve(ctx, external);
    assert.equal(state.line, 'POST /api/v1/platform/modules/sec-mod/secrets/resolve');
    assert.deepEqual(JSON.parse(state.body), { ref: external });
    assert.equal(secret.value(), VALUE);
  }));
