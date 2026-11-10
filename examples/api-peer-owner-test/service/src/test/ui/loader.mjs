// Resolves the page import map's specifiers to the stubs (smoke test only): node --import ./loader.mjs
import { register } from 'node:module';

register('data:text/javascript,' + encodeURIComponent(`
const map = {
  'react': ${JSON.stringify(new URL('./stubs/react.mjs', import.meta.url).href)},
  '@kf/ui': ${JSON.stringify(new URL('./stubs/kf-ui.mjs', import.meta.url).href)},
  '@kf/host-sdk': ${JSON.stringify(new URL('./stubs/kf-host-sdk.mjs', import.meta.url).href)},
};
export async function resolve(specifier, context, next) {
  if (map[specifier]) return { url: map[specifier], shortCircuit: true };
  return next(specifier, context);
}
`));
