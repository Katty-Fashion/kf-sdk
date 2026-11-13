// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestRequiresTest.java. Do not edit by hand.
//
// requires and featureFlags[].requiresModules checked locally (protocol 2.0), with the
// platform's messages (ModulePackValidator.validateRequires, ModuleManifestValidator): the shape only -- whether the
// required module exists, a cycle or stay the platform's.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ManifestCheck } from '../dist/index.js';

function manifest(schemaVersion, requires, flags) {
  return `{"schemaVersion":${schemaVersion},"slug":"req-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
 "i18n":{"en":{"module.name":"Requires","flag.l":"Flag"}},
 "requires":[${requires}],"featureFlags":[${flags == null ? '' : flags}]}
`;
}

const errors = (json) => ManifestCheck.validate(json).errors;

function refused(json, fragment) {
  const found = errors(json);
  assert.ok(found.some((e) => e.includes(fragment)), `an error must contain <${fragment}>: ${JSON.stringify(found)}`);
}

const BASE = '{"slug":"base-mod","versionRange":">=1.0.0 <2.0.0"}';
const OPTIONAL = '{"slug":"extra-mod","versionRange":">=0.5.0","optional":true}';

test('two modules, one optional, under schema 3: valid', () => {
  assert.deepEqual(errors(manifest(3, `${BASE},${OPTIONAL}`, null)), []);
});

test('a slug that is not one, the module itself, a duplicate: refused', () => {
  refused(manifest(2, '{"slug":"Bad Slug","versionRange":">=1.0.0"}', null), '"requires" slug must match');
  refused(manifest(2, '{"slug":"req-mod","versionRange":">=1.0.0"}', null), 'a module cannot require itself');
  refused(manifest(2, `${BASE},${BASE}`, null), 'duplicate "requires" entry for "base-mod"');
});

test('a range the platform cannot parse, or that no version can satisfy: refused with the expected form', () => {
  refused(manifest(2, '{"slug":"base-mod","versionRange":"~1.2"}', null), 'could not be parsed');
  refused(manifest(2, '{"slug":"base-mod","versionRange":">=2.0.0 <1.0.0"}', null), 'version range is impossible');
  refused(manifest(2, '{"slug":"base-mod","versionRange":""}', null), 'needs a non-empty "versionRange"');
});

test('optional under schema 2 is refused; under 3 it must be a boolean', () => {
  refused(manifest(2, OPTIONAL, null), '"requires[0].optional" requires "schemaVersion": 3');
  refused(manifest(3, '{"slug":"base-mod","versionRange":">=1.0.0","optional":"yes"}', null),
    '"requires[0].optional" must be a boolean');
});

test('requiresModules: valid when declared in requires under schema 3, refused otherwise', () => {
  const flag = '{"key":"needs","label":"flag.l","requiresModules":["extra-mod"]}';
  assert.deepEqual(errors(manifest(3, `${BASE},${OPTIONAL}`, flag)), []);
  refused(manifest(3, BASE, flag), '"featureFlags[0].requiresModules": "extra-mod" is not in requires');
  refused(manifest(2, BASE, flag.replace('extra-mod', 'base-mod')),
    '"featureFlags[0].requiresModules" requires "schemaVersion": 3');
});
