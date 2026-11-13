// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/conformance/ConformanceSuiteTest.java + FixtureRunner.java. Do not edit by hand.
//
// The TS runner of the shared conformance suite: every JSON file under sdk/conformance/ (relative to this
// package: ../../conformance) becomes one test. Each fixture is checked against fixture.schema.json with explicit key
// checks (required, additionalProperties: false, the area enum, id = <area>/<file>, a non-blank source) and run by
// area. An area the runner does not know fails, a failing fixture fails, and zero fixtures fail the suite: there is
// no skip. Runs on the BUILT package (dist/): `npm test` builds first.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AuditForbidden, AuditRecord, AuditUnauthorized, DeliveryContext, FlowEvents, FlowRules, KfException, KfModule, ManifestCheck, Manifest, ModelHooks, NotificationRejected, OrderServiceLine, PlatformRefusal, RequestContext, Rejected, Signatures, Surface, setSdkLogger } from '../dist/index.js';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'conformance');
const TOKEN = 'kfm_conformance-token-never-logged';
const SECRET = 'conformance-secret';

// the SDK logs every refusal: keep the test output readable
setSdkLogger({ info() {}, warn() {}, error() {} });

assert.ok(existsSync(ROOT) && statSync(ROOT).isDirectory(), `the conformance suite is not at ${ROOT}`);
const schema = read(join(ROOT, 'fixture.schema.json'));
const shape = schemaOf(schema);

const fixtures = [];
(function walk(dir) {
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path);
    else if (name.endsWith('.json') && dir !== ROOT) fixtures.push(path); // fixture.schema.json and other root files
  }
})(ROOT);
fixtures.sort();

test('the suite has fixtures', () => {
  assert.ok(fixtures.length > 0, `0 fixtures found under ${ROOT}`);
});

const ids = new Set();
for (const path of fixtures) {
  const rel = relative(ROOT, path).replaceAll('\\', '/');
  test(rel, async () => {
    const fixture = read(path);
    shape.check(rel, fixture);
    assert.ok(!ids.has(fixture.id), `${rel}: id ${fixture.id} is not unique`);
    ids.add(fixture.id);
    await run(fixture);
  });
}

function read(path) {
  const parsed = JSON.parse(readFileSync(path, 'utf8'));
  assert.ok(parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed), `${path} must be a JSON object`);
  return parsed;
}

/** The parts of fixture.schema.json a runner enforces, read from the schema itself. */
function schemaOf(s) {
  assert.equal(s.additionalProperties, false, 'the schema must close the fixture keys');
  const required = s.required;
  const properties = Object.keys(s.properties);
  const areas = s.properties.area.enum;
  return {
    check(rel, fixture) {
      for (const key of required) assert.ok(Object.hasOwn(fixture, key), `${rel}: missing key "${key}"`);
      for (const key of Object.keys(fixture)) assert.ok(properties.includes(key), `${rel}: key "${key}" is not allowed by fixture.schema.json`);
      for (const key of ['id', 'area', 'description', 'source']) {
        assert.ok(typeof fixture[key] === 'string' && fixture[key].trim() !== '', `${rel}: "${key}" must be a non-blank string`);
      }
      for (const key of ['input', 'expect']) {
        assert.ok(fixture[key] !== null && typeof fixture[key] === 'object' && !Array.isArray(fixture[key]), `${rel}: "${key}" must be an object`);
      }
      assert.ok(areas.includes(fixture.area), `${rel}: area "${fixture.area}" is not in fixture.schema.json ${areas}`);
      assert.equal(rel.substring(0, rel.indexOf('/')), fixture.area, `${rel}: area must equal the directory name`);
      assert.equal(fixture.id, rel.substring(0, rel.length - '.json'.length), `${rel}: id must be <area>/<file name>`);
    },
  };
}

// ---- FixtureRunner ------------------------------------------------------------------------------------------

async function run(fixture) {
  const { area, input, expect, id } = fixture;
  switch (area) {
    case 'errors': return errors(id, input, expect);
    case 'registration': return registration(id, input, expect);
    case 'manifest': return manifest(id, input, expect);
    case 'signature': return signature(id, input, expect);
    case 'envelope':
    case 'before': return callback(id, input, expect);
    case 'flags': return flags(id, input, expect);
    case 'context': return context(id, input, expect);
    case 'audit': return audit(id, input, expect);
    case 'notifications': return notifications(id, input, expect);
    case 'reads':
    case 'settings':
    case 'secrets':
    case 'extensions':
    case 'storage':
    case 'peers':
    case 'services': return service(fixture.area, id, input, expect);
    case 'slots': return slots(id, input, expect);
    case 'flow': return flow(id, input, expect);
    case 'events': return Object.hasOwn(input, 'envelope') ? moduleEvent(id, input, expect) : service(fixture.area, id, input, expect);
    default: assert.fail(`${id}: the TS runner does not know the area "${area}"`);
  }
}

// ---- errors -------------------------------------------------------------------------------------------------

/** The platform's answer to the registration → whether the SDK retries, the class, the error line. */
async function errors(id, input, expect) {
  const retry = expect.retry;
  let requests = 0;
  let server = null;
  let url;
  if (input.network === 'connection-refused') {
    url = `http://127.0.0.1:${await freePort()}`;
  } else {
    assert.ok(input.response, `${id}: input.response or input.network is required`);
    server = await answering(input.response, () => requests++);
    url = `http://127.0.0.1:${server.address().port}`;
  }
  const kf = KfModule.of(url, 'conformance', TOKEN, 'conformance-secret', null).withRetryDelays([50]);
  try {
    kf.start();
    if (server === null) {
      await until(id, () => kf.lastRegistrationError() !== null, 15_000);
      await sleep(150);
      assert.equal(!kf.isRegistrationStopped(), retry, `${id}: retry`);
    } else if (retry) {
      await until(id, () => requests >= 2, 10_000);
      assert.equal(kf.isRegistrationStopped(), false, `${id}: a transient error must not stop the registration`);
    } else {
      await until(id, () => kf.isRegistrationStopped(), 10_000);
      await sleep(200);
      assert.equal(requests, 1, `${id}: a permanent error must not be retried`);
    }
    assert.ok(kf.lastRegistrationProblem() !== null, `${id}: the problem must be kept`);
    assert.equal(kf.lastRegistrationProblem().errorClass, expect.class, `${id}: class`);
    const line = kf.lastRegistrationError();
    for (const fragment of expect.messageContains ?? []) {
      assert.ok(line.includes(String(fragment)), `${id}: the error line must contain "${fragment}": ${line}`);
    }
    assert.ok(!line.includes(TOKEN), `${id}: the module token must never reach the error line`);
  } finally {
    kf.stop();
    if (server !== null) await new Promise((r) => server.close(r));
  }
}

function answering(response, onRequest) {
  const body = response.body;
  const bytes = body == null ? Buffer.alloc(0) : typeof body === 'string' ? Buffer.from(body, 'utf8') : Buffer.from(JSON.stringify(body), 'utf8');
  const server = createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      onRequest();
      res.writeHead(response.status, { ...(response.headers ?? {}), 'Content-Length': bytes.length });
      res.end(bytes);
    });
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r(server)));
}

function freePort() {
  const server = createServer();
  return new Promise((r) => server.listen(0, '127.0.0.1', () => {
    const { port } = server.address();
    server.close(() => r(port));
  }));
}

// ---- registration -------------------------------------------------------------------------------------------

/** A declarative module description -> registrationBody(), equal as JSON to expect.body. */
function registration(id, input, expect) {
  const kf = module(input);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(kf.registrationBody())), expect.body, `${id}: registration body`);
}

/**
 * Builds a module from a declarative description (shared by the areas that need one): slug, callbackBase,
 * fields[{entity,name,type,table,labelKey}], hooks[{event,priority,onFailure,fields}],
 * surfaces[{slug,kind,entry,export|tag,css,order,labelKey,flag,key,peer}].
 */
function module(input, platformUrl = 'http://127.0.0.1:1') {
  const kf = KfModule.of(platformUrl, input.slug ?? 'conformance', TOKEN, input.hookSecret ?? 'conformance-secret',
    input.callbackBase ?? null);
  for (const f of input.fields ?? []) kf.registerField(f.entity, f.name, f.type, f.table, f.labelKey ?? null);
  for (const h of input.hooks ?? []) hook(kf, h);
  for (const d of input.surfaces ?? []) {
    let s;
    switch (String(d.kind)) {
      case 'wc': s = Surface.wc(d.entry, d.tag); break;
      // protocol 2.0 (§7.12): the kinds without code of a slot of another module
      case 'layer': s = Surface.layer(d.layer, d.source); break;
      case 'data': s = Surface.data(slotData(d)); break;
      default: s = Surface.react(d.entry, d.export);
    }
    if (d.css != null) s.css(d.css);
    if (d.order != null) s.order(d.order);
    if (d.labelKey != null) s.labelKey(d.labelKey);
    if (d.flag != null) s.flag(d.flag);
    if (d.key != null) s.key(d.key);
    for (const [k, v] of Object.entries(d.peer ?? {})) s.peer(k, String(v));
    kf.ui().contribute(d.slug, s);
  }
  return kf;
}

/** `data`, or `dataRepeat {field, char, count}`: an object with one text of `count` characters. */
function slotData(d) {
  if (d.dataRepeat == null) return d.data;
  return { [d.dataRepeat.field]: String(d.dataRepeat.char).repeat(d.dataRepeat.count) };
}

// ---- slots (protocol 2.0) ----------------------------------------------------------------------

/**
 * The contributions to the slots of other modules: what the SDK sends (expect.surfaces) and the registration warnings
 * it exposes (expect.warnings) from the platform's scripted answer; or a contribution refused locally before
 * anything is sent (expect.localError).
 */
async function slots(id, input, expect) {
  if (expect.localError != null) {
    assert.throws(() => module(input), (e) => {
      assert.ok(e.message.includes(expect.localError),
        `${id}: the local refusal must contain <${expect.localError}>: ${e.message}`);
      return true;
    }, `${id}: refused locally`);
    return;
  }
  let sent = null;
  const answer = input.platform;
  const bytes = Buffer.from(JSON.stringify(answer.body), 'utf8');
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      if (req.url.split('?')[0].endsWith('/registrations')) {
        sent = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        res.writeHead(answer.status, { 'Content-Type': 'application/json', 'Content-Length': bytes.length });
        res.end(bytes);
      } else {
        // the flags read after a registration: none
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('[]');
      }
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const kf = module(input, `http://127.0.0.1:${server.address().port}`);
  try {
    kf.start();
    await until(id, () => kf.isRegistered(), 10_000);
    assert.deepStrictEqual(sent.surfaces, expect.surfaces, `${id}: the contributions sent`);
    assert.deepStrictEqual(kf.registrationWarnings(), expect.warnings, `${id}: the warnings exposed`);
  } finally {
    kf.stop();
    await new Promise((r) => server.close(r));
  }
}

/** One declared hook; before handlers allow. */
function hook(kf, h) {
  const opts = { priority: h.priority ?? ModelHooks.DEFAULT_PRIORITY, onFailure: h.onFailure === 'block' ? 'block' : 'allow' };
  const allow = (ctx) => ctx.allow();
  const noop = () => {};
  const m = kf.models();
  switch (h.event) {
    case 'model.before.create': m.beforeCreate(allow, opts); break;
    case 'model.before.update': m.beforeUpdate(allow, opts); break;
    case 'model.before.delete': m.beforeDelete(allow, opts); break;
    case 'model.after.create': m.afterCreate(noop); break;
    case 'model.after.update': m.afterUpdate(noop); break;
    case 'model.after.delete': m.afterDelete(noop); break;
    case 'model.field.changed': m.onFieldChange((h.fields ?? []).map(String), () => {}); break;
    default: assert.fail(`the declarative module does not know the event ${h.event}`);
  }
}

// ---- manifest -----------------------------------------------------------------------------------------------

/**
 * input.manifest (+ optional input.registration, a declarative module; platformLanguages) -> ManifestCheck:
 * expect.valid, errorsContain, warningsContain, and read (JSON pointer -> value on the typed Manifest).
 */
function manifest(id, input, expect) {
  const json = JSON.stringify(input.manifest);
  const languages = (input.platformLanguages ?? []).map(String);
  const result = input.registration == null
    ? ManifestCheck.validate(json, languages)
    : ManifestCheck.check(json, module(input.registration).registrationBody(), languages);
  assert.equal(result.ok(), expect.valid, `${id}: valid; errors=${JSON.stringify(result.errors)}`);
  for (const fragment of expect.errorsContain ?? []) {
    assert.ok(result.errors.some((e) => e.includes(String(fragment))), `${id}: an error must contain <${fragment}>: ${JSON.stringify(result.errors)}`);
  }
  for (const fragment of expect.warningsContain ?? []) {
    assert.ok(result.warnings.some((w) => w.includes(String(fragment))), `${id}: a warning must contain <${fragment}>: ${JSON.stringify(result.warnings)}`);
  }
  if (Object.hasOwn(expect, 'warningsContain') && (expect.warningsContain ?? []).length === 0) {
    assert.deepEqual(result.warnings, [], `${id}: no warning expected`);
  }
  if (expect.read != null) {
    const typed = JSON.parse(JSON.stringify(Manifest.parse(json)));
    for (const [ptr, value] of Object.entries(expect.read)) assert.deepStrictEqual(pointer(typed, ptr), value, `${id}: ${ptr}`);
  }
}

// ---- flow (protocol 2.0, sdk-protocol.md 16.4) ----------------------------------------------------------

/**
 * The module's side of a flow rule. `input.query` (the raw query of the rule's request) then `expect.valid`
 * and `expect.projection` (the typed projection: each field the fixture writes, compared; `orderStages` a list,
 * `onLine` a boolean) or `expect.errorContains`. `input.decision` (`pass` | `block`, with `key` and `params`) then
 * `expect.valid` and `expect.json` (the answer's body) or `expect.errorContains` (refused locally).
 */
function flow(id, input, expect) {
  const valid = expect.valid;
  if (Object.hasOwn(input, 'query')) {
    let p;
    try {
      p = FlowRules.Projection.fromQuery(input.query);
    } catch (e) {
      assert.ok(e instanceof TypeError, `${id}: ${e}`);
      assert.equal(valid, false, `${id}: the projection must be read, it was refused: ${e.message}`);
      assert.ok(e.message.includes(expect.errorContains), `${id}: ${e.message}`);
      return;
    }
    assert.equal(valid, true, `${id}: the projection must be refused, it was read`);
    const actual = {
      ruleKey: p.ruleKey, orderId: p.orderId, orderStatus: p.orderStatus, flowKey: p.flowKey, flowVersion: p.flowVersion,
      instanceId: p.instanceId, stepKey: p.stepKey, stepTo: p.stepTo, orderNumber: p.orderNumber,
      orderInternal: p.orderInternal, orderCustomerId: p.orderCustomerId, stepBehavesLike: p.stepBehavesLike,
      orderStages: [...p.orderStages], lineId: p.lineId, lineModelId: p.lineModelId,
      lineModelVersionId: p.lineModelVersionId, lineQuantity: p.lineQuantity, onLine: p.onLine(),
    };
    for (const [k, v] of Object.entries(expect.projection ?? {})) assert.deepStrictEqual(actual[k], v, `${id}: projection.${k}`);
    return;
  }
  let d;
  try {
    switch (input.decision) {
      case 'pass': d = FlowRules.Decision.pass(); break;
      case 'block': d = FlowRules.Decision.block(input.key, input.params); break;
      default: assert.fail(`${id}: unknown decision ${input.decision}`);
    }
  } catch (e) {
    assert.ok(e instanceof TypeError, `${id}: ${e}`);
    assert.equal(valid, false, `${id}: the decision must be built, it was refused: ${e.message}`);
    assert.ok(e.message.includes(expect.errorContains), `${id}: ${e.message}`);
    return;
  }
  assert.equal(valid, true, `${id}: the decision must be refused locally, it was built`);
  assert.deepStrictEqual(d.toJson(), expect.json, `${id}: json`);
}

/** A JSON pointer (/a/0/b) into a tree. */
function pointer(tree, ptr) {
  let at = tree;
  for (const token of ptr.substring(1).split('/')) {
    if (Array.isArray(at)) at = at[Number.parseInt(token, 10)];
    else if (at !== null && typeof at === 'object') at = at[token];
    else assert.fail(`pointer ${ptr} leaves the tree at ${token}`);
  }
  return at;
}

// ---- signature ----------------------------------------------------------------------------------------------

/** sign must produce expect.signature; verify must answer expect.valid. */
function signature(id, input, expect) {
  const body = Buffer.from(input.body, 'utf8');
  if (Object.hasOwn(expect, 'signature')) {
    assert.equal(Signatures.sign(input.secret, input.timestamp, body), expect.signature, `${id}: signature`);
  }
  if (Object.hasOwn(expect, 'valid')) {
    const reason = Signatures.verify(input.secret, input.timestamp, input.signature, body, input.now);
    assert.equal(reason === null, expect.valid, `${id}: valid (reason: ${reason})`);
    if (expect.reasonContains != null) {
      assert.ok(reason !== null, `${id}: a reason is expected`);
      assert.ok(reason.includes(expect.reasonContains), `${id}: reason ${reason}`);
    }
  }
}

// ---- envelope / before --------------------------------------------------------------------------------------

/**
 * One callback: input.envelope (or input.rawBody), signed by the runner with the module's secret now (input.signature:
 * valid by default, wrong); the module has a recording handler on every point, whose before handlers answer
 * input.answer. expect: status, handlerCalled, handler (what the handler saw), body (exact JSON) or bodyContains
 * (subset), contentType.
 */
async function callback(id, input, expect) {
  const recorder = new Recorder(input.answer);
  const kf = recorder.module();
  const result = await send(kf, input.envelope, input.rawBody, input.signature);
  assert.equal(result.status, expect.status, `${id}: status; body ${result.body}`);
  if (Object.hasOwn(expect, 'handlerCalled')) assert.equal(recorder.seen !== null, expect.handlerCalled, `${id}: handlerCalled`);
  if (expect.handler != null) {
    assert.ok(recorder.seen !== null, `${id}: the handler was not called`);
    for (const [k, v] of Object.entries(expect.handler)) assert.deepStrictEqual(recorder.seen[k], v, `${id}: handler saw ${k}`);
  }
  const body = JSON.parse(result.body);
  if (Object.hasOwn(expect, 'body')) assert.deepStrictEqual(body, expect.body, `${id}: body`);
  if (expect.bodyContains != null) {
    for (const [k, v] of Object.entries(expect.bodyContains)) assert.deepStrictEqual(body[k], v, `${id}: body.${k} in ${result.body}`);
  }
  if (Object.hasOwn(expect, 'contentType')) assert.equal(result.contentType, expect.contentType, `${id}: content type`);
}

function send(kf, envelope, rawBody, sig) {
  const bytes = rawBody != null ? Buffer.from(rawBody, 'utf8') : Buffer.from(JSON.stringify(envelope), 'utf8');
  const now = Math.floor(Date.now() / 1000);
  const ts = String(now);
  const signatureHeader = sig === 'wrong' ? '0'.repeat(64) : Signatures.sign(SECRET, ts, bytes);
  return kf.handle(bytes, ts, signatureHeader, now);
}

/** A module with a recording handler on every point. */
class Recorder {
  constructor(answer) {
    this.answer = answer ?? { decision: 'allow' };
    this.seen = null;
  }

  module() {
    const kf = KfModule.of('http://127.0.0.1:1', 'conformance', TOKEN, SECRET, null);
    const before = (ctx) => {
      this.seen = Recorder.seen(ctx.event, ctx.envelope, ctx.id);
      Recorder.versions(this.seen, ctx.changedVersionIds(), ctx.changedVersionFields(), ctx.before, ctx.after);
      return this.decide(ctx);
    };
    const after = (ev) => {
      this.seen = Recorder.seen(ev.event, ev.envelope, ev.id);
      Recorder.versions(this.seen, ev.changedVersionIds(), ev.changedVersionFields(), ev.before, ev.after);
    };
    kf.models().beforeCreate(before).beforeUpdate(before).beforeDelete(before)
      .afterCreate(after).afterUpdate(after).afterDelete(after)
      .onFieldChange([], (ch) => {
        this.seen = Recorder.seen('model.field.changed', ch.envelope, ch.id);
        this.seen.field = ch.field;
        this.seen.versionField = Recorder.versionField(ch.versionField());
      });
    // protocol 2.0 (sdk-protocol.md 16.2, 16.3): the order's field changes, the events of the flow engine
    kf.orders().onFieldChange([], (ch) => {
      this.seen = Recorder.seen('order.field.changed', ch.envelope, ch.id);
      this.seen.field = ch.field;
      this.seen.oldValue = ch.oldValue;
      this.seen.newValue = ch.newValue;
    });
    for (const type of FlowEvents.TYPES) {
      kf.flows().on(type, (ev) => {
        this.seen = Recorder.seen(ev.type, ev.envelope, ev.orderId);
        this.seen.orderId = ev.orderId;
        this.seen.instanceId = ev.instanceId;
        this.seen.flowKey = ev.flowKey;
        this.seen.flowVersion = ev.flowVersion;
        this.seen.stepKey = ev.stepKey;
      });
    }
    kf.tenants().onDeleted((deleted) => {
      this.seen = Recorder.seen('tenant.after.delete', deleted.envelope, deleted.tenantId);
    }).onSoftDelete((e) => {
      this.seen = Recorder.seen('tenant.after.soft-delete', e.envelope, e.tenantId);
    }).onRestore((e) => {
      this.seen = Recorder.seen('tenant.after.restore', e.envelope, e.tenantId);
    });
    return kf;
  }

  /**
   * A version's values in the model's envelope: `changedVersionIds`,
   * `changedVersionFields` (`[{versionId, slug, field}]`) and `versionValues` (the same, with `before` / `after` read by
   * `versionExtension`).
   */
  static versions(seen, ids, fields, before, after) {
    seen.changedVersionIds = [...ids];
    seen.changedVersionFields = fields.map((f) => Recorder.versionField(f));
    seen.versionValues = fields.map((f) => ({
      ...Recorder.versionField(f),
      before: before == null ? null : before.versionExtension(f.versionId, f.slug, f.field),
      after: after == null ? null : after.versionExtension(f.versionId, f.slug, f.field),
    }));
  }

  static versionField(f) {
    return f == null ? null : { versionId: f.versionId, slug: f.slug, field: f.field };
  }

  static seen(event, e, id) {
    return {
      event, id, tenant: e.tenant, locale: e.locale, eventId: e.eventId, deliveryId: e.deliveryId,
      envelopeVersion: e.envelopeVersion, actor: e.actor, aggregateType: e.aggregateType,
    };
  }

  decide(ctx) {
    const a = this.answer;
    switch (a.decision) {
      case 'allow': return ctx.allow();
      case 'reject': return Object.hasOwn(a, 'params') ? ctx.reject(a.key, a.params) : ctx.reject(a.key);
      case 'patch': {
        const patch = ctx.patch();
        for (const [k, v] of Object.entries(a.set ?? {})) patch.set(k, v);
        for (const [k, v] of Object.entries(a.extensions ?? {})) patch.extension(k, v);
        return patch;
      }
      default: throw new Error(`unknown answer ${a.decision}`);
    }
  }
}

// ---- flags --------------------------------------------------------------------------------------------------

/** input.envelopes handled in order; then expect.flags (key -> enabled). */
async function flags(id, input, expect) {
  const kf = new Recorder(null).module();
  for (const envelope of input.envelopes ?? []) {
    const result = await send(kf, envelope, null, null);
    assert.equal(result.status, 200, `${id}: every callback is answered 200; ${result.body}`);
  }
  for (const [key, enabled] of Object.entries(expect.flags)) assert.equal(kf.flags().isEnabled(key), enabled, `${id}: flag ${key}`);
}

// ---- context -----------------------------------------------------------------------------------------

/**
 * input: secret, now, headers (the three X-KF-* headers; any may be absent), body. expect: valid: true + context (every
 * key, actor with its wire type), or valid: false + status, class, reasonContains.
 */
function context(id, input, expect) {
  const h = input.headers;
  const body = Buffer.from(input.body ?? '', 'utf8');
  let ctx;
  try {
    ctx = RequestContext.verify(h[RequestContext.CONTEXT_HEADER], h[RequestContext.TIMESTAMP_HEADER], h[RequestContext.SIGNATURE_HEADER],
      body, input.secret, input.now);
  } catch (e) {
    if (!(e instanceof Rejected)) throw e;
    assert.equal(expect.valid, false, `${id}: the context must be accepted, it was refused: ${e.message}`);
    assert.equal(e.problem.status, expect.status, `${id}: status`);
    assert.equal(e.errorClass, expect.class, `${id}: class`);
    if (expect.reasonContains != null) assert.ok(e.message.includes(expect.reasonContains), `${id}: reason ${e.message}`);
    assert.equal(JSON.parse(e.problemJson()).class, expect.class, `${id}: RFC 9457 body class`);
    return;
  }
  assert.equal(expect.valid, true, `${id}: the context must be refused, it was accepted`);
  const actual = {
    v: ctx.v, tenant: ctx.tenant, user: ctx.user,
    actor: { type: ctx.actor.type, via: ctx.actor.via, hookId: ctx.actor.hookId },
    locale: ctx.locale, rights: [...ctx.rights], method: ctx.method, path: ctx.path, query: ctx.query, ts: ctx.ts,
    requestId: ctx.requestId,
  };
  // protocol 1.2: person only when the context carries it
  if (ctx.person != null) actual.person = { email: ctx.person.email, displayName: ctx.person.displayName };
  assert.deepStrictEqual(actual, expect.context, `${id}: context`);
}

// ---- audit -----------------------------------------------------------------------------------------

/**
 * `AuditClient.record/list` against a platform stub: the request it sends (method, path, query, the three headers, the
 * body) and what it makes of the answer. The context of `input.context` is signed by the runner with `ts` = now and
 * verified through `KfModule.verifyContext` with `input.proof`, as an adapter does; `"$context"` in the expected
 * headers is that exact `X-KF-Context`.
 */
async function audit(id, input, expect) {
  const secret = 'conformance-audit-secret';
  const now = Math.floor(Date.now() / 1000);
  const contextJson = { ...input.context, ts: now };
  const context = Buffer.from(JSON.stringify(contextJson), 'utf8').toString('base64url');
  const signature = Signatures.sign(secret, String(now), Buffer.from(`${context}.`, 'ascii'));

  const answer = input.platform;
  let seen = null;
  let requests = 0;
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      requests++;
      const url = new URL(req.url, 'http://localhost');
      const raw = Buffer.concat(chunks).toString('utf8');
      seen = {
        method: req.method,
        path: url.pathname,
        query: req.url.includes('?') ? req.url.slice(req.url.indexOf('?') + 1) : null,
        headers: {
          Authorization: req.headers.authorization ?? null,
          'X-KF-Context': req.headers['x-kf-context'] ?? null,
          'X-KF-Context-Proof': req.headers['x-kf-context-proof'] ?? null,
        },
        body: raw === '' ? null : JSON.parse(raw),
      };
      res.writeHead(answer.status, { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(answer.body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, input.slug, TOKEN, secret, null);
    const ctx = kf.verifyContext(context, String(now), signature, new Uint8Array(0), input.proof);
    let rows = null;
    let error = null;
    try {
      if (input.call === 'record') {
        const r = input.record;
        await kf.audit().record(ctx, new AuditRecord(r.action, r.aggregateType, r.aggregateRef));
      } else if (input.call === 'list') {
        rows = await kf.audit().list(ctx, input.query.aggregateType, input.query.aggregateRef ?? null);
      } else {
        assert.fail(`${id}: unknown call ${input.call}`);
      }
    } catch (e) {
      if (!(e instanceof KfException)) throw e;
      error = e;
    }

    if (expect.request == null) {
      assert.equal(requests, 0, `${id}: no request may be sent`);
    } else {
      assert.equal(requests, 1, `${id}: exactly one request`);
      const wanted = { ...expect.request, headers: { ...expect.request.headers } };
      for (const [k, v] of Object.entries(wanted.headers)) if (v === '$context') wanted.headers[k] = context;
      assert.deepStrictEqual(seen, wanted, `${id}: request`);
    }

    if (expect.outcome === 'ok') {
      assert.equal(error, null, `${id}: must succeed, got ${error}`);
      if ('rows' in expect) assert.deepStrictEqual(rows, expect.rows, `${id}: rows`);
    } else {
      assert.notEqual(error, null, `${id}: must fail`);
      const e = expect.error;
      assert.equal(error.problem.status, e.status, `${id}: status`);
      assert.equal(error.errorClass, e.class, `${id}: class`);
      const kind = error instanceof AuditForbidden ? 'forbidden' : error instanceof AuditUnauthorized ? 'unauthorized' : 'other';
      assert.equal(kind, e.kind, `${id}: kind`);
      for (const fragment of e.messageContains ?? []) assert.ok(error.message.includes(fragment), `${id}: message ${error.message}`);
      assert.ok(!error.message.includes(TOKEN), `${id}: the token in an error`);
    }
  } finally {
    server.close();
  }
}

// ---- notifications -----------------------------------------------------------------------------------

/**
 * `Notifications.send` against a platform stub that answers the n-th request with `input.platform[n]` (the last one
 * repeats): how many requests the SDK sends, that EVERY one of them is `expect.request` (method, path, the module
 * token, the body -- a retry sends the same idempotency key), and what it makes of the answer. The runner shortens the
 * retry schedule to three steps of 10 ms (the default has three steps too).
 */
async function notifications(id, input, expect) {
  const answers = input.platform;
  const seen = [];
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const url = new URL(req.url, 'http://localhost');
      const raw = Buffer.concat(chunks).toString('utf8');
      const answer = answers[Math.min(seen.length, answers.length - 1)];
      seen.push({
        method: req.method,
        path: url.pathname,
        headers: { Authorization: req.headers.authorization ?? null },
        body: raw === '' ? null : JSON.parse(raw),
      });
      res.writeHead(answer.status, { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(answer.body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, input.slug, TOKEN, 'conformance-notifications-secret', null);
    kf.notifications().withRetryDelays([10, 10, 10]);
    if (input.call !== 'send') assert.fail(`${id}: unknown call ${input.call}`);
    const o = input.options ?? {};
    let result = null;
    let error = null;
    try {
      result = await kf.notifications().send(input.tenantId, input.key, input.recipients, input.variables,
        { deepLink: o.deepLink ?? null, channels: o.channels ?? null, idempotencyKey: o.idempotencyKey ?? null,
          recipientRoles: o.recipientRoles ?? null });
    } catch (e) {
      if (!(e instanceof KfException)) throw e;
      error = e;
    }

    assert.equal(seen.length, expect.requests, `${id}: requests sent`);
    for (const one of seen) assert.deepStrictEqual(one, expect.request, `${id}: request`);

    if (expect.outcome === 'ok') {
      assert.equal(error, null, `${id}: must succeed, got ${error}`);
      assert.equal(result.accepted, expect.result.accepted, `${id}: accepted`);
      assert.equal(result.duplicates, expect.result.duplicates, `${id}: duplicates`);
    } else {
      assert.notEqual(error, null, `${id}: must fail`);
      const e = expect.error;
      assert.equal(error.problem.status, e.status, `${id}: status`);
      assert.equal(error.errorClass, e.class, `${id}: class`);
      const kind = error instanceof NotificationRejected ? 'rejected' : 'other';
      assert.equal(kind, e.kind, `${id}: kind`);
      if (error instanceof NotificationRejected) assert.equal(error.reason, e.reason, `${id}: reason`);
      for (const fragment of e.messageContains ?? []) assert.ok(error.message.includes(fragment), `${id}: message ${error.message}`);
      assert.ok(!error.message.includes(TOKEN), `${id}: the token in an error`);
    }
  } finally {
    server.close();
  }
}

// ---- the platform services of protocol 1.1: reads, settings, secrets, extensions ----------------------

/**
 * One call of a protocol 1.1 client (`reads().list/get`, …) against a platform stub, exactly as {@link audit}: the
 * context of `input.context` is signed with `ts` = now and verified through `KfModule.verifyContext` with
 * `input.proof`; `input.manifest`, when present, is given to the module (`withManifest`); the call is `input.call` with
 * `input.args`; the server answers `input.platform`. `expect.request` is the request sent (or `null` = none);
 * `expect.result` the value returned (`null` = an empty object read); `expect.error` = `{status (0 = local), class,
 * kind: invalid | unauthorized | forbidden | not-found | other, reason?, errors?, messageContains[]}`. Neither the
 * token, nor the proof, nor a secret value of `args.value` may appear in an error.
 */
async function service(area, id, input, expect) {
  const secret = 'conformance-service-secret';
  const now = Math.floor(Date.now() / 1000);
  // protocol 2.0: an after delivery's context for a background job, possibly aged past its window
  const delivered = input.contextSource === 'delivery';
  const age = typeof input.contextAgeSeconds === 'number' ? input.contextAgeSeconds : 0;
  const contextJson = { ...input.context, ts: now - age };
  const context = Buffer.from(JSON.stringify(contextJson), 'utf8').toString('base64url');
  const signature = Signatures.sign(secret, String(now), Buffer.from(`${context}.`, 'ascii'));

  const answer = input.platform;
  let seen = null;
  let requests = 0;
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      requests++;
      const url = new URL(req.url, 'http://localhost');
      const raw = Buffer.concat(chunks).toString('utf8');
      seen = {
        method: req.method,
        path: url.pathname,
        query: req.url.includes('?') ? req.url.slice(req.url.indexOf('?') + 1) : null,
        headers: {
          Authorization: req.headers.authorization ?? null,
          'X-KF-Context': req.headers['x-kf-context'] ?? null,
          'X-KF-Context-Proof': req.headers['x-kf-context-proof'] ?? null,
        },
        body: raw === '' ? null : JSON.parse(raw),
      };
      // protocol 2.0: a command's key, only when sent
      if (req.headers['idempotency-key'] != null) seen.headers['Idempotency-Key'] = req.headers['idempotency-key'];
      res.writeHead(answer.status, { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' });
      res.end(answer.body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const kf = KfModule.of(`http://127.0.0.1:${server.address().port}`, input.slug, TOKEN, secret, null);
    if (input.manifest != null) kf.withManifest(JSON.stringify(input.manifest));
    const ctx = delivered ? DeliveryContext.of(context, input.proof)
      : kf.verifyContext(context, String(now), signature, new Uint8Array(0), input.proof);
    const args = input.args;
    let result = null;
    let error = null;
    try {
      result = await call(id, `${area}.${input.call}`, kf, ctx, args);
    } catch (e) {
      if (!(e instanceof KfException)) throw e;
      error = e;
    }

    if (expect.request == null) {
      assert.equal(requests, 0, `${id}: no request may be sent`);
    } else {
      assert.equal(requests, 1, `${id}: exactly one request`);
      const wanted = { ...expect.request, headers: { ...expect.request.headers } };
      for (const [k, v] of Object.entries(wanted.headers)) if (v === '$context') wanted.headers[k] = context;
      assert.deepStrictEqual(seen, wanted, `${id}: request`);
    }

    if (expect.outcome === 'ok') {
      assert.equal(error, null, `${id}: must succeed, got ${error}`);
      assert.deepStrictEqual(JSON.parse(JSON.stringify(result ?? null)), expect.result, `${id}: result`);
    } else {
      assert.notEqual(error, null, `${id}: must fail`);
      const e = expect.error;
      assert.equal(error.problem.status, e.status, `${id}: status`);
      assert.equal(error.errorClass, e.class, `${id}: class`);
      const kind = error instanceof PlatformRefusal ? error.kind : 'other';
      assert.equal(kind, e.kind, `${id}: kind`);
      if ('reason' in e) assert.equal(error.reason, e.reason, `${id}: reason`);
      if ('errors' in e) assert.deepStrictEqual({ ...error.errors }, e.errors, `${id}: errors`);
      for (const fragment of e.messageContains ?? []) assert.ok(error.message.includes(fragment), `${id}: message ${error.message}`);
      assert.ok(!error.message.includes(TOKEN), `${id}: the token in an error`);
      if (typeof input.proof === 'string') assert.ok(!error.message.includes(input.proof), `${id}: the proof in an error`);
      if (typeof args.value === 'string' && args.value !== '') {
        assert.ok(!error.message.includes(args.value), `${id}: the secret value in an error`);
      }
    }
  } finally {
    server.close();
  }
}

/** The call of {@link service}, by `<area>.<call>`; the value it returns, as the fixtures write it. */
async function call(id, name, kf, ctx, args) {
  switch (name) {
    case 'reads.list': {
      const page = 'size' in args
        ? await kf.reads().list(ctx, args.port, args.filters, args.page, args.size)
        : await kf.reads().list(ctx, args.port, args.filters);
      return { items: page.items, page: page.page, size: page.size, total: page.total };
    }
    case 'reads.get':
      return 'include' in args
        ? kf.reads().get(ctx, args.port, args.id, args.include)
        : kf.reads().get(ctx, args.port, args.id);
    case 'settings.effective':
      return kf.settings().effective(ctx);
    case 'secrets.write':
      return kf.secrets().write(ctx, args.name, args.value);
    case 'secrets.resolve':
      return (await kf.secrets().resolve(ctx, args.ref)).value();
    case 'storage.uploadUrl': {
      const u = await kf.storage().uploadUrl(ctx, args.contentType, args.size);
      return { key: u.key, url: u.url, method: u.method, headers: u.headers, expiresAt: u.expiresAt };
    }
    case 'storage.downloadUrl': {
      const d = await kf.storage().downloadUrl(ctx, args.key);
      return { url: d.url, expiresAt: d.expiresAt };
    }
    case 'extensions.write':
      return kf.extensions().write(ctx, args.entity, args.id, args.values);
    case 'peers.read': {
      const a = await kf.peers().read(ctx, args.owner, args.port, args.query);
      return { status: a.status, body: a.body };
    }
    case 'peers.command': {
      const a = await kf.peers().command(ctx, args.owner, args.name, args.idempotencyKey, args.body);
      return { status: a.status, body: a.body };
    }
    case 'events.emit': {
      const e = await kf.events().emit(ctx, args.name, args.data);
      return { eventId: e.eventId, type: e.type };
    }
    case 'services.upsert': {
      // protocol 2.0 (sdk-protocol.md 16.6): order.services.v1; the amount as text keeps 12.50 exact
      await kf.orderServices().upsert(ctx, new OrderServiceLine(args.orderId, args.entryId, args.name,
        args.amount == null ? null : String(args.amount), args.currency, args.externalRef));
      return null;
    }
    case 'services.delete':
      await kf.orderServices().delete(ctx, args.orderId, args.externalRef);
      return null;
    default:
      assert.fail(`${id}: unknown call ${name}`);
  }
}

/**
 * Protocol 2.0 (§6.3): a module event `input.envelope`, signed with `ts` = now, delivered to a
 * module subscribed with `events().on(input.subscribe.owner, input.subscribe.name)`, with the delivery's `X-KF-Context`
 * (built from `input.context`, `ts` = now) and `input.proof`. `expect.status` is the answer; `expect.handler` what the
 * handler saw: `owner`, `name`, `eventId`, `tenant`, `data` and `deliveryContext` = `{tenant, contextHeader ("$context"
 * = the header sent), proof}` or `null` when none.
 */
async function moduleEvent(id, input, expect) {
  const now = Math.floor(Date.now() / 1000);
  let header = null;
  if (input.context != null) {
    header = Buffer.from(JSON.stringify({ ...input.context, ts: now }), 'utf8').toString('base64url');
  }
  const kf = KfModule.of('http://127.0.0.1:1', 'conformance', TOKEN, SECRET, null);
  const seen = [];
  kf.events().on(input.subscribe.owner, input.subscribe.name, (ev) => { seen.push(ev); });
  const bytes = Buffer.from(JSON.stringify(input.envelope), 'utf8');
  const ts = String(now);
  const result = await kf.handle(bytes, ts, Signatures.sign(SECRET, ts, bytes),
    { contextHeader: header, proofHeader: input.proof ?? null, nowSeconds: now });
  assert.equal(result.status, expect.status, `${id}: status; body ${result.body}`);
  if (expect.handler == null) {
    assert.equal(seen.length, 0, `${id}: the handler must not be called`);
    return;
  }
  assert.equal(seen.length, 1, `${id}: the handler was called ${seen.length} times`);
  const ev = seen[0];
  const d = ev.deliveryContext();
  const actual = {
    owner: ev.owner, name: ev.name, eventId: ev.eventId, tenant: ev.tenant, data: ev.data,
    deliveryContext: d == null ? null : { tenant: d.tenant(), contextHeader: d.contextHeader, proof: d.proof },
  };
  const wanted = { ...expect.handler };
  if (wanted.deliveryContext != null && wanted.deliveryContext.contextHeader === '$context') {
    wanted.deliveryContext = { ...wanted.deliveryContext, contextHeader: header };
  }
  assert.deepStrictEqual(JSON.parse(JSON.stringify(actual)), wanted, `${id}: handler`);
}

// ---- helpers ------------------------------------------------------------------------------------------------

async function until(id, condition, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) assert.fail(`${id}: condition not reached within ${timeoutMs} ms`);
    await sleep(10);
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
