// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ContextPlatformActorTest.java. Do not edit by hand.
//
// The actor {type: system, via: platform, hookId: null} (protocol 1.1; sdk-protocol.md §9.2): the platform
// calls a route the module declares in provides as the system, without a user and without a hook. Coherent only with
// user null, hookId null and rights []; a system context never yields a user the module could deduce.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Actor, ErrorClass, Rejected, RequestContext, Signatures } from '../dist/index.js';

const SECRET = 'platform-actor-secret';
const NOW = 1760000000;

function verify(user, actor, rights) {
  const json = `{"v":1,"tenant":"t-1","user":${user},"actor":${actor},"locale":"ro","rights":${rights},"method":"GET",`
    + `"path":"/stations","query":null,"ts":${NOW},"requestId":"r-1"}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(NOW), Buffer.from(`${context}.`, 'ascii'));
  return RequestContext.verify(context, String(NOW), signature, Buffer.alloc(0), SECRET, NOW);
}

function refused(user, actor, rights, reason) {
  let rejected = null;
  try {
    verify(user, actor, rights);
  } catch (e) {
    rejected = e;
  }
  assert.ok(rejected instanceof Rejected, 'expected Rejected');
  assert.equal(rejected.problem.status, 401);
  assert.equal(rejected.problem.errorClass, ErrorClass.PERMANENT);
  assert.ok(rejected.problem.detail.includes(reason), `${reason} not in ${rejected.problem.detail}`);
}

test('system via platform, user null, hookId null, rights [] is accepted; no user is deduced', () => {
  const ctx = verify('null', '{"type":"system","via":"platform","hookId":null}', '[]');
  assert.equal(ctx.actor.type, 'system');
  assert.equal(ctx.actor.via, 'platform');
  assert.equal(ctx.actor.hookId, null);
  assert.equal(ctx.user, null);
  assert.equal(ctx.rights.length, 0);
  assert.equal(ctx.actor, Actor.PLATFORM);
});

test('system via platform with a hookId is refused', () => {
  refused('null', '{"type":"system","via":"platform","hookId":"h-1"}', '[]', 'actor system via platform has no hookId');
});

test('system via platform with a user is refused', () => {
  refused('"sub-1"', '{"type":"system","via":"platform","hookId":null}', '[]', 'actor system has no user');
});

test('system via platform with rights is refused', () => {
  refused('null', '{"type":"system","via":"platform","hookId":null}', '["api.v1.m.c11.stations.get"]',
    'actor system via platform has no rights');
});

test('system via an unknown channel is refused', () => {
  refused('null', '{"type":"system","via":"cron","hookId":null}', '[]', 'actor system requires via = hook or platform');
});
