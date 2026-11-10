/**
 * The module's own routes (`../kf-module-conf.json` capabilities.routes), through the host's proxy:
 * `http(moduleUrl(moduleSlug(), path))`. A non-2xx answer is thrown as `ApiError` by the host.
 */
import { http, moduleUrl } from '@kf/host-sdk';
import type { CardImage } from './imageExpiry';
import { moduleSlug } from './moduleSlug';

export interface ModelVersionDto {
  id: number;
  name: string | null;
  status: string | null;
  createdAt: string | null;
}

/** The card of a model: `model.v1` (with `modelOwner`, protocol 1.3.1), the garment image of a version and the pin. */
export interface ModelDto {
  modelId: number;
  name: string | null;
  styleNumber: string | null;
  /** The model's free-text owner (protocol 1.3.1); `null` when not filled in. */
  modelOwner: string | null;
  createdAt: string | null;
  currentVersionId: number | null;
  versions: ModelVersionDto[];
  /** A presigned URL of 10 minutes (`model-version.garment-image.v1`), or `null`: then the card shows the monogram. */
  image: CardImage | null;
  pinned: boolean;
}

export interface PinnedCardDto extends ModelDto {
  pinnedAt: string | null;
}

export interface CardsDto {
  items: PinnedCardDto[];
  missing: number[];
}

/** A page of the tenant's models (`model.v1` without `ids`, protocol 1.3.1), by id. */
export interface ModelsPageDto {
  items: ModelDto[];
  page: number;
  size: number;
  total: number;
}

export interface PinDto {
  modelId: number;
  pinned: boolean;
}

/** The page size the module page asks for (the module serves 1 to 48). */
export const PAGE_SIZE = 12;

function call<T>(path: string, init?: RequestInit): Promise<T> {
  return http<T>(moduleUrl(moduleSlug(), path), init);
}

export const api = {
  /** The card of one model, with the image of `versionId` (the version the page has selected) when given. */
  model: (modelId: number, versionId?: number | null) =>
    call<ModelDto>(versionId == null ? `/models/${modelId}` : `/models/${modelId}?versionId=${versionId}`),
  models: (page: number, size = PAGE_SIZE) => call<ModelsPageDto>(`/models?page=${page}&size=${size}`),
  cards: () => call<CardsDto>('/cards'),
  pin: (modelId: number) => call<PinDto>(`/pins/${modelId}`, { method: 'PUT' }),
  unpin: (modelId: number) => call<PinDto>(`/pins/${modelId}`, { method: 'DELETE' }),
};
