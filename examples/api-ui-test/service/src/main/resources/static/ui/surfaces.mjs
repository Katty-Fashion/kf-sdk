// api-ui-test (quick 261026-pachete-test): the UI contributions of the test module, one per UI surface of protocol 1.1.
// Hand-written ES module, no build step: `react`, `@kf/ui` and `@kf/host-sdk` are the host's own instances, resolved by
// the page import map (sdk-protocol.md §7.9). Only host API 1 primitives are used (Card*, Button, Badge), so it renders
// on any host that serves module pages. Each export is registered by ApiUiTestModule.configure:
//
//   module.pages (page "demo")        ApiUiDemoPage           213-17.1
//   setup.sections (section "servers") ApiUiServersSection    213-17.3
//   tenant-management.cards           ApiUiTenantCard         213-17.3
//   orderflow.rail.sections           ApiUiOrderPanel         213-18.1   (crash pack: ApiUiOrderPanelCrash, 213-18.3)
//   orderflow.review.after            ApiUiOrderReview        213-18.2   (crash pack: ApiUiOrderReviewCrash, 213-18.3)
import { createElement as h, useEffect, useState } from 'react';
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@kf/ui';
import { apiVersion, http, locale, moduleUrl } from '@kf/host-sdk';

const SLUG = 'api-ui-test';

function Row(props) {
  return h('div', { className: 'flex gap-2 text-sm' },
    h('span', { className: 'text-muted-foreground min-w-32' }, props.label),
    h('span', { 'data-testid': props.testId, className: 'font-mono break-all' }, props.value == null ? '—' : String(props.value)));
}

function Frame(props) {
  return h(Card, { 'data-testid': props.testId, className: 'border-dashed' },
    h(CardHeader, null,
      h(CardTitle, { className: 'flex items-center gap-2' }, props.title, h(Badge, { variant: 'secondary' }, SLUG)),
      props.description ? h(CardDescription, null, props.description) : null),
    h(CardContent, { className: 'space-y-1' }, props.children));
}

// ---------------------------------------------------------------------------
// module.pages — /m/api-ui-test/demo (213-17.1)

export function ApiUiDemoPage({ ctx, actions }) {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    actions.setTitle('API UI Test demo');
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once: the host may hand a new actions object per render
  }, []);
  useEffect(() => {
    let live = true;
    http(moduleUrl(SLUG, 'info'))
      .then((answer) => { if (live) setInfo(answer); })
      .catch((e) => { if (live) setError(e && e.status ? 'HTTP ' + e.status : String(e)); });
    return () => { live = false; };
  }, []);
  return h('div', { className: 'space-y-4 p-4' },
    h(Frame, { testId: 'api-ui-test-demo', title: 'API UI Test demo', description: 'A page of a module, rendered in the platform shell (module.pages, page "demo").' },
      h(Row, { label: 'page', value: ctx.page, testId: 'demo-page' }),
      h(Row, { label: 'path', value: JSON.stringify(ctx.path) }),
      h(Row, { label: 'query', value: JSON.stringify(ctx.query) }),
      h(Row, { label: 'locale', value: locale() }),
      h(Row, { label: 'host API', value: apiVersion }),
      h(Row, { label: 'GET /info', value: error ? error : info ? JSON.stringify(info) : 'loading…', testId: 'demo-info' }),
      h('div', { className: 'pt-2' },
        h(Button, { variant: 'outline', size: 'sm', onClick: () => actions.toast('api-ui-test: toast from the demo page', { type: 'success' }) }, 'Toast'))));
}

// ---------------------------------------------------------------------------
// setup.sections — /setup/m/api-ui-test/servers (213-17.3), platform administrator only

export function ApiUiServersSection({ ctx, actions }) {
  useEffect(() => {
    actions.setTitle('API UI Test servers');
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once: the host may hand a new actions object per render
  }, []);
  return h('div', { className: 'space-y-4' },
    h(Frame, { testId: 'api-ui-test-servers', title: 'API UI Test servers', description: 'The module\'s own section of /setup (setup.sections, section "servers").' },
      h(Row, { label: 'section', value: ctx.section, testId: 'servers-section' }),
      h(Row, { label: 'servers', value: 'srv-a.example, srv-b.example (static test data)' })));
}

// ---------------------------------------------------------------------------
// tenant-management.cards (213-17.3)

export function ApiUiTenantCard({ ctx, actions }) {
  const [refreshed, setRefreshed] = useState(0);
  const tenant = ctx.tenant || {};
  return h(Frame, { testId: 'api-ui-test-tenant-card', title: 'API UI Test', description: 'The module\'s card in tenant management (tenant-management.cards).' },
    h(Row, { label: 'tenant name', value: tenant.name, testId: 'card-tenant-name' }),
    h(Row, { label: 'tenant id', value: tenant.id, testId: 'card-tenant-id' }),
    h(Row, { label: 'refresh() calls', value: refreshed }),
    h('div', { className: 'pt-2' },
      h(Button, {
        variant: 'outline', size: 'sm',
        onClick: () => actions.refresh().then(() => { setRefreshed((n) => n + 1); actions.toast('api-ui-test: refresh() resolved'); }),
      }, 'refresh()')));
}

// ---------------------------------------------------------------------------
// orderflow.rail.sections (213-18.1) and orderflow.review.after (213-18.2)

function OrderFrame({ ctx, actions, testId, where }) {
  const order = ctx.order || {};
  const [busy, setBusy] = useState(false);
  const [calls, setCalls] = useState(0);
  return h(Frame, { testId, title: 'AAS', description: 'api-ui-test contribution on ' + where + '.' },
    h(Row, { label: 'order id', value: order.id, testId: testId + '-id' }),
    h(Row, { label: 'order name', value: order.name, testId: testId + '-name' }),
    h(Row, { label: 'order status', value: order.status, testId: testId + '-status' }),
    h(Row, { label: 'refresh() calls', value: calls }),
    h('div', { className: 'pt-2' },
      h(Button, {
        variant: 'outline', size: 'sm', disabled: busy,
        onClick: () => {
          setBusy(true);
          actions.refresh()
            .then(() => { setCalls((n) => n + 1); actions.toast('api-ui-test: refresh() resolved'); })
            .catch((e) => actions.toast('api-ui-test: refresh() failed', { type: 'error', description: String(e) }))
            .finally(() => setBusy(false));
        },
      }, busy ? 'Refreshing…' : 'refresh()')));
}

export function ApiUiOrderPanel(props) {
  return h(OrderFrame, { ...props, testId: 'api-ui-test-order-panel', where: 'orderflow.rail.sections' });
}

export function ApiUiOrderReview(props) {
  return h(OrderFrame, { ...props, testId: 'api-ui-test-order-review', where: 'orderflow.review.after' });
}

// ---------------------------------------------------------------------------
// The crash pack (213-18.3): the same surfaces, a component that throws while rendering. The host's isolated wrapper
// (error boundary) must leave nothing in its place.

export function ApiUiOrderPanelCrash() {
  throw new Error('api-ui-test: ApiUiOrderPanelCrash throws on purpose (213-18.3)');
}

export function ApiUiOrderReviewCrash() {
  throw new Error('api-ui-test: ApiUiOrderReviewCrash throws on purpose (213-18.3)');
}
