// `npm run build:css`: the module's own Tailwind 4 build, src/surfaces.css → dist/surfaces.css.
//
// @tailwindcss/node compiles the stylesheet and @tailwindcss/oxide scans src/ for the classes the module uses (the two
// packages under @tailwindcss/postcss, at the version and integrity of the host UI's lockfile). `@kf/sdk-ui/<file>`
// resolves to the SDK's sdk/ui/<file>: the PINNED SDK export when build.sh sets KF_SDK_DIR (.sdk/), else this
// checkout's sdk/ (dev builds and tests). build.sh copies dist/surfaces.css into the jar's static/ui/, which the
// platform serves at /m/<slug>/ui/surfaces.css (ModuleAssetProxyController: .css allowed, under 2 MiB — checked here).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { compile, optimize } from '@tailwindcss/node';
import { Scanner } from '@tailwindcss/oxide';

const UI_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAX_ASSET_BYTES = 2 * 1024 * 1024;
const SDK_UI_PREFIX = '@kf/sdk-ui/';

export function sdkRoot() {
  return process.env.KF_SDK_DIR ? path.resolve(process.env.KF_SDK_DIR) : path.resolve(UI_DIR, '../../..');
}

/** Compiles `input` (default src/surfaces.css) and returns the minified CSS. */
export async function buildCss({ input = path.join(UI_DIR, 'src/surfaces.css'), minify = true } = {}) {
  const base = path.dirname(input);
  const compiler = await compile(fs.readFileSync(input, 'utf8'), {
    base,
    from: input,
    onDependency: () => {},
    customCssResolver: async (id) =>
      id.startsWith(SDK_UI_PREFIX) ? path.join(sdkRoot(), 'sdk/ui', id.slice(SDK_UI_PREFIX.length)) : undefined,
  });
  if (compiler.root !== 'none' && compiler.root !== null) throw new Error('surfaces.css: use @source, not source(...)');
  const scanner = new Scanner({ sources: compiler.sources });
  const css = compiler.build(scanner.scan());
  return optimize(css, { file: input, minify }).code;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isMain) {
  const out = path.join(UI_DIR, 'dist/surfaces.css');
  const css = await buildCss();
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, css);
  const size = Buffer.byteLength(css);
  console.log(`  dist/surfaces.css  ${(size / 1024).toFixed(1)} KiB`);
  if (size > MAX_ASSET_BYTES) {
    console.error(`  dist/surfaces.css is over the platform's asset limit (${MAX_ASSET_BYTES} bytes)`);
    process.exit(1);
  }
}
