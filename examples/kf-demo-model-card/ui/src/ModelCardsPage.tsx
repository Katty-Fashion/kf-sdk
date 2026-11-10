/**
 * `module.pages`, page `cards`: the module's page, with two views.
 *
 * - **All models** (the default): a page of the tenant's models (`GET /models?page=&size=12`, i.e. `model.v1` without
 *   `ids`, protocol 1.3.1, for a user holding `api.v1.models.get`), each card with the owner (`modelOwner`), the garment
 *   image of the current version and the Pin / Unpin button. Previous / Next walk the pages. Without the right the
 *   platform answers 403 and the page says so.
 * - **Pinned**: the cards of the models the user pinned (`GET /cards`); Unpin removes the card from the list.
 *
 * The garment images are presigned URLs of 10 minutes: the view re-reads its cards 30 s before the first one expires.
 * The port gives no status, so each card shows the number of versions and the current one (the last of `versions`).
 * Open goes to the model's page.
 */
import { useEffect, useState } from 'react';
import { ApiError } from '@kf/host-sdk';
import { Button } from '@kf/ui';
import { ModelCardView, type CardVersion } from './components/ModelCardView';
import { HostTooOld } from './components/HostTooOld';
import { api, PAGE_SIZE, type ModelDto } from './lib/api';
import { hostTooOld } from './lib/hostGuard';
import { earliestExpiry, refreshDelay } from './lib/imageExpiry';
import { setModuleSlug } from './lib/moduleSlug';
import { tr } from './lib/texts';

export interface PageActions {
  navigate?: (href: string) => void;
  setTitle?: (title: string) => void;
  toast?: (message: string, options?: { type?: 'success' | 'error' | 'info' }) => void;
}

export interface ModelCardsPageProps {
  ctx: { page: string; path: string[]; query: Record<string, string | string[] | undefined> };
  actions?: PageActions;
  module: { slug: string };
}

type View = 'all' | 'pinned';

type State =
  | { kind: 'loading' }
  | { kind: 'error'; forbidden: boolean }
  | { kind: 'ready'; items: ModelDto[]; total: number };

export function ModelCardsPage(props: ModelCardsPageProps) {
  setModuleSlug(props.module.slug);
  if (hostTooOld()) return <HostTooOld />;
  return <ModelCardsBody {...props} />;
}

function currentVersion(card: ModelDto): CardVersion | null {
  const last = card.versions?.at(-1);
  return last ? { id: last.id, name: last.name } : null;
}

function ModelCardsBody({ actions }: ModelCardsPageProps) {
  const [view, setView] = useState<View>('all');
  const [page, setPage] = useState(0);
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [busy, setBusy] = useState<number | null>(null);
  // bumped to re-read the view before its image URLs expire
  const [reload, setReload] = useState(0);

  // The title is set once, on mount.
  const setTitle = actions?.setTitle;
  useEffect(() => {
    setTitle?.(tr('ui.page.title'));
  }, [setTitle]);

  useEffect(() => {
    let live = true;
    const load =
      view === 'all'
        ? api.models(page).then((dto) => ({ items: dto.items ?? [], total: dto.total ?? 0 }))
        : api.cards().then((dto) => ({ items: dto.items ?? [], total: dto.items?.length ?? 0 }));
    load.then(
      ({ items, total }) => {
        if (live) setState({ kind: 'ready', items, total });
      },
      (e: unknown) => {
        if (live) setState({ kind: 'error', forbidden: e instanceof ApiError && e.status === 403 });
      },
    );
    return () => {
      live = false;
    };
  }, [view, page, reload]);

  // the presigned URLs live 10 minutes: re-read the view 30 s before the first one expires (a page kept open)
  const expiresAt = state.kind === 'ready' ? earliestExpiry(state.items) : null;
  useEffect(() => {
    const delay = refreshDelay(expiresAt);
    if (delay === null) return;
    const timer = setTimeout(() => setReload((n) => n + 1), delay);
    return () => clearTimeout(timer);
  }, [expiresAt]);

  function show(next: View) {
    if (next === view) return;
    setState({ kind: 'loading' });
    setView(next);
    setPage(0);
  }

  function goTo(next: number) {
    setState({ kind: 'loading' });
    setPage(next);
  }

  async function togglePin(card: ModelDto) {
    setBusy(card.modelId);
    try {
      const result = card.pinned ? await api.unpin(card.modelId) : await api.pin(card.modelId);
      setState((s) => {
        if (s.kind !== 'ready') return s;
        if (view === 'pinned' && !result.pinned) {
          return { ...s, items: s.items.filter((c) => c.modelId !== card.modelId), total: s.total - 1 };
        }
        return { ...s, items: s.items.map((c) => (c.modelId === card.modelId ? { ...c, pinned: result.pinned } : c)) };
      });
      actions?.toast?.(tr(result.pinned ? 'ui.card.pinned' : 'ui.card.unpinned'), { type: 'success' });
    } catch {
      actions?.toast?.(tr('ui.card.error'), { type: 'error' });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex flex-col gap-4 p-4">
      <div role="group" aria-label={tr('ui.page.views')} className="flex gap-2">
        {(['all', 'pinned'] as const).map((v) => (
          <Button
            key={v}
            size="sm"
            variant={view === v ? 'default' : 'outline'}
            aria-pressed={view === v}
            onClick={() => show(v)}
          >
            {tr(v === 'all' ? 'ui.page.all' : 'ui.page.pinned')}
          </Button>
        ))}
      </div>
      <Body
        view={view}
        state={state}
        page={page}
        busy={busy}
        onTogglePin={(card) => void togglePin(card)}
        onOpen={(card) => actions?.navigate?.(`/models/${card.modelId}`)}
        onPage={goTo}
      />
    </div>
  );
}

interface BodyProps {
  view: View;
  state: State;
  page: number;
  busy: number | null;
  onTogglePin: (card: ModelDto) => void;
  onOpen: (card: ModelDto) => void;
  onPage: (page: number) => void;
}

function Body({ view, state, page, busy, onTogglePin, onOpen, onPage }: BodyProps) {
  if (state.kind === 'loading') {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        {tr('ui.page.loading')}
      </p>
    );
  }
  if (state.kind === 'error') {
    return (
      <p role="alert" className="text-sm text-destructive">
        {tr(state.forbidden ? 'ui.page.forbidden' : 'ui.page.error')}
      </p>
    );
  }
  if (state.items.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">{tr(view === 'all' ? 'ui.page.no-models' : 'ui.page.empty')}</p>
    );
  }
  const pages = Math.max(1, Math.ceil(state.total / PAGE_SIZE));
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {state.items.map((card) => (
          <ModelCardView
            key={card.modelId}
            model={{ id: card.modelId, name: card.name, styleNumber: card.styleNumber, createdAt: card.createdAt }}
            owner={card.modelOwner ?? null}
            image={card.image ?? null}
            versionCount={card.versions?.length ?? 0}
            version={currentVersion(card)}
            pinned={card.pinned === true}
            busy={busy === card.modelId}
            onTogglePin={() => onTogglePin(card)}
            onOpen={() => onOpen(card)}
          />
        ))}
      </div>
      {view === 'all' && (
        <div className="flex items-center gap-3 text-sm">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => onPage(page - 1)}>
            {tr('ui.page.previous')}
          </Button>
          <span className="text-muted-foreground">{tr('ui.page.of', { page: page + 1, pages })}</span>
          <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
            {tr('ui.page.next')}
          </Button>
        </div>
      )}
    </>
  );
}
