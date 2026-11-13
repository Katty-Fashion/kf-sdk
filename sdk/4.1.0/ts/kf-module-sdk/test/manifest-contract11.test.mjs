// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestContract11Test.java. Do not edit by hand.
//
// The keys added to kf-module-conf.json in protocol 1.1 (additive, no new schemaVersion):
// settings (closed scalars only: the rule "settings carry no data"), routes[].admin, secrets (by reference), provides,
// surfaces[].page, and the grammar of a port id (one optional sub-segment). The SDK reads them on the typed
// manifest and refuses locally what it can see without the platform's catalogs (the published and provided ports, the
// surface types: the platform's check at upload). A 1.0 manifest without the new keys stays
// accepted unchanged.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

const BASE_ROUTES = `
{"method":"GET","path":"/m/c11/stations","right":"api.v1.m.c11.stations.get"},
{"method":"GET","path":"/m/c11/admin/config","right":"api.v1.m.c11.admin.config.get","admin":"tenant"},
{"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform"},
{"method":"PUT","path":"/m/c11/admin/tenant-keys","right":"api.v1.m.c11.admin.tenant-keys.put","admin":"tenant"}`;

const I18N = `"i18n":{"en":{"module.name":"Contract 1.1","setting.push":"Push on save","setting.timeout":"Timeout",
 "setting.mode":"Mode","setting.mode.help":"How the module pushes","tab.label":"AAS"}}`;

function manifestWith(routes, extra) {
  return `{"schemaVersion":2,"slug":"c11","version":"1.0.0","platform":">=4.1","name":"module.name",${I18N},
 "capabilities":{"routes":[${routes}]${extra == null ? '' : `,${extra}`}}}`;
}

function manifest(extra) {
  return manifestWith(BASE_ROUTES, extra);
}

const VALID_SETTINGS = `"settings":[
  {"key":"push-on-save","type":"BOOLEAN","default":false,"label":"setting.push"},
  {"key":"timeout-seconds","type":"NUMBER","default":30,"min":5,"max":600,"label":"setting.timeout"},
  {"key":"mode","type":"ENUM","default":"auto","values":["auto","manual"],"label":"setting.mode",
   "description":"setting.mode.help"}]`;

const VALID_SECRETS = `"secrets":[
  {"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put"},
  {"name":"tenant-token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]`;

const VALID_PROVIDES = '"provides":[{"port":"factory.stations.v1","path":"/m/c11/stations"}]';

const VALID_SURFACES = '"surfaces":[{"slug":"module.page","kind":"react","label":"tab.label","page":"shells"}]';

const VALID_READS = '"reads":[{"port":"model.v1"},{"port":"file.public-url.v1"}]';

function errors(json) {
  return ManifestCheck.validate(json).errors;
}

function refused(json, expected) {
  const found = errors(json);
  assert.ok(found.length > 0, json);
  assert.ok(found.some((e) => e.includes(expected)), `${expected} not in ${JSON.stringify(found)}`);
}

test('a manifest that uses every 1.1 key of correctly is accepted and read', () => {
  const json = manifest([VALID_SETTINGS, VALID_SECRETS, VALID_PROVIDES, VALID_SURFACES, VALID_READS].join(','));
  const result = ManifestCheck.validate(json);
  assert.ok(result.ok(), JSON.stringify(result.errors));

  const caps = Manifest.parse(json).capabilities;
  assert.equal(caps.settings.length, 3);
  const timeout = caps.settings[1];
  assert.equal(timeout.key, 'timeout-seconds');
  assert.equal(timeout.type, 'NUMBER');
  assert.equal(timeout.defaultValue, 30);
  assert.equal(timeout.min, 5);
  assert.equal(timeout.max, 600);
  const mode = caps.settings[2];
  assert.deepEqual([...mode.values], ['auto', 'manual']);
  assert.equal(mode.description, 'setting.mode.help');
  assert.equal(caps.settings[0].defaultValue, false);

  assert.deepEqual({ ...caps.secrets[0], external: [...caps.secrets[0].external] },
    { name: 'r3-client-secret', scope: 'platform', writeRight: 'api.v1.m.c11.admin.keys.put', external: [] });
  assert.deepEqual({ ...caps.provides[0] }, { port: 'factory.stations.v1', path: '/m/c11/stations' });
  assert.equal(caps.surfaces[0].page, 'shells');
  assert.equal(caps.routes[0].admin, null);
  assert.equal(caps.routes[1].admin, 'tenant');
  assert.equal(caps.routes[2].admin, 'platform');
});

test('a 1.0 manifest (none of the new keys) is accepted unchanged; the new lists read as []', () => {
  const json = manifestWith(
    '{"method":"POST","path":"/m/c11/notes","right":"api.v1.m.c11.notes.post","roles":["QC"],"hookable":true}',
    '"surfaces":[{"slug":"models.detail.tabs","kind":"react","label":"tab.label"}]');
  assert.ok(ManifestCheck.validate(json).ok(), JSON.stringify(errors(json)));
  const caps = Manifest.parse(json).capabilities;
  assert.equal(caps.settings.length, 0);
  assert.equal(caps.secrets.length, 0);
  assert.equal(caps.provides.length, 0);
  assert.equal(caps.surfaces[0].page, null);
  assert.equal(caps.routes[0].admin, null);
});

// ---- settings ---------------------------------------------------------------------------------------------

test('a STRING setting is refused: settings carry no data', () => {
  refused(manifest('"settings":[{"key":"base-url","type":"STRING","default":"https://x","label":"setting.push"}]'),
    'settings carry no data: BOOLEAN, NUMBER or ENUM');
});

test('an ENUM setting without values is refused', () => {
  refused(manifest('"settings":[{"key":"mode","type":"ENUM","default":"auto","label":"setting.mode"}]'),
    '.values must be a non-empty list');
});

test('an ENUM default outside its values is refused', () => {
  refused(manifest('"settings":[{"key":"mode","type":"ENUM","default":"other","values":["auto"],"label":"setting.mode"}]'),
    '.default must be one of the values');
});

test('a NUMBER default outside min..max is refused', () => {
  refused(manifest('"settings":[{"key":"timeout","type":"NUMBER","default":1,"min":5,"max":10,"label":"setting.timeout"}]'),
    '.default must be between min and max');
});

test('a setting key declared twice is refused', () => {
  refused(manifest(`"settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push"},
                {"key":"push","type":"BOOLEAN","default":false,"label":"setting.push"}]`),
  '.key "push" is declared twice');
});

test('a setting with a key outside the closed object (e.g. a value list) is refused', () => {
  refused(manifest('"settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push","items":[1]}]'),
    '"items" is not a key of a setting');
});

test('more than 30 settings are refused', () => {
  const settings = [];
  for (let i = 0; i < 31; i++) {
    settings.push(`{"key":"s${i}x","type":"BOOLEAN","default":true,"label":"setting.push"}`);
  }
  refused(manifest(`"settings":[${settings.join(',')}]`), 'at most 30 settings');
});

// ---- routes[].admin -----------------------------------------------------------------------------------------

test('admin platform with roles is refused', () => {
  refused(manifestWith(
    '{"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform","roles":["QC"]}',
    null), '.admin "platform" declares no roles');
});

test('admin on a hookable route is refused', () => {
  refused(manifestWith(
    '{"method":"POST","path":"/m/c11/admin/run","right":"api.v1.m.c11.admin.run.post","admin":"tenant","hookable":true}',
    null), 'admin and hookable exclude each other');
});

test('an unknown admin value is refused', () => {
  refused(manifestWith('{"method":"GET","path":"/m/c11/admin/x","right":"api.v1.m.c11.admin.x.get","admin":"super"}', null),
    '.admin must be one of tenant, platform');
});

// ---- secrets ----------------------------------------------------------------------------------------------

test('a secret whose writeRight is not the right of a declared route is refused', () => {
  refused(manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.nowhere.put"}]'),
    '.writeRight "api.v1.m.c11.nowhere.put" is not the right of a route this module declares');
});

test('a platform secret whose writeRight is the right of a route without admin platform is refused', () => {
  refused(manifest('"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]'),
    'a platform secret is written only through a route with "admin": "platform"');
});

test('a secret that carries a value is refused (a closed object: secrets travel by reference)', () => {
  refused(manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put","value":"x"}]'),
    '"value" is not a key of a secret');
});

test('a secret name declared twice and an unknown scope are refused', () => {
  const json = manifest(`"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"},
               {"name":"token","scope":"global","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]`);
  refused(json, '.name "token" is declared twice');
  refused(json, '.scope must be one of platform, tenant');
});

// ---- provides ---------------------------------------------------------------------------------------------

test('provides on a path that is not a declared GET route is refused', () => {
  refused(manifest('"provides":[{"port":"factory.stations.v1","path":"/m/c11/admin/keys"}]'),
    '.path "/m/c11/admin/keys" is not a GET route this module declares');
});

test('provides with a malformed port id is refused', () => {
  refused(manifest('"provides":[{"port":"Factory.Stations","path":"/m/c11/stations"}]'),
    '.port "Factory.Stations" must match');
});

// ---- surfaces[].page and reads[].port -----------------------------------------------------------------------

test('a page with upper case letters is refused', () => {
  refused(manifest('"surfaces":[{"slug":"module.page","kind":"react","page":"Shells"}]'), '.page "Shells" must match');
});

test('the port grammar takes one optional sub-segment, not two', () => {
  assert.equal(errors(manifest('"reads":[{"port":"file.public-url.v1"}]')).length, 0);
  refused(manifest('"reads":[{"port":"a.b.c.v1"}]'), '.port "a.b.c.v1" must match');
  refused(manifest('"reads":[{"port":"model"}]'), '.port "model" must match');
});

// ---- secrets[].external -----------------------------------------------------------

test('a platform secret with external prefixes is accepted', () => {
  const json = manifest(`"secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",
               "external":["platform/aas/","kv/r3-clients/"]}]`);
  assert.deepEqual(errors(json), []);
});

test('external on a tenant secret, empty, outside the grammar, into kf-modules or twice is refused', () => {
  refused(manifest(`"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put",
               "external":["platform/aas/"]}]`),
    '.external is allowed only on a platform secret');
  refused(manifest('"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put","external":[]}]'),
    '.external must be a non-empty array of at most 5 prefixes');
  const json = manifest(`"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",
               "external":["platform/","platform/aas","platform/kf-modules/x/","platform/aas/","platform/aas/"]}]`);
  refused(json, '.external[0] must match ^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$ (got "platform/")');
  refused(json, '.external[1] must match');
  refused(json, '.external[2] "platform/kf-modules/x/" must not reach the modules\' secrets (kf-modules)');
  refused(json, '.external[4] "platform/aas/" is declared twice');
});

// ---- routes[].timeoutSeconds (SDK 0.7.3) ------------------------------------------

function timeoutRoute(value) {
  return `${BASE_ROUTES}
,{"method":"GET","path":"/m/c11/shells","right":"api.v1.m.c11.shells.get","admin":"tenant","timeoutSeconds":${value}}`;
}

test('timeoutSeconds between 1 and the cap is read on the route; absent or null reads null (the platform\'s default)', () => {
  const json = manifestWith(timeoutRoute('60'));
  assert.deepEqual(errors(json), []);
  const caps = Manifest.parse(json).capabilities;
  assert.equal(caps.routes[4].timeoutSeconds, 60);
  assert.equal(caps.routes[0].timeoutSeconds, null);
  assert.equal(Manifest.MAX_ROUTE_TIMEOUT_SECONDS, 60);
  const nullJson = manifestWith(timeoutRoute('null'));
  assert.deepEqual(errors(nullJson), []);
  assert.equal(Manifest.parse(nullJson).capabilities.routes[4].timeoutSeconds, null);
  assert.ok(ManifestCheck.validate(manifestWith(timeoutRoute('1'))).ok());
});

test('timeoutSeconds over the cap, zero, negative, fractional or not a number is refused, naming the cap', () => {
  refused(manifestWith(timeoutRoute('61')),
    '(GET /m/c11/shells).timeoutSeconds must be a whole number of seconds between 1 and 60 (got 61)');
  for (const value of ['0', '-1', '1.5', '"15"', 'true']) {
    refused(manifestWith(timeoutRoute(value)), '.timeoutSeconds must be a whole number of seconds between 1 and 60');
  }
});
