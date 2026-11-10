import fs from 'node:fs';
import path from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { calls, onHttp, resetHost, setApiVersion, setTexts, textSlugs } from '@kf/host-sdk';
import { ModelCard, ModelCardsPage } from './surfaces';
import { forgetModuleSlug } from './lib/moduleSlug';

// The texts are the manifest's own (../kf-module-conf.json, i18n.en / ro): the tests read what the host would serve.
const UI_DIR = path.resolve(__dirname, '..');
const manifest = JSON.parse(fs.readFileSync(path.join(UI_DIR, '../kf-module-conf.json'), 'utf8')) as {
  i18n: Record<string, Record<string, string>>;
};
const EN = manifest.i18n.en;

const SLUG = 'kf-demo-model-card';
const DEV = 'kf-demo-model-card-dev';

function model(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    name: 'Summer Linen Dress',
    styleNumber: 'SLD-2041',
    status: 'SAMPLING' as const,
    createdAt: '2026-03-14T10:00:00Z',
    updatedAt: null,
    extensions: {},
    ...overrides,
  };
}

function actions() {
  return { navigate: vi.fn(), toast: vi.fn(), setTitle: vi.fn() };
}

const IMAGE_URL = 'https://s3.example/models/42/versions/7/garment-image/a.png?X-Amz-Expires=600';

function apiModel(pinned: boolean, extra: Record<string, unknown> = {}) {
  return {
    modelId: 42,
    name: 'Summer Linen Dress',
    styleNumber: 'SLD-2041',
    modelOwner: 'Ana Pop',
    createdAt: '2026-03-14T10:00:00Z',
    currentVersionId: 7,
    versions: [{ id: 7, name: 'V1', status: 'DRAFT', createdAt: '2026-03-14T10:00:00Z' }],
    image: null,
    pinned,
    ...extra,
  };
}

function card(
  modelId: number,
  name: string,
  versions: Array<{ id: number; name: string | null }>,
  extra: Record<string, unknown> = {},
) {
  return {
    modelId,
    name,
    styleNumber: `ST-${modelId}`,
    modelOwner: null,
    createdAt: '2026-01-02T08:00:00Z',
    currentVersionId: versions.at(-1)?.id ?? null,
    versions: versions.map((v) => ({ ...v, status: 'DRAFT', createdAt: '2026-01-02T08:00:00Z' })),
    image: null,
    pinned: true,
    pinnedAt: '2026-10-01T08:00:00Z',
    ...extra,
  };
}

function modelsPage(items: unknown[], page: number, total: number, size = 12) {
  return { items, page, size, total };
}

function imageName(name: string) {
  return EN['ui.card.image'].replace('{name}', name);
}

function inTenMinutes() {
  return new Date(Date.now() + 600_000).toISOString();
}

beforeEach(() => {
  resetHost();
  forgetModuleSlug();
  setTexts(EN, 'en');
});

describe('the manifest texts', () => {
  it('declares every ui.* key the code uses, in en and ro', () => {
    const used = new Set<string>();
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const file = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(file);
        else if (/\.tsx?$/.test(entry.name) && !/\.test\./.test(entry.name)) {
          for (const m of fs.readFileSync(file, 'utf8').matchAll(/'(ui\.[a-z.-]+)'/g)) used.add(m[1]);
        }
      }
    };
    walk(path.join(UI_DIR, 'src'));
    expect(used.size).toBeGreaterThan(10);
    for (const key of used) {
      expect(manifest.i18n.en[key], `en ${key}`).toBeTruthy();
      expect(manifest.i18n.ro[key], `ro ${key}`).toBeTruthy();
    }
  });
});

describe('ModelCard', () => {
  it('renders the model from ctx: monogram, name, style number, status, created at, version, QR', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 200, body: apiModel(false) });
    render(<ModelCard ctx={{ model: model(), version: { id: 7, name: 'Sample 2' } }} actions={actions()} module={{ slug: SLUG }} />);

    expect(screen.getByText('SL')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Summer Linen Dress' })).toBeInTheDocument();
    expect(screen.getByText('SLD-2041')).toBeInTheDocument();
    expect(screen.getByText('SAMPLING')).toBeInTheDocument();
    expect(screen.getByText(new Date('2026-03-14T10:00:00Z').toLocaleDateString('en'))).toBeInTheDocument();
    expect(screen.getByText('Sample 2')).toBeInTheDocument();
    const qr = screen.getByRole('img', { name: EN['ui.card.qr'] });
    expect(qr.tagName.toLowerCase()).toBe('svg');
    expect(qr.getAttribute('data-qr-value')).toBe(`${window.location.origin}/models/42?version=7`);
    expect(qr.querySelector('path')?.getAttribute('d')).toMatch(/^M\d/);
    expect(await screen.findByRole('button', { name: EN['ui.card.pin'] })).toBeEnabled();
  });

  it('asks the module for the selected version (versionId) and shows the owner it answers (modelOwner)', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 200, body: apiModel(false) });
    render(<ModelCard ctx={{ model: model(), version: { id: 7, name: 'V1' } }} actions={actions()} module={{ slug: SLUG }} />);

    expect(await screen.findByText('Ana Pop')).toBeInTheDocument();
    expect(screen.getByText(EN['ui.card.owner'])).toBeInTheDocument();
    expect(calls[0].url).toBe(`/api/proxy/m/${SLUG}/models/42?versionId=7`);
  });

  it('shows the garment image of the version in place of the monogram', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, {
      status: 200,
      body: apiModel(false, { image: { url: IMAGE_URL, expiresAt: inTenMinutes() } }),
    });
    render(<ModelCard ctx={{ model: model(), version: { id: 7, name: 'V1' } }} actions={actions()} module={{ slug: SLUG }} />);

    const image = await screen.findByRole('img', { name: imageName('Summer Linen Dress') });
    expect(image.getAttribute('src')).toBe(IMAGE_URL);
    expect(screen.queryByText('SL')).toBeNull();
  });

  it('falls back to the monogram when the image does not load (an expired URL)', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, {
      status: 200,
      body: apiModel(false, { image: { url: IMAGE_URL, expiresAt: inTenMinutes() } }),
    });
    render(<ModelCard ctx={{ model: model(), version: null }} actions={actions()} module={{ slug: SLUG }} />);

    fireEvent.error(await screen.findByRole('img', { name: imageName('Summer Linen Dress') }));
    expect(await screen.findByText('SL')).toBeInTheDocument();
  });

  it('re-reads the card 30 s before the image expires (a presigned URL of 10 minutes)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let n = 0;
      onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, () => {
        n += 1;
        return { status: 200, body: apiModel(false, { image: { url: `${IMAGE_URL}&n=${n}`, expiresAt: inTenMinutes() } }) };
      });
      render(<ModelCard ctx={{ model: model(), version: null }} actions={actions()} module={{ slug: SLUG }} />);
      const name = imageName('Summer Linen Dress');
      expect((await screen.findByRole('img', { name })).getAttribute('src')).toBe(`${IMAGE_URL}&n=1`);

      await act(async () => {
        vi.advanceTimersByTime(600_000 - 30_000 + 10);
      });
      await waitFor(() => expect(screen.getByRole('img', { name }).getAttribute('src')).toBe(`${IMAGE_URL}&n=2`));
      expect(calls.filter((c) => c.method === 'GET')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('asks the module for the pin under the slug the host gives', async () => {
    onHttp('GET', `/api/proxy/m/${DEV}/models/42`, { status: 200, body: apiModel(true) });
    render(<ModelCard ctx={{ model: model(), version: null }} actions={actions()} module={{ slug: DEV }} />);

    expect(await screen.findByRole('button', { name: EN['ui.card.unpin'] })).toBeInTheDocument();
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([`GET /api/proxy/m/${DEV}/models/42`]);
    expect([...textSlugs]).toEqual([DEV]);
  });

  it('pins the model (PUT) and says so', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 200, body: apiModel(false) });
    onHttp('PUT', `/api/proxy/m/${SLUG}/pins/42`, { status: 200, body: { modelId: 42, pinned: true } });
    const a = actions();
    render(<ModelCard ctx={{ model: model(), version: null }} actions={a} module={{ slug: SLUG }} />);

    await userEvent.click(await screen.findByRole('button', { name: EN['ui.card.pin'] }));

    expect(await screen.findByRole('button', { name: EN['ui.card.unpin'] })).toBeInTheDocument();
    expect(calls.at(-1)).toMatchObject({ method: 'PUT', path: `/api/proxy/m/${SLUG}/pins/42` });
    expect(a.toast).toHaveBeenCalledWith(EN['ui.card.pinned'], { type: 'success' });
  });

  it('unpins the model (DELETE)', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 200, body: apiModel(true) });
    onHttp('DELETE', `/api/proxy/m/${SLUG}/pins/42`, { status: 200, body: { modelId: 42, pinned: false } });
    const a = actions();
    render(<ModelCard ctx={{ model: model(), version: null }} actions={a} module={{ slug: SLUG }} />);

    await userEvent.click(await screen.findByRole('button', { name: EN['ui.card.unpin'] }));

    expect(await screen.findByRole('button', { name: EN['ui.card.pin'] })).toBeInTheDocument();
    expect(a.toast).toHaveBeenCalledWith(EN['ui.card.unpinned'], { type: 'success' });
  });

  it('does not fall over without a version key in ctx (a host before protocol 1.3) nor without actions', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 200, body: apiModel(false) });
    onHttp('PUT', `/api/proxy/m/${SLUG}/pins/42`, { status: 200, body: { modelId: 42, pinned: true } });
    render(<ModelCard ctx={{ model: model() }} module={{ slug: SLUG }} />);

    expect(screen.getByText(EN['ui.card.no-version'])).toBeInTheDocument();
    expect(screen.getByRole('img', { name: EN['ui.card.qr'] }).getAttribute('data-qr-value')).toBe(
      `${window.location.origin}/models/42`,
    );
    await userEvent.click(await screen.findByRole('button', { name: EN['ui.card.pin'] }));
    expect(await screen.findByRole('button', { name: EN['ui.card.unpin'] })).toBeInTheDocument();
  });

  it('on the customer portal (the module routes answer 404) keeps the card, without the pin button and without an error', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 404 });
    render(<ModelCard ctx={{ model: model(), version: null, href: '/customer/models/42' }} module={{ slug: SLUG }} />);
    expect(await screen.findByText('Summer Linen Dress')).toBeTruthy();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
    expect(document.querySelector('[data-qr-value]')?.getAttribute('data-qr-value')).toBe(`${window.location.origin}/customer/models/42`);
  });

  it('shows the card error when the pin cannot be read, and keeps the data', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models/42`, { status: 500 });
    render(<ModelCard ctx={{ model: model(), version: null }} actions={actions()} module={{ slug: SLUG }} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(EN['ui.card.error']);
    expect(screen.getByText('Summer Linen Dress')).toBeInTheDocument();
  });

  it('calls nothing for a model without an id', () => {
    render(<ModelCard ctx={{ model: model({ id: null, name: null, styleNumber: null }) }} module={{ slug: SLUG }} />);
    expect(screen.getByText('?')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('says the host is too old under host API 4', () => {
    setApiVersion(3);
    render(<ModelCard ctx={{ model: model() }} module={{ slug: SLUG }} />);
    expect(screen.getByText(EN['ui.host.too-old'].replace('{version}', '3'))).toBeInTheDocument();
    expect(calls).toEqual([]);
  });
});

describe('ModelCardsPage', () => {
  const page = { page: 'cards', path: [], query: {} };

  async function pinnedView() {
    await userEvent.click(await screen.findByRole('button', { name: EN['ui.page.pinned'] }));
  }

  it('sets the title and lists the first page of the tenant models (GET /models?page=0&size=12) with owner, image and pin', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, {
      status: 200,
      body: modelsPage(
        [
          card(1, 'Summer Linen Dress', [{ id: 11, name: 'V1' }], {
            modelOwner: 'Ana Pop',
            pinned: false,
            image: { url: IMAGE_URL, expiresAt: inTenMinutes() },
          }),
          card(2, 'Wool Coat', [], { pinned: true }),
        ],
        0,
        30,
      ),
    });
    const a = actions();
    render(<ModelCardsPage ctx={page} actions={a} module={{ slug: SLUG }} />);

    expect(screen.getByRole('status')).toHaveTextContent(EN['ui.page.loading']);
    const first = await screen.findByRole('article', { name: 'Summer Linen Dress' });
    expect(a.setTitle).toHaveBeenCalledWith(EN['ui.page.title']);
    expect(screen.getByRole('button', { name: EN['ui.page.all'] })).toHaveAttribute('aria-pressed', 'true');
    expect(calls[0].url).toBe(`/api/proxy/m/${SLUG}/models?page=0&size=12`);
    expect(within(first).getByText('Ana Pop')).toBeInTheDocument();
    expect(within(first).getByRole('img', { name: imageName('Summer Linen Dress') })).toHaveAttribute('src', IMAGE_URL);
    expect(within(first).getByRole('button', { name: EN['ui.card.pin'] })).toBeInTheDocument();
    const second = screen.getByRole('article', { name: 'Wool Coat' });
    expect(within(second).getByText('WC')).toBeInTheDocument();
    expect(within(second).getByRole('button', { name: EN['ui.card.unpin'] })).toBeInTheDocument();
    expect(screen.getByText(EN['ui.page.of'].replace('{page}', '1').replace('{pages}', '3'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: EN['ui.page.previous'] })).toBeDisabled();
  });

  it('pages forward and back', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, (call) => {
      const p = Number(new URLSearchParams(call.url.split('?')[1]).get('page'));
      return { status: 200, body: modelsPage([card(100 + p, `Model ${p}`, [], { pinned: false })], p, 13) };
    });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);

    await screen.findByRole('article', { name: 'Model 0' });
    await userEvent.click(screen.getByRole('button', { name: EN['ui.page.next'] }));
    expect(await screen.findByRole('article', { name: 'Model 1' })).toBeInTheDocument();
    expect(calls.at(-1)?.url).toBe(`/api/proxy/m/${SLUG}/models?page=1&size=12`);
    expect(screen.getByRole('button', { name: EN['ui.page.next'] })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: EN['ui.page.previous'] }));
    expect(await screen.findByRole('article', { name: 'Model 0' })).toBeInTheDocument();
  });

  it('pins a model from the list (PUT) and the card says pinned', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, {
      status: 200,
      body: modelsPage([card(5, 'Linen Shirt', [], { pinned: false })], 0, 1),
    });
    onHttp('PUT', `/api/proxy/m/${SLUG}/pins/5`, { status: 200, body: { modelId: 5, pinned: true } });
    const a = actions();
    render(<ModelCardsPage ctx={page} actions={a} module={{ slug: SLUG }} />);

    const shirt = await screen.findByRole('article', { name: 'Linen Shirt' });
    await userEvent.click(within(shirt).getByRole('button', { name: EN['ui.card.pin'] }));
    expect(await within(shirt).findByRole('button', { name: EN['ui.card.unpin'] })).toBeInTheDocument();
    expect(a.toast).toHaveBeenCalledWith(EN['ui.card.pinned'], { type: 'success' });
  });

  it('re-reads the page 30 s before the first image expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let n = 0;
      onHttp('GET', `/api/proxy/m/${SLUG}/models`, () => {
        n += 1;
        return {
          status: 200,
          body: modelsPage(
            [card(1, 'Summer Linen Dress', [{ id: 11, name: 'V1' }], { pinned: false, image: { url: `${IMAGE_URL}&n=${n}`, expiresAt: inTenMinutes() } })],
            0,
            1,
          ),
        };
      });
      render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
      const name = imageName('Summer Linen Dress');
      expect((await screen.findByRole('img', { name })).getAttribute('src')).toBe(`${IMAGE_URL}&n=1`);
      await act(async () => {
        vi.advanceTimersByTime(600_000 - 30_000 + 10);
      });
      await waitFor(() => expect(screen.getByRole('img', { name }).getAttribute('src')).toBe(`${IMAGE_URL}&n=2`));
    } finally {
      vi.useRealTimers();
    }
  });

  it('says the user may not see the models when the platform refuses (403, without api.v1.models.get)', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, { status: 403 });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(EN['ui.page.forbidden']);
  });

  it('says so when the tenant has no models', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, { status: 200, body: modelsPage([], 0, 0) });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
    expect(await screen.findByText(EN['ui.page.no-models'])).toBeInTheDocument();
  });

  it('lists the pinned cards from /cards in the Pinned view', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, { status: 200, body: modelsPage([], 0, 0) });
    onHttp('GET', `/api/proxy/m/${SLUG}/cards`, {
      status: 200,
      body: {
        items: [card(42, 'Summer Linen Dress', [{ id: 7, name: 'V1' }, { id: 9, name: 'V2' }]), card(43, 'Wool Coat', [])],
        missing: [44],
      },
    });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
    await pinnedView();

    const first = await screen.findByRole('article', { name: 'Summer Linen Dress' });
    expect(screen.getByRole('button', { name: EN['ui.page.pinned'] })).toHaveAttribute('aria-pressed', 'true');
    expect(within(first).getByText('V2')).toBeInTheDocument();
    expect(within(first).getByText(EN['ui.page.versions'].replace('{count}', '2'))).toBeInTheDocument();
    expect(within(first).getByRole('img', { name: EN['ui.card.qr'] }).getAttribute('data-qr-value')).toBe(
      `${window.location.origin}/models/42?version=9`,
    );
    const second = screen.getByRole('article', { name: 'Wool Coat' });
    expect(within(second).getByText(EN['ui.card.no-version'])).toBeInTheDocument();
    expect(within(second).getByText('WC')).toBeInTheDocument();
  });

  it('reads /models and /cards under the dev slug', async () => {
    onHttp('GET', `/api/proxy/m/${DEV}/models`, { status: 200, body: modelsPage([], 0, 0) });
    onHttp('GET', `/api/proxy/m/${DEV}/cards`, { status: 200, body: { items: [], missing: [] } });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: DEV }} />);
    await screen.findByText(EN['ui.page.no-models']);
    await pinnedView();
    expect(await screen.findByText(EN['ui.page.empty'])).toBeInTheDocument();
    expect(calls.map((c) => c.path)).toEqual([`/api/proxy/m/${DEV}/models`, `/api/proxy/m/${DEV}/cards`]);
  });

  it('says so when the models cannot be loaded', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, { status: 503 });
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(EN['ui.page.error']);
  });

  it('unpins a card in the Pinned view (DELETE), removes it from the list and says so', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, { status: 200, body: modelsPage([], 0, 0) });
    onHttp('GET', `/api/proxy/m/${SLUG}/cards`, {
      status: 200,
      body: { items: [card(42, 'Summer Linen Dress', []), card(43, 'Wool Coat', [])], missing: [] },
    });
    onHttp('DELETE', `/api/proxy/m/${SLUG}/pins/42`, { status: 200, body: { modelId: 42, pinned: false } });
    const a = actions();
    render(<ModelCardsPage ctx={page} actions={a} module={{ slug: SLUG }} />);
    await pinnedView();

    const first = await screen.findByRole('article', { name: 'Summer Linen Dress' });
    await userEvent.click(within(first).getByRole('button', { name: EN['ui.card.unpin'] }));

    await waitFor(() => expect(screen.queryByRole('article', { name: 'Summer Linen Dress' })).not.toBeInTheDocument());
    expect(screen.getByRole('article', { name: 'Wool Coat' })).toBeInTheDocument();
    expect(calls.at(-1)).toMatchObject({ method: 'DELETE', path: `/api/proxy/m/${SLUG}/pins/42` });
    expect(a.toast).toHaveBeenCalledWith(EN['ui.card.unpinned'], { type: 'success' });
  });

  it('opens the model', async () => {
    onHttp('GET', `/api/proxy/m/${SLUG}/models`, {
      status: 200,
      body: modelsPage([card(43, 'Wool Coat', [], { pinned: false })], 0, 1),
    });
    const a = actions();
    render(<ModelCardsPage ctx={page} actions={a} module={{ slug: SLUG }} />);

    await userEvent.click(await screen.findByRole('button', { name: EN['ui.card.open'] }));
    expect(a.navigate).toHaveBeenCalledWith('/models/43');
  });

  it('says the host is too old under host API 4', () => {
    setApiVersion(2);
    render(<ModelCardsPage ctx={page} actions={actions()} module={{ slug: SLUG }} />);
    expect(screen.getByText(EN['ui.host.too-old'].replace('{version}', '2'))).toBeInTheDocument();
    expect(calls).toEqual([]);
  });
});
