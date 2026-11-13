<!-- kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0 -->
<!-- Derived by /kf-sdk-derive from the Java SDK (package ro.kf.sdk). Do not edit by hand: fix Java, then re-derive. -->
# kf/module-sdk (PHP >= 8.3)

The KF platform module SDK for PHP, **derived from the Java reference SDK** `ro.kf:kf-module-sdk`. Same API
(named the PHP way), same rules, same version; **zero runtime dependencies** (`hash_hmac`, `json_*`, PHP streams for
HTTP: no ext-curl, no Composer package). The wire contract is [`sdk/sdk-protocol.md`](../../sdk-protocol.md) (v2.1); the
behaviour is fixed by the shared conformance suite [`sdk/conformance/`](../../conformance/README.md).

## The PHP pattern: one definition file, two entry points

PHP has no long-lived boot: every request starts from scratch. The Java SDK registers from a background thread when
the application starts; in PHP the module is declared once, in a file that **returns** the configured `KfModule`
(`kf.php`), and two entry points load it:

| Entry point | When | What it does |
|---|---|---|
| `php vendor/bin/kf-register kf.php` | once, at container start, in the background | the local check of `kf-module-conf.json`, then `registerWithRetry()`: `PUT …/registrations`, retrying only transient errors (1 s … 30 s); a permanent error stops it at once (exit 1) |
| `public/index.php` (router of `php -S`, or any front controller) | every request | `Sapi::handleGlobals($kf)` on `POST /_kf/hooks`, `Sapi::serveUi()` on `/ui/**`, `Sapi::verifyGlobals($kf)` on the module's own routes |

What must outlive a request (the registration state, the flags) lives in small JSON files under
`{sys_get_temp_dir()}/kf-sdk-{slug}` (override: `KF_SDK_STATE_DIR`), `StateStore`.

```php
<?php // kf.php
declare(strict_types=1);

use Kf\Sdk\{BeforeContext, FieldType, KfModule, OnFailure, Surface};
use Kf\Sdk\HookEvents\AfterEvent;

require_once __DIR__ . '/vendor/autoload.php';

$kf = KfModule::fromEnv();
$kf->models()
    ->beforeCreate(fn (BeforeContext $ctx) => str_contains($ctx->after?->name ?? '', 'x')
        ? $ctx->reject('veto.no-x', ['word' => 'x']) : $ctx->allow())
    ->beforeUpdate(fn (BeforeContext $ctx) => $ctx->patch()->extension('sku', 'KF-1'), priority: 50, onFailure: OnFailure::BLOCK)
    ->afterUpdate(fn (AfterEvent $e) => error_log("model {$e->id} updated (event {$e->eventId()})"));
$kf->registerField('order', 'priority_code', FieldType::STRING, 'orders_extra', 'field.priority');   // label = an i18n key
$kf->ui()->contribute('models.list.columns', Surface::wc('/m/my-mod/ui/surfaces.mjs', 'kf-sku-cell')->flag('sku-column'));
return $kf;
```

```php
<?php // public/index.php
use Kf\Sdk\Sapi;

$kf = require __DIR__ . '/../kf.php';
$path = Sapi::path();
if ($path === $kf->hookPath()) { Sapi::handleGlobals($kf); return; }
if (Sapi::serveUi(__DIR__ . '/../ui')) return;
if ($kf->isPublicPath($path)) { echo '{"status":"UP"}'; return; }
$ctx = Sapi::verifyGlobals($kf);          // 401 problem+json already answered when null
if ($ctx === null) return;
// ... $ctx->tenant, $ctx->actor->type ('user' | 'system'), $ctx->hasRight('api.v1.m.my-mod.notes.post'), Sapi::body()
```

A PSR-15 application uses `Kf\Sdk\Psr15\KfMiddleware` instead (it exists only when `psr/http-server-middleware` and
`psr/http-factory` are installed); the verified context is the request attribute `kf.context`.

## What the SDK does

- **Registration**: hooks with `priority` and `onFailure`, fields on `model` / `order` / `customer`, UI contributions;
  labels are translation keys of the manifest's `i18n`. Only `transient` errors are retried (`ErrorClass`, RFC 9457
  `class`); a `permanent` one stops with one ERROR (`isRegistrationStopped()`, `lastRegistrationError()`).
- **Callbacks**: HMAC signature (±300 s) → 401; not a JSON object → 400; `kfenvelopeversion` with an unsupported major
  → 422 `unsupported-envelope-version`; then the handlers (`$ctx->allow()`, `$ctx->reject($key, $params)`,
  `$ctx->patch()...`; a patch on delete is a 500 `permanent`).
- **The module's own routes**: the signed context (`tenant`, `user`, `actor`, `locale`, `rights`, method and
  path) is verified; anything else is 401 `permanent`.
- **Feature flags**: `$kf->flags()->isEnabled($key)`, fetched after registering and replaced by every callback's
  `kfflags`.
- **Menu entries** (`capabilities.nav`, protocol 1.1): declared in the manifest only, never registered;
  the local check refuses a wrong surface, key, label, url (an in-app path), icon, order, flag or right (none on
  `customer`; under `api.v1.m.` only the right of one of the module's routes). The platform composes them into the
  staff, customer or platform menu while the module is live there.
- **The keys added to protocol 1.1** (delivered by the): `settings`
  (closed scalars only, `BOOLEAN`/`NUMBER`/`ENUM` -- settings carry no data), `routes[].admin` (`tenant` | `platform`),
  `secrets` (names and scopes, values only by reference), `provides` (reads served to the platform on a declared GET
  route), `surfaces[].page`, and the port id grammar of `reads` / `provides`; all read on the typed manifest and checked
  locally. The signed context accepts the actor `system` via `platform` (no user, no hookId, no rights).
- **Audit** (`$kf->audit()`, capability `audit`, implemented by the platform): inside a route handler,
  `$kf->audit()->record($ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'))` writes a row in the tenant of the
  request, under the module's slug (stored as `m.<slug>.Note`); `$kf->audit()->list($ctx, 'Note', '42')` reads only the
  module's own rows. The SDK sends the module token with `X-KF-Context` and the platform's proof `X-KF-Context-Proof`
  exactly as they arrived (the adapters keep them); the record is checked locally first; `401` is `AuditUnauthorized`,
  `403` is `AuditForbidden`.
- **Notifications** (`$kf->notifications()`): `$kf->notifications()->send($tenantId, 'author-changed', [$subject],
  ['modelName' => 'Rochie'], new NotificationOptions(deepLink: '/models/42'))` sends a notification declared in
  `capabilities.notifications` to members of the tenant named by their OIDC subject; the platform stores it as
  `m.<slug>.author-changed` and renders the module's texts in each recipient's language. Checked locally first; a
  `transient` answer is retried with the same `idempotencyKey`; a `permanent` refusal is `NotificationRejected` with the
  protocol's `reason`.
- **Surfaces**: the slugs of the platform's release list and the host's fixed tab orders are GENERATED in
  `src/Surfaces.php` from `sdk/catalog/surfaces.public.json`.

## The platform services of protocol 1.1 (`reads`, `settings`, `secrets`, extension values)

Four clients, as in Java: each call carries the module token and the context the platform forwarded (`X-KF-Context`
and its proof, unchanged), so the platform works in the context's tenant, for the context's user. A context the SDK did
not verify (or older than 300 s), or one without the proof, is refused locally (`KfException`, PERMANENT, nothing
sent). The platform's refusals are `PlatformRefusal` with `kind()` (`PlatformRefusalKind::INVALID` 400, `UNAUTHORIZED`
401, `FORBIDDEN` 403, `NOT_FOUND` 404), `reason()` and `errors()` (the field errors of a 400); a `503` is a TRANSIENT
`KfException`.

```php
$page = $kf->reads()->list($ctx, 'order.v1', ['modelVersionId' => '17'], 0, 50);   // $page->items, hasNext(), total
$model = $kf->reads()->get($ctx, 'model.v1', '42');                                 // 404 → null
$version = $kf->reads()->get($ctx, 'model-version.v1', '17', ['grading']);         // include (not for the system)
$settings = $kf->settings()->effective($ctx);        // typed by the manifest: bool, int|float, string (ENUM)
$ref = $kf->secrets()->write($ctx, 'r3-client-secret', $value);                    // → vault://…/kf-modules/<slug>/…
$value = $kf->secrets()->resolve($ctx, $ref)->value();                              // (string) $secret shows ***
$kf->extensions()->write($ctx, 'model_version', '17', ['aas_shell' => 'X', 'aas_last_push' => null]);  // null clears
```

`page` from 0, `size` 1–200 (default 20); the system context of a hook reads only objects, only the base fields. A
secret's value is never logged and never put in an error. The Java SDK's `FakePlatform` (its `tests` artifact) is Java
test code and has no PHP twin.

**An existing reference** (0.7.2): a `platform` secret may declare `"external": ["platform/aas/"]`; the module may then
keep a reference the platform already holds (`vault://platform/aas/kf-r3/client-secret`) instead of writing a value, and
`resolve()` sends it (the platform audits every such resolve). `$kf->secrets()->externalRefProblem($ref)` says why a
reference is not acceptable (null when it is). The secret store's own failures come back as a KfException whose
`problem()->type` ends with `secret-store-unreachable` (TRANSIENT) or `secret-store-rejected` (PERMANENT).

**Files and tenant deletion** (protocol 1.2, SDK 0.8.0): `capabilities.storage` gives `$kf->storage()`:
`$kf->storage()->uploadUrl($ctx, 'image/png', $size)` → UploadUrl `{key, url, method, headers, expiresAt}` (a presigned
`PUT` under a key the platform chooses), `$kf->storage()->downloadUrl($ctx, $key)` → DownloadUrl `{url, expiresAt}`; a
`size` ≤ 0 or a type the manifest does not declare is refused locally. `$kf->tenants()->onDeleted(fn ($d) => …)`
receives `tenant.after.delete` (`$d->tenantId`) after the signature and the envelope are verified, at least once:
delete idempotently (the SDK does not deduplicate by `kfeventid`). The platform purges the module's files of the tenant.

**Protocol 2.0, between modules** (SDK 4.1.0,0-18, sdk-protocol.md §15.9, §6.3, §5.5): the
declarations `capabilities.peerReads`, `commands`, `events` (the owner) and `peers` (the caller, the owner in `requires`)
are read and checked locally; `RequestContext` accepts `actor.via = module:{slug}` (`$ctx->actor->viaModule()`).
`$kf->peers()->read($ctx, $owner, $port, $query)` and `$kf->peers()->command($ctx, $owner, $name, $idempotencyKey, $body)`
call another module through the platform (the key is mandatory: `PeersClient::newIdempotencyKey()`, the same one on a
retry); `$kf->events()->emit($ctx, $name, $data)` emits the module's own `m.{slug}.after.{name}` and
`$kf->events()->on($owner, $name, fn ($ev) => …)` subscribes to another module's. An after delivery carries its context
and proof (`$ev->deliveryContext()`, a `HookEvents\DeliveryContext`); a background job -- in PHP, a queued job, since a
request does not outlive its delivery -- carries `contextHeader` and `proof`, rebuilds it with `DeliveryContext::of()`
and passes it in place of `$ctx` within ±300 s.

**Protocol 2.0, the order flow** (SDK 4.1.0,0-31, sdk-protocol.md §16; delivered by the
platform -- `ORDER_SERVICES`, `FLOW_RULES`, `FLOW_EVENTS` and `ORDERFLOW_STEP` with -- a module
uses a key only when the platform announces its feature, a platform before them refuses it at upload, and the SDK
checks only the grammar): `$kf->orders()` is the model's hooks on the public points `order.*` (`HookEvents.ORDER_*`, the paths
`ORDER_PATH_STATUS` / `ORDER_PATH_ENTRIES`, the statuses `ORDER_STATUSES` with `COMPLETED`; a patch carries only the
module's own `extensions`). `capabilities.flowRules` (`FlowRuleCapability`) and `capabilities.services`
(`ServiceCapability`) are read and checked; a rule reads `FlowRules\Projection::fromQuery($_SERVER['QUERY_STRING'])`
(`order.flow-rule.v1`: never `order.v1`) and answers `FlowRules\Decision::pass()` /
`FlowRules\Decision::block($key, $params)`; `$kf->flows()->on(FlowEvents::STEP_ENTERED, fn ($e) => …)` subscribes to the events of
the flow engine; `$kf->orderServices()->upsert($ctx, new OrderServices\Line($orderId, $entryId, $name, $amount, $currency,
$externalRef))` and `->delete($ctx, $orderId, $externalRef)` write the module's own service lines (`422 not-delivered` is a
`refused` refusal, `501 no-provider` an `unavailable` one). The surface `orderflow.step` takes the key of its step
(`Surface::key($stepKey)`).

**Protocol 2.1, the module flows** (SDK 4.1.0, sdk-protocol.md §17): `capabilities.flows`
(`Manifest\FlowCapability`: `key`, `file`, `name`) is read and checked -- `flows/<name>.bpmn` files in the pack,
`schemaVersion` 3 only; `ManifestCheck::checkPack($manifestJson, $files)` (an array of path => size in bytes) adds the
presence of each file and the 256 KiB cap. The platform compiles the BPMN and delivers the key with `MODULE_FLOWS`.

## Tests

```bash
php tests/conformance.php     # every fixture of sdk/conformance; exit 1 on a failed or unknown fixture
php tests/unit.php            # the SDK's own tests (derived from the Java tests)
```

## Provenance

Every file of `src/`, `bin/`, `tests/` and this README carries `kf-sdk-derived-from: sdk/java@<sha> v<version>`; the
commit that brings them carries `Sdk-Derived-From: <sha>`; `composer.json` has the pom's version.
`node sdk/tools/sdk-provenance-check.mjs` (CI job `provenance`) fails on a file edited by hand. To change the SDK,
change Java (with its fixture), then run `/kf-sdk-derive php`.


**Protocol 1.3** (SDK 0.9.0): a notification may go to a role —
`$kf->notifications()->send($tenantId, $key, [], $vars, NotificationOptions::roles('TENANT_ADMIN'))`; the platform
adds the role's active members of the tenant, each person once, and the module sees no subject.
`$kf->tenants()->onSoftDelete(fn ($e) => …)->onRestore(fn ($e) => …)` receive `tenant.after.soft-delete` /
`tenant.after.restore` (stop, then resume, the work for `$e->tenantId`; nothing is deleted on a soft-delete).
`TenantDataExport::bundle($schema, $tenantId, $part, $parts, $counts, $content)` builds the bundle of
`GET /m/{slug}/admin/export` with its `contentSha256`. The platform's own context may list `tenant.v1` without `ids`.

**Protocol 1.3.1** (SDK 0.9.1, platform `>=6.1`): no API change, the reads client is port-agnostic. A user holding
`api.v1.models.get` may list `model.v1` without `ids` (`$kf->reads()->list($ctx, 'model.v1', [], 0, 50)`), every item
with `modelOwner`; `model-version.garment-image.v1` gives `{id, url, expiresAt}`, a presigned GET URL valid 10 minutes
(object, or a list with `ids`), never to the system context. The surface `models.detail.overview.cards` is in the
generated vocabulary.

**Protocol 2.0** (SDK 4.1.0; the first cut release, tags `sdk-v4.1.0` / `sdk-internal-v4.1.0`; a module declares `platform` `>=4.1`): the manifest `schemaVersion` 3,
a strict superset of 2 (`SdkVersion::PROTOCOL_SCHEMA_VERSION` = 3; 1 and 2 stay readable). `ManifestCheck` checks
locally `capabilities.slots` (`Manifest\SlotCapability`, `Manifest\SlotKind` A/B/D/R, `Manifest\SlotVisibility`,
sdk-protocol.md §7.12), `requires` (slug, never itself, once, the platform's range grammar; `optional` under 3) and
`featureFlags[].requiresModules` (under 3, each slug declared in `requires`), with the platform's messages.
