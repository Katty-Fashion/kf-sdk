// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/VersionExtensionTest.java. Do not edit by hand.
//
// A version's values in the model's envelope (protocol 1.1, sdk-protocol.md §5.1): a
// module's write on model_version is an update of the version's model, with
// versions.{versionId}.extensions.{slug}.{field} in changed and the values under before/after.versions -- read through
// ModelSnapshot.versionExtension and the changed version paths (SDK 0.7.4).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AfterEvent, BeforeContext, Envelope, FieldChange, HookEvents, ModelSnapshot, VersionField } from '../dist/index.js';

/** The data of the example of §5.1, with a second field and a second version. */
const DATA = `
{ "entity": "model", "id": "42",
  "before": { "id": 42, "name": "Rochie Ana", "materialList": [],
              "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
              "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": null, "aas_pushed": false } } },
                            "18": { "extensions": { "kf-aas-r3": { "aas_shell": "old" } } } } },
  "after":  { "id": 42, "name": "Rochie Ana", "materialList": [],
              "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
              "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": "TechnicalPack-17", "aas_pushed": true } } },
                            "18": { "extensions": { "kf-aas-r3": { "aas_shell": "new" } } } } },
  "changed": [ "versions.17.extensions.kf-aas-r3.aas_shell", "versions.17.extensions.kf-aas-r3.aas_pushed",
               "versions.18.extensions.kf-aas-r3.aas_shell" ] }
`;

const ENVELOPE = { type: 'kf.model.before.update', id: 'd-1', kfeventid: 'e-1', kftenant: 't-1', kfenvelopeversion: '1', kfmodule: 'conformance' };

const data = (json) => JSON.parse(json);
const vf = (versionId, slug, field) => new VersionField(versionId, slug, field);

test('versionExtension reads versions.{id}.extensions.{slug}.{field} on before and after; the model\'s own extensions stay as stored', () => {
  const d = data(DATA);
  const before = ModelSnapshot.fromMap(d.before);
  const after = ModelSnapshot.fromMap(d.after);

  assert.equal(before.versionExtension('17', 'kf-aas-r3', 'aas_shell'), null);
  assert.equal(after.versionExtension('17', 'kf-aas-r3', 'aas_shell'), 'TechnicalPack-17');
  assert.equal(before.versionExtension('17', 'kf-aas-r3', 'aas_pushed'), false);
  assert.equal(after.versionExtension('17', 'kf-aas-r3', 'aas_pushed'), true);
  assert.equal(before.versionExtension('18', 'kf-aas-r3', 'aas_shell'), 'old');
  assert.equal(after.versionExtension('18', 'kf-aas-r3', 'aas_shell'), 'new');
  assert.equal(after.extension('kf-aas-r3', 'aas_shell'), 'model-shell');
  assert.deepEqual(Object.keys(after.versionExtensions), ['17', '18']);
});

test('versionExtension is null for a version, a module or a field the snapshot does not carry, and an envelope without versions reads empty', () => {
  const after = ModelSnapshot.fromMap(data(DATA).after);
  assert.equal(after.versionExtension('19', 'kf-aas-r3', 'aas_shell'), null);
  assert.equal(after.versionExtension('17', 'other-module', 'aas_shell'), null);
  assert.equal(after.versionExtension('17', 'kf-aas-r3', 'missing'), null);

  const plain = ModelSnapshot.fromMap(data('{ "after": { "id": 42, "name": "Rochie", "materialList": [], "extensions": {} } }').after);
  assert.equal(Object.keys(plain.versionExtensions).length, 0);
  assert.equal(plain.versionExtension('17', 'kf-aas-r3', 'aas_shell'), null);

  const odd = ModelSnapshot.fromMap(data(`
    { "after": { "versions": { "17": "not-an-object", "18": { "extensions": "not-an-object" },
                               "19": { "extensions": { "kf-aas-r3": "not-an-object" } } } } }`).after);
  assert.equal(Object.keys(odd.versionExtensions).length, 0, `shapes that are not objects are skipped: ${JSON.stringify(odd.versionExtensions)}`);
});

test('VersionField.parse reads versions.{versionId}.extensions.{slug}.{field} and nothing else; path() writes it back', () => {
  const f = VersionField.parse('versions.17.extensions.kf-aas-r3.aas_shell');
  assert.deepEqual(f, vf('17', 'kf-aas-r3', 'aas_shell'));
  assert.equal(f.path(), 'versions.17.extensions.kf-aas-r3.aas_shell');

  for (const notOne of ['name', 'materialList', 'extensions.kf-aas-r3.aas_shell',
    'versions.17.extensions.kf-aas-r3', 'versions.17.extensions.kf-aas-r3.aas_shell.more',
    'versions..extensions.kf-aas-r3.aas_shell', 'versions.17.other.kf-aas-r3.aas_shell',
    'versions.17.extensions..aas_shell', 'versions.17.extensions.kf-aas-r3.', 'colors.17.extensions.a.b', '']) {
    assert.equal(VersionField.parse(notOne), null, notOne);
  }
  assert.equal(VersionField.parse(null), null);
});

test('a before hook sees the changed version paths and the versions they touch, in order, without the model\'s own paths', () => {
  const ctx = new BeforeContext('conformance', HookEvents.BEFORE_UPDATE, Envelope.of(ENVELOPE), data(DATA));

  assert.deepEqual(ctx.changedVersionFields(), [vf('17', 'kf-aas-r3', 'aas_shell'), vf('17', 'kf-aas-r3', 'aas_pushed'),
    vf('18', 'kf-aas-r3', 'aas_shell')]);
  assert.deepEqual(ctx.changedVersionIds(), ['17', '18']);
  assert.equal(ctx.after.versionExtension('17', 'kf-aas-r3', 'aas_shell'), 'TechnicalPack-17');

  const model = new BeforeContext('conformance', HookEvents.BEFORE_UPDATE, Envelope.of(ENVELOPE),
    data('{ "id": "42", "changed": ["name", "extensions.conformance.author"] }'));
  assert.deepEqual(model.changedVersionFields(), []);
  assert.deepEqual(model.changedVersionIds(), []);
});

test('an after event sees the same, and a field change names its version path (null for a model path)', () => {
  const d = data(DATA);
  const after = AfterEvent.of(HookEvents.AFTER_UPDATE, Envelope.of(ENVELOPE), d);
  assert.deepEqual(after.changedVersionIds(), ['17', '18']);
  assert.equal(after.changedVersionFields().length, 3);
  assert.equal(after.after.versionExtension('18', 'kf-aas-r3', 'aas_shell'), 'new');

  d.field = 'versions.18.extensions.kf-aas-r3.aas_shell';
  const change = FieldChange.of(Envelope.of(ENVELOPE), d);
  assert.deepEqual(change.versionField(), vf('18', 'kf-aas-r3', 'aas_shell'));

  d.field = 'name';
  assert.equal(FieldChange.of(Envelope.of(ENVELOPE), d).versionField(), null);
});
