// `npm test`: runs the module's UI tests with the host UI's vitest (see fe-node-modules.mjs). Extra arguments are
// passed to vitest (`npm test -- src/pages/utils`).
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { feNodeModules, UI_DIR } from './fe-node-modules.mjs';

const nodeModules = feNodeModules();
const vitest = path.join(nodeModules, 'vitest', 'vitest.mjs');
const args = [vitest, 'run', '--config', path.join(UI_DIR, 'vitest.config.mjs'), ...process.argv.slice(2)];
const result = spawnSync(process.execPath, args, {
  cwd: UI_DIR,
  stdio: 'inherit',
  env: { ...process.env, KF_FE_NODE_MODULES: nodeModules },
});
process.exit(result.status ?? 1);
