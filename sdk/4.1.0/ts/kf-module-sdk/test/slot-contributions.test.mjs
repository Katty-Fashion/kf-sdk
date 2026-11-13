// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SlotContributionsTest.java. Do not edit by hand.
//
// (protocol 2.0, sdk-protocol.md §7.12): the contributions of a module to the slots of
// other modules — the kinds without code (Surface.layer, Surface.data), the warnings of the registration
// (registrationWarnings()), and the local check of the contributor (ManifestCheck). The Java test drives the cases
// through FakePlatform (Java test code, not derived); here the platform's answer is scripted with the same texts.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { KfModule, ManifestCheck, Surface } from '../dist/index.js';

const LAYERS = 'm.kf-3d.viewport.layers.v1';
const INSPECTOR = 'm.kf-3d.studio.inspector.v1';
const MISSING = 'm.kf-3d.studio.missing.v1';

const d19 = (i, slug) => `surfaces[${i}].slug: "${slug}" is not a slot the installed version of its owner declares; `
  + 'the contribution is stored inactive and is not served until the owner declares it';

test('layer and data are sent without code', () => {
  const kf = KfModule.of('http://127.0.0.1:1', 'slot-contrib', 'kfm_x', 'secret', null);
  kf.ui().contribute(LAYERS, Surface.layer('pin', '/m/slot-contrib/api/pins').key('pins'))
    .contribute('m.kf-3d.materials.v1', Surface.data({ name: 'wool' }).key('wool'));

  const surfaces = kf.registrationBody().surfaces;
  assert.deepStrictEqual(surfaces[0], { slug: LAYERS, kind: 'layer', layer: 'pin', source: '/m/slot-contrib/api/pins', key: 'pins' });
  assert.deepStrictEqual(surfaces[1], { slug: 'm.kf-3d.materials.v1', kind: 'data', data: { name: 'wool' }, key: 'wool' });
  assert.equal(Object.hasOwn(surfaces[0], 'entry'), false);
});

test('data too large or missing is refused locally', () => {
  assert.throws(() => Surface.data({ blob: 'x'.repeat(Surface.MAX_DATA_BYTES) }), /at most 65536 bytes/);
  assert.throws(() => Surface.data(null));
  assert.throws(() => Surface.layer('Pin', '/m/x/api/pins'));
  assert.throws(() => Surface.layer('pin', 'https://elsewhere/pins'));
});

/** A platform that answers the registration with `warnings` (and `[]` to the flags read after it). */
async function platform(warnings) {
  const server = createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      const body = req.url.split('?')[0].endsWith('/registrations') ? JSON.stringify({ warnings }) : '[]';
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return server;
}

async function registered(kf) {
  const until = Date.now() + 10_000;
  while (!kf.isRegistered() && Date.now() < until) await new Promise((r) => setTimeout(r, 20));
  assert.ok(kf.isRegistered());
}

test(': the warnings of the registration are exposed, and internal is like missing', async () => {
  const server = await platform([d19(1, INSPECTOR), d19(2, MISSING)]);
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'slot-contrib', 'kfm_x', 'secret', null)
    .withRetryDelays([50]);
  kf.ui().contribute(LAYERS, Surface.layer('pin', '/m/slot-contrib/api/pins'))
    .contribute(INSPECTOR, Surface.react('/m/slot-contrib/ui/p.mjs', 'Panel'))
    .contribute(MISSING, Surface.react('/m/slot-contrib/ui/p.mjs', 'Other'));
  try {
    kf.start();
    await registered(kf);
    const warnings = kf.registrationWarnings();
    assert.equal(warnings.length, 2, String(warnings));
    // the internal slot and the missing one get the same sentence, only the slug differs
    assert.equal(warnings[0].replace('surfaces[1]', '').replace(INSPECTOR, 'X'),
      warnings[1].replace('surfaces[2]', '').replace(MISSING, 'X'));
  } finally {
    kf.stop();
    await new Promise((r) => server.close(r));
  }
});

test(': an internal module gets the internal slot (no warning)', async () => {
  const server = await platform([]);
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'slot-contrib', 'kfm_x', 'secret', null);
  kf.ui().contribute(INSPECTOR, Surface.react('/m/slot-contrib/ui/p.mjs', 'Panel'));
  try {
    kf.start();
    await registered(kf);
    assert.deepStrictEqual(kf.registrationWarnings(), []);
  } finally {
    kf.stop();
    await new Promise((r) => server.close(r));
  }
});

test('manifest: the owner must be required, mandatory or optional', () => {
  const base = (requires) => `{"schemaVersion":3,"slug":"slot-contrib","name":"module.name","version":"1.0.0","platform":">=4.1",
 "i18n":{"en":{"module.name":"Slot contributor"}},${requires}
 "capabilities":{"surfaces":[{"slug":"m.kf-3d.viewport.layers.v1","kind":"layer"}]}}`;
  const missing = ManifestCheck.validate(base(''));
  assert.equal(missing.ok(), false);
  assert.ok(missing.errors.some((e) => e.includes('the owner "kf-3d" is not in requires')), String(missing.errors));

  const optional = ManifestCheck.validate(base('"requires":[{"slug":"kf-3d","versionRange":">=1.0.0","optional":true}],'));
  assert.ok(optional.ok(), String(optional.errors));

  const onHost = ManifestCheck.validate(base('').replace('m.kf-3d.viewport.layers.v1', 'models.detail.tabs'));
  assert.ok(onHost.errors.some((e) => e.includes('is only for a slot of a module')), String(onHost.errors));
});

test('a data contribution with a null value registers', () => {
  const kf = KfModule.of('http://127.0.0.1:1', 'slot-contrib', 'kfm_x', 'secret', null);
  kf.ui().contribute('m.kf-3d.materials.v1', Surface.data({ finish: null, a: 1 }).key('wool'));
  const sent = kf.registrationBody().surfaces[0].data;
  assert.deepStrictEqual(Object.keys(sent), ['finish', 'a']);
  assert.equal(Object.hasOwn(sent, 'finish') && sent.finish === null, true);
});
