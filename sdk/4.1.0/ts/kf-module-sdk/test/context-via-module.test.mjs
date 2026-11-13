// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ContextViaModuleTest.java. Do not edit by hand.
//
// Protocol 2.0: actor.via = module:{slug} -- a user via a module (the user kept) and the system
// via a module (a background job, no user, no rights); a bad slug, a hookId, a user on the system or rights on the
// system are refused; the old actors have no calling module.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Actor, ErrorClass, Rejected, RequestContext, Signatures } from '../dist/index.js';

const SECRET = 'via-module-secret';
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

test('a user via a module is accepted, with the user kept', () => {
  const ctx = verify('"oidc-sub-1"', '{"type":"user","via":"module:kf-3d","hookId":null}', '["api.v1.m.kf-qc.checks.get"]');
  assert.equal(ctx.actor.type, 'user');
  assert.equal(ctx.actor.via, 'module:kf-3d');
  assert.equal(ctx.actor.viaModule(), 'kf-3d');
  assert.equal(ctx.user, 'oidc-sub-1');
});

test('the system via a module is accepted without user or rights', () => {
  const ctx = verify('null', '{"type":"system","via":"module:kf-3d","hookId":null}', '[]');
  assert.equal(ctx.actor.type, 'system');
  assert.equal(ctx.actor.viaModule(), 'kf-3d');
  assert.equal(ctx.user, null);
});

test('a bad slug, a user on the system, rights on the system or a hookId are refused', () => {
  refused('"oidc-sub-1"', '{"type":"user","via":"module:Bad","hookId":null}', '[]', 'is not module:{slug}');
  refused('"oidc-sub-1"', '{"type":"user","via":"module:","hookId":null}', '[]', 'is not module:{slug}');
  refused('"oidc-sub-1"', '{"type":"system","via":"module:kf-3d","hookId":null}', '[]', 'actor system has no user');
  refused('null', '{"type":"system","via":"module:kf-3d","hookId":null}', '["x"]', 'has no rights');
  refused('"oidc-sub-1"', '{"type":"user","via":"module:kf-3d","hookId":"h-1"}', '[]', 'has no hookId');
});

test('another via stays refused, and the old actors have no calling module', () => {
  refused('"oidc-sub-1"', '{"type":"user","via":"hook","hookId":null}', '[]', 'actor user has no via and no hookId');
  assert.equal(Actor.USER.viaModule(), null);
  assert.equal(Actor.PLATFORM.viaModule(), null);
});
