// api-peer-owner-test (protocol 2.0): the owner's page, which mounts its own slot
// m.api-peer-owner-test.panel.sections.v1 (kind A) with the host's Slot (host API 5, sdk-protocol.md §7.12). Hand-written
// ES module, no build step: `react`, `@kf/ui` and `@kf/host-sdk` are the host's own instances (the page import map).
//
//   module.pages (page "panel")   PeerOwnerPanelPage   the items of the tenant + the slot with the callers' contributions
import { createElement as h, useEffect, useState } from 'react';
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@kf/ui';
import { apiVersion, http, moduleUrl, Slot } from '@kf/host-sdk';

const SLUG = 'api-peer-owner-test';
export const SLOT = 'm.' + SLUG + '.panel.sections.v1';

export function PeerOwnerPanelPage({ actions }) {
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const load = () => http(moduleUrl(SLUG, 'items'))
    .then((answer) => setItems(answer && answer.items ? answer.items : []))
    .catch((e) => setError(e && e.status ? 'HTTP ' + e.status : String(e)));
  useEffect(() => {
    actions.setTitle('Peer owner panel');
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once
  }, []);
  return h('div', { className: 'space-y-4 p-4' },
    h(Card, { 'data-testid': 'peer-owner-panel' },
      h(CardHeader, null,
        h(CardTitle, { className: 'flex items-center gap-2' }, 'Peer owner panel', h(Badge, { variant: 'secondary' }, SLUG)),
        h(CardDescription, null, 'The items of the tenant; below, the slot ' + SLOT + ' with what the callers contribute.')),
      h(CardContent, { className: 'space-y-2' },
        h('div', { 'data-testid': 'peer-owner-items', className: 'text-sm font-mono' },
          error ? error : items == null ? 'loading…' : items.length === 0 ? 'no item' : items.map((i) => i.name).join(', ')),
        h(Button, { variant: 'outline', size: 'sm', onClick: load }, 'Refresh'))),
    apiVersion >= 5
      ? h('div', { 'data-testid': 'peer-owner-slot' }, h(Slot, { slug: SLOT, ctx: { items: items || [] }, actions: { refresh: load } }))
      : h('p', { className: 'text-sm text-muted-foreground' }, 'The host API is ' + apiVersion + ': the slot needs 5.'));
}
