// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestNavTest.java. Do not edit by hand.
//
// The capability nav of kf-module-conf.json (protocol 1.1, additive): the module's menu
// entries, on the staff sidebar, the customer portal menu or the platform menu. The SDK reads them on the typed
// manifest and refuses locally what it can see without the platform's catalogs: the shape, the keys, the url, the
// label key, a right that is not one of the module's own routes, a right on the customer surface, an undeclared flag.
// The groups of a surface and the platform's codes stay the platform's check at upload.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

const ROUTES = '"routes":[{"method":"GET","path":"/m/nav-mod/events","right":"api.v1.m.nav-mod.events.get"}]';

function manifest(nav) {
  return `{"schemaVersion":2,"slug":"nav-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
 "featureFlags":[{"key":"beta-menu","label":"flag.beta","default":false}],
 "i18n":{"en":{"module.name":"Nav module","nav.events":"Events","flag.beta":"Beta menu"}},
 "capabilities":{${ROUTES}${nav == null ? '' : `,"nav":${nav}`}}}`;
}

function entry(surface, extra) {
  return `{"surface":"${surface}","group":"admin-utils","key":"events","label":"nav.events","url":"/modules"${extra}}`;
}

function errors(nav) {
  return ManifestCheck.validate(manifest(nav)).errors;
}

function refused(nav, expected) {
  const found = errors(nav);
  assert.ok(found.length > 0, nav);
  assert.ok(found.some((e) => e.includes(expected)), `${expected} not in ${JSON.stringify(found)}`);
}

test("a valid staff entry with the right of one of the module's routes is read and accepted", () => {
  const json = manifest(`[${entry('staff', ',"icon":"Boxes","right":"api.v1.m.nav-mod.events.get","order":20,"flag":"beta-menu"')}]`);
  const result = ManifestCheck.validate(json);
  assert.ok(result.ok(), JSON.stringify(result.errors));
  const nav = Manifest.parse(json).capabilities.nav[0];
  assert.equal(nav.surface, 'staff');
  assert.equal(nav.group, 'admin-utils');
  assert.equal(nav.key, 'events');
  assert.equal(nav.label, 'nav.events');
  assert.equal(nav.url, '/modules');
  assert.equal(nav.icon, 'Boxes');
  assert.equal(nav.right, 'api.v1.m.nav-mod.events.get');
  assert.equal(nav.order, 20);
  assert.equal(nav.flag, 'beta-menu');
});

test("a platform code as right is accepted locally (whether it is declared is the platform's check)", () => {
  assert.equal(errors(`[${entry('staff', ',"right":"api.v1.customers.get"')}]`).length, 0);
});

test('nav absent reads as [], and the optional keys read as their defaults', () => {
  assert.ok(ManifestCheck.validate(manifest(null)).ok());
  assert.equal(Manifest.parse(manifest(null)).capabilities.nav.length, 0);
  const nav = Manifest.parse(manifest(`[${entry('platform', '')}]`)).capabilities.nav[0];
  assert.equal(nav.icon, null);
  assert.equal(nav.right, null);
  assert.equal(nav.flag, null);
  assert.equal(nav.order, 100);
  assert.ok(Manifest.CAPABILITY_KEYS.includes('nav'));
});

test('an unknown surface is refused, naming the three surfaces', () => {
  refused(`[${entry('sidebar', '')}]`,
    'capabilities.nav[0].surface must be one of staff, customer, platform (got "sidebar")');
});

test('the same key twice on one surface is refused; the same key on two surfaces is legal', () => {
  refused(`[${entry('staff', '')},${entry('staff', '')}]`,
    'capabilities.nav[1].key "events" is declared twice on the surface staff');
  assert.equal(errors(`[${entry('staff', '')},${entry('platform', '')}]`).length, 0);
});

test('a url that is not an in-app path is refused: //, .., a scheme, a query, %', () => {
  for (const url of ['//evil.example/x', '/a/../setup', 'https://evil.example', '/a?x=1', '/a%2e', 'modules']) {
    refused(`[{"surface":"staff","group":"admin-utils","key":"events","label":"nav.events","url":"${url}"}]`,
      'capabilities.nav[0].url must be an in-app path');
  }
});

test('a label that is not a key of i18n.en is refused', () => {
  refused('[{"surface":"staff","group":"admin-utils","key":"events","label":"nav.missing","url":"/modules"}]',
    'capabilities.nav[0].label "nav.missing" is not defined in i18n.en');
});

test('a right on the customer surface is refused: the customer menu is filtered on identity', () => {
  refused(`[${entry('customer', ',"right":"api.v1.m.nav-mod.events.get"')}]`,
    'capabilities.nav[0].right is not allowed on the customer surface');
});

test("a module right that is not one of the module's routes is refused", () => {
  refused(`[${entry('staff', ',"right":"api.v1.m.nav-mod.secret.get"')}]`,
    'capabilities.nav[0].right "api.v1.m.nav-mod.secret.get" is not the right of a route this module declares');
  refused(`[${entry('staff', ',"right":"api.v1.m.other-mod.events.get"')}]`,
    'capabilities.nav[0].right "api.v1.m.other-mod.events.get" is not the right of a route this module declares');
});

test('an undeclared flag, an order out of range, a bad group or key, an unknown key are refused', () => {
  refused(`[${entry('staff', ',"flag":"nope"')}]`,
    'capabilities.nav[0].flag "nope" is not a feature flag of this module');
  refused(`[${entry('staff', ',"order":10001')}]`,
    'capabilities.nav[0].order must be an integer from 0 to 10000');
  refused(`[${entry('staff', ',"icon":"<svg>"')}]`, 'capabilities.nav[0].icon must be');
  refused('[{"surface":"staff","group":"Admin Utils","key":"events","label":"nav.events","url":"/modules"}]',
    'capabilities.nav[0].group must match');
  refused('[{"surface":"staff","group":"admin-utils","key":"m.events","label":"nav.events","url":"/modules"}]',
    'capabilities.nav[0].key must match');
  refused(`[${entry('staff', ',"target":"_blank"')}]`,
    'capabilities.nav[0]: "target" is not a key of a nav entry');
  refused('{}', 'capabilities.nav must be an array');
});
