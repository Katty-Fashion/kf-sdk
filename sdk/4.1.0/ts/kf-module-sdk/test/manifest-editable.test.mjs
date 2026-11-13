// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestEditableTest.java. Do not edit by hand.
//
// capabilities.fields[].editable: a boolean, false when absent, a
// rule of the host's generic rendering, not of writing. The SDK reads it on the typed manifest and the local check
// refuses a value that is not a boolean (null included), like the platform at upload. The generic-field
// rule itself is fixed only in sdk-protocol.md: no conformance fixture.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

function manifest(editable) {
  const author = { entity: 'model', name: 'author', type: 'STRING', table: 'models_extra', label: 'field.author' };
  if (editable !== null) author.editable = JSON.parse(editable);
  return JSON.stringify({
    schemaVersion: 2, slug: 'edit-mod', version: '1.0.0', platform: '>=4.1', name: 'module.name',
    i18n: { en: { 'module.name': 'Editable', 'field.author': 'Author', 'field.note': 'Note' } },
    capabilities: { fields: [author, { entity: 'model', name: 'note', type: 'STRING', table: 'models_extra', label: 'field.note' }] },
  });
}

test('editable true is read; a field without it reads false', () => {
  const m = Manifest.parse(manifest('true'));
  assert.equal(m.capabilities.fields[0].editable, true);
  assert.equal(m.capabilities.fields[1].editable, false);
  assert.ok(ManifestCheck.validate(manifest('true')).ok());
});

test('editable false and absent are legal', () => {
  assert.ok(ManifestCheck.validate(manifest('false')).ok());
  assert.ok(ManifestCheck.validate(manifest(null)).ok());
  assert.equal(Manifest.parse(manifest(null)).capabilities.fields[0].editable, false);
});

test('editable that is not a boolean is refused, naming the field (null included)', () => {
  for (const value of ['"yes"', '1', 'null']) {
    const result = ManifestCheck.validate(manifest(value));
    assert.equal(result.ok(), false, value);
    assert.equal(result.errors.length, 1, `${value}: ${JSON.stringify(result.errors)}`);
    assert.ok(result.errors[0].includes('capabilities.fields[0].editable must be a boolean'), result.errors[0]);
    assert.equal(Manifest.parse(manifest(value)).capabilities.fields[0].editable, false, value);
  }
});
