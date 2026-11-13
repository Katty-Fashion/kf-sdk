// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestSlotsTest.java. Do not edit by hand.
//
// Protocol 2.0, manifest schemaVersion 3: the module slots capabilities.slots
// (sdk-protocol.md §7.12, called "§7.11" by the plans). Schema 3 is a strict superset of 2; slots is
// refused under 1–2; every rule of the contract has an accepted case and a refused one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ManifestCheck } from '../dist/index.js';

const I18N = `"i18n":{"en":{"module.name":"Slots","slot.layers":"Viewport layers"}}`;

function manifest(schemaVersion, slots) {
  return `{"schemaVersion":${schemaVersion},"slug":"kf-3d","version":"1.0.0","platform":">=4.1","name":"module.name",${I18N}
 ${slots == null ? '' : `,"capabilities":{"slots":[${slots}]}`}}
`;
}

const LAYERS = '{"key":"viewport.layers.v1","kind":"B","type":"layer","visibility":"public",'
  + '"label":"slot.layers","layerKinds":["pin","highlight"]}';
const INSPECTOR = '{"key":"studio.inspector.sections.v1","kind":"A","type":"extension","visibility":"internal",'
  + '"publicMembers":["model","version"],"actions":["select-piece"]}';
const EVENTS = '{"key":"viewport.events.v1","kind":"D","type":"events","visibility":"public",'
  + '"events":["piece-selected"]}';
const MATERIALS = '{"key":"materials.v1","kind":"R","type":"resource","visibility":"public",'
  + '"dataSchema":"m.kf-3d.material.v1"}';

const errors = (json) => ManifestCheck.validate(json).errors;

function refused(json, fragment) {
  const found = errors(json);
  assert.ok(found.some((e) => e.includes(fragment)), `an error must contain <${fragment}>: ${JSON.stringify(found)}`);
}

test('schema 3 is a strict superset of 2: a valid schema 2 manifest is valid with only the number changed', () => {
  assert.deepEqual(errors(manifest(2, null)), []);
  assert.deepEqual(errors(manifest(3, null)), []);
});

test('schemaVersion 4 is refused locally, naming the interval 1..3', () => {
  refused(manifest(4, null), 'supported: 1..3');
});

test('every kind of slot, used correctly, is accepted under schema 3', () => {
  assert.deepEqual(errors(manifest(3, [LAYERS, INSPECTOR, EVENTS, MATERIALS].join(','))), []);
});

test('capabilities.slots under schema 2 is refused: it requires schemaVersion 3', () => {
  refused(manifest(2, LAYERS), 'requires "schemaVersion": 3');
});

test('a key without its major as last segment is refused', () => {
  refused(manifest(3, LAYERS.replace('viewport.layers.v1', 'viewport.layers')), 'capabilities.slots[0].key');
});

test('a key declared twice is refused', () => {
  refused(manifest(3, `${LAYERS},${LAYERS}`), 'is declared twice');
});

test("a type that is not one of the kind's is refused", () => {
  refused(manifest(3, LAYERS.replace('"type":"layer"', '"type":"tab"')), 'capabilities.slots[0].type');
});

test('visibility is mandatory, without a default', () => {
  refused(manifest(3, LAYERS.replace('"visibility":"public",', '')), 'capabilities.slots[0].visibility');
});

test('a slot of kind B without layerKinds is refused', () => {
  refused(manifest(3, LAYERS.replace(',"layerKinds":["pin","highlight"]', '')), 'capabilities.slots[0].layerKinds');
});

test('a slot of kind R without dataSchema is refused (data only)', () => {
  refused(manifest(3, MATERIALS.replace(',"dataSchema":"m.kf-3d.material.v1"', '')), 'capabilities.slots[0].dataSchema');
});

test('a key outside the closed object of a slot is refused', () => {
  refused(manifest(3, LAYERS.replace('"kind":"B"', '"kind":"B","code":"x.js"')), '"code" is not a key of a slot');
});

test('a key of another kind is refused (layerKinds only on kind B)', () => {
  refused(manifest(3, INSPECTOR.replace('"actions"', '"layerKinds":["pin"],"actions"')), 'only on kind B');
});

test('events on a commands slot and a dataSchema of another owner are refused', () => {
  refused(manifest(3, EVENTS.replace('"type":"events"', '"type":"commands"')), 'capabilities.slots[0].commands');
  refused(manifest(3, MATERIALS.replace('m.kf-3d.material.v1', 'm.other.material.v1')), 'capabilities.slots[0].dataSchema');
});

test('a label that is not a key of i18n.en is refused', () => {
  refused(manifest(3, LAYERS.replace('slot.layers', 'slot.missing')), 'capabilities.slots[0].label');
});
