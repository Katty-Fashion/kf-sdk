// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ContextPersonTest.java. Do not edit by hand.
//
// person in the signed context (protocol 1.2; sdk-protocol.md §9.2): on the routes
// audience: member the platform adds, as the last key after requestId, "person": {"email": <text|null>, "displayName":
// <text|null>} -- the caller's e-mail and display name at the time of the request. Absent on every other route and on a
// system context. A module never authorizes on it (the identity stays user); a context without it is read as before.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Actor, ErrorClass, Rejected, RequestContext, Signatures } from '../dist/index.js';

const SECRET = 'person-secret';
const NOW = 1760000000;
const USER = '{"type":"user","via":null,"hookId":null}';
const HOOK = '{"type":"system","via":"hook","hookId":"h-1"}';

function verify(user, actor, person) {
  const json = `{"v":1,"tenant":"t-1","user":${user},"actor":${actor},"locale":"ro","rights":[],"method":"POST",`
    + `"path":"/tickets","query":null,"ts":${NOW},"requestId":"r-1"${person == null ? '' : ',"person":' + person}}`;
  const context = Buffer.from(json, 'utf8').toString('base64url');
  const signature = Signatures.sign(SECRET, String(NOW), Buffer.from(`${context}.`, 'ascii'));
  return RequestContext.verify(context, String(NOW), signature, Buffer.alloc(0), SECRET, NOW);
}

function refused(user, actor, person, reason) {
  let rejected = null;
  try {
    verify(user, actor, person);
  } catch (e) {
    rejected = e;
  }
  assert.ok(rejected instanceof Rejected, 'expected Rejected');
  assert.equal(rejected.problem.status, 401);
  assert.equal(rejected.problem.type, 'https://kf.ro/problems/invalid-context');
  assert.equal(rejected.problem.errorClass, ErrorClass.PERMANENT);
  assert.ok(rejected.problem.detail.includes(reason), `${reason} not in ${rejected.problem.detail}`);
}

test('a member route context carries person: e-mail and display name are read', () => {
  const ctx = verify('"sub-1"', USER, '{"email":"ana@kf.ro","displayName":"Ana Pop"}');
  assert.deepEqual({ ...ctx.person }, { email: 'ana@kf.ro', displayName: 'Ana Pop' });
  assert.equal(ctx.user, 'sub-1');
});

test('person with null fields is accepted; a missing field reads null', () => {
  assert.deepEqual({ ...verify('"sub-1"', USER, '{"email":null,"displayName":"Ana Pop"}').person }, { email: null, displayName: 'Ana Pop' });
  assert.deepEqual({ ...verify('"sub-1"', USER, '{"email":"ana@kf.ro"}').person }, { email: 'ana@kf.ro', displayName: null });
});

test('a context without person is accepted and reads person null (every route but member, a platform before 1.2)', () => {
  assert.equal(verify('"sub-1"', USER, null).person, null);
  assert.equal(verify('"sub-1"', USER, 'null').person, null);
});

test('person that is not an object, or with a field that is not text, is refused', () => {
  refused('"sub-1"', USER, '"ana@kf.ro"', 'context person must be an object');
  refused('"sub-1"', USER, '{"email":42,"displayName":"Ana"}', 'context person.email must be text or null');
  refused('"sub-1"', USER, '{"email":"a@kf.ro","displayName":[]}', 'context person.displayName must be text or null');
});

test('a system context with person is refused', () => {
  refused('null', HOOK, '{"email":"ana@kf.ro","displayName":"Ana Pop"}', 'a system context carries no person');
});

test('the constructor with the eleven components of SDK 0.7.x still builds a context, without person', () => {
  const ctx = new RequestContext(1, 't', 'u', Actor.USER, 'en', [], 'POST', '/n', null, NOW, 'r');
  assert.equal(ctx.person, null);
});
