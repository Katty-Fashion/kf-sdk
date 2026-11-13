// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ContextRouteTest.java. Do not edit by hand.
//
// The Node adapter of the module's own routes: only a context signed for this method and path reaches the handler.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { HookResult, KfModule, Signatures, route, setSdkLogger } from '../dist/index.js';

const SECRET = 'route-secret';
setSdkLogger({ info() {}, warn() {}, error() {} });

async function start() {
  const seen = { ctx: null };
  const kf = KfModule.of(null, 'route-mod', null, SECRET, null);
  const server = createServer(route(kf, (ctx) => {
    seen.ctx = ctx;
    return new HookResult(200, JSON.stringify({ tenant: ctx.tenant }));
  }));
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, seen };
}

async function get(server, path, contextPath, signed) {
  const ts = Math.floor(Date.now() / 1000);
  const json = JSON.stringify({ v: 1, tenant: 't-1', user: 'sub-1', locale: 'ro', rights: [], method: 'GET', path: contextPath, query: null, ts, requestId: 'r-1' });
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(ts), Buffer.from(`${context}.`, 'ascii'));
  const headers = signed ? { 'X-KF-Context': context, 'X-KF-Timestamp': String(ts), 'X-KF-Signature': signature } : {};
  return fetch(`http://127.0.0.1:${server.address().port}${path}`, { headers });
}

test('a context signed for GET /events reaches the handler, actor user', async () => {
  const { server, seen } = await start();
  try {
    const response = await get(server, '/events', '/events', true);
    assert.equal(response.status, 200, await response.clone().text());
    assert.equal(seen.ctx.tenant, 't-1');
    assert.equal(seen.ctx.actor.type, 'user');
  } finally {
    server.close();
  }
});

test('no context: 401 RFC 9457, class permanent, the handler is not called', async () => {
  const { server, seen } = await start();
  try {
    const response = await get(server, '/events', '/events', false);
    assert.equal(response.status, 401);
    assert.ok((response.headers.get('content-type') ?? '').startsWith('application/problem+json'));
    assert.match(await response.text(), /"class":"permanent"/);
    assert.equal(seen.ctx, null);
  } finally {
    server.close();
  }
});

test('a context signed for another path is refused', async () => {
  const { server, seen } = await start();
  try {
    const response = await get(server, '/events', '/other', true);
    assert.equal(response.status, 401, await response.clone().text());
    assert.equal(seen.ctx, null);
  } finally {
    server.close();
  }
});
