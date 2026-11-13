// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ExtensionsClientTest.java. Do not edit by hand.
//
// ExtensionsClient against a platform stub (protocol 1.1 §15.4): PATCH …/extensions/{entity}/{id} with
// exactly the given keys (null kept: it clears); 400 with errors["extensions.<slug>.<field>"] names the field; 404 is
// not-found; the local rules refuse first.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { KfException, KfModule, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'ext-secret';
const TOKEN = 'kfm_ext-token';
const PROOF = 'd'.repeat(64);

setSdkLogger({ info() {}, warn() {}, error() {} });

async function withPlatform(body) {
  const state = { requests: 0, status: 200, answer: '{}', line: null, body: null, proof: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.line = `${req.method} ${req.url}`;
      state.proof = req.headers['x-kf-context-proof'] ?? null;
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'ext-mod', TOKEN, SECRET, null);
  try {
    await body({ state, kf });
  } finally {
    server.close();
  }
}

function verified(kf, requestId) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"00000000-0000-0000-0000-00000000000a","user":"sub-1","locale":"en","rights":[],"method":"POST",`
    + `"path":"/push","query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), PROOF);
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

test('write: PATCH …/extensions/model_version/17 with exactly the two given keys, null kept → the module\'s values', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-1');
    state.answer = '{"aas_shell":"X","aas_last_push":null}';

    const written = await kf.extensions().write(ctx, 'model_version', '17', { aas_shell: 'X', aas_last_push: null });

    assert.equal(state.line, 'PATCH /api/v1/platform/modules/ext-mod/extensions/model_version/17');
    assert.equal(state.proof, PROOF);
    const sent = JSON.parse(state.body);
    assert.deepStrictEqual(Object.keys(sent), ['aas_shell', 'aas_last_push'], 'exactly the given keys, in order');
    assert.equal(sent.aas_shell, 'X');
    assert.ok(Object.hasOwn(sent, 'aas_last_push'));
    assert.equal(sent.aas_last_push, null, 'null is sent: it clears');
    assert.equal(written.aas_shell, 'X');
  }));

test('400 with errors["extensions.<slug>.<field>"] → invalid naming the field; 404 → not-found', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-2');
    state.status = 400;
    state.answer = '{"type":"https://kf.ro/problems/validation","status":400,"detail":"invalid extension values",'
      + '"class":"permanent","errors":{"extensions.ext-mod.aas_shell":"must be text"}}';
    const invalid = await rejects(kf.extensions().write(ctx, 'model_version', '17', { aas_shell: 12 }));
    assert.ok(invalid instanceof PlatformRefusal);
    assert.equal(invalid.kind, 'invalid');
    assert.deepStrictEqual({ ...invalid.errors }, { 'extensions.ext-mod.aas_shell': 'must be text' });
    assert.ok(invalid.message.includes('extensions.ext-mod.aas_shell: must be text'), invalid.message);

    state.status = 404;
    state.answer = '{"type":"https://kf.ro/problems/not-found","status":404,"class":"permanent"}';
    const notFound = await rejects(kf.extensions().write(ctx, 'order', 'o 9', { p: 'A' }));
    assert.ok(notFound instanceof PlatformRefusal);
    assert.equal(notFound.kind, 'not-found');
    assert.equal(state.line, 'PATCH /api/v1/platform/modules/ext-mod/extensions/order/o%209');
  }));

test('the local rules: the entity, the id, at least one value, the field names, the declared fields', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-3');
    assert.ok((await rejects(kf.extensions().write(ctx, 'supplier', '1', { a: 'b' })))
      .message.includes('entity must be one of model, order, customer, model_version'));
    assert.ok((await rejects(kf.extensions().write(ctx, 'model', ' ', { a: 'b' }))).message.includes('id is required'));
    assert.ok((await rejects(kf.extensions().write(ctx, 'model', '1', {}))).message.includes('at least one value is required'));
    assert.ok((await rejects(kf.extensions().write(ctx, 'model', '1', { 'Bad-Name': 'x' }))).message.includes('field Bad-Name must match'));

    kf.withManifest('{"schemaVersion":2,"slug":"ext-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"E","f":"F"}},'
      + '"capabilities":{"fields":[{"entity":"model_version","name":"aas_shell","type":"STRING","table":"t","label":"f"}]}}');
    assert.ok((await rejects(kf.extensions().write(ctx, 'model', '1', { aas_shell: 'x' })))
      .message.includes('aas_shell is not declared in capabilities.fields for model'));
    assert.equal(state.requests, 0);

    state.answer = '{"aas_shell":"x"}';
    assert.equal((await kf.extensions().write(ctx, 'model_version', '1', { aas_shell: 'x' })).aas_shell, 'x');
  }));
