// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestRollbackFloorTest.java. Do not edit by hand.
//
// The optional top-level rollbackFloor of kf-module-conf.json: the oldest version of the
// module that still runs on the data (the module's own database) this version leaves behind. A semver at or below
// version; absent (or null) when the module declares none. The SDK reads it on the typed manifest and the local check
// refuses a value that is not semver or is above version, naming the key.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

function manifest(version, rollbackFloor) {
  const m = { schemaVersion: 2, slug: 'floor-mod', version, platform: '>=4.1', name: 'module.name' };
  if (rollbackFloor !== null) m.rollbackFloor = JSON.parse(rollbackFloor);
  m.i18n = { en: { 'module.name': 'Rollback floor' } };
  return JSON.stringify(m);
}

test('a rollbackFloor below version is read and accepted', () => {
  const json = manifest('1.2.0', '"1.1.0"');
  const result = ManifestCheck.validate(json);
  assert.ok(result.ok(), JSON.stringify(result.errors));
  assert.equal(result.warnings.length, 0, JSON.stringify(result.warnings));
  assert.equal(Manifest.parse(json).rollbackFloor, '1.1.0');
});

test('a rollbackFloor equal to version is accepted: no older version runs on this data', () => {
  const json = manifest('1.2.0', '"1.2.0"');
  assert.ok(ManifestCheck.validate(json).ok());
  assert.equal(Manifest.parse(json).rollbackFloor, '1.2.0');
});

test('an absent or null rollbackFloor is legal and reads null', () => {
  assert.ok(ManifestCheck.validate(manifest('1.2.0', null)).ok());
  assert.ok(ManifestCheck.validate(manifest('1.2.0', 'null')).ok());
  assert.equal(Manifest.parse(manifest('1.2.0', null)).rollbackFloor, null);
  assert.equal(Manifest.parse(manifest('1.2.0', 'null')).rollbackFloor, null);
});

test('a rollbackFloor above version is refused, naming the key and both versions', () => {
  const cases = [
    ['1.2.0', '"1.3.0"', '"rollbackFloor" "1.3.0" is above "version" "1.2.0"'],
    ['1.9.0', '"1.10.0"', '"rollbackFloor" "1.10.0" is above "version" "1.9.0"'],
    ['1.2.0-rc.1', '"1.2.0"', '"rollbackFloor" "1.2.0" is above "version" "1.2.0-rc.1"'],
  ];
  for (const [version, value, message] of cases) {
    const result = ManifestCheck.validate(manifest(version, value));
    assert.equal(result.ok(), false, value);
    assert.equal(result.errors.length, 1, `${value}: ${JSON.stringify(result.errors)}`);
    assert.ok(result.errors[0].includes(message), result.errors[0]);
  }
  // semver order, not text order: 1.10.0 is above 1.9.0, so 1.9.0 is a legal floor of 1.10.0
  assert.ok(ManifestCheck.validate(manifest('1.10.0', '"1.9.0"')).ok());
});

test('a rollbackFloor that is not a semver 2.0 version is refused, naming the key', () => {
  const cases = [
    ['"one"', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "one")'],
    ['"1.1"', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "1.1")'],
    ['""', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "")'],
    ['110', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got 110)'],
  ];
  for (const [value, message] of cases) {
    const result = ManifestCheck.validate(manifest('1.2.0', value));
    assert.equal(result.ok(), false, value);
    assert.equal(result.errors.length, 1, `${value}: ${JSON.stringify(result.errors)}`);
    assert.ok(result.errors[0].includes(message), result.errors[0]);
  }
  assert.equal(Manifest.parse(manifest('1.2.0', '110')).rollbackFloor, null);
});
