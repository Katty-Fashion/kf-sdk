// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestFlowsTest.java. Do not edit by hand.
//
// Protocol 2.1 (sdk-protocol.md §17): capabilities.flows, the module's baseline flows, checked locally with the
// platform's rules and messages (ModuleManifestValidator / ModulePackValidator) -- only the grammar and, on a
// pack, the files: the BPMN is compiled by the platform, and whether it delivers MODULE_FLOWS is its gate, never the
// SDK's.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

function manifest(schemaVersion, flows) {
  return `{ "schemaVersion": ${schemaVersion}, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "3D", "flow.twin": "Digital twin sample" } },
    "capabilities": { "flows": [ ${flows} ] } }`;
}

const TWIN = '{ "key": "kf-3d-digital-twin", "file": "flows/kf-3d-digital-twin.bpmn", "name": "flow.twin" }';

function errors(json) {
  return [...ManifestCheck.validate(json).errors];
}

function refused(errs, fragment) {
  assert.ok(errs.some((e) => e.includes(fragment)), `an error must contain <${fragment}>: ${JSON.stringify(errs)}`);
}

test('a module flow is valid under schema 3 and read as declared', () => {
  const json = manifest(3, TWIN);
  assert.deepEqual(errors(json), []);
  const flow = Manifest.parse(json).capabilities.flows[0];
  assert.equal(flow.key, 'kf-3d-digital-twin');
  assert.equal(flow.file, 'flows/kf-3d-digital-twin.bpmn');
  assert.equal(flow.name, 'flow.twin');
  assert.ok(Manifest.CAPABILITY_KEYS.includes('flows'), 'flows is a capability of protocol 2.1');
});

test('absent flows read as an empty list', () => {
  const json = manifest(3, '').replace('"flows": [  ]', '"routes": []');
  assert.deepEqual([...Manifest.parse(json).capabilities.flows], []);
});

test('flows under schema 2 are refused named', () => {
  refused(errors(manifest(2, TWIN)), 'capabilities.flows requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
});

test('the key has its grammar and is unique', () => {
  refused(errors(manifest(3, '{ "key": "Twin", "file": "flows/twin.bpmn", "name": "flow.twin" }')),
    'capabilities.flows[0].key "Twin" must match ^[a-z][a-z0-9-]{2,62}$');
  refused(errors(manifest(3, '{ "key": "ab", "file": "flows/ab.bpmn", "name": "flow.twin" }')),
    'capabilities.flows[0].key "ab" must match');
  refused(errors(manifest(3, `${TWIN}, { "key": "kf-3d-digital-twin", "file": "flows/other.bpmn", "name": "flow.twin" }`)),
    'capabilities.flows[1].key "kf-3d-digital-twin" is declared twice');
});

test('the file is a bpmn directly under flows without dot dot and unique', () => {
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/../x.bpmn", "name": "flow.twin" }')),
    'capabilities.flows[0].file "flows/../x.bpmn" must not contain ".."');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "bpmn/a.bpmn", "name": "flow.twin" }')),
    'capabilities.flows[0].file "bpmn/a.bpmn" must be a .bpmn file directly under flows/');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/sub/a.bpmn", "name": "flow.twin" }')),
    'must be a .bpmn file directly under flows/');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/a.xml", "name": "flow.twin" }')),
    'capabilities.flows[0].file "flows/a.xml" must be a .bpmn file directly under flows/');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "name": "flow.twin" }')),
    'capabilities.flows[0].file null must be a .bpmn file directly under flows/');
  refused(errors(manifest(3, `${TWIN}, { "key": "kf-3d-other", "file": "flows/kf-3d-digital-twin.bpmn", "name": "flow.twin" }`)),
    'capabilities.flows[1].file "flows/kf-3d-digital-twin.bpmn" is declared twice');
});

test('the name is a key of i18n.en and the object is closed', () => {
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/a.bpmn", "name": "flow.missing" }')),
    'capabilities.flows[0].name "flow.missing" is not defined in i18n.en');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/a.bpmn" }')),
    'capabilities.flows[0].name is required: a translation key defined in i18n.en');
  refused(errors(manifest(3, '{ "key": "kf-3d-a", "file": "flows/a.bpmn", "name": "flow.twin", "bpmn": "<x/>" }')),
    'capabilities.flows[0]: "bpmn" is not a key of a module flow (known: key, file, name)');
});

test('at most twenty flows', () => {
  const flows = [];
  for (let i = 0; i < 21; i++) flows.push(`{ "key": "kf-3d-f${i}", "file": "flows/f${i}.bpmn", "name": "flow.twin" }`);
  refused(errors(manifest(3, flows.join(', '))), 'capabilities.flows holds at most 20 flows (got 21)');
});

test('checkPack finds a missing or too large file', () => {
  const json = manifest(3, TWIN);
  assert.ok(ManifestCheck.checkPack(json, new Map([['flows/kf-3d-digital-twin.bpmn', 1024]])).ok());
  assert.ok(ManifestCheck.checkPack(json, { 'flows/kf-3d-digital-twin.bpmn': 262144 }).ok(), '256 KiB is the cap, inclusive');
  refused([...ManifestCheck.checkPack(json, new Map()).errors],
    'capabilities.flows[0].file "flows/kf-3d-digital-twin.bpmn" is not in the pack');
  refused([...ManifestCheck.checkPack(json, null).errors], 'is not in the pack');
  refused([...ManifestCheck.checkPack(json, new Map([['flows/kf-3d-digital-twin.bpmn', 262145]])).errors],
    'capabilities.flows[0].file "flows/kf-3d-digital-twin.bpmn" is larger than 262144 bytes (got 262145)');
});

test('checkPack keeps the manifest rules and reports a bad file once', () => {
  const errs = [...ManifestCheck.checkPack(
    manifest(3, '{ "key": "kf-3d-a", "file": "flows/../a.bpmn", "name": "flow.twin" }'), new Map()).errors];
  assert.equal(errs.length, 1, JSON.stringify(errs));
  refused(errs, 'must not contain ".."');
});
