// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestHelpTest.java. Do not edit by hand.
//
// The capability help of kf-module-conf.json (protocol 2.2, additive, sdk-protocol.md §7.13): the module's help
// notes, each a link to a module.pages page or a setup.sections section the same manifest declares. Known only under
// schemaVersion 3. The SDK reads them on the typed manifest and refuses locally, with the texts of §7.13 (the
// platform's validator uses the same), everything but whether a platform code named in right is declared.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Manifest, ManifestCheck } from '../dist/index.js';

const SURFACES = `"routes":[{"method":"GET","path":"/m/help-mod/shells","right":"api.v1.m.help-mod.shells.get"}],
"surfaces":[{"slug":"module.pages","kind":"react","page":"utils-help"},
            {"slug":"module.pages","kind":"react","page":"utils-shells"},
            {"slug":"module.pages","kind":"react","page":"mapping"},
            {"slug":"setup.sections","kind":"react","page":"platforms"}]`;

function manifest(schemaVersion, help) {
  return `{"schemaVersion":${schemaVersion},"slug":"help-mod","version":"1.0.0","platform":">=4.1","name":"module.name",
 "i18n":{"en":{"module.name":"Help module","page.utils-help":"AAS help",
               "help.aas-overview.summary":"What the AAS pages do","help.platforms":"Platforms"}},
 "capabilities":{${SURFACES}${help == null ? '' : `,"help":${help}`}}}`;
}

const NOTE = `{"key":"aas-overview","title":"page.utils-help","summary":"help.aas-overview.summary","page":"utils-help",
 "context":["utils-shells","mapping"],"order":10}`;

const P = 'kf-module-conf.json: capabilities.help[0]';

function errors(help) {
  return ManifestCheck.validate(manifest(3, help)).errors;
}

function refused(help, expected) {
  const found = errors(help);
  assert.ok(found.length > 0, help);
  assert.ok(found.some((e) => e.includes(expected)), `${expected} not in ${JSON.stringify(found)}`);
}

function note(extra) {
  return `{"key":"aas-overview","title":"page.utils-help"${extra}}`;
}

test('a valid page note and a valid section note are read in the declared order, order 100 by default', () => {
  const json = manifest(3, `[${NOTE},{"key":"platforms","title":"help.platforms","section":"platforms"}]`);
  const result = ManifestCheck.validate(json);
  assert.ok(result.ok(), JSON.stringify(result.errors));
  const help = Manifest.parse(json).capabilities.help;
  assert.equal(help.length, 2);
  const n = help[0];
  assert.equal(n.key, 'aas-overview');
  assert.equal(n.title, 'page.utils-help');
  assert.equal(n.summary, 'help.aas-overview.summary');
  assert.equal(n.page, 'utils-help');
  assert.equal(n.section, null);
  assert.deepEqual([...n.context], ['utils-shells', 'mapping']);
  assert.equal(n.right, null);
  assert.equal(n.order, 10);
  const section = help[1];
  assert.equal(section.section, 'platforms');
  assert.equal(section.page, null);
  assert.equal(section.summary, null);
  assert.equal(section.context.length, 0);
  assert.equal(section.order, Manifest.HELP_DEFAULT_ORDER);
});

test('help absent reads as []', () => {
  assert.ok(ManifestCheck.validate(manifest(3, null)).ok());
  assert.equal(Manifest.parse(manifest(3, null)).capabilities.help.length, 0);
});

test('help under schemaVersion 2 is refused with the schema guard of §7.13', () => {
  const found = ManifestCheck.validate(manifest(2, `[${NOTE}]`)).errors;
  assert.ok(found.includes('kf-module-conf.json: capabilities.help requires "schemaVersion": 3 '
    + '(this manifest is schemaVersion 2)'), JSON.stringify(found));
});

test('a page that is not declared on module.pages is refused (a setup section is not a page)', () => {
  refused(`[${note(',"page":"nowhere"')}]`, `${P}.page "nowhere" is not a page this module `
    + 'declares on module.pages (capabilities.surfaces[].page)');
  refused(`[${note(',"page":"platforms"')}]`, `${P}.page "platforms" is not a page`);
});

test('a section that is not declared on setup.sections is refused (a module page is not a section)', () => {
  refused(`[${note(',"section":"utils-help"')}]`, `${P}.section "utils-help" is not a section `
    + 'this module declares on setup.sections (capabilities.surfaces[].page)');
});

test('no target, or both targets, is refused', () => {
  const t = `${P} must declare exactly one target: "page" (a module.pages page) or "section" `
    + '(a setup.sections section)';
  refused(`[${note('')}]`, t);
  refused(`[${note(',"page":"utils-help","section":"platforms"')}]`, t);
});

test('title and summary must be keys of i18n.en; title is required', () => {
  refused('[{"key":"a","title":"help.missing","page":"utils-help"}]',
    `${P}.title "help.missing" is not defined in i18n.en`);
  refused(`[${note(',"page":"utils-help","summary":"help.missing"')}]`,
    `${P}.summary "help.missing" is not defined in i18n.en`);
  refused('[{"key":"a","page":"utils-help"}]',
    `${P}.title is required: a translation key defined in i18n.en`);
  refused(`[${note(',"page":"utils-help","summary":"Not A Key"')}]`,
    `${P}.summary "Not A Key" is not a translation key`);
});

test('a repeated key, or one outside the grammar, is refused', () => {
  const one = note(',"page":"utils-help"');
  refused(`[${one},${one}]`,
    'kf-module-conf.json: capabilities.help[1].key "aas-overview" is declared twice');
  refused('[{"key":"AasOverview","title":"page.utils-help","page":"utils-help"}]',
    `${P}.key must match ^[a-z][a-z0-9-]{0,40}$ (got "AasOverview")`);
  refused('[{"title":"page.utils-help","page":"utils-help"}]',
    `${P}.key must match ^[a-z][a-z0-9-]{0,40}$ (got null)`);
});

test('a context page that is not declared, repeated, or a context that is not an array is refused', () => {
  refused(`[${note(',"page":"utils-help","context":["utils-shells","nowhere"]')}]`,
    `${P}.context[1] "nowhere" is not a page this module declares on module.pages `
      + '(capabilities.surfaces[].page)');
  refused(`[${note(',"page":"utils-help","context":["mapping","mapping"]')}]`,
    `${P}.context[1] "mapping" is listed twice`);
  refused(`[${note(',"page":"utils-help","context":"mapping"')}]`,
    `${P}.context must be an array of module.pages pages (got "mapping")`);
});

test('url and body are not keys of a help note (a closed object)', () => {
  refused(`[${note(',"page":"utils-help","url":"https://wiki.example/aas"')}]`,
    `${P}: "url" is not a key of a help note (known: key, title, summary, page, section, context, right, order)`);
  refused(`[${note(',"page":"utils-help","body":"help.body"')}]`,
    `${P}: "body" is not a key of a help note`);
  refused('["aas-overview"]', `${P} must be an object`);
});

test("more than 20 notes, an order outside 0-10000, a right that is not an own route's are refused", () => {
  const many = [];
  for (let i = 0; i < 21; i++) many.push(`{"key":"n${i}","title":"page.utils-help","page":"utils-help"}`);
  refused(`[${many.join(',')}]`, 'kf-module-conf.json: capabilities.help holds at most 20 notes (got 21)');
  refused(`[${note(',"page":"utils-help","order":10001')}]`,
    `${P}.order must be an integer from 0 to 10000 (got 10001)`);
  refused(`[${note(',"page":"utils-help","order":-1')}]`,
    `${P}.order must be an integer from 0 to 10000 (got -1)`);
  refused(`[${note(',"page":"utils-help","right":"api.v1.m.help-mod.secret.get"')}]`,
    `${P}.right "api.v1.m.help-mod.secret.get" is not the right of a route this module declares `
      + '(capabilities.routes[].right of api.v1.m.help-mod.*)');
  refused(`[${note(',"page":"utils-help","right":"Not a right"')}]`,
    `${P}.right "Not a right" is not a right code: the right of one of the module's routes or a code `
      + 'the platform declares');
  assert.equal(errors(`[${note(',"page":"utils-help","right":"api.v1.customers.get","order":0')}]`).length, 0,
    "a platform code is the platform's check at upload");
});
