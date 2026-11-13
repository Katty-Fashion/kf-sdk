// Packaging test of the TS SDK (not derived: it has no Java twin — the Java jar carries its version in sdk.properties).
// A module bundles its entry point WITH the SDK (esbuild, --bundle --platform=node) and runs the single file far from
// the SDK's package.json: the SDK must start there. Before, SdkVersion read ../package.json through
// import.meta.url and the bundle died at import with ENOENT, so modules had to keep the SDK --external.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const PKG_DIR = fileURLToPath(new URL('..', import.meta.url));
const PKG_VERSION = JSON.parse(readFileSync(join(PKG_DIR, 'package.json'), 'utf8')).version;
const SDK_ENTRY = './dist/index.js'; // resolved by esbuild from resolveDir = the package root

/** Bundles `source` (an ES module importing the built SDK) into a fresh temp dir and runs it with node. */
async function bundleAndRun(source) {
  const dir = mkdtempSync(join(tmpdir(), 'kf-sdk-bundle-'));
  try {
    const outfile = join(dir, 'app.mjs');
    await build({
      stdin: { contents: source, resolveDir: PKG_DIR, sourcefile: 'app.ts', loader: 'ts' },
      bundle: true,
      platform: 'node',
      target: 'node22',
      format: 'esm',
      outfile,
      logLevel: 'silent',
    });
    const bundled = readFileSync(outfile, 'utf8');
    const run = spawnSync(process.execPath, [outfile], { cwd: dir, encoding: 'utf8', timeout: 20_000 });
    assert.equal(run.status, 0, `the bundle failed: ${run.stderr}`);
    // nothing of the SDK is left outside the file: no import of kf-module-sdk, no package.json read at run time
    assert.doesNotMatch(bundled, /from\s+["']kf-module-sdk/);
    assert.doesNotMatch(bundled, /package\.json/);
    return run;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a bundle of the SDK carries the SDK version (no package.json at run time)', async () => {
  const run = await bundleAndRun(`
    import { SdkVersion } from ${JSON.stringify(SDK_ENTRY)};
    process.stdout.write(JSON.stringify({ version: SdkVersion.SDK_VERSION, schema: SdkVersion.PROTOCOL_SCHEMA_VERSION }));
  `);
  assert.deepEqual(JSON.parse(run.stdout), { version: PKG_VERSION, schema: 3 });
});

test('a bundled module starts: nodeHandler answers /health-style public paths and refuses an unsigned route', async () => {
  const run = await bundleAndRun(`
    import { createServer } from 'node:http';
    import { KfModule, nodeHandler, SdkVersion } from ${JSON.stringify(SDK_ENTRY)};
    const kf = KfModule.of(null, 'bundle-test', null, 'secret', null);
    const handler = nodeHandler(kf);
    const server = createServer((req, res) => handler(req, res, () => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ status: 'UP', sdk: SdkVersion.SDK_VERSION }));
    }));
    server.listen(0, '127.0.0.1', async () => {
      const base = 'http://127.0.0.1:' + server.address().port;
      const health = await fetch(base + '/health');
      const route = await fetch(base + '/notes');
      process.stdout.write(JSON.stringify({ health: health.status, body: await health.json(), route: route.status }));
      server.close();
    });
  `);
  const out = JSON.parse(run.stdout);
  assert.equal(out.health, 200);
  assert.deepEqual(out.body, { status: 'UP', sdk: PKG_VERSION });
  assert.equal(out.route, 401, 'a route outside the public paths needs the signed context');
});
