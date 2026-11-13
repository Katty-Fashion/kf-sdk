// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/StorageClientTest.java. Do not edit by hand.
//
// StorageClient against a platform stub (protocol 1.2 §3.5): uploadUrl → {key, url, method,
// headers, expiresAt}, downloadUrl → {url, expiresAt}, with the token, the context and the proof; a size ≤ 0 or a type
// the manifest does not declare is refused locally; the platform's refusals are PlatformRefusals with their class;
// neither the token nor the proof is logged.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { KfException, KfModule, PlatformRefusal, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'storage-secret';
const TOKEN = 'kfm_storage-token-never-logged';
const PROOF = 'd'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000a';
const KEY = `kf-modules/fb-mod/${TENANT}/5b1f0c1e-1111-4222-8333-444455556666.png`;
const MANIFEST = `{"schemaVersion": 2, "slug": "fb-mod", "version": "0.1.0", "name": "app.name",
 "i18n": {"en": {"app.name": "Feedback"}},
 "capabilities": {"storage": [{"contentTypes": ["image/png", "image/jpeg"], "maxBytes": 5242880}]}}`;

const logged = [];
setSdkLogger({
  info: (m) => logged.push(m),
  warn: (m) => logged.push(m),
  error: (m, e) => logged.push(`${m} ${e === undefined ? '' : String(e)}`),
});

async function withPlatform(body) {
  const state = { requests: 0, status: 200, answer: '{}', line: null, body: null, headers: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.line = `${req.method} ${req.url}`;
      state.headers = {
        Authorization: String(req.headers.authorization),
        'X-KF-Context': String(req.headers['x-kf-context']),
        'X-KF-Context-Proof': String(req.headers['x-kf-context-proof']),
      };
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(state.status, { 'Content-Type': state.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(state.answer);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'fb-mod', TOKEN, SECRET, null).withManifest(MANIFEST);
  logged.length = 0;
  try {
    await body({ state, kf });
  } finally {
    server.close();
    assert.ok(!logged.some((l) => l.includes(TOKEN) || l.includes(PROOF)), `the token or the proof in a log line: ${logged}`);
  }
}

function verified(kf, requestId) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"${TENANT}","user":"sub-1","locale":"en","rights":[],`
    + `"method":"POST","path":"/feedback","query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return { ctx: kf.verifyContext(context, String(ts), signature, new Uint8Array(0), PROOF), context };
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

test('uploadUrl: POST …/storage/upload-url {contentType, size} with the three headers → {key, url, method, headers, expiresAt}', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx, context } = verified(kf, 'req-1');
    state.answer = `{"key":"${KEY}","url":"https://s3.local/put?sig=1","method":"PUT",`
      + '"headers":{"Content-Type":"image/png","Content-Length":"1234"},"expiresAt":"2026-10-29T10:10:00Z"}';

    const upload = await kf.storage().uploadUrl(ctx, 'image/png', 1234);

    assert.equal(state.line, 'POST /api/v1/platform/modules/fb-mod/storage/upload-url');
    assert.deepEqual(JSON.parse(state.body), { contentType: 'image/png', size: 1234 });
    assert.deepEqual(state.headers, { Authorization: `Bearer ${TOKEN}`, 'X-KF-Context': context, 'X-KF-Context-Proof': PROOF });
    assert.equal(upload.key, KEY);
    assert.equal(upload.url, 'https://s3.local/put?sig=1');
    assert.equal(upload.method, 'PUT');
    assert.deepEqual(upload.headers, { 'Content-Type': 'image/png', 'Content-Length': '1234' });
    assert.equal(upload.expiresAt, '2026-10-29T10:10:00Z');
  }));

test('uploadUrl: a size of 0 (or less) is refused locally, permanent, nothing sent', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-2');

    const zero = await rejects(kf.storage().uploadUrl(ctx, 'image/png', 0));
    const negative = await rejects(kf.storage().uploadUrl(ctx, 'image/png', -5));

    assert.equal(state.requests, 0);
    assert.equal(zero.errorClass, 'permanent');
    assert.ok(zero.message.includes('size'), zero.message);
    assert.equal(negative.errorClass, 'permanent');
  }));

test('uploadUrl: a type the manifest does not declare in capabilities.storage is refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-3');

    const e = await rejects(kf.storage().uploadUrl(ctx, 'application/pdf', 10));

    assert.equal(state.requests, 0);
    assert.equal(e.errorClass, 'permanent');
    assert.ok(e.message.includes('application/pdf'), e.message);
  }));

test('uploadUrl: 403 capability-not-declared → PlatformRefusal forbidden, permanent', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-4');
    state.status = 403;
    state.answer = '{"type":"https://kf.ro/problems/capability-not-declared","title":"capability-not-declared",'
      + '"status":403,"detail":"the module does not declare capabilities.storage","class":"permanent"}';

    const e = await rejects(kf.storage().uploadUrl(ctx, 'image/png', 10));

    assert.ok(e instanceof PlatformRefusal);
    assert.equal(e.kind, 'forbidden');
    assert.equal(e.reason, 'capability-not-declared');
    assert.equal(e.errorClass, 'permanent');
    assert.ok(!e.message.includes(TOKEN));
    assert.ok(!e.message.includes(PROOF));
  }));

test('uploadUrl: 503 → transient', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-5');
    state.status = 503;
    state.answer = '{"type":"https://kf.ro/problems/storage-unavailable","title":"storage-unavailable","status":503}';

    const e = await rejects(kf.storage().uploadUrl(ctx, 'image/png', 10));

    assert.equal(e.errorClass, 'transient');
    assert.equal(e.problem.status, 503);
  }));

test('downloadUrl: POST …/storage/download-url {key} → {url, expiresAt}; 404 → PlatformRefusal not-found', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-6');
    state.answer = '{"url":"https://s3.local/get?sig=2","expiresAt":"2026-10-29T11:00:00Z"}';

    const download = await kf.storage().downloadUrl(ctx, KEY);

    assert.equal(state.line, 'POST /api/v1/platform/modules/fb-mod/storage/download-url');
    assert.deepEqual(JSON.parse(state.body), { key: KEY });
    assert.equal(download.url, 'https://s3.local/get?sig=2');
    assert.equal(download.expiresAt, '2026-10-29T11:00:00Z');

    state.status = 404;
    state.answer = '{"type":"https://kf.ro/problems/not-found","title":"not-found","status":404,"class":"permanent"}';
    const e = await rejects(kf.storage().downloadUrl(ctx, KEY));

    assert.ok(e instanceof PlatformRefusal);
    assert.equal(e.problem.status, 404);
    assert.equal(e.kind, 'not-found');
  }));

test('downloadUrl: an empty key is refused locally', () =>
  withPlatform(async ({ state, kf }) => {
    const { ctx } = verified(kf, 'req-7');

    const e = await rejects(kf.storage().downloadUrl(ctx, ' '));

    assert.equal(state.requests, 0);
    assert.equal(e.errorClass, 'permanent');
  }));
