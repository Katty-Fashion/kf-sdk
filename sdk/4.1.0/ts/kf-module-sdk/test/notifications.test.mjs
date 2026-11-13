// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/NotificationsTest.java. Do not edit by hand.
//
// Notifications against a platform stub: the request it sends, the local refusals (nothing sent), the retry
// of a transient answer with the SAME idempotency key, and no retry of a permanent one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { KfException, KfModule, NotificationOptions, NotificationRejected, setSdkLogger } from '../dist/index.js';

const TOKEN = 'kfm_notifications-token';
const TENANT = '00000000-0000-0000-0000-00000000000a';

setSdkLogger({ info() {}, warn() {}, error() {} });

async function platform() {
  const state = { answers: [], urls: [], authorizations: [], bodies: [] };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.urls.push(`${req.method} ${req.url}`);
      state.authorizations.push(req.headers.authorization);
      state.bodies.push(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      const [status, body] = state.answers[Math.min(state.bodies.length, state.answers.length) - 1];
      res.writeHead(status, { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, 'notif-mod', TOKEN, 'secret', null);
  kf.notifications().withRetryDelays([10, 20]);
  return { server, state, kf };
}

test('send posts the wire form with the module token', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([202, '{"accepted":2,"duplicates":0}']);
    const result = await kf.notifications().send(TENANT, 'author-changed', ['sub-1'], { modelName: 'X' });
    assert.equal(result.accepted, 2);
    assert.equal(result.duplicates, 0);
    assert.deepStrictEqual(state.urls, ['POST /api/v1/platform/modules/notif-mod/notifications']);
    assert.equal(state.authorizations[0], `Bearer ${TOKEN}`);
    const body = state.bodies[0];
    assert.equal(body.tenantId, TENANT);
    assert.equal(body.key, 'author-changed');
    assert.deepStrictEqual(body.recipients, ['sub-1']);
    assert.deepStrictEqual(body.variables, { modelName: 'X' });
    assert.ok(!('deepLink' in body));
    assert.ok(!('channels' in body));
    assert.match(body.idempotencyKey, /^[0-9a-f-]{36}$/, 'an idempotency key is always sent');
  } finally {
    server.close();
  }
});

test('the options travel as given', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([202, '{"accepted":1,"duplicates":0}']);
    const key = '7a07a07a-0000-4000-8000-000000000207';
    await kf.notifications().send(TENANT, 'author-changed', ['sub-1'], { modelName: 'X' },
      { deepLink: '/models/42', channels: ['in_app'], idempotencyKey: key });
    const body = state.bodies[0];
    assert.equal(body.deepLink, '/models/42');
    assert.deepStrictEqual(body.channels, ['in_app']);
    assert.equal(body.idempotencyKey, key);
  } finally {
    server.close();
  }
});

test('recipientRoles travel and recipients may be empty', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([202, '{"accepted":2,"duplicates":0}']);
    const result = await kf.notifications().send(TENANT, 'author-changed', [], { modelName: 'X' },
      NotificationOptions.roles('TENANT_ADMIN', 'QC', 'TENANT_ADMIN'));
    assert.equal(result.accepted, 2);
    const body = state.bodies[0];
    assert.deepStrictEqual(body.recipients, [], 'recipients is sent, empty: a platform before 1.3 refuses it');
    assert.deepStrictEqual(body.recipientRoles, ['TENANT_ADMIN', 'QC'], 'a repeated role counts once');
  } finally {
    server.close();
  }
});

test('without roles the body has no recipientRoles', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([202, '{"accepted":1,"duplicates":0}']);
    await kf.notifications().send(TENANT, 'author-changed', ['sub-1'], { modelName: 'X' }, { deepLink: '/models/42' });
    assert.ok(!('recipientRoles' in state.bodies[0]));
  } finally {
    server.close();
  }
});

test('recipientRoles local refusals send nothing', async () => {
  const { server, state, kf } = await platform();
  try {
    const n = kf.notifications();
    const eleven = Array.from({ length: 11 }, (_, i) => `ROLE_${i + 1}`);
    const calls = [
      () => n.send(TENANT, 'ok', [], {}, { recipientRoles: [] }),
      () => n.send(TENANT, 'ok', [], {}, NotificationOptions.roles(' ')),
      () => n.send(TENANT, 'ok', [], {}, NotificationOptions.roles('tenant_admin')),
      () => n.send(TENANT, 'ok', [], {}, { recipientRoles: eleven }),
      () => n.send(TENANT, 'ok', null, {}),
    ];
    for (const call of calls) {
      await assert.rejects(call(), (e) => {
        assert.ok(e instanceof KfException);
        assert.equal(e.errorClass, 'permanent');
        assert.equal(e.problem.status, 0);
        return true;
      });
    }
    assert.equal(state.bodies.length, 0, 'a local refusal sends nothing');
  } finally {
    server.close();
  }
});

test('a 422 is permanent and not retried', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([422, '{"type":"https://kf.ro/problems/notification-rejected","status":422,'
      + `"detail":"The active version of notif-mod does not declare the notification 'x'.",`
      + '"reason":"unknown-key","class":"permanent"}']);
    await assert.rejects(kf.notifications().send(TENANT, 'x', ['sub-1'], {}), (e) => {
      assert.ok(e instanceof NotificationRejected);
      assert.equal(e.errorClass, 'permanent');
      assert.equal(e.problem.status, 422);
      assert.equal(e.reason, 'unknown-key');
      assert.ok(e.message.includes('does not declare the notification'));
      assert.ok(!e.message.includes(TOKEN));
      return true;
    });
    assert.equal(state.bodies.length, 1, 'no retry');
  } finally {
    server.close();
  }
});

test('a 503 is retried with the same idempotency key', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([503, '{"status":503,"detail":"busy","class":"transient"}']);
    state.answers.push([202, '{"accepted":0,"duplicates":1}']);
    const result = await kf.notifications().send(TENANT, 'author-changed', ['sub-1'], { modelName: 'X' });
    assert.equal(state.bodies.length, 2);
    assert.equal(state.bodies[0].idempotencyKey, state.bodies[1].idempotencyKey);
    assert.deepStrictEqual(state.bodies[0], state.bodies[1], 'the retry sends the same request');
    assert.equal(result.duplicates, 1);
  } finally {
    server.close();
  }
});

test('transient until the schedule ends is a KfException', async () => {
  const { server, state, kf } = await platform();
  try {
    state.answers.push([503, '{"status":503,"detail":"busy","class":"transient"}']);
    await assert.rejects(kf.notifications().send(TENANT, 'author-changed', ['sub-1'], { modelName: 'X' }), (e) => {
      assert.ok(e instanceof KfException);
      assert.equal(e.errorClass, 'transient');
      return true;
    });
    assert.equal(state.bodies.length, 3, 'the first try and one per step of the schedule');
  } finally {
    server.close();
  }
});

test('local refusals send nothing', async () => {
  const { server, state, kf } = await platform();
  try {
    const n = kf.notifications();
    const many = Array.from({ length: 101 }, (_, i) => `s${i + 1}`);
    const calls = [
      () => n.send(null, 'author-changed', ['s'], {}),
      () => n.send(TENANT, 'm.notif-mod.x', ['s'], {}),
      () => n.send(TENANT, 'Bad', ['s'], {}),
      () => n.send(TENANT, 'ok', [], {}),
      () => n.send(TENANT, 'ok', many, {}),
      () => n.send(TENANT, 'ok', [' '], {}),
      () => n.send(TENANT, 'ok', ['s'], { v: [1] }),
      () => n.send(TENANT, 'ok', ['s'], {}, { deepLink: 'https://evil.example' }),
      () => n.send(TENANT, 'ok', ['s'], {}, { channels: ['sms'] }),
    ];
    for (const call of calls) {
      await assert.rejects(call(), (e) => {
        assert.ok(e instanceof KfException);
        assert.equal(e.errorClass, 'permanent');
        assert.equal(e.problem.status, 0);
        return true;
      });
    }
    assert.equal(state.bodies.length, 0, 'a local refusal sends nothing');
  } finally {
    server.close();
  }
});
