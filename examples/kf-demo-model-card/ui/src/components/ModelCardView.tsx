/**
 * The model's card, the same in the Overview (ModelCard) and on the module page (ModelCardsPage): the garment image of
 * the version or, without one, the monogram; the name, the style number, the owner (`modelOwner`), the status
 * (Overview) or the number of versions (page), the creation date, the version and a QR code to the model; the Pin /
 * Unpin button and, on the page, Open.
 */
import { useState } from 'react';
import { Badge, Button, Card, CardContent, CardFooter, CardHeader, CardTitle } from '@kf/ui';
import type { CardImage } from '../lib/imageExpiry';
import { qrLink } from '../lib/modelLink';
import { monogram, monogramColor } from '../lib/monogram';
import { localDate, tr } from '../lib/texts';
import { QrCode } from './QrCode';

export type ModelStatus = 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED';

export interface CardModel {
  id: number | null;
  name: string | null;
  styleNumber: string | null;
  createdAt: string | null;
}

export interface CardVersion {
  id: number;
  name: string | null;
}

export interface ModelCardViewProps {
  model: CardModel;
  /** Known in the Overview (the surface's ctx); the module's port does not give it. */
  status?: ModelStatus | null;
  /** On the page, in place of the status. */
  versionCount?: number;
  version: CardVersion | null;
  /** The model's free-text owner (`model.v1.modelOwner`); `undefined` while unknown: no row. */
  owner?: string | null;
  /**
   * The garment image of the version (`model-version.garment-image.v1`, a presigned URL of 10 minutes). Without a URL,
   * or when the browser cannot load it (an expired URL), the card shows the monogram.
   */
  image?: CardImage | null;
  /**
   * The link the host built for the model (`ctx.href` of models.detail.overview.cards): correct on staff and on the
   * portal. Without it (the page, an older host) the card builds `<origin>/models/<id>[?version=]` itself.
   */
  href?: string | null;
  /** `null` while unknown: no pin button. */
  pinned: boolean | null;
  busy?: boolean;
  error?: string | null;
  onTogglePin?: () => void;
  onOpen?: () => void;
}

const STATUS_VARIANT: Record<ModelStatus, 'default' | 'secondary' | 'outline'> = {
  DRAFT: 'secondary',
  SAMPLING: 'outline',
  PRODUCTION: 'default',
  ARCHIVED: 'outline',
};

export function versionLabel(version: CardVersion | null): string {
  if (!version) return tr('ui.card.no-version');
  return version.name?.trim() ? version.name : `#${version.id}`;
}

export function ModelCardView(props: ModelCardViewProps) {
  const { model, status, versionCount, version, owner, image, pinned, busy, error, onTogglePin, onOpen } = props;
  const title = model.name?.trim() ? model.name : tr('ui.card.title');
  // the URL the browser failed to load: the monogram until the card receives another URL
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const imageUrl = image?.url && image.url !== failedUrl ? image.url : null;
  return (
    <Card role="article" aria-label={title} className="w-full max-w-md">
      <CardHeader className="flex flex-row items-center gap-3">
        {imageUrl ? (
          <img
            src={imageUrl}
            alt={tr('ui.card.image', { name: model.name?.trim() ? model.name : '—' })}
            className="size-16 shrink-0 rounded-md border object-cover"
            onError={() => setFailedUrl(imageUrl)}
          />
        ) : (
          <span
            aria-hidden="true"
            className="flex size-12 shrink-0 items-center justify-center rounded-full text-lg font-semibold text-white"
            style={{ backgroundColor: monogramColor(model.id) }}
          >
            {monogram(model.name, model.styleNumber)}
          </span>
        )}
        <CardTitle className="min-w-0 truncate text-base">{model.name?.trim() ? model.name : '—'}</CardTitle>
      </CardHeader>
      <CardContent className="flex gap-4">
        <div className="min-w-0 flex-1">
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">{tr('ui.card.style')}</dt>
            <dd className="truncate">{model.styleNumber ?? '—'}</dd>
            {owner !== undefined && (
              <>
                <dt className="text-muted-foreground">{tr('ui.card.owner')}</dt>
                <dd className="truncate">{owner?.trim() ? owner : '—'}</dd>
              </>
            )}
            {status !== undefined && (
              <>
                <dt className="text-muted-foreground">{tr('ui.card.status')}</dt>
                <dd>{status ? <Badge variant={STATUS_VARIANT[status] ?? 'outline'}>{status}</Badge> : '—'}</dd>
              </>
            )}
            <dt className="text-muted-foreground">{tr('ui.card.created')}</dt>
            <dd>{localDate(model.createdAt)}</dd>
            <dt className="text-muted-foreground">{tr('ui.card.version')}</dt>
            <dd className="truncate">{versionLabel(version)}</dd>
          </dl>
          {versionCount !== undefined && (
            <p className="mt-1 text-sm text-muted-foreground">{tr('ui.page.versions', { count: versionCount })}</p>
          )}
        </div>
        {model.id !== null && (
          <QrCode
            value={qrLink(props.href, model.id, version?.id ?? null)}
            label={tr('ui.card.qr')}
            className="size-24 shrink-0 rounded-sm border"
          />
        )}
      </CardContent>
      {(error || pinned !== null || onOpen) && (
        <CardFooter className="flex flex-wrap items-center gap-2">
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          {pinned !== null && onTogglePin && (
            <Button size="sm" variant={pinned ? 'outline' : 'default'} disabled={busy} onClick={onTogglePin}>
              {tr(pinned ? 'ui.card.unpin' : 'ui.card.pin')}
            </Button>
          )}
          {onOpen && (
            <Button size="sm" variant="ghost" onClick={onOpen}>
              {tr('ui.card.open')}
            </Button>
          )}
        </CardFooter>
      )}
    </Card>
  );
}
