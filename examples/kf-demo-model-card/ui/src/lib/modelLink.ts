/**
 * The address the card's QR code carries: the model's page in the host, `<origin>/models/<id>`, with
 * `?version=<versionId>` when a version is known (the selected one in the Overview, the current one on the page).
 */
/** The QR's link: the host's `href` when it gave one (absolute or a path of the host), else {@link modelLink}. */
export function qrLink(href: string | null | undefined, modelId: number, versionId?: number | null): string {
  if (typeof href === 'string' && href.trim() !== '') {
    return href.startsWith('/') ? `${window.location.origin.replace(/\/+$/, '')}${href}` : href;
  }
  return modelLink(undefined, modelId, versionId);
}

export function modelLink(
  origin: string | undefined,
  modelId: number,
  versionId?: number | null,
): string {
  const base = (origin ?? window.location.origin).replace(/\/+$/, '');
  const link = `${base}/models/${modelId}`;
  return versionId === null || versionId === undefined ? link : `${link}?version=${versionId}`;
}
