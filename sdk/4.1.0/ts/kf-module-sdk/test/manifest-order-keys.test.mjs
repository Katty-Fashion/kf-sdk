// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestOrderKeysTest.java. Do not edit by hand.
//
// Protocol 2.0 (sdk-protocol.md §16): the keys of the order flow, checked locally with the platform's rules and
// messages (ModuleManifestValidator / SurfaceValidator) -- only the grammar: an older platform /
// refuses them at upload, the SDK never does.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

const ROUTES = `"routes": [ { "method": "GET", "path": "/m/kf-step/rules/ready", "right": "api.v1.m.kf-step.rules.ready.get" },
                        { "method": "POST", "path": "/m/kf-step/items", "right": "api.v1.m.kf-step.items.post" } ]`;

function manifest(schemaVersion, capabilities) {
  return `{ "schemaVersion": ${schemaVersion}, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "Step", "rule.blocked": "Not ready" } },
    "capabilities": { ${ROUTES}, ${capabilities} } }`;
}

const FLOW_RULE = '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]';
const SERVICES = '"services": [ { "port": "order.services.v1" } ]';
const STEP = '"surfaces": [ { "slug": "orderflow.step", "kind": "react", "key": "item-check" } ]';
const FLOW_HOOK = '"hooks": [ { "point": "workflow.flow-step-entered" } ]';

function errors(json) {
  return [...ManifestCheck.validate(json).errors];
}

function refused(json, fragment) {
  const e = errors(json);
  assert.ok(e.some((x) => x.includes(fragment)), `an error must contain <${fragment}>: ${JSON.stringify(e)}`);
}

test('the order keys are valid under schema 3 and read as declared', () => {
  const json = manifest(3, `${FLOW_RULE}, ${SERVICES}, ${STEP}, ${FLOW_HOOK}`);
  assert.deepEqual(errors(json), []);
  const m = Manifest.parse(json);
  const rule = m.capabilities.flowRules[0];
  assert.equal(rule.key, 'item-ready');
  assert.equal(rule.path, '/m/kf-step/rules/ready');
  assert.equal(rule.timeoutSeconds, null);
  assert.equal(Manifest.effectiveTimeoutSeconds(rule), 2, 'absent = the default 2 s');
  assert.equal(rule.projection, 'order.flow-rule.v1');
  assert.equal(m.capabilities.services[0].port, 'order.services.v1');
  assert.equal(m.capabilities.surfaces[0].key, 'item-check');
  assert.equal(m.capabilities.hooks[0].point, 'workflow.flow-step-entered');
});

test('flowRules and services under schema 2 are refused, each named', () => {
  const json = manifest(2, `${FLOW_RULE}, ${SERVICES}`);
  refused(json, 'capabilities.flowRules requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
  refused(json, 'capabilities.services requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
});

test('a flow rule is a closed object with its grammar', () => {
  refused(manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/missing", "projection": "order.flow-rule.v1" } ]'),
    'capabilities.flowRules[0].path "/m/kf-step/rules/missing" is not a GET route this module declares');
  refused(manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/items", "projection": "order.flow-rule.v1" } ]'),
    'is not a GET route this module declares');
  refused(manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 61, "projection": "order.flow-rule.v1" } ]'),
    'capabilities.flowRules[0].timeoutSeconds 61 must be a whole number from 1 to 60');
  refused(manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 0, "projection": "order.flow-rule.v1" } ]'),
    'timeoutSeconds 0 must be a whole number from 1 to 60');
  refused(manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v2" } ]'),
    'capabilities.flowRules[0].projection "order.flow-rule.v2" must be "order.flow-rule.v1"');
  refused(manifest(3, '"flowRules": [ { "key": "Item", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]'),
    'capabilities.flowRules[0].key "Item" must match ^[a-z][a-z0-9-]{1,40}$');
  refused(manifest(3, `"flowRules": [ { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" },
                               { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1" } ]`),
    'capabilities.flowRules[1].key "a-rule" is declared twice');
  refused(manifest(3, '"flowRules": [ { "key": "a-rule", "path": "/m/kf-step/rules/ready", "projection": "order.flow-rule.v1", "evaluate": "x" } ]'),
    '"evaluate" is not a key of a flow rule');
  const withTimeout = manifest(3, '"flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/ready", "timeoutSeconds": 10, "projection": "order.flow-rule.v1" } ]');
  assert.deepEqual(errors(withTimeout), []);
  assert.equal(Manifest.effectiveTimeoutSeconds(Manifest.parse(withTimeout).capabilities.flowRules[0]), 10);
});

test('a service names only order.services.v1, once', () => {
  refused(manifest(3, '"services": [ { "port": "order.costs.v1" } ]'),
    'capabilities.services[0].port "order.costs.v1" must be "order.services.v1"');
  refused(manifest(3, '"services": [ { "port": "order.services.v1" }, { "port": "order.services.v1" } ]'),
    'capabilities.services[1].port "order.services.v1" is declared twice');
  refused(manifest(3, '"services": [ { "port": "order.services.v1", "write": true } ]'), '"write" is not a key of a service');
});

test('the step surface needs the key of its step', () => {
  refused(manifest(3, '"surfaces": [ { "slug": "orderflow.step", "kind": "react" } ]'),
    'capabilities.surfaces[0] on "orderflow.step" needs a "key": the key of the step it renders');
  refused(manifest(3, '"surfaces": [ { "slug": "orderflow.step", "kind": "react", "key": "Bad Key" } ]'),
    'capabilities.surfaces[0].key "Bad Key" must match');
});

test('a flow event takes no onFailure', () => {
  refused(manifest(3, '"hooks": [ { "point": "workflow.flow-step-completed", "onFailure": "allow" } ]'),
    'capabilities.hooks[0].onFailure is not allowed on the flow event "workflow.flow-step-completed": it runs after the step and cannot block it');
});

test('the order hooks are ordinary points', () => {
  const hooks = manifest(2, '"hooks": [ { "point": "order.before.update", "onFailure": "block" }, { "point": "order.field.changed" } ]');
  assert.deepEqual(errors(hooks), []);
  // capabilities.flows is a capability of protocol 2.1 (manifest-flows.test.mjs), no longer unknown.
  assert.deepEqual(errors(manifest(3, '"flows": []')), []);
});

test('the registration names a declared step', () => {
  const json = manifest(3, STEP);
  const declared = { slug: 'orderflow.step', kind: 'react', key: 'item-check' };
  const other = { ...declared, key: 'another-step' };
  const keyless = { slug: 'orderflow.step', kind: 'react' };
  assert.deepEqual([...ManifestCheck.check(json, { hooks: [], fields: [], surfaces: [declared] }, []).errors], []);
  const e = [...ManifestCheck.check(json, { hooks: [], fields: [], surfaces: [other, keyless] }, []).errors];
  assert.ok(e.some((x) => x.includes('the step "another-step" of "orderflow.step" is not declared')), JSON.stringify(e));
  assert.ok(e.some((x) => x.includes('registration: surfaces[1] on "orderflow.step" needs a "key"')), JSON.stringify(e));
});

test('a flow rule path with a {param} segment is refused with the platform\'s text', () => {
  const json = `{ "schemaVersion": 3, "slug": "kf-step", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "Step" } },
    "capabilities": { "routes": [ { "method": "GET", "path": "/m/kf-step/rules/{id}", "right": "api.v1.m.kf-step.rules.id.get" } ],
      "flowRules": [ { "key": "item-ready", "path": "/m/kf-step/rules/{id}", "projection": "order.flow-rule.v1" } ] } }`;
  refused(json, 'kf-module-conf.json: capabilities.flowRules[0].path "/m/kf-step/rules/{id}" must be a route with '
    + 'literal segments only (no {param} segment)');
});
