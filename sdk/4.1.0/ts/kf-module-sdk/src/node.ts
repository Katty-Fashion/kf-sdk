// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfHttpServer.java + ro/kf/sdk/spring/KfSpringConfiguration.java (the
// idiomatic Node adapter). Do not edit by hand: fix Java, then re-derive.
import { createReadStream, statSync } from 'node:fs';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { HookResult } from './hookResult.js';
import type { KfModule } from './kfModule.js';
import { log } from './log.js';
import { Rejected, RequestContext } from './requestContext.js';

type Next = (err?: unknown) => void;

/** A request the adapter verified: the context and the raw body it read (the stream is consumed). */
export interface KfRequest extends IncomingMessage {
  kfContext?: RequestContext;
  kfBody?: Buffer;
}

/** A module route: receives the verified context and the raw body, answers status + JSON. */
export type RouteHandler = (context: RequestContext, req: IncomingMessage, body: Buffer) => HookResult | Promise<HookResult>;

export interface NodeHandlerOptions {
  /** Directory of the module's UI bundles, served at `GET|HEAD /ui/**` (the Java adapter's `classpath:static/ui/`). */
  uiDir?: string;
}

/**
 * `(req, res, next?)` handler for node:http, Express or Connect-style servers (the Node twin of the Java adapters):
 * - `POST {hookPath}`: the platform callbacks (`KfModule.handle`); another method is `405`;
 * - `GET|HEAD /ui/**` from `uiDir`: the module's UI contributions (`serveUi`);
 * - any other path outside `KfModule.isPublicPath` must carry a context signed by the platform for its method and path; otherwise it is answered `401` with an RFC 9457 body (`class: permanent`) and never reaches
 *   `next()`. A verified request carries `req.kfContext` (the `RequestContext`) and `req.kfBody` (the raw body: the
 *   stream was read to verify the signature);
 * - public paths go to `next()` untouched (`404` without `next`).
 *
 * Mount it before any body parser: the signatures need the raw body.
 */
export function nodeHandler(kf: KfModule, opts: NodeHandlerOptions = {}): (req: IncomingMessage, res: ServerResponse, next?: Next) => void {
  return (req, res, next) => {
    const path = requestPath(req);
    const notFound = () => (next ? next() : write(res, new HookResult(404, '{"error":"not found"}')));
    if (path === kf.hookPath()) {
      if (req.method !== 'POST') {
        res.writeHead(405).end();
        return;
      }
      readBody(req)
        .then((body) => kf.handle(body, header(req, 'x-kf-timestamp'), header(req, 'x-kf-signature'), {
          contextHeader: header(req, 'x-kf-context'), proofHeader: header(req, 'x-kf-context-proof'),
        }))
        .then((result) => write(res, result))
        .catch((e) => {
          log().error('KF SDK: callback failed', e);
          write(res, handlerFailed());
        });
      return;
    }
    if (opts.uiDir != null && serveUi(req, res, { dir: opts.uiDir })) return;
    if (kf.isPublicPath(path)) {
      notFound();
      return;
    }
    readBody(req)
      .then((body) => {
        const ctx = verified(kf, req, path, body);
        const kfReq = req as KfRequest;
        kfReq.kfContext = ctx;
        kfReq.kfBody = body;
        notFound();
      })
      .catch((e) => {
        if (e instanceof Rejected) {
          log().warn(`KF SDK: ${req.method} ${req.url} refused: ${e.problem.detail}`);
          write(res, e.toResult());
        } else {
          log().error('KF SDK: reading the request failed', e);
          write(res, handlerFailed());
        }
      });
  };
}

/**
 * One module route behind the verified context (the Node twin of `KfHttpServer.route`): the request is answered
 * `401` (`class: permanent`, RFC 9457) unless it carries a valid context signed by the platform for this method and
 * path; otherwise `handler` gets the `RequestContext`. A handler that throws answers `500`.
 */
export function route(kf: KfModule, handler: RouteHandler): (req: IncomingMessage, res: ServerResponse) => void {
  return (req, res) => {
    readBody(req)
      .then(async (body) => {
        let result: HookResult;
        try {
          result = await handler(verified(kf, req, requestPath(req), body), req, body);
        } catch (e) {
          if (e instanceof Rejected) {
            result = e.toResult();
          } else {
            log().error(`KF SDK: route ${requestPath(req)} failed`, e);
            result = handlerFailed();
          }
        }
        write(res, result);
      })
      .catch((e) => {
        log().error('KF SDK: reading the request failed', e);
        write(res, handlerFailed());
      });
  };
}

function verified(kf: KfModule, req: IncomingMessage, path: string, body: Buffer): RequestContext {
  const ctx = kf.verifyContext(header(req, 'x-kf-context'), header(req, 'x-kf-timestamp'), header(req, 'x-kf-signature'), body,
    header(req, 'x-kf-context-proof'));
  if (ctx.method !== req.method || ctx.path !== path) {
    throw new Rejected(`the context was signed for ${ctx.method} ${ctx.path}, not for ${req.method} ${path}`);
  }
  return ctx;
}

function handlerFailed(): HookResult {
  return new HookResult(500, JSON.stringify({ type: 'https://kf.ro/problems/handler-failed', title: 'Handler failed', status: 500, class: 'transient' }),
    HookResult.PROBLEM_CONTENT_TYPE);
}

/** The decoded path the module received. */
function requestPath(req: IncomingMessage): string {
  const raw = (req.url ?? '').split('?')[0];
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

function header(req: IncomingMessage, name: string): string | undefined {
  const v = req.headers[name];
  return Array.isArray(v) ? v[0] : v;
}

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolveBody, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('error', reject);
    req.on('end', () => resolveBody(Buffer.concat(chunks)));
  });
}

function write(res: ServerResponse, result: HookResult): void {
  const out = Buffer.from(result.body ?? '', 'utf8');
  res.writeHead(result.status, { 'Content-Type': result.contentType, 'Content-Length': out.length });
  res.end(out);
}

/**
 * Explicit MIME types: `.mjs` MUST be `text/javascript`, browsers refuse module scripts served as
 * application/octet-stream.
 */
const UI_TYPES: Record<string, string> = {
  '.mjs': 'text/javascript;charset=UTF-8',
  '.js': 'text/javascript;charset=UTF-8',
  '.css': 'text/css;charset=UTF-8',
  '.map': 'application/json',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

export interface ServeUiOptions {
  /** Directory with the built UI files. */
  dir: string;
  /** URL prefix inside the module (after the platform strips /m/{slug}); default `/ui/`. */
  prefix?: string;
}

/**
 * Serves `GET|HEAD {prefix}**` from `dir` with explicit MIME types, `Cache-Control: no-cache` (new module versions show
 * up at once) and `X-Content-Type-Options: nosniff`. Returns false when the request is not for it (method or prefix),
 * true when it answered (200 or 404). The UI paths are public (`KfModule.isPublicPath`): the platform's asset proxy
 * fetches them without a context.
 */
export function serveUi(req: IncomingMessage, res: ServerResponse, opts: ServeUiOptions): boolean {
  const prefix = opts.prefix ?? '/ui/';
  const path = requestPath(req);
  if (!path.startsWith(prefix) || (req.method !== 'GET' && req.method !== 'HEAD')) return false;
  const rel = path.substring(prefix.length);
  const type = UI_TYPES[extname(rel).toLowerCase()];
  const root = resolve(opts.dir);
  const file = resolve(join(root, normalize(rel)));
  let size = -1;
  if (rel !== '' && type != null && !rel.includes('..') && !rel.includes('\\') && !rel.startsWith('/') && file.startsWith(root + sep)) {
    try {
      const st = statSync(file);
      if (st.isFile()) size = st.size;
    } catch {
      /* not found */
    }
  }
  if (size < 0) {
    write(res, new HookResult(404, '{"error":"not found"}'));
    return true;
  }
  res.writeHead(200, { 'Content-Type': type, 'Content-Length': size, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
  if (req.method === 'HEAD') res.end();
  else createReadStream(file).pipe(res);
  return true;
}
