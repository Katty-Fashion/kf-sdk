/**
 * The stand-in of `@kf/host-sdk` in the module's UI tests (copied from kf-feedback, reduced to what the bundle uses).
 * Same members as the host's (`sdk/ui/kf-host.d.ts`, host API 4): `http` answers from the routes a test declares with
 * `onHttp`, records every call in `calls`, throws `ApiError` on a non-2xx exactly as the host does; `apiVersion` is a
 * live binding a test changes with `setApiVersion`; `t` applies the host's fallback chain (the texts a test sets with
 * `setTexts`, else the fallback, else the slug) with `{name}` placeholders and records the slug it was asked for in
 * `textSlugs`.
 */

export let apiVersion = 4;
export const reactVersion = '19.2.4';

export class ApiError extends Error {
  readonly status: number;
  readonly problem: unknown;
  constructor(status: number, message: string, problem: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

export interface HttpAnswer {
  status: number;
  body?: unknown;
}

export interface HttpCall {
  method: string;
  /** The full proxied path, with the query. */
  url: string;
  /** The path without the query. */
  path: string;
}

type Handler = (call: HttpCall) => HttpAnswer | Promise<HttpAnswer>;

const routes: Array<{ method: string; path: string; handler: Handler }> = [];
export const calls: HttpCall[] = [];
export const textSlugs = new Set<string>();
let texts: Record<string, string> = {};
let currentLocale = 'en';

/** Declares an answer for `method path` (path without query; the last declaration wins). */
export function onHttp(method: string, path: string, answer: HttpAnswer | Handler): void {
  const handler: Handler = typeof answer === 'function' ? answer : () => answer;
  routes.unshift({ method: method.toUpperCase(), path, handler });
}

export function setTexts(next: Record<string, string>, locale = 'en'): void {
  texts = next;
  currentLocale = locale;
}

export function setApiVersion(version: number): void {
  apiVersion = version;
}

export function resetHost(): void {
  routes.length = 0;
  calls.length = 0;
  textSlugs.clear();
  texts = {};
  currentLocale = 'en';
  apiVersion = 4;
}

export async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const url = path.startsWith('/api/proxy') ? path : `/api/proxy${path.startsWith('/') ? '' : '/'}${path}`;
  const [bare] = url.split('?');
  const method = (init?.method ?? 'GET').toUpperCase();
  const call: HttpCall = { method, url, path: bare };
  calls.push(call);
  const route = routes.find((r) => r.method === method && r.path === bare);
  if (!route) throw new ApiError(404, `no test route for ${method} ${bare}`, null);
  const answer = await route.handler(call);
  if (answer.status < 200 || answer.status >= 300) {
    throw new ApiError(answer.status, `HTTP ${answer.status}`, answer.body ?? null);
  }
  if (answer.status === 204) return undefined as T;
  return answer.body as T;
}

export function moduleUrl(module: string, path = ''): string {
  return `/api/proxy/m/${module}/${path.replace(/^\/+/, '')}`;
}

export function locale(): string {
  return currentLocale;
}

export function onLocaleChange(_listener: (locale: string) => void): () => void {
  return () => undefined;
}

export function t(
  slug: string,
  key: string,
  options?: { params?: Record<string, string | number>; fallback?: string },
): string {
  textSlugs.add(slug);
  const template = texts[key] ?? options?.fallback ?? slug;
  const params = options?.params;
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole,
  );
}
