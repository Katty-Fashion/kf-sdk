// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RegistrationErrorTest.java. Do not edit by hand.
//
// Registration errors must name their cause. with the platform unreachable the SDK logged a
// bare error; fetch keeps the reason (refused, unresolvable host, ...) on the cause chain. "Backend down" and "wrong
// host" read the same without it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { KfModule, setSdkLogger } from '../dist/index.js';

const TOKEN = 'kfm_0123456789abcdef-test-only';

test('a refused connection names its cause instead of a bare "fetch failed"', async () => {
  const lines = [];
  setSdkLogger({ info: (m) => lines.push(m), warn: (m) => lines.push(m), error: (m) => lines.push(m) });
  const server = createServer();
  const port = await new Promise((r) => server.listen(0, '127.0.0.1', () => {
    const p = server.address().port;
    server.close(() => r(p));
  }));
  const kf = KfModule.fromEnv({
    KF_PLATFORM_URL: `http://127.0.0.1:${port}`, KF_MODULE_SLUG: 'sdk-error-test', KF_MODULE_TOKEN: TOKEN,
    KF_MODULE_MANIFEST: 'does-not-exist/kf-module-conf.json',
  });
  try {
    kf.start();
    const deadline = Date.now() + 10_000;
    while (kf.lastRegistrationError() === null && Date.now() < deadline) await new Promise((r) => setTimeout(r, 20));

    const error = kf.lastRegistrationError();
    assert.ok(error !== null, 'the first attempt against a closed port must fail within the deadline');
    assert.ok(error.startsWith('TypeError: fetch failed'), error);
    assert.ok(!error.includes('null'), `a missing message must not be printed as 'null': ${error}`);
    assert.ok(error.includes('(cause: '), `the cause chain must be named: ${error}`);
    assert.ok(error.includes('ECONNREFUSED'), `the refusal must be named: ${error}`);
    for (const line of [error, ...lines]) assert.ok(!line.includes(TOKEN), 'the module token must never reach an error message');
  } finally {
    kf.stop();
  }
});
