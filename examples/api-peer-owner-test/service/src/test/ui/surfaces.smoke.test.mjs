// Smoke test of the hand-written bundle (plan 230-32), with stubbed react / @kf/ui / @kf/host-sdk: the page sets its
// title, reads GET /items and mounts the host's Slot on m.api-peer-owner-test.panel.sections.v1.
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

function find(tree, type) {
  if (tree == null || typeof tree !== 'object') return null;
  if (tree.type === type) return tree;
  for (const c of tree.children || []) {
    const f = find(c, type);
    if (f) return f;
  }
  return null;
}

test('the page sets its title, reads /items and mounts the Slot of the owner', () => {
  const log = [];
  effects.length = 0;
  const tree = render(bundle.PeerOwnerPanelPage({ actions: { setTitle: (t) => log.push(['setTitle', t]), toast() {}, navigate() {} } }));
  effects.forEach((fn) => fn());
  assert.deepEqual(log[0], ['setTitle', 'Peer owner panel']);
  assert.ok(calls.includes('/api/proxy/m/api-peer-owner-test/items'));
  const slot = find(tree, 'Slot');
  assert.ok(slot, 'the Slot is mounted');
  assert.equal(slot.props.slug, 'm.api-peer-owner-test.panel.sections.v1');
  assert.equal(typeof slot.props.actions.refresh, 'function');
});
