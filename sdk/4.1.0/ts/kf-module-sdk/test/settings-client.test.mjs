// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SettingsClientTest.java. Do not edit by hand.
//
// SettingsClient against a platform stub (protocol 1.1 §15.2): GET …/settings with the token, the
// context and the proof; the values typed by the manifest; an undeclared key ignored with a WARN; a missing or
// mismatching value takes the default; the same local refusals as ReadsClient.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { Actor, KfException, KfModule, PlatformRefusal, RequestContext, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'settings-secret';
const TOKEN = 'kfm_settings-token';
const PROOF = 'b'.repeat(64);
const MANIFEST = '{"schemaVersion":2,"slug":"set-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"Settings","l":"Label"}},'
  + '"capabilities":{"settings":['
  + '{"key":"live","type":"BOOLEAN","default":false,"label":"l"},'
  + '{"key":"batch-size","type":"NUMBER","default":50,"label":"l","min":1,"max":500},'
  + '{"key":"push-mode","type":"ENUM","default":"manual","label":"l","values":["manual","auto"]}]}}';

const logged = [];
setSdkLogger({ info: (m) => logged.push(`INFO ${m}`), warn: (m) => logged.push(`WARNING ${m}`), error: (m) => logged.push(`SEVERE ${m}`) });

async function withPlatform(body) {
  const state = { requests: 0, status: 200, answer: '{}', seen: null };
  const server = createServer((req, res) => {
    req.on('data', () => {});
    req.on('end', () => {
      state.requests++;
      state.seen = { line: `${req.method} ${req.url}`, headers: req.headers };
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  const kf = KfModule.of(url, 'set-mod', TOKEN, SECRET, null).withManifest(MANIFEST);
  logged.length = 0;
  try {
    await body({ state, kf, url });
  } finally {
    server.close();
  }
}

function verified(kf, requestId, proof) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"00000000-0000-0000-0000-00000000000a","user":"sub-1","locale":"en","rights":[],"method":"GET",`
    + `"path":"/x","query":null,"ts":${ts},"requestId":"${requestId}"}`;
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

test('effective: GET …/settings with the token, the context and the proof → boolean, number, string', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-1', PROOF);
    state.answer = '{"live":true,"batch-size":120.50,"push-mode":"auto"}';

    const settings = await kf.settings().effective(ctx);

    assert.equal(state.seen.line, 'GET /api/v1/platform/modules/set-mod/settings');
    assert.equal(state.seen.headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.deepStrictEqual(Object.keys(settings), ['live', 'batch-size', 'push-mode'], 'the manifest order');
    assert.equal(settings.live, true);
    assert.equal(settings['batch-size'], 120.5, 'Java: BigDecimal 120.50');
    assert.equal(settings['push-mode'], 'auto');
    assert.deepStrictEqual(logged, []);
  }));

test('an undeclared key is ignored with a WARN; a missing, mistyped or out-of-range value takes the default', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-2', PROOF);
    state.answer = '{"live":"yes","batch-size":900,"retired":1}';

    const settings = await kf.settings().effective(ctx);

    assert.equal(settings.live, false);
    assert.equal(settings['batch-size'], 50, '900 is above max 500');
    assert.equal(settings['push-mode'], 'manual', 'missing from the answer');
    assert.ok(logged.some((l) => l.startsWith('WARNING') && l.includes('setting retired is not declared')), String(logged));
    assert.ok(logged.some((l) => l.includes('setting live does not match its declaration (BOOLEAN)')), String(logged));
    assert.ok(logged.some((l) => l.includes('setting push-mode is missing')), String(logged));
  }));

test('without the manifest, an unknown context or a context without proof: local refusal, no request', () =>
  withPlatform(async ({ state, kf, url }) => {
    const ctx = verified(kf, 'req-3', PROOF);
    const bare = KfModule.of(url, 'set-mod', TOKEN, SECRET, null);
    const noManifest = await rejects(bare.settings().effective(ctx));
    assert.ok(noManifest.message.includes('settings need the module manifest'), noManifest.message);

    const withoutProof = verified(kf, 'req-4', null);
    const noProof = await rejects(kf.settings().effective(withoutProof));
    assert.ok(noProof.message.includes('X-KF-Context-Proof'), noProof.message);

    const built = new RequestContext(1, 't', 'u', Actor.USER, 'en', [], 'GET', '/x', null, Math.floor(Date.now() / 1000),
      'never-verified');
    const unknown = await rejects(kf.settings().effective(built));
    assert.ok(unknown.message.includes('was not verified by this module'), unknown.message);
    for (const e of [noManifest, noProof, unknown]) assert.equal(e.errorClass, 'permanent');
    assert.equal(state.requests, 0);
  }));

test('403 module-not-live → PlatformRefusal forbidden with the reason', () =>
  withPlatform(async ({ state, kf }) => {
    const ctx = verified(kf, 'req-5', PROOF);
    state.status = 403;
    state.answer = '{"type":"https://kf.ro/problems/module-not-live","status":403,"detail":"not live","class":"permanent"}';
    const refusal = await rejects(kf.settings().effective(ctx));
    assert.ok(refusal instanceof PlatformRefusal);
    assert.equal(refusal.kind, 'forbidden');
    assert.equal(refusal.reason, 'module-not-live');
  }));
