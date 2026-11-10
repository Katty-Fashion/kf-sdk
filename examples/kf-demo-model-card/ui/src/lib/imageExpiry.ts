/**
 * The garment image of a card is a presigned URL of `model-version.garment-image.v1` (protocol 1.3.1): valid 10
 * minutes, with `expiresAt` the instant it expires. A page kept open longer re-reads it a little before that instant;
 * an `<img>` that fails anyway (an expired URL, a network error) falls back to the monogram.
 */

/** How long before `expiresAt` the card re-reads the image. */
export const REFRESH_MARGIN_MS = 30_000;
/** The shortest delay ever scheduled (an expiry already passed or too close: no tight loop). */
export const MIN_REFRESH_MS = 5_000;

export interface CardImage {
  url: string | null;
  expiresAt: string | null;
}

/** Milliseconds until the image must be re-read, or `null` when there is nothing to refresh. */
export function refreshDelay(expiresAt: string | null | undefined, now: number = Date.now()): number | null {
  if (!expiresAt) return null;
  const at = Date.parse(expiresAt);
  if (Number.isNaN(at)) return null;
  return Math.max(MIN_REFRESH_MS, at - now - REFRESH_MARGIN_MS);
}

/** The earliest `expiresAt` among the cards that carry an image (a page of cards refreshes once, for all). */
export function earliestExpiry(cards: Array<{ image?: CardImage | null }>): string | null {
  let earliest: string | null = null;
  for (const card of cards) {
    const at = card.image?.url ? card.image.expiresAt : null;
    if (at && (earliest === null || Date.parse(at) < Date.parse(earliest))) earliest = at;
  }
  return earliest;
}
