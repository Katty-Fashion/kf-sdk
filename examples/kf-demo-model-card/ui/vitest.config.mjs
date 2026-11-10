// The UI tests of kf-demo-model-card (copied from kf-feedback). Plain object, no `vitest/config` import: vitest is
// kf-fe-platform's (scripts/run-vitest.mjs). React, Testing Library and jsdom are kf-fe-platform's too — ONE copy of
// React, the same the host renders with. `@kf/ui` and `@kf/host-sdk` are the host's at run time (external in the
// bundle); in the tests they are the small stand-ins under test/. `qrcode-generator` is the module's own
// (ui/node_modules, in the bundle) and runs for real in the tests.
import path from 'node:path';
import { feNodeModules, UI_DIR } from './scripts/fe-node-modules.mjs';

const fe = feNodeModules();

export default {
  root: UI_DIR,
  esbuild: { jsx: 'automatic' },
  resolve: {
    alias: [
      { find: '@kf/ui', replacement: path.join(UI_DIR, 'test/kf-ui.tsx') },
      { find: '@kf/host-sdk', replacement: path.join(UI_DIR, 'test/kf-host-sdk.ts') },
      { find: /^react$/, replacement: path.join(fe, 'react/index.js') },
      { find: /^react\/jsx-runtime$/, replacement: path.join(fe, 'react/jsx-runtime.js') },
      { find: /^react\/jsx-dev-runtime$/, replacement: path.join(fe, 'react/jsx-dev-runtime.js') },
      { find: /^react-dom$/, replacement: path.join(fe, 'react-dom/index.js') },
      { find: /^react-dom\/client$/, replacement: path.join(fe, 'react-dom/client.js') },
      { find: /^@testing-library\/react$/, replacement: path.join(fe, '@testing-library/react') },
      { find: /^@testing-library\/user-event$/, replacement: path.join(fe, '@testing-library/user-event') },
      { find: /^@testing-library\/jest-dom\/vitest$/, replacement: path.join(fe, '@testing-library/jest-dom/dist/vitest.mjs') },
    ],
  },
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.mjs'],
    setupFiles: [path.join(UI_DIR, 'test/setup.ts')],
    restoreMocks: true,
  },
};
