// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/AuditClientTest.java. Do not edit by hand.
//
// AuditClient against a platform stub: the module token, the context and the platform's proof of it go back
// unchanged; the record is checked locally first; 401 / 403 map to AuditUnauthorized / AuditForbidden.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { Actor, AuditForbidden, AuditRecord, AuditUnauthorized, KfException, KfModule, RequestContext, Signatures, setSdkLogger }
  from '../dist/index.js';

const SECRET = 'audit-secret';
const TOKEN = 'kfm_audit-token';
const PROOF = 'f'.repeat(64);

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { requests: 0, status: 201, answer: '{"aggregateType":"m.audit-mod.Note"}', seen: null };
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
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'audit-mod', TOKEN, SECRET, null);
  return { server, state, kf };
}

/** A context as the platform forwards it, verified through the module (which keeps it with its proof). */
function verified(kf, requestId, proof) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"00000000-0000-0000-0000-00000000000a","user":"sub-1","locale":"en","rights":[],"method":"POST",`
    + `"path":"/notes","query":null,"ts":${ts},"requestId":"${requestId}"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), proof);
}

test('record: POST …/audit-mod/audit with the token, the context and the proof unchanged, the record as JSON', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-1', PROOF);
    await kf.audit().record(ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'));
    assert.equal(state.requests, 1);
    assert.equal(state.seen.method, 'POST');
    assert.equal(state.seen.url, '/api/v1/platform/modules/audit-mod/audit');
    assert.equal(state.seen.headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.ok(Buffer.from(state.seen.headers['x-kf-context'], 'base64url').toString('utf8').includes('"requestId":"req-1"'));
    assert.deepStrictEqual(JSON.parse(state.seen.body), { action: 'NOTE_CREATED', aggregateType: 'Note', aggregateRef: '42' });
  } finally {
    server.close();
  }
});

test('list: GET …/audit?aggregateType&aggregateRef, the rows of the answer', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-2', PROOF);
    state.status = 200;
    state.answer = '{"rows":[{"id":7,"aggregateType":"Note","aggregateRef":"42","action":"NOTE_CREATED","actor":"sub-1",'
      + '"performedAt":"2026-10-20T10:00:00Z","requestId":"req-1"}]}';
    const rows = await kf.audit().list(ctx, 'Note', '4 2');
    assert.equal(state.seen.method, 'GET');
    assert.equal(state.seen.url, '/api/v1/platform/modules/audit-mod/audit?aggregateType=Note&aggregateRef=4+2');
    assert.equal(state.seen.headers['x-kf-context-proof'], PROOF);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].action, 'NOTE_CREATED');
    await kf.audit().list(ctx, 'Note', null);
    assert.equal(state.seen.url, '/api/v1/platform/modules/audit-mod/audit?aggregateType=Note');
  } finally {
    server.close();
  }
});

test('an invalid record is refused locally, permanent, every problem named, no request', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-3', PROOF);
    await assert.rejects(kf.audit().record(ctx, new AuditRecord('note.created', 'm/x', ' ')), (e) => {
      assert.ok(e instanceof KfException);
      assert.equal(e.errorClass, 'permanent');
      assert.match(e.message, /action must match/);
      assert.match(e.message, /aggregateType must match/);
      assert.match(e.message, /aggregateRef is required/);
      return true;
    });
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('a context not verified by this module, or verified without the platform\'s proof: local refusal', async () => {
  const { server, state, kf } = await platform();
  try {
    const built = new RequestContext(1, 't', 'u', Actor.USER, 'en', [], 'POST', '/n', null, Math.floor(Date.now() / 1000),
      'never-verified');
    await assert.rejects(kf.audit().record(built, new AuditRecord('NOTE_CREATED', 'Note', '42')),
      (e) => e instanceof KfException && e.message.includes('was not verified by this module'));
    const withoutProof = verified(kf, 'req-4', null);
    await assert.rejects(kf.audit().list(withoutProof, 'Note', null),
      (e) => e instanceof KfException && e.message.includes('X-KF-Context-Proof') && e.errorClass === 'permanent');
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('403 permanent from the platform -> AuditForbidden; 401 -> AuditUnauthorized', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-5', PROOF);
    state.status = 403;
    state.answer = '{"type":"https://kf.ro/problems/capability-not-declared","title":"Capability not declared","status":403,'
      + '"detail":"The active version of audit-mod does not declare capabilities.audit","class":"permanent"}';
    await assert.rejects(kf.audit().record(ctx, new AuditRecord('NOTE_CREATED', 'Note', '42')), (e) => {
      assert.ok(e instanceof AuditForbidden);
      assert.equal(e.errorClass, 'permanent');
      assert.equal(e.problem.status, 403);
      return true;
    });
    state.status = 401;
    state.answer = '{"type":"https://kf.ro/problems/invalid-context","title":"Invalid context","status":401,"class":"permanent"}';
    await assert.rejects(kf.audit().list(ctx, 'Note', null), (e) => e instanceof AuditUnauthorized);
    state.status = 503;
    state.answer = '';
    await assert.rejects(kf.audit().list(ctx, 'Note', null),
      (e) => e instanceof KfException && e.errorClass === 'transient' && !(e instanceof AuditForbidden) && !(e instanceof AuditUnauthorized));
  } finally {
    server.close();
  }
});

test('the token never appears in an error', async () => {
  const { server, state, kf } = await platform();
  try {
    const ctx = verified(kf, 'req-6', PROOF);
    state.status = 403;
    state.answer = '{"status":403,"class":"permanent"}';
    await assert.rejects(kf.audit().record(ctx, new AuditRecord('NOTE_CREATED', 'Note', '42')),
      (e) => !e.message.includes(TOKEN) && !e.message.includes(PROOF));
  } finally {
    server.close();
  }
});
