// Where the UI tests of kf-demo-model-card find vitest, jsdom, React and Testing Library (copied from kf-feedback, which copied it from KF-AAS-R3, plan 213-24).
//
// The module installs no test package of its own: the npm gate of 213-24 (Task 1) was not passed by the operator, so the
// tests run on the packages kf-fe-platform already has, locked in ITS package-lock.json (vitest 3.2, jsdom 26, react 19.2,
// @testing-library/react 16.3) — the same versions the plan asked for, already vetted for the host. Nothing is
// downloaded. The directory is, in order: $KF_FE_NODE_MODULES; the super-project's kf-fe-platform/node_modules (this
// module checked out in kf-platform/kf-platform-modules); a sibling worktree's fe/node_modules (.wt/<plan>/{modules,fe});
// the super-project's kf-fe-platform/node_modules seen from a worktree (.wt/<plan>/modules).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function feNodeModules() {
  const candidates = [
    process.env.KF_FE_NODE_MODULES,
    path.resolve(UI, '../../../../kf-fe-platform/node_modules'),
    path.resolve(UI, '../../../../fe/node_modules'),
    path.resolve(UI, '../../../../../../kf-fe-platform/node_modules'),
  ].filter(Boolean);
  for (const dir of candidates) {
    if (fs.existsSync(path.join(dir, 'vitest', 'package.json')) && fs.existsSync(path.join(dir, 'react', 'package.json'))) {
      return dir;
    }
  }
  throw new Error(
    'kf-fe-platform/node_modules (with vitest and react) not found; set KF_FE_NODE_MODULES. Looked in:\n  ' +
      candidates.join('\n  '),
  );
}

export const UI_DIR = UI;
