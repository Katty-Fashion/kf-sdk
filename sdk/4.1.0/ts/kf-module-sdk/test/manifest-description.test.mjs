// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestDescriptionTest.java. Do not edit by hand.
//
// The optional top-level description of kf-module-conf.json: the same shape as
// name — under schemaVersion 2 a translation key defined in i18n.en, absent (or null) when the module has none. The
// SDK reads it on the typed manifest and the local check refuses a key that is malformed, blank or not defined in en,
// like the platform at upload.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

function manifest(description) {
  const m = { schemaVersion: 2, slug: 'desc-mod', version: '1.0.0', platform: '>=4.1', name: 'module.name' };
  if (description !== null) m.description = JSON.parse(description);
  m.i18n = {
    en: { 'module.name': 'Description', 'module.description': 'Adds an author tab' },
    ro: { 'module.description': 'Adaugă un tab de autor' },
  };
  return JSON.stringify(m);
}

test('a description key defined in en is read and accepted', () => {
  const m = Manifest.parse(manifest('"module.description"'));
  assert.equal(m.description, 'module.description');
  assert.equal(m.enText(m.description), 'Adds an author tab');
  assert.ok(ManifestCheck.validate(manifest('"module.description"')).ok());
});

test('an absent or null description is legal and reads null', () => {
  assert.ok(ManifestCheck.validate(manifest(null)).ok());
  assert.ok(ManifestCheck.validate(manifest('null')).ok());
  assert.equal(Manifest.parse(manifest(null)).description, null);
  assert.equal(Manifest.parse(manifest('null')).description, null);
});

test('a description that is not a key of en is refused, naming the description', () => {
  const cases = [
    ['"module.missing"', '"description" "module.missing" is not defined in i18n.en'],
    ['"Adds an author tab"', '"description" "Adds an author tab" is not a translation key'],
    ['""', '"description" is required: a translation key defined in i18n.en'],
  ];
  for (const [value, message] of cases) {
    const result = ManifestCheck.validate(manifest(value));
    assert.equal(result.ok(), false, value);
    assert.equal(result.errors.length, 1, `${value}: ${JSON.stringify(result.errors)}`);
    assert.ok(result.errors[0].includes(message), result.errors[0]);
  }
});
