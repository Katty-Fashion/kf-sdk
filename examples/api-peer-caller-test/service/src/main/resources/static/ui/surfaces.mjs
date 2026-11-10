// api-peer-caller-test (plan 230-32, protocol 2.0): the caller's contribution to the owner's slot
// m.api-peer-owner-test.panel.sections.v1 (kind A, sdk-protocol.md §7.12). Hand-written ES module, no build step:
// `react`, `@kf/ui` and `@kf/host-sdk` are the host's own instances (the page import map).
//
//   m.api-peer-owner-test.panel.sections.v1   PeerCallerSlotPanel   the last item the caller read from the owner
import { createElement as h, useEffect, useState } from 'react';
import { Badge, Card, CardContent, CardHeader, CardTitle } from '@kf/ui';
import { http, moduleUrl } from '@kf/host-sdk';

const SLUG = 'api-peer-caller-test';

export function PeerCallerSlotPanel({ ctx }) {
  const [item, setItem] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    let live = true;
    http(moduleUrl(SLUG, 'latest'))
      .then((answer) => { if (live) setItem(answer ? answer.item : null); })
      .catch((e) => { if (live) setError(e && e.status ? 'HTTP ' + e.status : String(e)); });
    return () => { live = false; };
  }, []);
  const count = ctx && Array.isArray(ctx.items) ? ctx.items.length : 0;
  return h(Card, { 'data-testid': 'peer-caller-slot-panel', className: 'border-dashed' },
    h(CardHeader, null, h(CardTitle, { className: 'flex items-center gap-2' }, 'Peer caller', h(Badge, { variant: 'secondary' }, SLUG))),
    h(CardContent, { className: 'space-y-1 text-sm' },
      h('div', { 'data-testid': 'peer-caller-latest' },
        'last item read: ' + (error ? error : item ? item.name : 'none')),
      h('div', { className: 'text-muted-foreground' }, 'items in the owner\'s page: ' + count)));
}
