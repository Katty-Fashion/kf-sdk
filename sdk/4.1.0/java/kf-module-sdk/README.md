# ro.kf:kf-module-sdk — the Java reference SDK

The reference implementation of the KF module protocol v2.1 (`../../sdk-protocol.md`), SDK **4.1.0**. The TS and PHP SDKs are
derived from this code (`/kf-sdk-derive`); the shared suite `../../conformance/` fixes the behaviour of all three
. Build and test: `./mvnw -B verify`.

## A module in a few lines

```java
KfModule kf = KfModule.fromEnv();                 // KF_PLATFORM_URL, KF_MODULE_SLUG, KF_MODULE_TOKEN, KF_HOOK_SECRET, …
kf.models().beforeUpdate(ctx -> ctx.allow());
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
KfHttpServer.mount(server, kf);                   // the hook path, /ui/**, /health
KfHttpServer.route(server, kf, "/notes", (ctx, exchange, body) -> {
    // ctx = the verified context: ctx.tenant(), ctx.user(), ctx.actor(), ctx.rights()
    return new HookResult(200, "[]");
});
server.start();
kf.start();                                        // registers, retrying only on transient errors
```

## The audit trail (capability `audit` — implemented by the platform)

Declare it in `kf-module-conf.json`:

```json
"capabilities": { "audit": [ { "access": "write" }, { "access": "read-own" } ] }
```

Inside a route handler, with the `RequestContext` the adapter gives you:

```java
kf.audit().record(ctx, new AuditRecord("NOTE_CREATED", "Note", "42"));        // a row in ctx's tenant, under your slug
List<Map<String, Object>> rows = kf.audit().list(ctx, "Note", "42");          // only your own rows of that tenant
```

- The SDK sends the module token and the context exactly as the platform forwarded it (`X-KF-Context` and the
  platform's own proof, `X-KF-Context-Proof`); the platform takes the tenant and the actor from that context only.
  Use the adapters (`KfHttpServer.route`, the Spring configuration) or
  `kf.verifyContext(context, timestamp, signature, proof, body)`: a context the SDK did not verify, or one older than
  300 s, is refused locally.
- The record is checked locally with the platform's rules: `action` `[A-Z][A-Z0-9_]{1,63}`, `aggregateType`
  `[A-Za-z][A-Za-z0-9_.-]{0,49}` (stored as `m.<slug>.<type>`), `aggregateRef` at most 255 characters.
- Errors: a local refusal or a platform error is a `KfException` (`errorClass()` `permanent`/`transient`); `401` is
  `AuditClient.Unauthorized`, `403` (capability not declared, module not live for the tenant) is
  `AuditClient.Forbidden`.

## Notifications (capability `notifications` — sent through the platform)

Declare each notification in `kf-module-conf.json`, with its texts in `i18n` (the platform renders them, in each
recipient's language):

```json
"capabilities": { "notifications": [ { "key": "author-changed", "variables": ["modelName"],
                                       "channels": ["in_app", "email"],
                                       "subject": "notif.author.subject", "body": "notif.author.body" } ] }
```

Send it to members of a tenant, named by their OIDC subject (`ctx.user()`, `kfactor` of a callback):

```java
kf.notifications().send(tenantId, "author-changed", List.of(ctx.user()), Map.of("modelName", "Rochie"));
kf.notifications().send(tenantId, "author-changed", List.of(subject), Map.of("modelName", "Rochie"),
        NotificationOptions.builder().deepLink("/models/42").channels(List.of("in_app")).build());
```

- `POST /api/v1/platform/modules/{slug}/notifications` with the module token, answered `202 {accepted, duplicates}`;
  the platform stores the key as `m.<slug>.author-changed` (`sdk-protocol.md` §11.1).
- Checked locally first (the key as declared, 1–100 non-blank recipients, string/number/boolean values, a relative
  `deepLink`, `in_app`/`email`): a `KfException` of class `permanent`, nothing sent.
- A `transient` answer is retried with the SAME request (the same `idempotencyKey`, generated once per call unless the
  options give one), so the platform writes it once; a `permanent` refusal is `Notifications.Rejected` with the
  protocol's `reason()` (`unknown-key`, `undeclared-variable`, `not-a-member`, `not-live`, …).

## The platform services of protocol 1.1 (`reads`, `settings`, `secrets`, extension values)

Four clients, one rule: each call carries the module token and the context the platform forwarded for the request
(`X-KF-Context` and its proof `X-KF-Context-Proof`, unchanged), so the platform works **in the context's tenant** and
for **the context's user**. Use the `RequestContext` the adapter gives your route or hook handler; a context the SDK did
not verify (or older than 300 s), or one without the proof, is refused locally (`KfException`, `permanent`, nothing
sent). The platform's refusals are `PlatformRefusal` with `kind()` (`INVALID` 400, `UNAUTHORIZED` 401, `FORBIDDEN` 403,
`NOT_FOUND` 404), `reason()` (`insufficient-rights`, `capability-not-declared`, `module-not-live`, `invalid-context`,
`validation`, …) and `errors()` (the field errors of a 400); a `503` is a `KfException` of class `transient`.
`sdk-protocol.md` §15 is the wire; `../../conformance/{reads,settings,secrets,extensions}/` fix the behaviour.

### Reads (capability `reads`, §15.1)

Declare the ports (the list, their fields, filters and rights: `docs/modules/specifications/10-citirile.md`, decided
2026-10-21):

```json
"capabilities": { "reads": [ { "port": "model.v1" }, { "port": "model-version.v1" }, { "port": "order.v1" } ] }
```

```java
ReadPage page = kf.reads().list(ctx, "order.v1", Map.of("modelVersionId", "17"), 0, 50);   // GET …/reads/order.v1?…
for (Map<String, Object> order : page.items()) { … }                                        // page.hasNext(), total()
Optional<Map<String, Object>> version = kf.reads().get(ctx, "model-version.v1", "17", List.of("grading"));
Optional<Map<String, Object>> model = kf.reads().get(ctx, "model.v1", "42");                // 404 → Optional.empty()
```

- `page` from 0, `size` 1–200 (default 20), the platform's order by `id`; a list filter (`ids`) takes its values
  comma-separated, `ids` at most 200 of them (more is refused locally). Numbers with a fraction come as `BigDecimal`,
  exactly as sent.
- The platform checks the port's right **held by the context's user** (`403 insufficient-rights`) and the tenant's
  visibility (`404`). The system context of a hook reads **only objects**, **only the base fields** (no `include`), and
  only on the ports marked `system` (`model.v1`, `model-version.v1`): a list or an `include` is refused locally.
- Protocol 1.2.1 adds the ports `component.v1` (a list takes `ids` **or** `q`, 2–100 characters, with an
  optional `componentType`) and `inventory-roll.v1` (a list takes `componentId` **or** `ids`; `state` comes computed).
  The client is the same: `kf.reads().list(ctx, "component.v1", Map.of("q", "denim", "componentType", "MATERIAL"))`.

### Settings (capability `settings`, §15.2 — settings carry no data)

```java
Map<String, Object> s = kf.settings().effective(ctx);   // every declared key, in the manifest's order
boolean live = (Boolean) s.get("live");                 // BOOLEAN → Boolean, NUMBER → BigDecimal, ENUM → String
```

Typed by the manifest (`KF_MODULE_MANIFEST`; without it the call is refused locally). A missing or mismatching value
takes the declared default, an undeclared key is ignored (both with a WARN).

### Secrets (capability `secrets`, §15.3)

```java
String ref = kf.secrets().write(ctx, "r3-client-secret", value);   // in a route whose right is the writeRight
String value = kf.secrets().resolve(ctx, ref).value();             // Secret.toString() never shows the value
```

The reference (`vault://<mount>/kf-modules/<slug>/<platform | tenantId>/<name>`) may be stored; the value is never
logged and never put in an error (a refusal that echoes it is redacted to `***`). A reference of another module or of
another tenant than the context's is refused locally.

**An existing reference** (0.7.2): a `platform` secret may declare `"external": ["platform/aas/"]`; the module may then
keep a reference the platform already holds (`vault://platform/aas/kf-r3/client-secret`) instead of writing a value, and
`resolve` sends it (the platform audits every such resolve). `kf.secrets().externalRefProblem(ref)` says why a
reference is not acceptable (null when it is) — the check to run before storing one. The secret store's own failures
come back as a `KfException` whose `problem().type()` ends with `secret-store-unreachable` (transient) or
`secret-store-rejected` (permanent: the store's policy refuses the path). In the fake: `fake.secret(ref, value)`,
`fake.secretStoreDown(true)`, `fake.secretStoreRejects(true)`.

### Extension values (§15.4)

```java
Map<String, Object> values = new LinkedHashMap<>();
values.put("aas_shell", "urn:kf:shell:17");
values.put("aas_last_push", null);                                  // null clears
kf.extensions().write(ctx, "model_version", "17", values);          // PATCH with exactly these keys
```

Only the fields the module declares in `capabilities.fields` for the entity (`model`, `order`, `customer`,
`model_version`); a `400` names the field in `errors()` (`extensions.<slug>.<field>`), a `404` is an object not visible
in the tenant.

## Between modules (protocol 2.0)

The declarations `capabilities.peerReads`, `commands`, `events` (the owner) and `peers` (the caller, the owner in
`requires`) are read by `Manifest` and checked by `ManifestCheck`; `RequestContext` accepts `actor.via = module:{slug}`
(`ctx.actor().viaModule()`). Through the platform (sdk-protocol.md §15.9, §6.3, §5.5):

```java
PeersClient.Answer level = kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", Map.of("sku", "KF-42"));
PeersClient.Answer reserved = kf.peers().command(ctx, "kf-stock", "reserve", PeersClient.newIdempotencyKey(), body);
kf.events().emit(ctx, "order-placed", Map.of("orderId", "17"));            // the module's own m.{slug}.after.order-placed
kf.events().on("kf-stock", "level-changed", ev -> jobs.submit(ev));       // another module's event
```

The idempotency key is mandatory (the same one on a retry: the platform replays the stored answer). An after delivery
carries its context and proof (`ev.deliveryContext()`, a `HookEvents.DeliveryContext`), which a background job passes in
place of `ctx` within ±300 s; `DeliveryContext.of(header, proof)` rebuilds it in another process. The refusals are
`PlatformRefusal`s, with the kinds `conflict` (409), `refused` (422), `invalid-answer` (502) and `unavailable` (503) on
these calls. `FakePlatform` answers them for a module's own tests: `fake.peer("kf-stock").read(port, handler)`,
`.command(name, handler)`, `.stopped(true)`, `fake.internalClass(true)`, `fake.emittedEvents()`,
`fake.deliverModuleEvent(owner, name, tenant, data)`.

## Testing without the platform (`FakePlatform`)

The SDK's `tests` artifact carries a fake platform for the module's own tests (never the runtime): an HTTP server in
the test's process that answers the module's calls of protocols 1.1 and 1.2 with the platform's checks, issues the
signed contexts the platform forwards, and delivers the platform's callbacks. A module needs no `PlatformFront` or
`ModuleCalls` of its own (kf-feedback still has them, written against 0.8.0).

```xml
<dependency>
    <groupId>ro.kf</groupId>
    <artifactId>kf-module-sdk</artifactId>
    <version>4.1.0</version>
    <classifier>tests</classifier>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

```java
try (FakePlatform fake = FakePlatform.start("my-mod")) {
    fake.manifest(Files.readString(Path.of("kf-module-conf.json")));      // capabilities, setting defaults, secrets
    fake.reads("model.v1").put(TENANT, "42", Map.of("id", "42", "name", "Rochie"));
    fake.settings(TENANT, "live", true);
    KfModule kf = fake.module();                                          // URL, slug, token, hook secret, manifest
    // … start the module's routes and its /_kf/hooks with kf, then call a route as the platform would:
    FakePlatform.SignedContext ctx = fake.signedContext(TENANT, "sub-1", List.of("api.v1.m.my-mod.x.get"), "GET", "/x");
    HttpRequest request = ctx.applyTo(HttpRequest.newBuilder(URI.create(moduleUrl + "/x"))).GET().build();
    // … then assert on what the module did:
    fake.extensions(TENANT, "model_version", "17");   fake.auditRows();   fake.notifications();   fake.requests();
}
```

**What it simulates.**

| area | the fake | the test's handles |
|---|---|---|
| the common checks of §15 | the token (`401`), the slug (`403`), the capability when given the manifest (`403 capability-not-declared`), the context's proof and `ts` (`401 invalid-context`), the module live in the tenant (`403 module-not-live`) | `manifest(json)`, `notLive(tenant)` |
| `reads` (§15.1) | per tenant, paging, `ids` and equality filters, the decided ports' rights and system flags (`DECIDED_PORTS`, with `tenant.v1`, `component.v1` and `inventory-roll.v1` from 0.8.1); the 1.2.1 ports keep the platform's filter rules (`component.v1`: `ids` or `q`, never both, `q` 2–100 characters matched in the name whatever the case, one `componentType`; `inventory-roll.v1`: `componentId` or `ids`) and answer its `400` with `errors` (`"required (or ids)"`, `"unknown filter"`, a malformed id) | `reads(port).put(…)`, `userRights(tenant, user, rights…)`, `failReads(port, Refusal.UNAVAILABLE)` (0.8.1: `503`, `class: transient`, or any `Refusal` given; `null` clears) |
| `settings`, `secrets`, `extensions`, `audit`, `notifications`, `registrations`, `flags` | as on the platform (protocol 1.1) | `settings(…)`, `secret(…)`, `secretStoreDown/Rejects(…)`, `flag(…)`, `extensions(…)`, `auditRows()`, `notifications()`, `registrations()` |
| `recipientRoles` (§11.1, 0.9.0, protocol 1.3) | the tenant's members and their roles; a notification's `recipientRoles` resolved to the active members of each role in the notification's tenant only, joined with `recipients`, each person once; `422 unknown-role` outside `GRANTABLE_ROLES` (`SUPER_ADMIN` included), `422 too-many-recipients` over 200 people, `422 no-recipients`; the answer `{accepted, duplicates}` names no one | `member(tenant, subject, roles…)`, `inactiveMember(…)`, `notificationRecipients()` (the people each accepted notification reached) |
| `tenant.v1` listed by the platform (§15.1, 0.9.0, protocol 1.3) | the list without `ids` only for the platform's actor — `systemContext(…)` or a user on an `admin: platform` route — every tenant of every bucket; anyone else `400 ids: required`; every object with `deletedAt` (`null` unless the test put one) | `reads("tenant.v1").put(tenant, id, …)` |
| `model.v1` without `ids`, `model-version.garment-image.v1` (§15.1, 0.9.1, protocol 1.3.1) | `model.v1` listed without `ids` by a user holding `api.v1.models.get` (every model of the tenant, by `id`, paged), `ids` its only filter; the system context reads only objects. `model-version.garment-image.v1`: object, list with `ids` mandatory (`400 ids: required`), not a system port (`403`). The fake has no customer persona: the portal's `400 ids: required` on `model.v1` is the platform's | `reads("model.v1").put(…)`, `reads("model-version.garment-image.v1").put(tenant, versionId, {id, url, expiresAt})` |
| `storage` (§15.7, 0.8.1) | `upload-url` and `download-url` with `ModuleStorageService`'s rules: the key made by the fake, `kf-modules/{slug}/{tenant}/{uuid}.{ext}`; an undeclared type or `size` outside `1…maxBytes` → `400`; a key outside the tenant's prefix, with `..`, `.`, `//` or a backslash → `403` — under `kf-modules/{slug}/` allowed when the context was issued for an `admin: platform` route of the manifest; a missing object → `404`. The presigned PUT (10 min) and GET (60 min) point at the fake itself, no S3: the PUT is held to the signed `Content-Type` and `Content-Length` (`403` otherwise, nothing stored) | `storage().put(tenant, type, bytes)`, `putAt(key, …)`, `object(key)`, `bytes(key)`, `keys(tenant)`, `remove(key)`, `purgeTenant(tenant)` |
| the contexts (§9.2) | `signedContext(…)` (a user), `systemContext(…)` (the platform), `hookContext(…)` (a hook); from 0.8.1 `signedContext(…, person)` puts `person {email, displayName}` as the last key — refused on a route the manifest does not declare `audience: "member"` | the old `signedContext` overloads are unchanged (no `person`) |
| `tenant.after.delete` (§6.1, 0.8.1) | `deliverTenantDeleted(tenant)` purges `kf-modules/{slug}/{tenant}/` and posts the CloudEvent `kf.tenant.after.delete`, signed under the hook secret, to `{callbackBase}/_kf/hooks`; `deliverTenantDeleted(tenant, eventId)` redelivers the same `kfeventid` (at least once); `deliverTenantDeletedSignedWith(tenant, secret)` a forged one (the module answers `401`). Refused when the manifest does not subscribe to the point | `callbackBase(url)`, the returned `Delivery {status, body, eventId, deliveryId}` |
| `tenant.after.soft-delete`, `tenant.after.restore` (§6.1, 0.9.0, protocol 1.3) | `deliverTenantSoftDeleted(tenant[, eventId])` and `deliverTenantRestored(tenant[, eventId])` post the CloudEvents with `data.after = {id}`; nothing is purged. Refused when the manifest does not subscribe to the point or the module is not live in the tenant (`notLive`) | the returned `Delivery` |

**What it does not simulate.** Listing or deleting objects (the platform has neither, §15.7); a real S3 (the URLs are
the fake's, so a browser-side upload is not tested here); the time limits of the presigned URLs beyond their signed
expiry; the deletion of the tenant anywhere else than in the fake's storage (the module's DB is the test's); the
platform's retries of a delivery (the test redelivers itself); the provided reads (`provides`), the surfaces, the
module's UI and the proxy in front of its routes (`routes[].timeoutSeconds`, `audience`, `portal` are the platform's).

## Versions

The SDK versions and the protocol each speaks are in `../../sdk-protocol.md` §1 (one semver for Java, TS and PHP). The last ones:

| version | protocol | what changed |
|---|---|---|
| 4.1.0 | 2.1 | The first cut release of protocol 2.0 and 2.1: the API of the uncut 0.10.0-dev, unchanged. The SDK follows the platform line: SDK X.Y.z needs a platform >= X.Y, so a module on protocol 2.x declares `platform` `>=4.1`; the message of a missing `platform` suggests `>=4.1`. No date suffix (Composer refuses it); tags `sdk-v4.1.0` and `sdk-internal-v4.1.0` on the same commit. |
| 0.9.1 | 1.3.1 | Protocol 1.3.1 (delivered by the platform, `platform` `>=6.1`): no runtime API change (the clients are port-agnostic); `Surfaces.MODELS_DETAIL_OVERVIEW_CARDS` in the generated vocabulary. In the `tests` artifact: `model-version.garment-image.v1` in `DECIDED_PORTS` (`api.v1.models.get`, not a system port), its list with a mandatory `ids` (`400 ids: required`); `model.v1` listed without `ids` by a user holding the right, `ids` its only filter (an unknown one `400`), the system context still refused. TS and PHP derived at 0.9.1. |
| 0.9.0 | 1.3 | Protocol 1.3: `NotificationOptions.recipientRoles` / `roles(…)` (a notification to a role, `recipients` may be empty); `HookEvents.TENANT_AFTER_SOFT_DELETE` / `TENANT_AFTER_RESTORE` with `TenantHooks.onSoftDelete` / `onRestore`; `TenantDataExport` (the bundle of `GET /m/{slug}/admin/export` and its `contentSha256`, the form the modules' imports recompute); `ReadsClient` lets the platform's own context list `tenant.v1`. In the `tests` artifact: the tenant's members and `recipientRoles`, the soft-delete and restore deliveries, `tenant.v1` listed by the platform. TS and PHP derived at 0.9.0. The parts are delivered by the platform (reserved in 1.3 until then). |
| 0.8.1 | 1.2.1 | The complete `FakePlatform` of the `tests` artifact: `storage()` (§15.7), `person` in a member's context, `deliverTenantDeleted`, `failReads`, `tenant.v1`. The read ports `component.v1` and `inventory-roll.v1` of protocol 1.2.1 in `DECIDED_PORTS`, with the platform's filter rules. The runtime API is unchanged; TS and PHP are re-derived at 0.8.1 with no API change. |
| 0.8.0 | 1.2 | Protocol 1.2 complete: `routes[].audience`/`portal`, `activation`, `capabilities.storage` and `StorageClient`, `person()`, `TenantHooks` (`tenant.after.delete`), the pinned 1.2 catalogs. |
