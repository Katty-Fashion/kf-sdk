/**
 * `models.detail.overview.cards` (protocol 1.3.1, platform 6.1): the model's card in the Overview tab of the model page,
 * on staff and on the customer portal. What it shows comes from the surface's `ctx` — the model, the version the page
 * has selected (`ctx.version`) and the host's link to the model (`ctx.href`, the QR code). The module adds what `ctx`
 * does not carry (`GET /models/{id}?versionId=`): the owner (`modelOwner`), the garment image of the selected version
 * (a presigned URL of 10 minutes, re-read before it expires) and whether the user pinned the model; it pins or unpins
 * it (`PUT` / `DELETE /pins/{id}`).
 */
import { useEffect, useState } from 'react';
import { ApiError } from '@kf/host-sdk';
import { ModelCardView, type CardVersion, type ModelStatus } from './components/ModelCardView';
import { HostTooOld } from './components/HostTooOld';
import { api, type ModelDto } from './lib/api';
import { hostTooOld } from './lib/hostGuard';
import { refreshDelay } from './lib/imageExpiry';
import { setModuleSlug } from './lib/moduleSlug';
import { tr } from './lib/texts';

export interface ModelCardCtx {
  model: {
    id: number | null;
    name: string | null;
    styleNumber: string | null;
    status: ModelStatus | null;
    createdAt: string | null;
    updatedAt: string | null;
    extensions: object;
  };
  version?: CardVersion | null;
  /** The host's link to the model (protocol 1.3.1); absent on an older host. */
  href?: string | null;
}

export interface CardActions {
  navigate?: (href: string) => void;
  toast?: (message: string, options?: { type?: 'success' | 'error' | 'info' }) => void;
}

export interface ModelCardProps {
  ctx: ModelCardCtx;
  actions?: CardActions;
  module: { slug: string };
}

export function ModelCard(props: ModelCardProps) {
  setModuleSlug(props.module.slug);
  if (hostTooOld()) return <HostTooOld />;
  return <ModelCardBody {...props} />;
}

function selectedVersion(ctx: ModelCardCtx): CardVersion | null {
  if (!('version' in ctx)) return null;
  const v = ctx.version;
  return v && typeof v.id === 'number' ? v : null;
}

function ModelCardBody({ ctx, actions }: ModelCardProps) {
  const model = ctx.model;
  const modelId = model.id;
  const version = selectedVersion(ctx);
  const versionId = version?.id ?? null;
  const [dto, setDto] = useState<ModelDto | null>(null);
  const [pinned, setPinned] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // bumped to re-read the card before its image URL expires
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (modelId === null) return;
    let live = true;
    setError(null);
    api.model(modelId, versionId).then(
      (answer) => {
        if (!live) return;
        setDto(answer);
        setPinned(answer.pinned === true);
      },
      (e: unknown) => {
        // 403/404: the module's routes are not served here (the customer portal: the routes are not `portal`) or the
        // caller cannot read the model -- the card stays, without the pin button and without an error.
        const status = e instanceof ApiError ? e.status : null;
        if (live && status !== 403 && status !== 404) setError(tr('ui.card.error'));
      },
    );
    return () => {
      live = false;
    };
  }, [modelId, versionId, reload]);

  // the presigned URL lives 10 minutes: re-read the card 30 s before it expires (a page kept open)
  const expiresAt = dto?.image?.url ? dto.image.expiresAt : null;
  useEffect(() => {
    const delay = refreshDelay(expiresAt);
    if (delay === null) return;
    const timer = setTimeout(() => setReload((n) => n + 1), delay);
    return () => clearTimeout(timer);
  }, [expiresAt]);

  async function togglePin() {
    if (modelId === null || pinned === null) return;
    setBusy(true);
    try {
      const result = pinned ? await api.unpin(modelId) : await api.pin(modelId);
      setPinned(result.pinned);
      actions?.toast?.(tr(result.pinned ? 'ui.card.pinned' : 'ui.card.unpinned'), { type: 'success' });
    } catch {
      actions?.toast?.(tr('ui.card.error'), { type: 'error' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <ModelCardView
      model={model}
      status={model.status}
      version={version}
      owner={dto ? dto.modelOwner ?? null : undefined}
      image={dto?.image ?? null}
      href={typeof ctx.href === 'string' ? ctx.href : null}
      pinned={pinned}
      busy={busy}
      error={error}
      onTogglePin={() => void togglePin()}
    />
  );
}
