/**
 * The module's texts, through the host (`t` of `@kf/host-sdk`, host API 2+): the keys are the manifest's
 * (`../kf-module-conf.json`, `i18n.en` / `ro`, prefix `ui.`), the slug is the one the host gave (`moduleSlug()`), so the
 * dev build `<slug>-dev` reads its own texts.
 */
import { locale, t } from '@kf/host-sdk';
import { moduleSlug } from './moduleSlug';

export function tr(key: string, params?: Record<string, string | number>): string {
  return t(moduleSlug(), key, params ? { params } : undefined);
}

/** The local date of an ISO timestamp, in the user's language; `—` without one, the raw value when unreadable. */
export function localDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return date.toLocaleDateString(locale());
  } catch {
    return date.toLocaleDateString();
  }
}
