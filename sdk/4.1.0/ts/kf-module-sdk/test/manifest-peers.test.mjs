// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestPeersTest.java. Do not edit by hand.
//
// Protocol 2.0: the declarations between modules of sdk-protocol.md §3.2, §15.9, §6.3 -- peerReads,
// commands, events (the owner) and peers (the caller) -- read by Manifest and checked by ManifestCheck with the
// platform's rules and messages (ModuleManifestValidator).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

const ROUTES = `"routes": [ { "method": "GET", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.get" },
                        { "method": "POST", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.post" } ]`;

function owner(schemaVersion, capabilities) {
  return `{ "schemaVersion": ${schemaVersion}, "slug": "kf-qc", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "Quality" } },
    "capabilities": { ${ROUTES}, ${capabilities} } }`;
}

function caller(requires, peers) {
  return `{ "schemaVersion": 3, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "3D" } }, "requires": ${requires},
    "capabilities": { "peers": ${peers} } }`;
}

const REQUIRES_QC = '[ { "slug": "kf-qc", "versionRange": ">=1.0.0" } ]';

function errors(json) {
  return [...ManifestCheck.validate(json).errors];
}

function refused(json, fragment) {
  const e = errors(json);
  assert.ok(e.some((x) => x.includes(fragment)), `an error must contain <${fragment}>: ${JSON.stringify(e)}`);
}

test('an owner and a caller are valid and read as declared', () => {
  const json = owner(3, `
    "peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/peer/checks", "visibility": "public" } ],
    "commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks", "method": "POST", "visibility": "internal" } ],
    "events": [ { "name": "check-closed", "visibility": "public" } ]`);
  assert.deepEqual(errors(json), []);
  const manifest = Manifest.parse(json);
  assert.equal(manifest.capabilities.peerReads[0].port, 'm.kf-qc.checks.v1');
  assert.equal(manifest.capabilities.commands[0].visibility, 'internal');
  assert.equal(manifest.capabilities.events[0].name, 'check-closed');

  const use = caller(REQUIRES_QC, '[ { "owner": "kf-qc", "reads": ["m.kf-qc.checks.v1"], "commands": ["open-check"] } ]');
  assert.deepEqual(errors(use), []);
  assert.deepEqual([...Manifest.parse(use).capabilities.peers[0].commands], ['open-check']);
});

test('under schema 2 every key is refused', () => {
  refused(owner(2, '"events": [ { "name": "check-closed", "visibility": "public" } ]'),
    'capabilities.events requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
});

test('the grammars, the routes, the duplicates and the visibility', () => {
  refused(owner(3, '"peerReads": [ { "port": "m.kf-3d.checks.v1", "path": "/m/kf-qc/peer/checks", "visibility": "public" } ]'),
    'capabilities.peerReads[0].port "m.kf-3d.checks.v1" must be m.kf-qc.{name}.v{N}');
  refused(owner(3, '"peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/nope", "visibility": "public" } ]'),
    'is not a GET route this module declares');
  refused(owner(3, '"commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks", "method": "PUT", "visibility": "public" } ]'),
    'is not a PUT route this module declares');
  refused(owner(3, '"commands": [ { "name": "x", "path": "/m/kf-qc/peer/checks", "method": "PATCH", "visibility": "public" } ]'),
    'capabilities.commands[0].method "PATCH" must be one of POST, PUT, DELETE');
  refused(owner(3, '"events": [ { "name": "a-b", "visibility": "public" }, { "name": "a-b", "visibility": "public" } ]'),
    'capabilities.events[1].name "a-b" is declared twice');
  refused(owner(3, '"events": [ { "name": "a-b" } ]'), 'capabilities.events[0].visibility null must be one of internal, public');
  refused(owner(3, '"events": [ { "name": "a-b", "visibility": "public", "path": "/x" } ]'), '"path" is not a key of an event');
});

test('a peer must be required, not itself, once, and name something', () => {
  refused(caller('[]', '[ { "owner": "kf-qc", "commands": ["open-check"] } ]'), 'capabilities.peers[0].owner "kf-qc" is not in requires');
  refused(caller(REQUIRES_QC, '[ { "owner": "kf-3d", "commands": ["x-y"] } ]'), 'is this module itself');
  refused(caller(REQUIRES_QC, '[ { "owner": "kf-qc", "commands": ["a-b"] }, { "owner": "kf-qc", "commands": ["c-d"] } ]'),
    'capabilities.peers[1].owner "kf-qc" is declared twice');
  refused(caller(REQUIRES_QC, '[ { "owner": "kf-qc" } ]'), 'must name at least one of reads, commands');
  refused(caller(REQUIRES_QC, '[ { "owner": "kf-qc", "reads": ["m.kf-3d.x.v1"] } ]'), 'capabilities.peers[0].reads[0]');
  const optional = '[ { "slug": "kf-qc", "versionRange": ">=1.0.0", "optional": true } ]';
  assert.deepEqual(errors(caller(optional, '[ { "owner": "kf-qc", "reads": ["m.kf-qc.checks.v1"] } ]')), []);
});

// ---- (an event of a module as a hook) (a route with {param}) ------------------------------

function hooker(schemaVersion, requires, hook) {
  return `{ "schemaVersion": ${schemaVersion}, "slug": "kf-3d", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "3D" } }, "requires": ${requires},
    "capabilities": { "hooks": [ ${hook} ] } }`;
}

const REQUIRES_STOCK = '[ { "slug": "kf-stock", "versionRange": ">=1.0.0" } ]';

test('an event of a module as a hook is checked like the platform does', () => {
  const point = '{ "point": "m.kf-stock.after.level-changed" }';
  assert.deepEqual(errors(hooker(3, REQUIRES_STOCK, point)), []);
  refused(hooker(2, REQUIRES_STOCK, point), 'capabilities.hooks[0].point "m.kf-stock.after.level-changed": '
    + 'an event of a module requires "schemaVersion": 3');
  refused(hooker(3, REQUIRES_STOCK, '{ "point": "m.kf-stock.after" }'),
    'capabilities.hooks[0].point "m.kf-stock.after" is not an event of a module (m.{owner}.after.{name})');
  refused(hooker(3, '[]', point), 'capabilities.hooks[0].point "m.kf-stock.after.level-changed" is an event of '
    + 'kf-stock, which this manifest does not require (requires, mandatory or optional)');
  const blocking = '{ "point": "m.kf-stock.after.level-changed", "onFailure": "block" }';
  refused(hooker(3, REQUIRES_STOCK, blocking),
    'capabilities.hooks[0].onFailure is not allowed on an event of a module: it runs after the fact');
  assert.ok(errors(hooker(3, REQUIRES_STOCK, blocking)).every((e) => !e.includes('only on before points')));
  refused(hooker(3, REQUIRES_STOCK, `${point}, ${point}`),
    'capabilities.hooks[1].point "m.kf-stock.after.level-changed" is declared twice');
});

test('a peer path with a {param} segment is refused with the platform\'s text', () => {
  const routes = `"routes": [ { "method": "GET", "path": "/m/kf-qc/peer/checks/{id}", "right": "api.v1.m.kf-qc.peer.checks.id.get" },
                { "method": "POST", "path": "/m/kf-qc/peer/checks/{id}", "right": "api.v1.m.kf-qc.peer.checks.id.post" } ]`;
  const json = (caps) => `{ "schemaVersion": 3, "slug": "kf-qc", "name": "module.name", "version": "1.0.0", "platform": ">=4.1",
    "i18n": { "en": { "module.name": "Quality" } },
    "capabilities": { ${routes}, ${caps} } }`;
  refused(json('"peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/peer/checks/{id}", "visibility": "public" } ]'),
    'kf-module-conf.json: capabilities.peerReads[0].path "/m/kf-qc/peer/checks/{id}" must be a route with literal '
    + 'segments only (no {param} segment)');
  refused(json('"commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks/{id}", "method": "POST", "visibility": "public" } ]'),
    'kf-module-conf.json: capabilities.commands[0].path "/m/kf-qc/peer/checks/{id}" must be a route with literal '
    + 'segments only (no {param} segment)');
});
