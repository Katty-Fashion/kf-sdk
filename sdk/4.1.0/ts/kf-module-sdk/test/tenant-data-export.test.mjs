// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/TenantDataExportTest.java. Do not edit by hand.
//
// TenantDataExport (protocol 1.3 §15.8): the bundle a module answers on GET /m/{slug}/admin/export
// is the one its admin/import takes back unchanged. The fixture is kf-feedback's own import fixture, read where the Java
// test reads it (sdk/java/kf-module-sdk/src/test/resources/ro/kf/sdk/tenant-data/tenant-data-sample.json, one copy):
// its contentSha256 was computed by another implementation (Node) and is accepted by kf-feedback's TenantDataImport, so
// the same hash here proves the SDK's canonical form is the importers' one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { TenantDataExport } from '../dist/index.js';

const TENANT = '00000000-0000-0000-0000-000000000001';
const FIXTURE_SHA = 'a55243f587fc62451c3a3df811101b2d0e9a2f5089eb38c9ac2488309a658bec';
const FIXTURE = new URL('../../../java/kf-module-sdk/src/test/resources/ro/kf/sdk/tenant-data/tenant-data-sample.json',
  import.meta.url);

function fixture() {
  return JSON.parse(readFileSync(FIXTURE, 'utf8'));
}

test("the hash of kf-feedback's import fixture is the one it carries (the importers' canonical form)", () => {
  const bundle = fixture();

  assert.equal(bundle.contentSha256, FIXTURE_SHA);
  assert.equal(TenantDataExport.contentSha256(bundle), FIXTURE_SHA);
});

test('a bundle built from the same content has every common field and the same hash, whatever exportedAt and source', () => {
  const f = fixture();
  const content = { tickets: f.tickets, warnings: f.warnings };

  const bundle = TenantDataExport.bundle('kf-feedback/tenant-data/v1', TENANT, 1, 1, f.counts, content,
    { exportedAt: new Date('2026-10-30T18:00:00Z') });

  assert.deepEqual(Object.keys(bundle), ['schema', 'tenantId', 'exportedAt', 'part', 'parts', 'tickets', 'warnings',
    'counts', 'contentSha256']);
  assert.equal(bundle.schema, 'kf-feedback/tenant-data/v1');
  assert.equal(bundle.tenantId, TENANT);
  assert.equal(bundle.exportedAt, '2026-10-30T18:00:00Z');
  assert.equal(bundle.part, 1);
  assert.equal(bundle.parts, 1);
  assert.equal(bundle.contentSha256, FIXTURE_SHA, 'exportedAt, source and warnings are not hashed');
  assert.equal(TenantDataExport.contentSha256(bundle), FIXTURE_SHA);
});

test('a changed ticket changes the hash: the importer would refuse it (bundle-altered)', () => {
  const bundle = fixture();
  bundle.tickets[0].message = 'changed';

  assert.notEqual(TenantDataExport.contentSha256(bundle), FIXTURE_SHA);
});

test('the canonical form: keys sorted at every level, compact, arrays kept in order, null kept', () => {
  const value = { b: 1, a: { d: true, c: [3, 'x', { z: 1 }] }, n: null, s: 'ă/"' };

  assert.equal(TenantDataExport.canonicalJson(value), '{"a":{"c":[3,"x",{"z":1}],"d":true},"b":1,"n":null,"s":"ă/\\""}');
});

test('part and parts: 1 ≤ part ≤ parts; the schema, the tenant and the counts are required', () => {
  const counts = { tickets: 0 };
  const content = { tickets: [] };
  const schema = 'kf-feedback/tenant-data/v1';

  assert.throws(() => TenantDataExport.bundle(schema, TENANT, 0, 1, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle(schema, TENANT, 3, 2, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle(schema, TENANT, 1, 0, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle(null, TENANT, 1, 1, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle('feedback/v1', TENANT, 1, 1, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle(schema, null, 1, 1, counts, content), TypeError);
  assert.throws(() => TenantDataExport.bundle(schema, TENANT, 1, 1, null, content), TypeError);
  const second = TenantDataExport.bundle(schema, TENANT, 2, 2, counts, content);
  assert.equal(second.part, 2);
  assert.ok(second.exportedAt.trim() !== '');
});

test('the content cannot carry a common key: it would overwrite the envelope', () => {
  for (const key of TenantDataExport.COMMON_KEYS) {
    assert.throws(() => TenantDataExport.bundle('kf-qc/tenant-data/v1', TENANT, 1, 1, {}, { [key]: 'x' }),
      (e) => e instanceof TypeError && e.message.includes(key), key);
  }
});
