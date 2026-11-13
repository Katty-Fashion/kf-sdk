// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/LocalManifestCheckTest.java. Do not edit by hand.
//
// KfModule.start() checks kf-module-conf.json locally before the first request: a violation is a permanent error,
// nothing is sent.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { KfModule, SdkVersion, setSdkLogger } from '../dist/index.js';

setSdkLogger({ info() {}, warn() {}, error() {} });

const HOOKABLE_GET = JSON.stringify({
  schemaVersion: 2, slug: 'local-check', version: '1.0.0', platform: '>=4.1', name: 'module.name',
  i18n: { en: { 'module.name': 'Local check' } },
  capabilities: { routes: [{ method: 'GET', path: '/m/local-check/events', right: 'api.v1.m.local-check.events.get', hookable: true }] },
});

test('hookable on a GET route stops the module before the first request, naming the route', async () => {
  let requests = 0;
  const platform = createServer((req, res) => {
    requests++;
    res.writeHead(200).end();
  });
  await new Promise((r) => platform.listen(0, '127.0.0.1', r));
  try {
    const manifest = join(mkdtempSync(join(tmpdir(), 'kf-module-conf-')), 'kf-module-conf.json');
    writeFileSync(manifest, HOOKABLE_GET);
    const kf = KfModule.fromEnv({
      KF_PLATFORM_URL: `http://127.0.0.1:${platform.address().port}`, KF_MODULE_SLUG: 'local-check', KF_MODULE_TOKEN: 'kfm_local',
      KF_HOOK_SECRET: 's', KF_MODULE_MANIFEST: manifest,
    });
    kf.start();
    await new Promise((r) => setTimeout(r, 200));

    assert.equal(kf.isRegistrationStopped(), true);
    assert.equal(requests, 0, 'nothing may be sent');
    assert.equal(kf.lastRegistrationProblem().errorClass, 'permanent');
    const error = kf.lastRegistrationError();
    assert.ok(error.includes('GET /m/local-check/events'), error);
    assert.ok(error.includes('hookable only on POST, PUT or PATCH'), error);
  } finally {
    await new Promise((r) => platform.close(r));
  }
});

test('without a manifest file the local check is skipped (warning), the registration proceeds', () => {
  const kf = KfModule.fromEnv({ KF_MODULE_MANIFEST: 'does-not-exist/kf-module-conf.json' });
  assert.equal(kf.manifest(), null);
  kf.start(); // no platform env: only the warning, no registration
  assert.equal(kf.isRegistrationStopped(), false);
});

test('SdkVersion carries the package version and the manifest schemaVersion 3 (protocol 2.0)', () => {
  assert.match(SdkVersion.SDK_VERSION, /^[0-9]+[.][0-9]+[.][0-9]+/);
  assert.equal(SdkVersion.PROTOCOL_SCHEMA_VERSION, 3);
});
