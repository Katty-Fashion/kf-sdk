// Smoke test of the hand-written bundle, with stubbed react / @kf/ui / @kf/host-sdk: the panel the caller
// contributes to the owner's slot renders and reads GET /latest.
//   node --import ./loader.mjs --test surfaces.smoke.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { effects } from './stubs/react.mjs';
import { calls } from './stubs/kf-host-sdk.mjs';

const bundle = await import(new URL('../../main/resources/static/ui/surfaces.mjs', import.meta.url).href);

function render(element) {
  if (element == null || typeof element !== 'object') return element;
  if (Array.isArray(element)) return { props: {}, children: element.map(render) };
  if (typeof element.type === 'function') return render(element.type({ ...element.props, children: element.children }));
  return { ...element, children: (element.children || []).map(render) };
}

function text(tree) {
  if (tree == null) return '';
  if (typeof tree !== 'object') return String(tree);
  return (tree.children || []).map(text).join(' ');
}

test('the slot panel renders and reads /latest', () => {
  effects.length = 0;
  const tree = render(bundle.PeerCallerSlotPanel({ ctx: { items: [{ id: 'item-1' }, { id: 'item-2' }] } }));
  effects.forEach((fn) => fn());
  assert.ok(calls.includes('/api/proxy/m/api-peer-caller-test/latest'));
  assert.ok(text(tree).includes('items in the owner\'s page: 2'), text(tree));
});
