// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelHooksVersionPointsTest.java. Do not edit by hand.
//
// Protocol 2.3 (docs/modules/kf-3d/CONTRACT.md §2, §5, §6): the two version points of a model
// (model.after.version-copied, model.after.version-delete) delivered to the handlers of ModelHooks; the service port
// collaboration.asset-annotations.v1 (AssetAnnotations), refused locally when not declared; the storage ceiling for a lone
// GLB. The FakePlatform of the Java test is Java-only (not derived): the deliveries are signed here by hand.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { DeliveryContext, HookEvents, KfException, KfModule, Manifest, Signatures, setSdkLogger } from '../dist/index.js';

const SECRET = 'version-points-secret';
const TOKEN = 'kfm_version-points-token';
const PROOF = 'd'.repeat(64);
const TENANT = '00000000-0000-0000-0000-00000000000a';
const MANIFEST = `{ "schemaVersion": 3, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
  "i18n": { "en": { "module.name": "3D" } },
  "capabilities": { "services": [ { "port": "collaboration.asset-annotations.v1" } ] } }`;

setSdkLogger({ info() {}, warn() {}, error() {} });

function envelope(point, before, after) {
  return {
    specversion: '1.0', id: '5e1a0000-0000-4000-8000-000000000001', kfeventid: '5e1a0000-0000-4000-8000-0000000000a1',
    type: `kf.${point}`, source: '/api/v1/models/42', subject: '42', time: '2026-11-13T10:15:30.123Z',
    datacontenttype: 'application/json', kfmodule: 'kf-3d', kfflags: {}, kftenant: TENANT, kflocale: 'en', kfactor: null,
    kfaggregatetype: 'model', kfenvelopeversion: '1',
    data: { entity: 'model', id: '42', before, after, changed: ['versions'] },
  };
}

function send(kf, env) {
  const bytes = Buffer.from(JSON.stringify(env), 'utf8');
  const now = Math.floor(Date.now() / 1000);
  const ts = String(now);
  return kf.handle(bytes, ts, Signatures.sign(SECRET, ts, bytes), now);
}

function verified(kf) {
  const ts = Math.floor(Date.now() / 1000);
  const json = `{"v":1,"tenant":"${TENANT}","user":"sub-1","actor":{"type":"user","via":null,"hookId":null},"locale":"en",`
    + `"rights":[],"method":"DELETE","path":"/m/kf-3d/assets/a1","query":null,"ts":${ts},"requestId":"req-1"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  return kf.verifyContext(context, String(ts), signature, new Uint8Array(0), PROOF);
}

test('the constants and the registration carry both points', () => {
  assert.equal(HookEvents.AFTER_VERSION_COPIED, 'model.after.version-copied');
  assert.equal(HookEvents.AFTER_VERSION_DELETE, 'model.after.version-delete');
  const kf = KfModule.of('http://127.0.0.1:1', 'kf-3d', TOKEN, SECRET, null);
  kf.models().afterVersionCopied(() => {}).afterVersionDelete(() => {});
  const events = kf.models().registration().map((h) => h.event);
  assert.ok(events.includes('model.after.version-copied'), String(events));
  assert.ok(events.includes('model.after.version-delete'), String(events));
});

test('afterVersionCopied receives the model id, the source and the target version; a redelivery comes again', async () => {
  const copied = [];
  const deleted = [];
  const kf = KfModule.of('http://127.0.0.1:1', 'kf-3d', TOKEN, SECRET, null);
  kf.models().afterVersionCopied((e) => { copied.push(e); }).afterVersionDelete((e) => { deleted.push(e); });
  const env = envelope('model.after.version-copied', { versionId: '17' }, { versionId: '18', sourceVersionId: '17' });

  const first = await send(kf, env);

  assert.equal(first.status, 200, first.body);
  const ev = copied[0];
  assert.equal(ev.event, 'model.after.version-copied');
  assert.equal(ev.id, '42');
  assert.equal(ev.eventId, '5e1a0000-0000-4000-8000-0000000000a1');
  assert.equal(ev.data.before.versionId, '17');
  assert.equal(ev.data.after.versionId, '18');
  assert.equal(ev.data.after.sourceVersionId, '17');
  assert.deepEqual(ev.changed, ['versions']);
  await send(kf, env);
  assert.equal(copied.length, 2); // at least once: the SDK does not deduplicate, the module is idempotent
  assert.equal(deleted.length, 0);
});

test('afterVersionDelete receives before.versionId and a null after', async () => {
  const copied = [];
  const deleted = [];
  const kf = KfModule.of('http://127.0.0.1:1', 'kf-3d', TOKEN, SECRET, null);
  kf.models().afterVersionCopied((e) => { copied.push(e); }).afterVersionDelete((e) => { deleted.push(e); });

  const result = await send(kf, envelope('model.after.version-delete', { versionId: '17' }, null));

  assert.equal(result.status, 200, result.body);
  const ev = deleted[0];
  assert.equal(ev.event, 'model.after.version-delete');
  assert.equal(ev.id, '42');
  assert.equal(ev.data.before.versionId, '17');
  assert.equal(ev.data.after, null);
  assert.equal(copied.length, 0);
});

async function platform() {
  const state = { requests: 0, uri: null, body: null };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.requests++;
      state.uri = `${req.method} ${req.url}`;
      state.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(204);
      res.end();
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, state, url: `http://127.0.0.1:${server.address().port}` };
}

function deliveryContext(ts = Math.floor(Date.now() / 1000)) {
  const json = `{"v":1,"tenant":"${TENANT}","user":null,"actor":{"type":"system","via":"hook","hookId":"d-1"},"locale":"en",`
    + `"rights":[],"method":"POST","path":"/_kf/hooks","query":null,"ts":${ts},"requestId":"d-1"}`;
  return DeliveryContext.of(Buffer.from(json, 'utf8').toString('base64url'), PROOF);
}

const READS_MANIFEST = `{ "schemaVersion": 3, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
  "i18n": { "en": { "module.name": "3D" } },
  "capabilities": { "reads": [ { "port": "model-version.pattern.v1" } ],
    "settings": [ { "key": "3d-live", "type": "BOOLEAN", "default": false, "label": "module.name" } ] } }`;

test(': a delivery\'s context reads one object and the effective settings, as system via hook', async () => {
  const seen = [];
  const server = createServer((req, res) => {
    seen.push({ url: req.url, context: req.headers['x-kf-context'], proof: req.headers['x-kf-context-proof'] });
    const found = req.url.endsWith('/reads/model-version.pattern.v1/17');
    const settings = req.url.endsWith('/settings');
    res.writeHead(found || settings ? 200 : 404, { 'Content-Type': found || settings ? 'application/json' : 'application/problem+json' });
    res.end(found ? '{"id":"17","groups":[]}' : settings ? '{"3d-live":true}' : '{"status":404}');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'kf-3d', TOKEN, SECRET, null).withManifest(READS_MANIFEST);
    const delivery = deliveryContext();

    assert.deepEqual(await kf.reads().get(delivery, 'model-version.pattern.v1', '17'), { id: '17', groups: [] });
    assert.equal(await kf.reads().get(delivery, 'model-version.pattern.v1', '99'), null);
    assert.equal((await kf.settings().effective(delivery))['3d-live'], true);
    assert.equal(seen.length, 3);
    assert.ok(seen.every((r) => r.context === delivery.contextHeader && r.proof === PROOF));
  } finally {
    server.close();
  }
});

test(': the delivery overloads refuse locally an undeclared port, a blank id and a stale context', async () => {
  const { server, state, url } = await platform();
  try {
    const kf = KfModule.of(url, 'kf-3d', TOKEN, SECRET, null).withManifest(READS_MANIFEST);
    const delivery = deliveryContext();
    await assert.rejects(kf.reads().get(delivery, 'model.v1', '17'),
      (e) => e instanceof KfException && e.message.includes('capabilities.reads'));
    await assert.rejects(kf.reads().get(delivery, 'model-version.pattern.v1', ' '),
      (e) => e instanceof KfException && e.message.includes('id is required'));
    const stale = deliveryContext(Math.floor(Date.now() / 1000) - 301);
    await assert.rejects(kf.settings().effective(stale),
      (e) => e instanceof KfException && e.message.includes('300 s'));
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('collaboration.asset-annotations.v1: a delete is sent to the platform; idempotent 204', async () => {
  const { server, state, url } = await platform();
  try {
    const kf = KfModule.of(url, 'kf-3d', TOKEN, SECRET, null).withManifest(MANIFEST);
    await kf.assetAnnotations().delete(verified(kf), 'c0ffee00-0000-4000-8000-000000000001');
    await kf.assetAnnotations().delete(verified(kf), 'c0ffee00-0000-4000-8000-000000000001');
    assert.equal(state.requests, 2);
    assert.equal(state.uri, 'DELETE /api/v1/platform/modules/kf-3d/services/collaboration.asset-annotations.v1/c0ffee00-0000-4000-8000-000000000001');
    assert.equal(state.body, '');
  } finally {
    server.close();
  }
});

test('asset-annotations: a bad assetId and an undeclared port are refused locally, nothing sent', async () => {
  const { server, state, url } = await platform();
  try {
    const kf = KfModule.of(url, 'kf-3d', TOKEN, SECRET, null).withManifest(MANIFEST);
    await assert.rejects(kf.assetAnnotations().delete(verified(kf), 'a/b'),
      (e) => e instanceof KfException && e.message.includes('assetId must match'));
    const undeclared = KfModule.of(url, 'kf-3d', TOKEN, SECRET, null).withManifest(`{ "schemaVersion": 3, "slug": "kf-3d",
      "name": "module.name", "version": "1.0.0", "platform": ">=4.1", "i18n": { "en": { "module.name": "3D" } },
      "capabilities": { "services": [ { "port": "order.services.v1" } ] } }`);
    await assert.rejects(undeclared.assetAnnotations().delete(verified(undeclared), 'a1'),
      (e) => e instanceof KfException && e.message.includes('not declared in capabilities.services'));
    assert.equal(state.requests, 0);
  } finally {
    server.close();
  }
});

test('storage: 512 MiB only for a lone GLB; the effective maximum is the declaration cut at the ceiling', () => {
  assert.equal(Manifest.storageCeiling(['model/gltf-binary']), 536_870_912);
  assert.equal(Manifest.storageCeiling(['model/gltf-binary', 'image/png']), 10_485_760);
  assert.equal(Manifest.storageCeiling(['image/png']), 10_485_760);
  assert.equal(Manifest.effectiveMaxBytes({ contentTypes: ['model/gltf-binary'], maxBytes: 536_870_913 }), 536_870_912);
  assert.equal(Manifest.effectiveMaxBytes({ contentTypes: ['image/png'], maxBytes: 20_971_520 }), 10_485_760);
});
