// Smoke test of the hand-written bundle (quick 261026-pachete-test), with stubbed react / @kf/ui / @kf/host-sdk:
// every export renders without throwing, calls the actions it should, and the two crash exports throw.
//   node --import ./loader.mjs --test surfaces.smoke.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { effects } from './stubs/react.mjs';
import { calls } from './stubs/kf-host-sdk.mjs';

const bundle = await import(new URL('../../main/resources/static/ui/surfaces.mjs', import.meta.url).href);

function actions(log) {
  return {
    navigate: (href) => log.push(['navigate', href]),
    setTitle: (title) => log.push(['setTitle', title]),
    toast: (message) => log.push(['toast', message]),
    refresh: () => { log.push(['refresh']); return Promise.resolve(); },
  };
}

/** Renders a component tree of stub elements, calling every function component (hooks run inline). */
function render(element) {
  if (element == null || typeof element !== 'object') return element;
  if (Array.isArray(element)) return { props: {}, children: element.map(render) };
  if (typeof element.type === 'function') return render(element.type({ ...element.props, children: element.children }));
  return { ...element, children: element.children.map(render) };
}

function text(tree) {
  if (tree == null) return '';
  if (typeof tree !== 'object') return String(tree);
  return [...Object.values(tree.props || {}).filter((v) => typeof v === 'string'), ...tree.children.map(text)].join(' ');
}

test('the demo page sets its title and calls GET /info', () => {
  const log = [];
  effects.length = 0;
  const tree = render(bundle.ApiUiDemoPage({ ctx: { page: 'demo', path: [], query: {} }, actions: actions(log) }));
  effects.forEach((fn) => fn());
  assert.deepEqual(log[0], ['setTitle', 'API UI Test demo']);
  assert.ok(calls.includes('/api/proxy/m/api-ui-test/info'));
  assert.match(text(tree), /demo/);
});

test('the setup section sets its title and shows the section', () => {
  const log = [];
  effects.length = 0;
  const tree = render(bundle.ApiUiServersSection({ ctx: { section: 'servers' }, actions: actions(log) }));
  effects.forEach((fn) => fn());
  assert.deepEqual(log[0], ['setTitle', 'API UI Test servers']);
  assert.match(text(tree), /servers/);
});

test('the tenant card shows the chosen tenant', () => {
  const tree = render(bundle.ApiUiTenantCard({ ctx: { tenant: { id: 't-1', name: 'UAT Dev Org' } }, actions: actions([]) }));
  assert.match(text(tree), /UAT Dev Org/);
  assert.match(text(tree), /t-1/);
});

test('the order contributions show id, name and status under the label AAS', () => {
  for (const name of ['ApiUiOrderPanel', 'ApiUiOrderReview']) {
    const tree = render(bundle[name]({ ctx: { order: { id: 6, name: 'AGENT_V1 Order', status: 'DRAFT' } }, actions: actions([]) }));
    const shown = text(tree);
    assert.match(shown, /AAS/, name);
    assert.match(shown, /AGENT_V1 Order/, name);
    assert.match(shown, /DRAFT/, name);
    assert.match(shown, /\b6\b/, name);
  }
});

test('the crash exports throw while rendering', () => {
  for (const name of ['ApiUiOrderPanelCrash', 'ApiUiOrderReviewCrash']) {
    assert.throws(() => bundle[name]({ ctx: { order: { id: 6, name: 'x', status: 'DRAFT' } }, actions: actions([]) }),
      /throws on purpose/, name);
  }
});
