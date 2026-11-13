# KF module protocol — v2.1 (normative; 2.0 delivered; 2.1 additive; SDK 4.1.0)

| | |
|---|---|
| Document | **v2.1 — additive over 2.0**: one capability more, `capabilities.flows` — the baseline flows a module brings as BPMN files in its pack (§17); no new `schemaVersion` (it stays 3), every manifest valid under 2.0 is valid under 2.1, unchanged; what it changes is listed in §1.B. **v2.0 — delivered (closed 2026-11-10), first cut in SDK 4.1.0**: the first major version since 1.0 — manifest `schemaVersion` 3, a strict superset of 2; what it changes is listed in §1.A. The text of 1.3.1 is frozen as `sdk-protocol-1.3.md` for the modules on `schemaVersion` 1–2. Before 2.0: **v1.3** (the role as a notification recipient, the selected version in the tab `ctx`, the tenant's soft-delete and restore points, the tenant-data export, `tenant.v1` listable by the platform; additive to v1.2.1, itself additive to v1.2, to v1.1 and to v1.0, §1). v1.0 replaced the prototype's v0.1. |
| Manifest | `kf-module-conf.json` **`schemaVersion` 3** (protocol 2.0: a strict superset of 2, §1.A); `schemaVersion` 2 and 1 are still read (§3.6, `sdk-protocol-1.3.md`) |
| Callback envelope | `kfenvelopeversion` **1** (`kfeventid`) |
| Signed context | `v` **1** (`actor`; `X-KF-Context-Proof`; `person` since 1.2, §9.2) |
| Host page API | `KF_HOST_API_VERSION` **5** (the slots of a module — `Slot`, `useSlotContributions`, the bus in the page — protocol 2.0, §7.12; before, **4**: `@kf/host-sdk`; the `@kf/ui` primitives of the module pages; the form binding `Form*` and `react-hook-form` in the import map, SDK 0.7.1; `moduleUrl()` aware of the customer portal, 1.2, §7.9) |
| Platform | semver from `GET /api/v1/platform/version`; the platform line follows the milestone, 4.1.x, dev/staging builds carry `-YYMMDD` (e.g. `4.1.0-261120`; the range compares the release core, `4.1.0`); the 5.x/6.x line is retired |
| SDKs | one semver for all, given by the Java `pom.xml`: **4.1.0** — protocol 2.1, the first cut release of protocol 2.0 (SDK X.Y.z needs a platform >= X.Y; no date suffix, which `composer validate` refuses), tagged `sdk-v4.1.0` (external variant) and `sdk-internal-v4.1.0` (internal variant) on the same commit. Before: **0.10.0-dev**, never cut (`-dev`, not `-SNAPSHOT`), and **0.9.1** — Java `ro.kf:kf-module-sdk`, TS `kf-module-sdk`, PHP `kf/module-sdk` (protocol 1.3.1: the `FakePlatform` rules of `model.v1` without `ids` and of `model-version.garment-image.v1`, the surface `models.detail.overview.cards` in the pinned catalog and the vocabularies; protocol 1.3 from 0.9.0: `recipientRoles`, the tenant's soft-delete and restore handlers, `TenantDataExport`, `tenant.v1` listed by the platform's context; protocol 1.2 complete from 0.8.0, the complete `FakePlatform` of the Java tests artifact from 0.8.1) |

How a module talks to the KF platform: plain HTTP both ways, on the platform's isolated network. **This document is the
contract.** The Java SDK is the reference implementation of it; the TS and PHP SDKs are derived from Java; the
shared conformance suite `sdk/conformance/` fixes their behaviour. A module may also speak the protocol
directly, in any language.

```
module ──PUT /api/v1/platform/modules/{slug}/registrations──▶ platform   (at boot, Bearer KF_MODULE_TOKEN)
module ◀──POST {callbackUrl} (CloudEvent, HMAC-signed)──────── platform   (before/after a mutation)
module ◀──{METHOD} {callbackBase}{path} (X-KF-Context, signed)── platform  (a user, or a hook, calls the module's API)
module ──GET  /api/v1/platform/modules/{slug}/flags──────────▶ platform   (Bearer KF_MODULE_TOKEN, read-only)
module ──POST|GET /api/v1/platform/modules/{slug}/audit─────▶ platform   (Bearer KF_MODULE_TOKEN + the forwarded context)
module ──POST /api/v1/platform/modules/{slug}/notifications──▶ platform   (Bearer KF_MODULE_TOKEN, a declared notification)
module ──GET  /api/v1/platform/modules/{slug}/reads/{port}…──▶ platform   (token + context + proof, 1.1, §15.1)
module ──GET|PUT|POST …/{slug}/settings | secrets/…─────────▶ platform   (token + context + proof, 1.1, §15.2, §15.3)
module ──PATCH …/{slug}/extensions/{entity}/{id}────────────▶ platform   (token + context + proof, 1.1, §15.4)
module ◀──GET {callbackBase}{path} (actor system via platform)── platform  (a provided read, 1.1, §15.5)
module ──POST …/{slug}/storage/upload-url | download-url────▶ platform   (token + context + proof, 1.2, §15.7)
```

## 0. Conventions

- **MUST**, **MUST NOT**, **SHOULD**, **MAY** are used as in RFC 2119.
- Each part of the protocol has a status:
  - **Delivered** — the platform implements it today;
  - **Reserved in 1.0** — the wire form is fixed here and in the SDKs, the platform implements it in a later release; until then the platform behaves as stated in the part;
  - **Reserved in 1.1** — the same, for the parts protocol 1.1 adds (§15): the wire form is fixed here and
    checked locally by the SDKs 0.7.0, the platform implements it in a later release;
  - **Reserved in 1.2** — the same, for the parts protocol 1.2 adds: the wire form is fixed
    here and checked locally by the SDKs 0.8.0, the platform implements it in a later release,
    and the SDK clients come;
  - **Reserved in 1.3** — the same, for the parts protocol 1.3 adds: the wire form is fixed
    here, the Java SDK speaks it (TS and PHP derived, SDK 0.9.0), the platform implements it in a later release;
  - **In progress** and **reserved until the business-core engine** — the states of the parts protocol 2.0 adds
   , listed in §1.A;
  - **Catalogued** — named by the contract, its form is fixed by the plan that delivers it; no wire form here.
- A change of the wire contract takes a new `schemaVersion` (manifest), a new `envelopeVersion` major (callbacks) or a
  new context `v`, and a version note in this document. A key added to an object is not a change.
- JSON object keys are case-sensitive; a receiver MUST ignore keys it does not know unless a section says otherwise
  (the manifest's closed lists, §3).

## 1. Versioning

Three numbers plus the SDK:

1. **The platform version** — a semver, `GET /api/v1/platform/version` (platform admin) →
   `{version, releaseCore, manifestSchemaVersions: {min, max}}`.
2. **The manifest `schemaVersion`** — `1`, `2` or `3` (protocol 2.0: the platform reads the three
   in parallel). A manifest MUST declare `"platform": ">=x.y"` under `schemaVersion` 2 and 3. Upload and approval **refuse** (`422`, `class: permanent`) a package whose `schemaVersion` is
   outside `manifestSchemaVersions` or whose `platform` range does not contain the running platform; the message names
   both versions.
3. **`KF_HOST_API_VERSION`** — the version of the host page API given to module UI bundles (`@kf/host-sdk`
   `apiVersion`, §7.9). `2`; a module that needs `locale`/`onLocaleChange`/`t` checks `apiVersion >= 2`.
   `3` (protocol 1.1): `@kf/ui` gains the primitives of the big module pages (§7.9); a module that uses one
   checks `apiVersion >= 3`. `4` from protocol 1.2: `moduleUrl()` returns the portal's route when the active
   persona is the customer portal's (§7.9); a module that calls its API from the portal checks `apiVersion >= 4`. Every
   step is additive.
4. **The SDK semver** — given by the Java `pom.xml`, identical in `package.json` and `composer.json`. Each SDK
   declares the manifest `schemaVersion` it speaks (`SdkVersion.PROTOCOL_SCHEMA_VERSION` = 3 from 0.10.0, 2 before) and the envelope majors it
   reads (`SUPPORTED_ENVELOPE_MAJORS` = `[1]`). An SDK MUST refuse locally a manifest newer than the one it speaks.

**The versions of this document.** A minor version of the protocol is **additive**: it adds keys and capabilities to
the existing wire forms and keeps every one of them; a manifest valid under 1.0 is valid under 1.1, unchanged, and needs
no new `schemaVersion` (§0).

| version | SDK | what it adds |
|---|---|---|
| 1.0 | up to 0.6.0 | the contract (§3–§14) |
| 1.1 | 0.7.0 | `capabilities.nav` — the module's menu entries on the staff, customer and platform menus (§3.2, §7.11). **Also in 1.1** (before the tag `sdk-v0.7.0`): the published reads `reads` with their port ids (§15.1), `settings` — closed scalars only, settings carry no data (§15.2), `routes[].admin` (§3.2, §9.5), `secrets` by reference (§15.3), the module's writes of its own extension values and the extensible entity `model_version` (§6.2, §15.4), `provides` — reads the module serves to the platform, with the actor `system` via `platform` (§9.2, §15.5), `surfaces[].page` (§3.2, §15.6) and the module's own storage (§3.3). Each part is **reserved in 1.1** until the plan named in its section delivers it. |
| 1.1 | 0.7.1 | No wire change: the SDKs check locally the **type** of a surface that carries `page`, against the release list they pin (§3.2); `@kf/ui` v3 gains the form binding `Form*`, with `react-hook-form` in the page import map (§7.9); `tenant-management.cards` gains the action `refresh` (§7.1). |
| 1.1 | 0.7.2 | Additive: `secrets[].external` — a `platform` secret may declare prefixes under which the module keeps an **existing** vault reference instead of a value it writes; the platform resolves it, audits each resolve and refuses an undeclared one with `403 external-ref-not-declared` (§3.2, §15.3); an unreachable secret store is `503 secret-store-unreachable`, one whose policy refuses the path `503 secret-store-rejected`; a module's `503` with a problem body reaches the user as it is (§9.1). A platform before this refuses the key at upload (a closed object). |
| 1.1 | 0.7.3 | Additive: `routes[].timeoutSeconds` — how long the platform waits for the module's answer on that route, 1–60 s; absent = the platform's default 5 s (§3.2, §9.1). A platform before this refuses the key at upload (a route is a closed object). |
| 1.1 | 0.7.3 (no SDK change) | Additive, wire only: a module's write of its own values on `model_version` runs the hooks of the version's **model** — `model.before.update` (veto), `model.after.update` and `model.field.changed` — with the paths `versions.{versionId}.extensions.{slug}.{field}` in the model's envelope; a `patch` answer to such a write is ignored (§5.1, §6.2, §15.4). A provided read answered `404` by the module is `NOT_CONFIGURED` (§15.5). The SDKs read both as they are: `before`/`after` keep unknown keys in `data`, `changed` is a list of text. |
| 1.1 | 0.7.4 | No wire change: the SDKs read a version's values in the model's envelope (§5.1) typed — `ModelSnapshot.versionExtension(versionId, slug, field)` and `versionExtensions` on `before`/`after`; the changed version paths `changedVersionFields()` (`VersionField` = `{versionId, slug, field}`) and `changedVersionIds()` on a before hook and an after-event; `versionField()` on a field change. `changed`, `field` and `data` are unchanged, so a module that reads `data` keeps working. |
| 1.1 | 0.7.4 (no SDK change) | Wire correction, additive: the platform now delivers what §5.1 states. The side that does not exist is `null`, never `{}` — `before` of `after.create`, `after` of `after.delete`, as on the before hooks. `after.extensions` of an update is the stored values of **every** module overlaid with the fields written (it carried only the fields written, the other modules `null` or absent). A version's values in the model's envelope are complete: `before.versions.{versionId}.extensions` the stored values, `after…` those overlaid with the values written, every active `model_version` field of the live modules present (`null` when not set) (§5.1). `changed`, `data.field` and the SDKs are unchanged; the conformance fixtures already had these shapes. |
| 1.1 | 0.7.5 | No wire change: the release list the SDKs pin gains the public surface `overview.cards` — type `extension`, with `hostOrders` (the host's own cards: `orders` 0, `collections` 1000, `customers` 2000, `models` 3000, `recent-activity` 9000), `ctx` `{}` and `actions.navigate` (§7.1); the generated vocabularies carry it (`Surfaces.OVERVIEW_CARDS`, its `TYPES` and `HOST_ORDERS` entries). No new surface type, so no new protocol version. An older platform refuses a contribution on it at registration as `unknown surface` (§7). |

| 1.2 | 0.8.0 | Additive: `routes[].audience: "member"` — any member of the active tenant calls the route, whatever its role — and `routes[].portal` — the route is also served on the customer portal, with the customer's tenant (§3.2, §9.1, §7.9); the top-level `activation: "platform"` — a module live in every tenant (§3.1); `capabilities.storage` — files uploaded and read through presigned URLs under `kf-modules/{slug}/{tenant}/`, purged with the tenant (§3.2, §15.7); `person` — the caller's e-mail and display name in the context of a `member` route (§9.2); the hook point `tenant.after.delete` (§6.1); the surface `app.overlay` (§7.1); the read port `tenant.v1` (§15.1); `KF_HOST_API_VERSION` 4 (§7.9). `schemaVersion` 2, context `v` 1, `kfenvelopeversion` 1 and the surfaces' `catalogVersion` 2 stay. Each part is **reserved in 1.2** until the plan named in its section delivers it; the SDK clients (`StorageClient`, the `tenant.after.delete` handler, `person()` typed, the pinned catalogs) come. **Every 1.2 part is delivered** (on the platform, the SDK clients, SDK 0.8.0). |
| 1.2.1 | 0.8.1 | Additive, wire only: two read ports in §15.1 — `component.v1` (the materials, accessories and packaging; list `ids` or `q`, one of them, with `componentType`) and `inventory-roll.v1` (the rolls of a material: list `componentId` or `ids`, one of them; `state` computed by the platform). **No new manifest key**, no new capability, no new surface: a port is data the platform publishes (§15.1), so a manifest valid under 1.2 is valid under 1.2.1, unchanged. The SDK clients are port-agnostic, so their code does not change; SDK 0.8.1 carries the complete `FakePlatform` of the Java tests artifact with both ports and their filter rules. `schemaVersion` 2, context `v` 1, `kfenvelopeversion` 1, `catalogVersion` 2 and `KF_HOST_API_VERSION` 4 stay. |
| 1.3 | 0.9.0 | Additive: **the role as a notification recipient** — `recipientRoles` on `POST …/notifications`, the platform resolves the active members of the role in the notification's tenant and the module sees no subject; `422 unknown-role`, `422 too-many-recipients` over 200 resolved recipients (§11.1; delivered); **the selected version in the tab `ctx`** — `ctx.version = {id, name} \| null` on `models.detail.tabs` (§7.3; delivered); **the tenant's soft-delete and restore** — the hook points `tenant.after.soft-delete` and `tenant.after.restore`, to the live modules, and no `tenant.before.delete` (§6.1; delivered); **the tenant-data export** — the optional top-level manifest key `tenantData`, the module route `GET /m/{slug}/admin/export` the platform calls, the common bundle that `admin/import` takes back (§3.1, §12, §15.8; delivered); **`tenant.v1` listable by the platform** — the list without `ids` for the platform's actor only, every tenant with `deletedAt` (§15.1; delivered). Each part is **reserved in 1.3** until its plan delivers it. **Every 1.3 part is delivered** (on the platform, SDK 0.9.0). No new capability, no new surface; `schemaVersion` 2, context `v` 1, `kfenvelopeversion` 1, `catalogVersion` 2 and `KF_HOST_API_VERSION` 4 stay (a module tests the presence of `ctx.version`). |
| 1.3.1 | 0.9.1 | Additive; **every 1.3.1 part is delivered** (BE 9b952ec09, FE a385a1e6, before the cut of 6.1.0), so a module that relies on them declares `platform` `>=6.1` on the 6.x line (since the renumbering — platform 4.1 — the line is 4.1.x and a module declares `>=4.1`): `model.v1` gains the base field `modelOwner` (the model's free-text owner, not an identity) on the object and the list items, and its list **without `ids`** for a user holding `api.v1.models.get` (§15.1); the read port **`model-version.garment-image.v1`** — a presigned GET URL of the version's garment image, valid 10 minutes, without ACL (§15.1); the public surface **`models.detail.overview.cards`** — type `extension`, in the Overview tab of the model page, `ctx` `{ model, version, href }` (§7.1, §7.2, §7.3). No new manifest key, no new capability, no new surface type; `schemaVersion` 2, context `v` 1, `kfenvelopeversion` 1, `catalogVersion` 2 and `KF_HOST_API_VERSION` 4 stay. The SDK clients are port-agnostic, so their code does not change; SDK 0.9.1 carries the `FakePlatform` rules of the list and the port, the surface in the pinned list and the generated vocabularies (catalog sync from the host). |
| 1.3.1 | 0.9.1 (no SDK change) | Platform only, no wire change (delivered before the cut of 6.1.0): **the export made at the uninstall carries the module's files** — for a version that declares `storage`, every object under `kf-modules/{slug}/{tenant}/` of every exported tenant, at `files/<key>` in the archive, with its `sha256` in the manifest (§12, §15.8); **the delete needs an export that carried them** (§12); the platform route `POST /api/v1/platform/modules/{slug}/exports/restore-files` puts them back after a delete and a new install (§15.8). The module's bundles, its routes, its manifest and the SDKs do not change: the bundles keep only the keys of the files (`imageKey`, `screenshotKey`), and an object put back at its own key is all the module's import needs. No module is rebuilt or signed again. |
| 2.0 | 0.10.0-dev | **Major — delivered (closed 2026-11-10), first cut in 4.1.0.** Manifest **`schemaVersion` 3**, a strict superset of 2: a manifest valid under 2 is valid under 3 with only the number changed. The platform reads `schemaVersion` 1, 2 and 3 in parallel (`manifestSchemaVersions` `{min: 1, max: 3}`); the modules on 1–2 keep reading `sdk-protocol-1.3.md` until their re-release. A platform that does not know 3 refuses the manifest at upload and approval (`PlatformCompatibility`, `422`, `class: permanent`, item 2 above). Every key of 3 is refused under 1–2 (`requires "schemaVersion": 3`). What 2.0 changes, part by part and with its state, is the table of §1.A. No tag is cut during the phase. |
| 2.1 | 0.10.0-dev | **Additive over 2.0.** One capability: `capabilities.flows[] {key, file, name}` (`schemaVersion` 3 only) — the module's baseline flows, each a BPMN file `flows/<name>.bpmn` in the pack, compiled by the platform at upload, approval and rollback, published as global flows (`origin = module`) at install and upgrade, visible only to the tenants where the module is active, archived at uninstall (§17). Delivered by the platform with `ProtocolFeature.MODULE_FLOWS`; a platform that does not deliver it refuses the key with its text (§16.7). No new `schemaVersion`, envelope, context `v` or `KF_HOST_API_VERSION`. The SDKs 0.10.0 check the grammar and, on a pack, the files (`ManifestCheck.checkPack`); never the BPMN. What 2.1 changes: §1.B. |
| 2.1 | 4.1.0 | **The first cut release** of protocol 2.0 + 2.1: no wire change from 0.10.0-dev. The SDKs follow the platform line: SDK X.Y.z needs a platform >= X.Y, so a module on protocol 2.x declares `platform` `>=4.1`; the 5.x/6.x line of the platform is retired. Tags `sdk-v4.1.0` and `sdk-internal-v4.1.0` on the same commit. |

A platform that does not know a capability of 1.1 refuses it at upload as an unknown capability (§3.2): a module
that declares `nav` needs a recent enough platform; one that declares `settings`, `secrets` or `provides`, a platform
on a recent enough platform. A key added to an existing element (`routes[].admin`, `surfaces[].page`) is refused by the platform's
strict check on a current platform (on an older one, it is ignored, §0); the SDKs 0.7.0 check all of them locally, and from 0.7.1 the
type of a surface that carries `page` too (against the release list they pin; a surface the list does not know is left
to the platform).

A platform before 1.2 refuses the 1.2 keys at upload — `audience` and `portal` on a route (a closed object),
`storage` (an unknown capability) and `activation` (on a recent enough platform, the platform's strict check of the root) — so a 1.2
module does not install on a platform that does not understand it. The SDKs 0.8.0 check every 1.2 key locally, with the
platform's texts (§3.2).

A platform before 1.2.1 does not publish `component.v1` and `inventory-roll.v1`, so it refuses at
upload a manifest that declares either in `capabilities.reads` (a port it does not publish, §15.1): a module that reads
them needs a recent enough platform (`platform` in its manifest). Nothing else of 1.2.1 reaches the wire.

A platform before 1.3 does not deliver the 1.3 parts, and a module MUST work without them or declare the platform
version that delivers them (`platform` in its manifest): an older host sends no `ctx.version` (the
module keeps its own way of finding the version, e.g. the address); an older platform never delivers the two
tenant points (a subscription to an unknown point is refused at registration, so a module subscribes only on a
platform that declares them); an older platform ignores `tenantData` at the root (§0) and never calls
`admin/export`; an older platform answers a `tenant.v1` list without `ids` with `400 ids: required`.

A platform before 1.3 reads `recipients` only: a request that names only `recipientRoles` reaches
it with `recipients: []` and is refused (`422 no-recipients`: the old platform ignores the key it does not know) —
nothing is sent, never to the wrong people. A module that
relies on `recipientRoles` declares the platform version that delivers it (`platform` in its manifest).

A platform before 1.3.1 (6.0.x and older) does not deliver the 1.3.1 parts, and each of them fails closed: it refuses
at upload a manifest that declares `model-version.garment-image.v1` in `capabilities.reads` (a port it does not
publish, §15.1); it refuses at registration a contribution on `models.detail.overview.cards` (`422 unknown surface`,
, §7); it answers a `model.v1` list without `ids` with `400 ids: required`; and its `model.v1` objects carry no
`modelOwner`, so a module tests the presence of the key. A module that relies on any of them declares the platform
version that delivers them (`platform` in its manifest): `>=6.1` on the 6.x line; since the renumbering
(platform 4.1) the line is 4.1.x and a module declares `>=4.1`.

## 1.A What changes from 1.3 (protocol 2.0)

Protocol 2.0 is the first major version since 1.0. It is written in this file while the platform delivers it, in
four waves: V1 `requires`, V2 between modules, V3 the module slots, V4 the shape of the module steps of the
order flow. The states of a part:

- **in progress** — the wire form is being fixed and delivered; the plan that delivers it marks it
  `delivered` here;
- **delivered** — the platform and the three SDKs implement it;
- **reserved until the business-core engine** — the wire form is fixed and the SDKs speak it, but the platform
  **refuses the key at upload** (`422`, `class: permanent`, with the text of the key) until business-core
  activates it together with its engine. A key declared and not applied cannot exist (fail-closed). No part of the
  table below is in this state any more: the order keys were activated.

| element | wave | state |
|---|---|---|
| the four holes of `requires`: (a) the upgrade without the dependents' check, (b) the platform modules invisible to `requires`, (c) start and auto-start without a check, (d) cycles not detected at upload — platform behaviour on **every** `schemaVersion`; a platform module requires only platform modules | V1 | **delivered** (SDK 0.10.0-dev, no tag) |
| `requires[].optional` (schema 3): blocks nothing in any direction; present outside its range = absent | V1 | **delivered** (SDK 0.10.0-dev, no tag) |
| `featureFlags[].requiresModules` (schema 3): one formula with the flag's own value | V1 | **delivered** (SDK 0.10.0-dev, no tag) |
| `requires` normative (§3.2, §3.5), checked locally by the three SDKs, shown at approval | V1 | **delivered** (SDK 0.10.0-dev, no tag) |
| reads and commands between modules, through the platform (`peerReads`, `commands`, `peers`) | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| `actor.via = module:{slug}` in the signed context of a call between modules | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| the proof carried by an after-hook delivery, for a background job's call | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| the events `m.{owner}.after.*` (`capabilities.events`, subscribed under `requires`) | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| one network per module: the module reaches the platform, never another module | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| `moduleUrl` / `http` bound to the module's slug; another slug needs `requires`, checked on `/m/{slug}/**` | V2 | **delivered** (SDK 0.10.0-dev, no tag) |
| the class of a module comes only from its signed envelope: `sdk {tag, commit, variant}` in the statement, the internal SDK's tag line `sdk-internal-v*`, the class computed on re-verification of the active version and shown at approval and in `signature{}` (§14.1, §14.5, §14.6, §14.8, §7.12) | V2 | **delivered** on the platform (`ModuleTrustClass`, the display) and in the tool (`build-info`, `sign`, `release-check`, `inspect`, the shared vector `valid-internal-sdk`) |
| the module slots (`capabilities.slots`, kinds A/B/D/R, `visibility`, §7.12) | V3 | **delivered** (the platform validates, stores, serves and guards them, the approval shows them; SDK Java 0.10.0-dev; the contract unchanged) |
| `KF_HOST_API_VERSION` 5: `Slot`, `useSlotContributions`, the bus in the page | V3 | **delivered** (`useLiveField` reserved, bound) |
| the order keys: `capabilities.flowRules`, the surface `orderflow.step`, the subscription to the flow engine's events (the types `workflow.flow-step-entered`, `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted`), `capabilities.services` (`order.services.v1`). The hooks `order.before\|after.*` and `order.field.changed` are **not** reserved: public, in the release list. The form: §16 (the projection `order.flow-rule.v1` with `order.stages[]`, §16.4) | V4 | **delivered** on the platform: `ORDER_SERVICES` (`OrderServicesProtocolDelivery`, with the provider `OrderServiceLinesAdapter`, the platform); `FLOW_RULES`, `FLOW_EVENTS`, `ORDERFLOW_STEP` (`FlowProtocolFeatures`, in the same commit as `ModuleExternalRuleEvaluator` and `FlowPublishedEvents`, the platform; the surface mounted, the host UI). The form fixed and spoken by the SDKs; §16. An older platform refuses each key with its text (§16.7) |
| `capabilities.flows` (the module flows of protocol 2.1, `ProtocolFeature.MODULE_FLOWS`). A reserved name is activated only by a kernel contributor (`ProtocolFeatureDelivery`) shipped in the same commit as the core part of the key (`ORDER_SERVICES`; `FLOW_RULES`, `FLOW_EVENTS`, `ORDERFLOW_STEP`; `MODULE_FLOWS`), never by a config flag (§16.7) | V4 (name reserved); 2.1 | **a capability of 2.1** (§1.B, §17): the form fixed and spoken by the SDKs; delivered by the platform with `MODULE_FLOWS`; until then refused with its text (§16.7) |

## 1.B What changes from 2.0 (protocol 2.1)

Protocol 2.1 is a minor, additive version (§1): it adds one capability, keeps every wire form of 2.0 and needs no new
`schemaVersion` — a manifest valid under 2.0 is valid under 2.1, unchanged. It is written before the platform implements it,
as the contract.

| element | what it is | state |
|---|---|---|
| `capabilities.flows[] {key, file, name}` (`schemaVersion` 3 only) | the module's **baseline flows**: each a BPMN file `flows/<name>.bpmn` in the pack, in the platform's flow vocabulary v1, with `kf.requiresModule` on the process naming the module's own slug; compiled at upload, approval and rollback (a flow that does not compile refuses the version, `422` with the report); published at install, upgrade, rollback and repair as a **global flow** `origin = module`, `source_ref = module:<slug>`, visible only to the tenants where the module is active; **archived** at uninstall, never deleted; the instances already started keep their snapshot (§17) | **delivered**: the form fixed and spoken by the SDKs 0.10.0-dev (grammar locally, the pack's files by `ManifestCheck.checkPack`); delivered by the platform with `ProtocolFeature.MODULE_FLOWS` (the platform) — the contributor `WorkflowProtocolFeatures` ships in the same commit as the compilation (`ModuleFlowContentCheck`) and the seed (`ModuleFlowSeeder`). A platform before it refuses the key at upload, approval, rollback (`422`, `class: permanent`, with the text of the key, §16.7) |

## 2. Environment

The platform injects these into **every service** of a module, through the generated `docker-compose.kf.yml`:

| Variable | Value |
|---|---|
| `KF_PLATFORM_URL` | e.g. `http://backend:8080` |
| `KF_MODULE_SLUG` | the module slug, e.g. `api-test` |
| `KF_MODULE_TOKEN` | `kfm_…`, random; generated at the first install, kept across upgrades. The platform stores only its SHA-256. |
| `KF_HOOK_SECRET` | random HMAC key: signs the callbacks (§5) and the context of the module's own routes (§9) |
| `KF_CALLBACK_BASE` | only for services with `kf.expose`: `http://{service}.{slug}.kfmod:{port}` |

`{service}.{slug}.kfmod` is a network alias on the platform network. Services without `kf.expose` are not on it and
cannot reach the platform. Compose also makes the **service name** an alias there, and it cannot be suppressed: the name
of a service with `kf.expose` is therefore one DNS label (`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`, no dot) that is not a
name of the platform's stack (`postgres`, `vault`, `minstack`, `keycloak`, `traefik`, … and the prefixes `kf-`,
`kfmod-`), and the module's own file never declares the network `platform` — refused at upload (`422`, the key named)
and by the module runner at `up` (10 Nov 2026). The SDKs also read `KF_MODULE_MANIFEST` (default `./kf-module-conf.json`): the manifest they
check the registration against before sending it (§4.4). A module MUST NOT log `KF_MODULE_TOKEN` or `KF_HOOK_SECRET`.

## 3. The manifest (`kf-module-conf.json`, `schemaVersion` 2)

```json
{
  "schemaVersion": 2, "slug": "api-test", "name": "module.name", "description": "module.description",
  "version": "0.5.0", "rollbackFloor": "0.4.0", "platform": ">=4.1", "author": "Katty Fashion", "activation": "tenant",
  "featureFlags": [ { "key": "author-tab", "label": "flag.author-tab.label", "description": "flag.author-tab.desc", "default": false } ],
  "requires": [ { "slug": "other", "versionRange": ">=1.0.0" } ],
  "i18n": { "en": { "module.name": "API Test", "module.description": "Notes and an author tab on models", "field.priority": "Priority" },
            "ro": { "module.name": "Test API" } },
  "capabilities": {
    "fields":        [ { "entity": "order", "name": "priority_code", "type": "STRING", "table": "orders_extra", "label": "field.priority", "editable": true } ],
    "hooks":         [ { "point": "model.before.update", "onFailure": "block" }, { "point": "tenant.after.delete" } ],
    "routes":        [ { "method": "POST", "path": "/m/api-test/notes", "right": "api.v1.m.api-test.notes.post", "roles": ["QC"], "hookable": true },
                       { "method": "POST", "path": "/m/api-test/feedback", "right": "api.v1.m.api-test.feedback.post", "audience": "member", "portal": true },
                       { "method": "GET", "path": "/m/api-test/stations", "right": "api.v1.m.api-test.stations.get" },
                       { "method": "PUT", "path": "/m/api-test/admin/r3-keys", "right": "api.v1.m.api-test.admin.r3-keys.put", "admin": "platform" },
                       { "method": "GET", "path": "/m/api-test/admin/shells", "right": "api.v1.m.api-test.admin.shells.get", "admin": "tenant" } ],
    "surfaces":      [ { "slug": "models.detail.tabs", "kind": "react", "label": "tab.label" },
                       { "slug": "module.pages", "kind": "react", "label": "page.label", "page": "shells" } ],
    "notifications": [ { "key": "author-changed", "variables": ["modelName"], "channels": ["in_app"], "subject": "n.subject", "body": "n.body" } ],
    "reads":         [ { "port": "model.v1" }, { "port": "file.public-url.v1" } ],
    "audit":         [ { "access": "write" }, { "access": "read-own" } ],
    "nav":           [ { "surface": "staff", "group": "admin-utils", "key": "notes", "label": "nav.notes", "url": "/modules", "icon": "Boxes", "right": "api.v1.m.api-test.notes.post", "order": 20, "flag": "author-tab" } ],
    "settings":      [ { "key": "push-on-save", "type": "BOOLEAN", "default": false, "label": "setting.push" },
                       { "key": "timeout-seconds", "type": "NUMBER", "default": 30, "min": 5, "max": 600, "label": "setting.timeout" },
                       { "key": "mode", "type": "ENUM", "default": "auto", "values": ["auto", "manual"], "label": "setting.mode" } ],
    "secrets":       [ { "name": "r3-client-secret", "scope": "platform", "writeRight": "api.v1.m.api-test.admin.r3-keys.put" } ],
    "provides":      [ { "port": "factory.stations.v1", "path": "/m/api-test/stations" } ],
    "storage":       [ { "contentTypes": ["image/jpeg", "image/png"], "maxBytes": 5242880 } ]
  }
}
```

### 3.1 Identity and texts

- `slug` MUST match `^[a-z0-9][a-z0-9-]{1,40}$`; `version` is the module's semver; `platform` is required (§1).
- **`rollbackFloor`** (optional, SDK 0.7.0): the oldest version of the module that runs
  correctly on the data this version leaves behind — the schema of the module's **own** database (its Flyway, in its
  compose), which the platform cannot see. A semver 2.0 version at or below `version` (semver precedence: `1.10.0` is
  above `1.9.0`); absent or `null` = none. A value that is not semver, or is above `version`, is refused, naming the
  key: by the SDKs locally (0.7.0), by the platform at upload. **Delivered**: a platform
  before it ignores the key. The meaning: version V declares `rollbackFloor = F` when every
  version ≥ F of the module runs correctly on the data left by V; between F and V the migrations of the module's own
  database are only additive (expand). The platform enforces it at rollback (§12). A key the platform does not know is
  ignored (§0), so it needs no new `schemaVersion`; the SDKs check it under every `schemaVersion`.
- **`activation`** (optional, 1.2; **delivered** — the manifest key): `"tenant"` (the default, absent or `null`) or `"platform"`; any other value is refused
  (`activation must be "tenant" or "platform" (got …)`), by the SDKs 0.8.0 locally under every `schemaVersion`. A
  `platform` module is **live in every tenant**, existing and new, while it is `INSTALLED` with an active version,
  started, not `TAMPERED` and not deactivated; no per-tenant activation is asked for and none can be made: activating or
  deactivating it in a tenant is answered `409` `module-platform-activation` (`class: permanent`). It is stopped
  globally — Stop or deactivation in `/modules`, by the platform administrator only. Flags are still evaluated per
  tenant. The approval shows it as a badge with a warning ("active in every tenant"). Starting its runtime stays the
  manual Start of `/modules`.
- **`tenantData`** (optional, 1.3; **delivered**):
  `{"format": "<slug>/tenant-data/v<N>", "export": true}` — the module keeps per-tenant data in its own database and
  answers the platform's export of it (§15.8). `format` is the `schema` of its bundles (e.g.
  `kf-feedback/tenant-data/v1`): it MUST start with the module's `slug`, then `/tenant-data/v` and a major version
  (`^[a-z0-9][a-z0-9-]{1,40}/tenant-data/v[1-9][0-9]{0,3}$`); `export` MUST be `true` (the key exists to declare it).
  A closed object (another key is refused); absent or `null` = the module keeps no tenant data of its own, the
  platform exports only what it keeps for the module (§12). The key inside is `format`, not `schema`: `schema` is
  forbidden at any level of a manifest (§3.3). Shown at approval. The SDKs 0.9.0 do not check it locally; the platform
  does at upload.
- **`devOf`** (optional, remade; **delivered** on the platform, the
  tool): the slug of the signed module this pack is the **dev build** of. The rule:
  `slug == devOf + "-dev"`, and `devOf` MUST be a slug (a number or a boolean is refused). **The suffix `-dev` is
  reserved:** a slug ending in `-dev` MUST declare `devOf`, otherwise the pack is refused at upload (422 `validation`),
  under every `schemaVersion`; the base slug therefore has at most 37 characters. A dev build is noted `<devOf>@dev`
  (`kf-aas-r3@dev`) in the journal, the documentation and `kf-module inspect`. The platform accepts it **only on the
  route of the development organization** (unsigned, channel `DEV`), and only when `devOf` is a global module that is
  installed and signed (`identity_publisher` set); otherwise, and on the administration route or at "send to review",
  the answer is 422 `module-signature` `identity-mismatch` — a dev build is never approved, signed or promoted. Once
  installed it belongs to that organization (`dev_owner_tenant`): activating it in another tenant is 403
  `module-dev-only` (for a SUPER_ADMIN too); it and its base are mutually exclusive in one tenant (409
  `module-dev-build-conflict`); its rights are not in the catalogue of another tenant; it keeps its own compose project
  (`kfmod-<devOf>-dev`), container, database and texts. `tenantData.format` stays the base's
  (`<devOf>/tenant-data/v<N>`): it is a contract of the code, not of the slug. The tool derives the pack:
  `kf-module dev-build <zip>` (§14.8). **A module whose code writes its slug literally cannot run as its own dev build:**
  it reads its slug at run time — `KF_MODULE_SLUG` (§2) in the service, `module.slug` given by the host to every
  contribution (§7.10) in the UI — for its routes (`/m/<slug>/…`), its right codes (`api.v1.m.<slug>.…`), its texts and
  its bundle URL.
- **Texts are keys**. `name`, `description`, every flag `label`/`description`, every field and surface `label`
  and every notification `subject`/`body` MUST be a key (`^[a-z][a-z0-9_.-]{0,119}$`) defined in **`i18n.en`**. The
  registration sends only keys (§4). Texts live in `i18n: { "<language>": { "<key>": "<text>" } }`, at most 4000
  characters each, never blank.
- **`description`** (optional, SDK 0.6.0): what the module does, in one or two sentences — the same shape
  as `name` (a key defined in `i18n.en`; a literal text under `schemaVersion` 1). Absent or `null` = none; a blank,
  malformed or undefined key is refused. The platform shows it, in the user's language with the fallback of a flag
  description (the user's language → `en`, never the slug), in the module list, the module detail and at
  upload/approval, and shows nothing when the module declares none. A platform that does not know the key ignores it
  (§0), so the key needs no new `schemaVersion`.
- `en` is **mandatory**: every key of another language MUST also exist in `en`. The accepted languages are
  the platform's `language` table; a language the platform does not serve is a **warning**, never a refusal.
- The platform writes the module's texts into its dictionary at install and upgrade (owner = slug) and deletes them at
  uninstall. Fallback of a module text: the user's language → `en` → the default label → the slug.

### 3.2 Capabilities

**`requires`** (a root key, every `schemaVersion`; **normative from 2.0**) — the modules this one
depends on:

| key | element | rules |
|---|---|---|
| `requires` | `{slug, versionRange, optional?}` | `slug` another module's slug (`^[a-z0-9][a-z0-9-]{1,40}$`), never the module itself, at most once; `versionRange` non-empty, in the platform's grammar: `*`, `1.2.3`, `>=1.2.3`, `>1.2.3`, `<=1.2.3`, `<1.2.3`, or two bounds separated by a space (`>=1.2.0 <2.0.0`), the bounds not crossing. Refused at upload with the platform's messages (`"requires" slug must match …`, `a module cannot require itself (…)`, `duplicate "requires" entry for …`, `… version range '…' could not be parsed (…); expected one of: …`); checked locally by the SDKs 0.10.0. Shown at the Validation step and at approval (slug, range, "optional"). |
| `requires[].optional` | boolean, default `false` | **`schemaVersion` 3 only**: refused under 1–2 (`"requires[i].optional" requires "schemaVersion": 3`), a boolean under 3 (`"requires[i].optional" must be a boolean (got …)`). An optional dependency **blocks nothing, in either direction** (§3.5). A platform that does not know the key reads the dependency as mandatory (fail-closed); one before `schemaVersion` 3 refuses the manifest. |
| `featureFlags[].requiresModules` | array of slugs | §3.4: `schemaVersion` 3 only; each slug declared in `requires`. |

`capabilities` is a closed object: `fields`, `hooks`, `routes`, `surfaces`, `notifications`, `reads`, `audit`, `nav`
(1.1), `settings`, `secrets`, `provides` (1.1), `storage` (1.2), `slots`, `peerReads`, `commands`, `events`, `peers` (2.0,
`schemaVersion` 3 only, §7.12, §15.9, §6.3), `flowRules` and `services` (2.0, `schemaVersion` 3 only,
**delivered**, §16), `flows` (2.1, `schemaVersion` 3 only, §17). Each
is an array (absent = `[]`). An unknown key is refused. The capabilities are **shown at approval** and **checked at
registration**: what a module registers MUST be included in its manifest and in the platform's catalogs.

| capability | element | rules |
|---|---|---|
| `fields` | `{entity, name, type, table, label, editable?}` | `entity` required: an extensible entity of the platform (`model`, `order`, `customer`; **`model_version`** from 1.1, delivered, §6.2); `name`, `table` match `^[a-z][a-z0-9_]{0,40}$` (no `.` in `table`); `type` ∈ `STRING`, `NUMBER`, `DATE`, `BOOLEAN`, `LIST`; `(entity, name)` unique; `label` a key. **`editable`**: boolean, **default `false`**; `null` or a non-boolean is refused. It is a rule of the host's **generic rendering, not of writing** (§7.6): a field that is not editable is still written by the module's own contribution, by a hook patch or through the module's API. A `schemaVersion` 1 manifest has no capabilities: its fields read as editable. |
| `hooks` | `{point, onFailure?}` | `point` a hook point id (§6.1), unique; `onFailure` ∈ `allow` (default), `block` — **only on before points**. `onFailure: block` at registration REQUIRES this capability (§4.2). |
| `routes` | `{method, path, right, roles?, hookable?, admin?, timeoutSeconds?, audience?, portal?}` | §9. `method` ∈ `GET`, `POST`, `PUT`, `PATCH`, `DELETE`; `path` under `/m/{slug}/` (literal segments or `{param}`), without `..`, `//`, `%`, `*`, a query, a fragment or a trailing `/`; `right` = `api.v1.m.{slug}.` + 1–4 segments `^[a-z][a-z0-9-]{0,40}$`; `(method, path)` unique. **`roles`**: an array of text, absent = `[]` = no role, at most 10, each non-empty, at most 100 characters, no control characters, no duplicate; `TENANT_ADMIN` and `SUPER_ADMIN` (any case) are refused at upload; a name that is not a global role is a **warning** at upload and is skipped with WARN at install. **`hookable`** (**delivered**, §9.4): boolean, **default `false`**; `true` MUST only appear on `POST`, `PUT` or `PATCH` — otherwise the manifest is refused, locally by the SDKs and at upload by the platform. The capability is shown at approval. **`admin`** (1.1, **delivered**, §9.5): `"tenant"` or `"platform"`; absent = an ordinary route. `platform` MUST declare no `roles` (only the platform administrator holds the right); `admin` MUST NOT appear on a `hookable` route. Shown at approval. **`timeoutSeconds`** (SDK 0.7.3, §9.1): how long the platform waits for the module's answer on this route — a whole number from `1` to **`60`** (the platform's cap); absent or `null` = the platform's default (5 s). Over the cap, `0`, negative, fractional or not a number is refused at upload (`timeoutSeconds must be a whole number of seconds between 1 and 60 (got …)`) and locally by the SDKs. **`audience`** (1.2; **delivered** at upload, the forward, §9.1): `"member"` — **any member of the active tenant** in which the module is live calls the route, with any role (TENANT_ADMIN, the global roles, the custom roles); absent or `null` = an ordinary route. Another value is refused (`audience must be "member" (got …)`). A `member` route declares no `roles` (a non-empty list), no `admin` and is not `hookable` (`audience member excludes roles, admin and hookable (any member of the tenant calls it)`); it keeps its `right` (the catalogue registers it), but **a module does not ask for a right on it**: its per-object rules are its own. Shown at approval ("any member of the tenant"). **`portal`** (1.2; **delivered** at upload, the portal forward, §7.9): boolean, **default `false`**, `true` **only on `audience: "member"`**; `null`, a non-boolean or `true` on another route is refused (`portal must be a boolean, and only on audience member (got …)`). Shown at approval ("also from the customer portal"). **A route is a closed object**: the platform refuses at upload a key outside `method`, `path`, `right`, `roles`, `hookable`, `admin`, `timeoutSeconds`, `audience`, `portal` (`"…" is not a key of a route (known: …)`); the SDKs 0.8.0 do not yet refuse an unknown route key locally. |
| `surfaces` | `{slug, kind, label?, page?}` | `slug` a public surface (§7), `kind` ∈ `react`, `wc`, `label` a key. **`page`** (1.1, **delivered**, §15.6): `^[a-z][a-z0-9-]{0,40}$`; **required** on a surface of type `page` and refused on any other type (the host's release list, checked by the platform at upload: `page only on surfaces of type page`, and from SDK 0.7.1 locally by the SDKs against the list they pin — a surface that list does not know is left to the platform); one module declares each page of a surface once |
| `notifications` | `{key, variables, channels, subject, body}` | §11.1. `key` `^[a-z][a-z0-9_-]{0,40}$`, unique; `channels` non-empty ⊆ {`in_app`, `email`}; `subject`, `body` keys whose texts are templates over `variables` |
| `reads` | `{port, access?}` | §15.1. A port the platform publishes; `port` matches `^[a-z][a-z0-9-]{1,40}(\.[a-z][a-z0-9-]{1,40})?\.v[0-9]+$` (1.1: an entity, one optional sub-segment, a major version); `access` absent or `"read"`. **Delivered:** the platform publishes the ports of §15.1 and refuses at upload a port it does not publish. |
| `audit` | `{access}` | §11.2, **delivered**: `access` ∈ `write`, `read-own`, each at most once; absent = `[]`; shown at approval. An older platform refuses the key at upload (an unknown capability). |
| `nav` | `{surface, group, key, label, url, icon?, right?, order?, flag?}` | §7.11, **1.1, delivered**. A closed object (an unknown key is refused). `surface` ∈ `staff`, `customer`, `platform`; `group` the key of a group of that surface's menu (the platform's list, checked at upload; `^[a-z][a-z0-9-]{0,40}$`); `key` `^[a-z][a-z0-9-]{0,40}$`, unique per module and surface — the public key is `m.{slug}.{key}`; `label` a key of `i18n.en`; `url` an in-app path: starts with `/`, without `//`, `..`, `%`, a backslash, whitespace, a scheme, a query or a fragment; `icon` the name of an icon of the host (`^[A-Z][A-Za-z0-9]{0,60}$`, an unknown name renders the default icon); `right` the right of one of the module's own routes or a code the platform declares, **never on `customer`**; `order` 0–10000, default 100; `flag` one of the module's flags. At most 50 entries. The SDKs check everything but the group and the platform codes, which the platform checks at upload. Shown at approval. |
| `settings` | `{key, type, default, label, description?, values?, min?, max?}` | §15.2, 1.1, **delivered**. A closed object. **Settings carry no data:** `type` ∈ `BOOLEAN`, `NUMBER`, `ENUM` only — no free text, list, reference or secret (refused, naming the rule "settings carry no data"); data lives on surfaces (§15.2). `key` `^[a-z][a-z0-9-]{1,40}$`, unique; `default` required and coherent with the type (a boolean; a number within `min..max`; one of `values`); `values` only and always on `ENUM`, a non-empty list of distinct codes `^[a-z0-9][a-z0-9_-]{0,40}$`; `min`/`max` only on `NUMBER`, `min ≤ max`; `label` a key, `description` an optional key; at most 30. |
| `secrets` | `{name, scope, writeRight, external?}` | §15.3, 1.1, **delivered**. A closed object: a value never appears in the manifest. `name` `^[a-z][a-z0-9-]{1,40}$`, unique; `scope` ∈ `platform` (one value), `tenant` (one value per tenant); `writeRight` the `right` of a route this module declares; for `scope: platform`, a route with `admin: platform`. **`external`** (SDK 0.7.2): the vault path prefixes under which the module may keep an **existing** reference `vault://<prefix>…` for this secret instead of a value it writes (§15.3) — a non-empty array of at most 5 distinct prefixes `^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$` (the mount and at least one segment more, ending with `/`, e.g. `platform/aas/`), none with a `kf-modules` segment, **only on `scope: platform`**; absent = `[]`. Shown at approval: the platform administrator approves which of the platform's own secrets the module may read. |
| `storage` | `{contentTypes, maxBytes}` | §15.7, 1.2, **delivered**. **At most one element** (`capabilities.storage holds at most one element`), a closed object. `contentTypes`: 1–10 distinct `type/subtype` values in lower case (`^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$`), without a wildcard (`contentTypes must be 1 to 10 distinct type/subtype values, without wildcards`); `maxBytes`: a whole number of bytes from `1` to **`10485760`** (10 MiB; `maxBytes must be a whole number of bytes between 1 and 10485760 (got …)`). Shown at approval. |
| `provides` | `{port, path}` | §15.5, 1.1, **delivered**. A closed object. `port` a port of the platform's catalog of provided reads (the port grammar above; today `factory.stations.v1`, `inventory.roll-availability.v1`, `purchaseorder.order-purchases.v1` — §15.5, checked at upload), once; `path` the `path` of a `GET` route this module declares. |
| `slots` | `{key, kind, type, visibility, label?, publicMembers?, actions?, layerKinds?, events?, commands?, dataSchema?}` | §7.12, **2.0, `schemaVersion` 3 only, in progress, contract fixed**. A closed object. Under `schemaVersion` 1–2 the key is refused (`requires "schemaVersion": 3`). The key grammar, the kinds A/B/D/R, the types per kind, the mandatory `visibility` and the keys per kind are those of §7.12. Shown at approval. |
| `peerReads` | `{port, path, visibility}` | §15.9, **2.0, `schemaVersion` 3 only, delivered**. The reads this module OFFERS to other modules. A closed object. `port` `^m\.{self}\.[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*\.v[1-9][0-9]*$` (`{self}` = this module's slug), unique; `path` a `GET` route this manifest declares (`routes`), **with literal segments only** — a `path` with a `{param}` segment is refused: `kf-module-conf.json: capabilities.peerReads[i].path "<path>" must be a route with literal segments only (no {param} segment)` (2026-11-10); `visibility` ∈ `public`, `internal`, **required**. Shown at approval. |
| `commands` | `{name, path, method, visibility}` | §15.9, **2.0, `schemaVersion` 3 only, delivered**. The commands this module OFFERS. A closed object. `name` `^[a-z][a-z0-9-]{1,40}$`, unique; `method` ∈ `POST`, `PUT`, `DELETE`; `method path` a route this manifest declares, **with literal segments only** (the same refusal, `capabilities.commands[i].path`) (2026-11-10); `visibility` required. Shown at approval. |
| `events` | `{name, visibility}` | §6.3, **2.0, `schemaVersion` 3 only, delivered**. The events this module EMITS, each the point `m.{self}.after.{name}`. A closed object. `name` `^[a-z][a-z0-9-]{1,40}$`, unique; `visibility` required. Shown at approval. |
| `peers` | `{owner, reads?, commands?}` | §15.9, **2.0, `schemaVersion` 3 only, delivered**. What this module CALLS of another one. A closed object. `owner` a slug ≠ this module, **in `requires`** (mandatory or `optional`), at most once; `reads` ports `m.{owner}.….v{N}`, `commands` names; at least one of the two, each distinct. Under `schemaVersion` 1–2 each of the four keys is refused (`capabilities.<key> requires "schemaVersion": 3`). The subscription to `m.{owner}.after.*` is a `hooks[]` entry, not a `peers` one (§6.3). |
| `flowRules` | `{key, path, timeoutSeconds?, projection}` | §16.4, **2.0, `schemaVersion` 3 only, delivered (`FLOW_RULES`)** (a platform before it refuses it at upload with the text of the key, §16.7). A closed object. `key` `^[a-z][a-z0-9-]{1,40}$`, unique; `path` a `GET` route this manifest declares, **with literal segments only** (the same refusal, `capabilities.flowRules[i].path`) (2026-11-10); `timeoutSeconds` a whole number 1–60, absent = 2; `projection` = `order.flow-rule.v1`. Checked locally by the SDKs 0.10.0 (the grammar only). |
| `services` | `{port}` | §16.6, **2.0, `schemaVersion` 3 only, delivered (`ORDER_SERVICES`)** (a platform before it refuses it with the text of the key, §16.7). A closed object. `port` = `order.services.v1`, once. Checked locally by the SDKs 0.10.0. |
| `flows` | `{key, file, name}` | §17, **2.1, `schemaVersion` 3 only**, delivered with `MODULE_FLOWS` (until then refused with the text of the key, §16.7). A closed object, at most 20. `key` `^[a-z][a-z0-9-]{2,62}$`, unique (the key of the global flow; SHOULD start with the module's slug); `file` a `.bpmn` file directly under `flows/` (`^flows/[a-z0-9][a-z0-9._-]{0,99}\.bpmn$`), without `..`, unique, present in the pack, at most 256 KiB; `name` a key of `i18n.en`. The BPMN is compiled by the platform (§17.3). Checked locally by the SDKs 0.10.0 (the grammar; the files with `ManifestCheck.checkPack`). Shown at approval. |

### 3.3 What a module cannot do

The manifest MUST NOT contain (any level, any case) `migrations`, `ddl`, `sql`, `schema` — a module cannot run DDL
outside `kf_modules`: extension tables are declared in `capabilities.fields` and created by the platform — nor
`dictionary`, `translations`, `writes` — a module cannot write the platform dictionary: its texts are declared in
`i18n` and written by the platform. A module does not write the platform's dictionary; it may
call a service the platform publishes (`capabilities.services`, §16.6) — a write of its own data through the
platform, never of the dictionary. A module has no route outside `/m/{slug}/`, no
right outside `api.v1.m.{slug}.`, no role it can name for `TENANT_ADMIN` or `SUPER_ADMIN`, and no
access to the platform's data except through published ports (`reads`, §15.1) and its own extensions (§15.4).

**The module's own storage** (1.1; **delivered**, the first one being KF-AAS-R3's). A module MAY keep data
in a database of its own: a service of its own `docker-compose.yml` **without `kf.expose`** (so off the platform
network, §2), with its own migrations. The platform never runs DDL outside `kf_modules`, never reaches the module's
database and never reads it; nothing of it is declared in the manifest (the DDL keys above stay forbidden). Tenant
isolation inside that database is the **module's** obligation, on the `tenant` of the signed context (§9.2). The schema
of that database is what `rollbackFloor` describes (§3.1).

### 3.4 Feature flags

`featureFlags[]`: `key` `^[a-z][a-z0-9-]{1,40}$` unique, `label` a key, `description` an optional key, `default`
boolean (**false when omitted**); at most 50. See §8.

**`featureFlags[].requiresModules`** (2.0, `schemaVersion` 3 only; **delivered**): an array of module
slugs, each one declared in `requires` (mandatory or optional), never the module itself. The flag is on for a tenant
only while **each** of these modules is live there: effective value = (the tenant's override, else `default`) **and**
every required module live for the tenant. The platform computes it in one place (`ModuleFlagService.resolve`), so the
menus, the contributions, the snapshot and `GET …/flags` see the same value. Under `schemaVersion` 1–2 the key is
refused (`"featureFlags[i].requiresModules" requires "schemaVersion": 3`); a slug not declared in `requires` is
refused (`"featureFlags[i].requiresModules": "<x>" is not in requires (declare it there, mandatory or optional)`).
Shown at the Validation step of the upload.

### 3.5 Validation

The platform validates the manifest at upload (`ModuleManifestValidator`) and answers `422` (`class: permanent`) with
`errors[]` and `warnings[]`; the approval step shows the verdict and the capabilities. The SDKs apply the same rules
locally before the first registration (§4.4), minus those that need the platform's catalogs (entities, hook points,
surfaces, global roles, languages) and minus the limits on counts and on physical names, which stay the platform's.

**What the platform checks about `requires`** (2.0: platform behaviour on **every**
`schemaVersion`, 1, 2 and 3; the SDKs check only the shape of §3.2, the rest needs the registry):

- **Approval** (an install, an upgrade, a roll-forward, a reinstall): every mandatory requirement is installed in
  range (`422`), **and** the version does not leave an installed module that requires this one mandatorily outside its
  declared range — otherwise **`409`** naming the dependents (`dependents[]`), before anything is written (hole (a),
  `checkCanBecome`; the rollback of §12 asks the same).
- **Cycles**: a pack that closes a cycle through itself in the graph of `requires` (mandatory and optional) of the
  installed modules is refused at upload, on every channel, and again at approval: **`422`**, `class: permanent`,
  `kf-module-conf.json: requires: dependency cycle a → b → a` (hole (d)).
- **Start**: the manual Start, the dev Start and the automatic start at activation need every mandatory requirement
  installed, in range and **started** — otherwise `422` naming the dependency
  (`… (installed but its runtime is stopped: start it first)`). The convergence and the restart on a new version
  **wait**: they skip the start, journal `RUNTIME_FAILED … waiting for dependency <x>` once, and retry at the next
  cycle — never a failed attempt, never a loop of errors (hole (c)).
- **Platform modules**: a module `"activation": "platform"` satisfies the requirements of a module of any tenant, and
  a tenant's deactivation of a module counts the platform modules that require it (they are live in every tenant)
  (hole (b)).
- a module `"activation": "platform"` may require (mandatory or optional) **only platform modules** —
  `422`, `class: permanent`, naming the dependency
  (`kf-module-conf.json: requires[i]: "<dep>" is a tenant module; a module with "activation": "platform" may require
  only platform modules`); and a pack that an installed platform module requires must itself be a platform module
  (`… "<slug>" is required by the platform module "<P>"; a module a platform module requires must declare
  "activation": "platform"`). At upload and at approval.
- **The policy of `optional`**: an optional dependency blocks **nothing, in either direction** — not the
  dependent's approval, activation or start (absent, stopped, not activated or installed outside its range), nor the
  stop, deactivation, uninstall or upgrade of the module it names. Present outside its range it counts as absent (the
  calls between modules to it answer `MODULE_UNAVAILABLE`, §15.9). and the cycle check include optional
  dependencies; the removal of a slot used by another module stays a `409` whatever `optional` says (§7.12).

### 3.6 `schemaVersion` 1

A manifest without `schemaVersion` is version 1: texts are literals, there is no `i18n` and no `capabilities`
(declaring them is refused), only the platform's catalogs limit the registration, a veto MAY carry `reason`
(§5.4), and `onFailure: block` is refused. New modules MUST use version 2.

## 4. Registration

`PUT /api/v1/platform/modules/{slug}/registrations`, `Authorization: Bearer {KF_MODULE_TOKEN}`:

```json
{ "callbackUrl": "http://api.api-test.kfmod:8080/_kf/hooks",
  "hooks": [ {"event": "model.before.update", "priority": 50, "onFailure": "block"},
             {"event": "model.after.update"},
             {"event": "model.field.changed", "fields": ["name", "extensions.api-test.author"]} ],
  "fields": [ {"entity": "order", "name": "priority_code", "type": "STRING", "table": "orders_extra", "label": "field.priority"} ],
  "surfaces": [ {"slug": "models.detail.tabs", "kind": "react", "entry": "/m/api-test/ui/surfaces.mjs", "export": "AuthorTab",
                 "key": "author", "label": "tab.label", "order": 10} ] }
```

### 4.1 Rules

- The body **replaces** everything the module declared before, all-or-nothing, in one transaction (DDL included).
- **Only keys**: `fields[].label` and `surfaces[].label` are keys of the manifest's `i18n.en`; an absent
  label stays legal.
- **Hooks**: one entry per event. A before hook carries `priority` (default 100, lower runs first) and `onFailure`
  (`allow` default; an SDK MUST send it explicitly); an after hook carries only `event`; `model.field.changed` carries
  `fields` (paths `name`, `materialList`, `extensions.{slug}.{field}`; `[]` = every field).
- **Fields**: physical storage `m_{slug with - → _}_{table}`, the column is the field name; only the platform runs the
  DDL. Fields missing from a new registration become inactive; their column is kept.
- **Surfaces**: §7.10.
- **Inclusion**: registration ⊆ manifest ∩ catalog — every hook event is a declared `capabilities.hooks[].point`
  and a point of the catalog; every field is declared with the same entity, name, type and table; every surface is
  declared with the same slug and kind, and is a public surface.

### 4.2 `onFailure`

What the platform does when a module's before hook fails (timeout, error, unreadable answer, §5.4): `allow` lets the
mutation through (the default: a module that is down must not stop every tenant from saving), `block` refuses it.
`block` REQUIRES `capabilities.hooks[].onFailure = "block"` for that point (refused at registration otherwise) and a
`schemaVersion` 2 manifest.

### 4.3 Answers

- `200` with a summary `{slug, callbackUrl, registeredAt, hooks, fieldsCreated, fieldsExisting, fieldsDeactivated, surfaces, tables, warnings?}`.
- Errors are RFC 9457 with `class` (§10): `401` (missing or invalid token) and `403` (another module's token) are
  `permanent`; `409` (the module is not `INSTALLED`) is **`permanent`**; `422` (`errors[]`: bad identifiers, an
  unknown event, a capability missing, a field re-registered with another type, a table of another module, an unknown
  or internal surface) is `permanent`; `503` while the module's first install runs is `transient`, with `Retry-After`.
- The platform logs `REGISTERED` (or `REGISTRATION_REFUSED`) in the module's journal.

### 4.4 What an SDK does

An SDK MUST, in this order:
1. check the manifest and the registration against it locally (§3.5); a violation is **permanent**: nothing is sent;
   without a manifest the check is skipped with a warning;
2. send the registration in the background (Java, TS) or from a start-up process (PHP `bin/kf-register`);
3. retry **only `transient`** errors (network errors included) on a growing schedule (1 s … 30 s, the last step
   repeats), honouring `Retry-After` up to the longest step;
4. stop on a `permanent` error with **one** error line naming the status, the class and the `detail` — never the token;
5. after a `200`, read its flags once (§8).

Fixtures: `sdk/conformance/errors/`, `registration/`, `manifest/`.

## 5. Callbacks

`POST {callbackUrl}` with:

```
Content-Type:   application/cloudevents+json
X-KF-Timestamp: <epoch seconds>
X-KF-Signature: hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + rawBody))
```

### 5.1 The envelope (CloudEvents 1:1)

The platform's in-process envelope (`eventId`, `type`, `aggregateType`, `aggregateRef`, `tenantId`, `actor`,
`occurredAt`, `payload`, `envelopeVersion`) maps 1:1 onto a CloudEvent with 16 keys:

```json
{ "specversion": "1.0",
  "id": "b9f43a5e-87fe-334e-a088-bfbdb5c39004",
  "kfeventid": "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d",
  "type": "kf.model.before.update", "source": "/api/v1/models/42", "subject": "42",
  "time": "2026-10-19T10:15:30.123Z", "datacontenttype": "application/json",
  "kfmodule": "api-test", "kfflags": { "author-tab": true },
  "kftenant": "0f1e2d3c-4b5a-4968-8776-655443322110", "kflocale": "ro", "kfactor": "oidc-sub-1",
  "kfaggregatetype": "model", "kfenvelopeversion": "1",
  "data": { "entity": "model", "id": "42", "before": { … }, "after": { … }, "changed": ["name"] } }
```

| attribute | meaning |
|---|---|
| `id` | the **delivery** id: derived from the event id, the target module and (field changes) the path; unique per delivery, **stable across retries** |
| `kfeventid` | the **event** id, `eventId` = `outbox_event.event_id`: the same on every copy of one event, the **idempotency key**. On a before hook: the id of that mutation call. `null` on a delivery queued by an older platform. |
| `type` | `kf.` + the hook point (§6.1) |
| `subject` | the aggregate's id as text (`aggregateRef`); `null` on a before-create |
| `kftenant` | the tenant the event happened in |
| `kflocale` | the language of the user whose request produced the event (`en` by default) |
| `kfactor` | the OIDC subject of that user (opaque); `null` for the system |
| `kfaggregatetype` | e.g. `model` |
| `kfenvelopeversion` | the envelope version as text, `"1"`; a minor (`"1.3"`) MAY be added without notice (§5.2) |
| `kfmodule` | the module this copy is addressed to |
| `kfflags` | the module's effective feature flags when the callback is sent (after hooks: at delivery time) (§8) |

`data`: `before` is `null` on create, `after` is `null` on delete; `changed` lists field paths (on create, those with a
value); `model.field.changed` sends one event per changed path with `data.field`, `data.oldValue`, `data.newValue`.
Snapshots carry the extensions of **live** modules only; every active field is present (`null` when not set).

**A version's values in the model's envelope** (1.1). `model_version` has no hook
point of its own: a module's write of its values on a version (§15.4) is an **update of the version's model** —
`subject`/`data.id` name the model, `type` is `kf.model.before.update` / `kf.model.after.update`. The version's values
travel under `versions.{versionId}` (the version id as a JSON key, i.e. text):

```json
"data": { "entity": "model", "id": "42",
  "before": { "extensions": { … }, "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": null } } } } },
  "after":  { "extensions": { … }, "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": "TechnicalPack…" } } } } },
  "changed": ["versions.17.extensions.kf-aas-r3.aas_shell"] }
```

- `changed` lists `versions.{versionId}.extensions.{slug}.{field}` for each written field whose value changes (none
  when the value is the same; the after-event is still recorded).
- `before.versions.{versionId}.extensions` carries the version's **stored** values, `after.versions…` the same
  overlaid with the values written; as on the model's own `extensions`, every active field of `model_version` of the
  **live** modules is present (`null` when not set) — the other modules' values on the version included, unchanged.
  (Corrected in 1.1: the earlier text said that only the fields written were sent;
  `changed` is unaffected.)
- `after.extensions` and `before.extensions` are the model's own extensions **as stored** — unchanged by such a write.
- `model.field.changed` goes out once per such path (`data.field` = the path, `oldValue`/`newValue` from the
  snapshots); a hook with a `fields` list is woken only if the list contains the path.
- A before hook may veto the write (§5.4); a `patch` is **ignored** (§6.2).

**In the SDKs** (0.7.4; Java names, TS and PHP derived):

| where | what | gives |
|---|---|---|
| `ModelSnapshot` (`before`, `after`) | `versionExtension(versionId, slug, field)` | the value under `versions.{versionId}.extensions.{slug}.{field}`, or `null` (version, module or field not carried) |
| `ModelSnapshot` | `versionExtensions` | `{versionId: {slug: {field: value}}}`, empty when the snapshot has no `versions`; an entry that is not an object is skipped |
| `HookEvents.VersionField` | `parse(path)`, `path()` | `{versionId, slug, field}` of a path `versions.{versionId}.extensions.{slug}.{field}` (exactly five segments, none empty); `null` for any other path |
| `BeforeContext`, `AfterEvent` | `changedVersionFields()` | the version paths of `changed`, in order (the model's own paths left out) |
| `BeforeContext`, `AfterEvent` | `changedVersionIds()` | the versions they touch, distinct, in order of first appearance |
| `FieldChange` | `versionField()` | the version path of `data.field`, or `null` for a model path |

The conformance fixtures `envelope/model-version-*` fix what a handler reads (§13).

### 5.2 What a receiver MUST do, in this order

1. Verify the signature on the **raw** body; refuse a timestamp outside **±300 s** (both directions): `401`.
2. Parse the body as a JSON object: otherwise `400`.
3. Read `kfenvelopeversion`: its **major** (the text before the first `.`) MUST be one the receiver
   supports; a missing, non-numeric or unsupported value is answered **`422`** `application/problem+json`:
   ```json
   { "type": "https://kf.ro/problems/unsupported-envelope-version", "title": "Unsupported envelope version",
     "status": 422, "detail": "kfenvelopeversion \"2\" is not supported; this SDK supports the majors [1]",
     "class": "permanent", "received": "2", "supported": [1] }
   ```
   and **no handler is called**. The order is therefore `401 → 400 → 422 → handler`
   (fixtures `sdk/conformance/envelope/version-*`, `unsigned-refused-before-version`, `not-an-object-400`).
4. Replace the module's flags with `kfflags` (§8), then call the handlers.

Every refusal of a receiver is RFC 9457 with `class` (§10). A handler that throws is answered `500`
`https://kf.ro/problems/handler-failed`, `class: transient`.

### 5.3 Signatures

`hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + rawBody))`, lower-case, over the UTF-8 bytes, compared in constant
time. Vectors: `sdk/conformance/signature/`.

### 5.4 Before hooks (synchronous)

The platform calls the before hooks of live modules **one by one**, by `priority`, then by slug. Each sees `after` as
already patched by the previous ones. The call timeout is **2 s**. The answer is `200` with one of:

| answer | effect |
|---|---|
| `{"decision":"allow"}` | continue |
| `{"decision":"reject","key":"<i18n key>","params":{…}}` | **veto**: the API answers `422` `https://kf.ro/problems/hook-vetoed`, `class: permanent`, with `module`, `key`, `params`; the user sees the key's text in their language with the parameters interpolated. `params`: at most 20, names `^[A-Za-z][A-Za-z0-9_]{0,40}$`, values text (≤ 500 characters), number or boolean. A malformed key or params are dropped by the platform but **the veto stands**. An SDK MUST send `params` (`{}` when none). Later hooks do not run, nothing is saved. |
| `{"decision":"patch","patch":{…}}` | allow and apply: own fields of the entity the platform's patch guard allows, and `extensions.{own slug}.{field}` only. **Not allowed on delete**: an SDK MUST NOT send it and answers `500` `class: permanent` instead. |

`reason` (a literal veto text) is accepted only from `schemaVersion` 1 modules; a version 2 module vetoes with
`{key, params}`.

**On failure** — a timeout, a connection error, a non-2xx, a body that is not JSON, an unknown decision, an invalid
patch, a patch on delete — the platform logs `HOOK_FAILED` and applies the hook's `onFailure` (§4.2): `allow` continues,
`block` refuses the mutation. Patches are logged `HOOK_PATCHED`, vetoes `HOOK_REJECTED`.

### 5.5 After hooks and field changes (asynchronous, at-least-once)

The platform records `model.after.*` and `model.field.changed` in its outbox **in the transaction of the mutation**,
then fans each event out to the subscribed live modules (field changes: only modules whose `fields` contain the path,
or are empty). Any `2xx` delivers. Each attempt has a 5 s timeout; retries back off (1 s, 5 s, 30 s, 2 min); after 5
attempts the delivery is `FAILED` (`HOOK_FAILED`). A module disabled meanwhile gets `SKIPPED`. Receivers MUST be
idempotent on **`kfeventid`** (the event), and MAY use `id` to recognise a redelivery of the same copy.

**The context of a delivery (protocol 2.0, delivered).** Every after delivery (`*.after.*`,
`*.field.changed`, `m.{owner}.after.*`) also carries `X-KF-Context` — `actor {"type": "system", "via": "hook"}`, no
user, no rights, the event's tenant and locale — and its proof `X-KF-Context-Proof`, signed by the platform for THIS
module. A background job started by the delivery MAY pass both on to one call between modules (§15.9) within ±300 s of
the context's `ts`; the platform then calls the owner as `system via module:{caller}`. The tenant always comes from the
platform's context, never from the module.

## 6. Hook points and extensions

### 6.1 Hook points

- A point is declared **in the platform's code** with `@HookPoint`. Its id is **a type of the
  registry** (`model.before.update`, …); the manifest only **subscribes** to points (`capabilities.hooks[].point`).
- The shape of the catalog is fixed by the schema `sdk/catalog/hook-points.schema.json` (a pinned copy of the
  platform's `hook-points.catalog.schema.json`, `sdk/catalog/SOURCES.json`): `catalogVersion`, `platformVersion`,
  `variant` (`complete` | `release`; the release catalog holds only `public` points), `points[]` with the attributes of
  `@HookPoint`. Public modules see only the release catalog.
- **Delivered points** (the seven of the `model` aggregate): `model.before.create`, `model.before.update`,
  `model.before.delete`, `model.after.create`, `model.after.update`, `model.after.delete`, `model.field.changed`.
- **The order points** (public; protocol 2.0, §16.2): `order.before.create`,
  `order.before.update`, `order.before.delete`, `order.after.create`, `order.after.update`, `order.after.delete` and
  `order.field.changed` (`fieldFilter: true`, the paths `status`, `entries`, `extensions.*`). The `customer.*` points
  stay `internal` (refused like an unknown point). The SDKs carry them as `HookEvents.ORDER_*` from 0.10.0.
- **`tenant.after.delete`** (1.2; **delivered**, the SDK handler):
  the release catalog gains `phase: after-commit`, `entity: tenant`, `envelope: kf.change` v1, `visibility: public`,
  `veto: false`, `patch: []`, `defaultOnFailure: allow`, `capability: hooks`, `fieldFilter: false`. The envelope:
  `kftype = tenant.after.delete`, `kftenant` = the deleted tenant,
  `data = {"entity": "tenant", "id": "<uuid>", "before": {"id": "<uuid>"}, "after": null, "changed": []}` (no name: the
  module does not need it). Delivered **at least once**, signed like every callback (§5.3), to every **installed** module
  subscribed to the point (`capabilities.hooks[].point`); liveness is **not** evaluated (the tenant no longer exists).
  The module deletes everything it keeps for the tenant, idempotently. The SDKs carry the id as
  `HookEvents.TENANT_AFTER_DELETE` from 0.8.0.
- **`tenant.after.soft-delete`** and **`tenant.after.restore`** (1.3;
  **delivered**): a tenant is first soft-deleted — access to it is off for every member and
  recoverable, nothing is deleted — then either restored or hard-deleted (the hard delete keeps
  `tenant.after.delete`). The release catalog gains, for each, `phase: after-commit`, `entity: tenant`,
  `envelope: kf.change` v1, `visibility: public`, `veto: false`, `patch: []`, `defaultOnFailure: allow`,
  `capability: hooks`, `fieldFilter: false`. The envelope is that of `tenant.after.delete` with the tenant still
  there: `kftype = tenant.after.soft-delete` (or `tenant.after.restore`), `kftenant` = the tenant, `kfactor` = `null`,
  `time` = when it happened, `data = {"entity": "tenant", "id": "<uuid>", "before": {"id": "<uuid>"}, "after":
  {"id": "<uuid>"}, "changed": []}` (no name, no subject). Delivered **at least once**, signed (§5.3), to every module
  subscribed to the point and **live for the tenant** (the tenant exists; a module not live there has nothing running
  for it). On `tenant.after.soft-delete` the module **stops** its work for the tenant (jobs, synchronisations,
  outgoing calls) and **deletes nothing**; on `tenant.after.restore` it resumes. The purge stays `tenant.after.delete`.
  The SDKs carry the ids as `HookEvents.TENANT_AFTER_SOFT_DELETE` / `TENANT_AFTER_RESTORE` and the handlers as
  `TenantHooks.onSoftDelete` / `onRestore` from 0.9.0.
- **There is no `tenant.before.delete`**: a synchronous veto would hold the deletion's transaction open
  while N modules answer (the risk), and the "before" of the purge already exists — the soft-delete,
  which every live module receives, and the platform's dump of the tenant, which asks each live module for its export
  (§15.8) before anything is deleted. A module that needs a point before the purge asks for it with its case.
- **Who sees which points (2026-11-10).** A pack sees the catalog its **class** sees: a public pack that declares an `internal` point gets the refusal of a point that does not exist (the list holds only the released ids); **a pack of the internal class may ask the `internal` points of the platform at upload**, and the delivery re-verifies the class (§14.6) — the class comes from the verified envelope or the development build, never from the manifest.
- **Delivered**: the generated catalogs (complete + release) and their drift check (
  `hooks/hook-points.{catalog,release}.json`, `HookPointCatalogDriftTest`), the endpoint that serves them (
  `GET /api/v1/platform/hook-points?variant=release|complete`) and the platform's single ingress/egress mechanism
  (one set of signing primitives in the kernel, ingress and egress kept apart). **Still reserved**:
  constants generated from the release catalog in every SDK (the SDKs carry the ids as hand-written constants,
  derived from Java).

### 6.2 Extensions on `model`, `order`, `customer`

- The extensible entities are `model`, `order` and `customer` (`@ExtensibleEntity`). A field is declared on
  one of them (`capabilities.fields[].entity`).
- `GET /api/v1/{models|orders|customers}[/{id}]` returns `extensions: {slug: {field: value}}` for the live modules
  (active fields only, `null` when not set). `POST`/`PUT` accept `extensions`: only the fields sent are written, `null`
  clears one; an unknown module or field, a module that is not live or a wrong type is `400` with
  `errors["extensions.{slug}.{field}"]`. `NUMBER` values are JSON numbers, `DATE` values `"yyyy-MM-dd"`, `LIST` values
  JSON arrays.
- `GET /api/v1/{models|orders|customers}/extension-fields` returns the active fields of the live modules, grouped by
  module, each with its label (in the user's language) and `editable` (§7.6).
- The before hooks are on `model` today; `order` and `customer` mutations run through the same mutation pipeline
 , their hook points come.
- **`model_version`** (1.1; **delivered**) becomes an extensible entity:
  `capabilities.fields[].entity: "model_version"`, the values returned and written like the others. An older platform refuses the entity at upload (not in its catalog). The module writes its own values through §15.4.
- **A write on `model_version` runs the model's hooks** (1.1). A version has no pipeline of its own; the write goes through the update pipeline of its
  model, with the version's paths in the model's envelope (§5.1):
  - **veto:** a `reject` from a `model.before.update` hook refuses the write — `422`
    `https://kf.ro/problems/hook-vetoed`, nothing written, no after-event, no audit row;
  - **patch ignored:** a `patch` answer is not a failure, but nothing of it is applied — neither to the version's values
    (they are the writing module's own) nor to the model. The platform logs `HOOK_PATCHED … patch ignored`. An invalid
    patch stays a failure (§5.4) and its `onFailure` applies;
  - **after:** `model.after.update` (and `model.field.changed` per path) after the commit, as for the model;
  - **the risk of `onFailure=block`:** a module subscribed to `model.before.update` with `onFailure: "block"` whose hook
    fails (timeout, non-2xx, …) now refuses **other modules'** writes on versions too. A module that writes a version
    value after an external action (e.g. KF-AAS-R3 writing `aas_shell` after a successful push to R3) can then end with
    the external side done and the value not written in KF; it MUST treat a `422` or a `5xx` on §15.4 as "not written"
    and retry or report it, never assume the value was stored.

### 6.3 The module events `m.{owner}.after.*` (protocol 2.0 — delivered)

- **Declaring.** The owner lists them in `capabilities.events[] {name, visibility}` (§3.2, schema 3). Each is the
  point `m.{owner}.after.{name}`: `{owner}` is the owner's slug and the **second** segment (a slug has no dot); after
  only — no veto, no `patch`, no `block`. The prefix `m.` is reserved: no platform hook point and no platform event type
  starts with it. The point is a type of the registry, registered while the owner's active version declares it
  (installed, upgraded, rolled back; gone at uninstall or with a version that no longer declares it).
- **Emitting.** Only the owner, with its own token and a proved context (`X-KF-Context` + `X-KF-Context-Proof`, §9.2):
  `POST /api/v1/platform/modules/{owner}/events` `{"name": "<name>", "data": {…}}` → `202 {"eventId", "type"}`. The
  platform records it in its outbox in the context's tenant. Refusals (RFC 9457, `class`): `{owner}` ≠ the token's slug
  → `403`; context missing or unproved → `401 invalid-context`; the owner not live for the tenant → `403
  module-not-live`; the body not an object, `name` not text, `data` not an object or over 64 KiB → `400 validation`;
  `name` not declared by the active version → `422 event-not-declared` (all permanent).
- **Subscribing.** A module subscribes with `capabilities.hooks[{point: "m.{owner}.after.{name}"}]` (schema 3, no
  `onFailure`) and the owner in its `requires` (mandatory or `optional`), then registers it in `hooks[].event`. The SDKs check the four rules of a `m.{owner}.after.{name}` hook locally, with the platform's texts (2026-11-10): `capabilities.hooks[i].point "<point>": an event of a module requires "schemaVersion": 3`; `… is not an event of a module (m.{owner}.after.{name})`; `… is an event of <owner>, which this manifest does not require (requires, mandatory or optional)`; `capabilities.hooks[i].onFailure is not allowed on an event of a module: it runs after the fact` (and not the generic "only on before points"); a point declared twice is `… is declared twice`. An event
  that is not offered to it now — the owner absent, the event not declared, or `internal` for a public subscriber — is
  **stored inactive** with the warning "hooks[i]: m.… is not offered to this module now; the subscription is stored
  inactive and receives nothing until it is"; it starts receiving when the event is offered. An event that WAS
  offered and left marks the subscription orphaned; its return restores it. An upgrade of the owner that drops a
  used event passes (no `409`).
- **Delivery.** At-least-once, as §5.5, to every live subscriber that requires the owner and may see the event: the
  CloudEvents envelope with `type` `kf.m.{owner}.after.{name}`, `kfeventid` = `eventId`, `source`
  `/module-event/{owner}`, `subject` = the owner, `kfaggregatetype: module-event`, the emitter's `kftenant`,
  `kflocale` and `kfactor`, and `data` = the owner's object unchanged; plus the context and proof of §5.5.

## 7. UI surfaces

Two planes: the **host** mounts surfaces in its pages; **modules** contribute UI to them. Two technologies only:
**React components** and **Web Components**. The surfaces a module may contribute to are **only those of the
platform's release list**; the release list is generated by the host
 and pinned in `sdk/catalog/surfaces.public.json`.

### 7.1 The public surfaces (the release list)

<!-- BEGIN GENERATED: catalog/surfaces.public.json -->
<!-- written by sdk/tools/gen-protocol.mjs from sdk/catalog/surfaces.public.json (sha256 7e3503d789dd594dd7d29c236e85fb8473391e53d2fae699fe690c4bfb042e30); do not edit inside the block -->

The release list has **14 surfaces, all public** (`catalogVersion` 2):

| slug | type | parts | host entries (`hostOrders`) |
|---|---|---|---|
| `app.overlay` | `extension` | — | — |
| `models.detail.overview.cards` | `extension` | — | — |
| `models.detail.tabs` | `tab` | trigger, panel | `overview` 0, `composition` 1000, `sizing` 2000, `consumption` 3000, `pattern` 3500, `3d-studio` 4000, `qa` 5000, `docs-notes` 6000, `cost-impact` 7000, `bill-of-labour` 8000, `passport` 9000 |
| `models.form.create.fields.after` | `extension` | — | — |
| `models.form.edit.fields.after` | `extension` | — | — |
| `models.list.card.after` | `extension` | — | — |
| `models.list.columns` | `column` | header, cell | — |
| `module.pages` | `page` | — | — |
| `orderflow.rail.sections` | `extension` | — | — |
| `orderflow.review.after` | `extension` | — | — |
| `orderflow.step` | `extension` | — | — |
| `overview.cards` | `extension` | — | `orders` 0, `collections` 1000, `customers` 2000, `models` 3000, `recent-activity` 9000 |
| `setup.sections` | `page` | — | — |
| `tenant-management.cards` | `extension` | — | — |

##### `app.overlay` (type `extension`)

- `ctx`: `{ route: string; inPortal: boolean; appVersion: string | null }`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `models.detail.overview.cards` (type `extension`)

- `ctx`: `{ model: { id: number | null; name: string | null; styleNumber: string | null; status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null; createdAt: string | null; updatedAt: string | null; extensions: { [key: string]: { [key: string]: unknown } } }; version: { id: number; name: string | null } | null; href: string }`
- `actions.navigate`: `(href: string) => void`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `models.detail.tabs` (type `tab`)

- part `trigger`: label only (the host renders the contribution's label; no module code is loaded)
- part `panel`:
  - `ctx`: `{ model: { id: number | null; name: string | null; styleNumber: string | null; status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null; createdAt: string | null; updatedAt: string | null; extensions: { [key: string]: { [key: string]: unknown } } }; isActive: boolean; version: { id: number; name: string | null } | null }`
  - `actions.navigate`: `(href: string) => void`
  - `actions.refresh`: `() => Promise<void>`
  - `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `models.form.create.fields.after` (type `extension`)

- `ctx`: `{ mode: 'create'; values: { name: string; styleNumber: string; status: string }; extensions: { [key: string]: { [key: string]: unknown } }; errors: { [key: string]: string } }`
- `hooks.useFormValue`: `(path: string) => unknown`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`
- `callbacks.setExtensionValue`: `(field: string, value: unknown) => void`
- `callbacks.setFieldError`: `(field: string, message: string | null) => void`

##### `models.form.edit.fields.after` (type `extension`)

- `ctx`: `{ mode: 'edit'; model: { id: number | null; name: string | null; styleNumber: string | null; status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null; createdAt: string | null; updatedAt: string | null; extensions: { [key: string]: { [key: string]: unknown } } }; values: { collectionId?: number | null; seasonId?: number | null; technicianId?: number | null; name?: string; styleNumber?: string; modelOwner?: string }; extensions: { [key: string]: { [key: string]: unknown } }; errors: { [key: string]: string } }`
- `hooks.useFormValue`: `(path: string) => unknown`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`
- `callbacks.setExtensionValue`: `(field: string, value: unknown) => void`
- `callbacks.setFieldError`: `(field: string, message: string | null) => void`

##### `models.list.card.after` (type `extension`)

- `ctx`: `{ row: { id: number | null; name: string | null; styleNumber: string | null; status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null; createdAt: string | null; updatedAt: string | null; extensions: { [key: string]: { [key: string]: unknown } } } }`

##### `models.list.columns` (type `column`)

- part `header`: label only (the host renders the contribution's label; no module code is loaded)
- part `cell`:
  - `ctx`: `{ row: { id: number | null; name: string | null; styleNumber: string | null; status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null; createdAt: string | null; updatedAt: string | null; extensions: { [key: string]: { [key: string]: unknown } } } }`

##### `module.pages` (type `page`)

- `ctx`: `{ page: string; path: string[]; query: { [key: string]: string } }`
- `actions.navigate`: `(href: string) => void`
- `actions.setTitle`: `(title: string) => void`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `orderflow.rail.sections` (type `extension`)

- `ctx`: `{ order: { id: number; name: string; status: string } }`
- `actions.navigate`: `(href: string) => void`
- `actions.refresh`: `() => Promise<void>`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `orderflow.review.after` (type `extension`)

- `ctx`: `{ order: { id: number; name: string; status: string } }`
- `actions.navigate`: `(href: string) => void`
- `actions.refresh`: `() => Promise<void>`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `orderflow.step` (type `extension`)

- `ctx`: `{ order: { id: number; name: string; status: string }; step: { key: string; name: string; stepInstanceId: number }; instance: { id: number; flowKey: string; flowVersion: number }; line: { id: number; modelVersionId: number } | null }`
- `actions.navigate`: `(href: string) => void`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `overview.cards` (type `extension`)

- `ctx`: `{}`
- `actions.navigate`: `(href: string) => void`

##### `setup.sections` (type `page`)

- `ctx`: `{ section: string }`
- `actions.navigate`: `(href: string) => void`
- `actions.setTitle`: `(title: string) => void`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

##### `tenant-management.cards` (type `extension`)

- `ctx`: `{ tenant: { id: string; name: string } }`
- `actions.refresh`: `() => Promise<void>`
- `actions.toast`: `(message: string, options?: { type?: 'error' | 'success' | 'info'; description?: string }) => void`

<!-- END GENERATED -->

- Protocol 1.0 has exactly the surfaces above, **all public**. There are no internal surfaces in
  the release list, and nothing else is a promise: a slot the host may add later is not part of 1.0 until a release
  list names it.
- **(1.1)** Protocol 1.1 adds three surfaces: **`module.pages`** and **`setup.sections`** — type `page`, a
  whole page of the module, one contribution per page (§15.6) — and **`tenant-management.cards`** (type `extension`).
  The last two are where a module keeps its **data** under the rule "module settings carry no data" (§15.2): what it
  records at platform level (an external platform, a server, a secret by reference) in its section of `/setup`, only
  for the platform administrator; what it chooses per tenant on its card of the tenant management page, with
  `ctx.tenant` = the tenant chosen there. The module saves both through its own `admin: platform` / `admin: tenant`
  routes (§9.5). The card's `refresh()` (SDK 0.7.1) makes the page re-read its module contributions —
  after the module saved a per-tenant choice that changes what it contributes; it resolves once the re-read is
  requested.
- **(1.2; **delivered**, the list above regenerated)** Protocol 1.2
  adds **`app.overlay`**, type `extension`, `visibility: public`, without `hostOrders`: mounted once in every shell
  with a session and a tenant — staff (`(auth)`), the customer portal (`customer`) and `/setup`; not on `/no-tenant`.
  `ctx`: `{ route: string; inPortal: boolean; appVersion: string | null }` (the current path, whether the persona is
  the customer portal's, the application version); `actions.toast`. The contribution renders in a fixed layer of the
  host (above the content, below the host's dialogs) and chooses its own position; it appears while the module is live
  for the tenant (§7.8). The SDKs carry the slug since they pinned the regenerated list (`Surfaces.APP_OVERLAY`,
  `sdk/catalog/surfaces.public.json`); an SDK before 0.8.0 leaves it to the platform (§3.2).
- `models.form.create.fields.after` is a slot of its own, **without `model`** in its ctx. It appears wherever
  the host mounts the model creation form — the "new model" dialog of `/models` and the order flow.
- `models.list.card.after` (type `extension`, ctx `{ row }`) exists next to `models.list.columns`: the card
  of a model in the list, and its column.
- **(1.3.1, delivered in 1.3.1, FE a385a1e6)** Protocol 1.3.1 adds **`models.detail.overview.cards`**, type
  `extension`, `visibility: public`, without `hostOrders`: a section of its own in the **Overview** tab of the model
  page, between the host's quick stats and its recent activity, a grid of the modules' cards (ordered by `order`,
  §7.7; no section title). `ctx`: `{ model: ModelSurfaceRow; version: { id: number; name: string | null } | null;
  href: string }` — `model` the projection of §7.2, `version` the version the page has selected (the rule of
  `ctx.version`, §7.3), `href` the path of the model page for that version (§7.3); the actions `navigate(href)` and
  `toast(message, options?)`; the public members `model`, `version`, `href`, `navigate`, `toast`. It is served on the
  staff pages and on the customer portal, as `models.detail.tabs` (the same page; the contributions follow the
  audience, §7.9). The generated list above carries it from SDK 0.9.1 (catalog sync from the host).
- Actions, buttons and badges are **not surfaces** in 1.0. A new surface type takes a new protocol version.

### 7.2 What a contribution sees

- The row or model of every model surface is the **typed projection**
  `{ id, name, styleNumber, status, createdAt, updatedAt, extensions }` — `row` in `models.list.columns` (cell) and
  `models.list.card.after`, `model` in `models.detail.tabs` (panel), `models.form.edit.fields.after` and, from 1.3.1
  (delivered in 1.3.1, FE a385a1e6), `models.detail.overview.cards` — the same for the card, the column, the tab, the forms
  and the Overview card. The seven keys are always present (`null` when unknown). Nothing outside
  the projection is promised: a contribution MUST NOT rely on other keys of the host's objects. The TS type is
  `ModelSurfaceRow` (`kf-module-sdk/surfaces`, `@kf/surfaces` in `sdk/ui/kf-host.d.ts`).
- `extensions` — in `ctx`, in `row`, in `model`, and what `useFormValue` answers — MUST hold **only the
  calling module's own values**, keyed by its slug (`row.extensions['my-module'].myField`). The host cuts them per
  contribution; a module never sees another module's values.
- A contribution sees only the **public members** of its surface (the generated list above): the host cuts every other
  member per contribution, at run time.

### 7.3 The tab contract

`models.detail.tabs`, part `panel`: `ctx.model` (the projection), `ctx.isActive`, and the actions `navigate(href)`,
`refresh(): Promise<void>` (resolves once the host has refetched the model) and `toast(message, options?)`. The part
`trigger` is label-only: the host renders the contribution's label, no module code is loaded. A panel is mounted on
its first activation and kept afterwards. The tab's value in the URL is `{module}.{key}` (`?tab=api-test-ts.co2`).

**`ctx.version`** (1.3; **delivered**): the version of the
model the page has selected, `{ id: number; name: string | null } | null` — a projection (the id and the name only:
no prices, no CLO data, never the host's version object, §7.2), `null` while none is selected. A **public member**
of the panel. When the user selects another version, the host re-renders the panel it keeps mounted with the new
`ctx`: there is no event to listen to and the module never reads the address. A module that runs on hosts before
 tests the **presence** of the key (`'version' in ctx`) and keeps its own way otherwise; `KF_HOST_API_VERSION`
does not change for it. The module's own choice of a version inside its panel stays the module's: it
writes nothing back to the host. The generated release list (§7.1), the pinned catalog
(`sdk/catalog/surfaces.public.json`), `sdk/ui/kf-host.d.ts` and the generated vocabularies are copies of the host's
files (`sdk/catalog/SOURCES.json`): they carry `version` since the catalog sync after the host declared it.

**`ctx.href`** (1.3.1, delivered in 1.3.1, FE a385a1e6; on `models.detail.overview.cards`): the path of the model page
for the selected version, built by the host on the current face — `/models/{id}?version={versionId}` on the staff
pages, `/customer/models/{id}?version={versionId}` on the customer portal, without `version` when `ctx.version` is
`null`. It is the **only address of the host a module may rely on**: a module that links to the model (a QR code,
with `location.origin`) uses `ctx.href` as it is and never builds or guesses a host route; any other route of the
host may change without notice.

### 7.4 The form contracts

`models.form.edit.fields.after` and `models.form.create.fields.after`:
- `ctx.mode` (`edit` | `create`); `ctx.model` — the projection — **only on edit**;
- `ctx.values` — the **real fields of that form** (the edit dialog, the edit page and the creation form each have their
  own; the list is in the generated ctx type above); `ctx.extensions` (own values, §7.2); `ctx.errors`;
- `hooks.useFormValue(path)` — answers **only** `values.<field>` of the same form and `extensions.<own slug>.<field>`;
- `actions.toast`; `callbacks.setExtensionValue(field, value)` (writes the module's own extension, saved with the
  form) and `callbacks.setFieldError(field, message | null)` (blocks the save while set).

### 7.5 Multiple contributions

A module MAY contribute several times to **any** surface, forms included. Each contribution has its own `key`
(§7.10), unique per module and surface.

### 7.6 Generic fields and `editable`

- The host is **not** required to render a module's fields: the UI of a module's data is the module's. A
  field MAY have no UI at all (e.g. a metric a module computes in an after hook and shows in a report).
- A field declares in the manifest `"editable": true | false` (**default `false`**, §3.2). On a form,
  the host renders generically **only the `editable` fields** of the live modules that have **no live contribution
  mounted on that form**; a field that is not editable is never rendered generically. `editable` decides rendering,
  not writing.
- The rule is **per module**: one live contribution of a module on a form takes over all of that module's
  fields on that form.
- A contribution that fails to load (or fails while rendering) falls back to the generic fields of its
  module on that form, **keeping the edits already made**.
- This rule is fixed **only by this document**: there is no conformance fixture for it and no dedicated test
  per form. The SDKs only read `editable` and refuse a value that is not a boolean.

### 7.7 Order

- The contributions of a surface are ordered by: the **tenant's override** if one
  exists for that contribution, otherwise the **declared `order`** (0–10000, default 100); then the module slug; then
  the contribution key. Lower comes first.
- On `models.detail.tabs` the module tabs sit **among the host's tabs**, by `order`. The host's own tabs
  have **fixed orders**, published as `hostOrders` in the release list (the table above; the SDK constants
  `Surfaces.HOST_ORDERS`); at a tie **the host comes first**. A module picks its `order` against them (e.g. 1500 puts a
  tab between "composition" and "sizing").
- **The tenant's override** (right `api.v1.platform-modules.contribution-order`, held by `TENANT_ADMIN`):
  - `GET /api/v1/platform/surfaces/{surface}/contribution-order` → `200`
    `{ surface, hostOrders: [{value, order}], entries: [{module, moduleName, key, label, declaredOrder, overrideOrder, effectiveOrder}] }`
    — the contributions of the modules live at the caller's tenant on that surface (whatever their flag);
  - `PUT /api/v1/platform/surfaces/{surface}/contribution-order` with `{ "entries": [{module, key, order}] }` → `200`
    (the GET form): the complete state of the surface; a contribution left out loses its override;
  - `DELETE /api/v1/platform/surfaces/{surface}/contribution-order` → `204`: every override of the tenant on that
    surface is gone;
  - errors: `422 permanent` with `errors[]` (an unknown surface, a contribution that does not exist or is not live —
    the same answer for both —, an order outside 0–10000, a duplicate pair); `503 transient` with `Retry-After` when the
    release list is unavailable; `403` without the right. The tenant is never taken from the path or the body.
  - The served order is `override ?? declared` at that tenant only; other tenants keep the declared order. An override
    survives deactivation and uninstall (not served while the module is not live) and goes with the module's deletion.
- There is **no cap** on contributions: tabs scroll, columns scroll horizontally.

### 7.8 Deactivation

- After a module is deactivated **nothing is left in place of its contributions**; its data stays stored and
  comes back on reactivation.
- Open sessions pick up an activation change within **30 s** or when the window regains focus; there is no
  push.
- A dialog that is open while a module is deactivated **sets aside the edits of that module** on save,
  **tells the user** (a toast naming the module) and **saves the host's fields**.
- A deep link to a tab that no longer exists (module deactivated, contribution gone) opens the requested page
  **on its default tab**, with a notice ("the tab no longer exists or its module was deactivated").

### 7.9 The customer portal and the host page API

- The same public surfaces run on the **customer portal** (`/customer/**`), with the **projection visible to
  the customer**: the seven keys, with what the portal's pages do not show set to `null` (`styleNumber` today). The
  contributions come from `GET /api/customer/v1/ui/contributions`, the assets from `/api/customer/v1/m/{slug}/**`.
- **On the portal the module's API is not available**: the portal serves only the module's UI assets,
  never its routes. `moduleUrl()` of `@kf/host-sdk` does not know the audience and still returns the staff route
  (`/api/proxy/m/…`): a call from the portal is answered **`403`**. A contribution that runs on the
  portal MUST work without its API.
- **From 1.2 a module route may be served on the portal** (**delivered**, `moduleUrl()`): a route declared `audience: "member"` and `portal: true` (§3.2) is also served at
  `/api/customer/v1/m/{slug}{rest of the path}`, for the user of the portal (persona CUSTOMER), behind the portal's gate
  (`CustomerPortalGateFilter`): the context's tenant is **the customer's tenant**, `user` the OIDC subject,
  `actor.type = user`, `rights = []`. The upstream is the one of the staff route (the path without `/m/{slug}`). A route
  without `portal: true` asked on the portal's prefix is `404`; a staff user on the portal's prefix gets the gate's
  refusal (`403`). The UI assets stay on `GET /api/customer/v1/m/{slug}/…`: a `GET` on a path that is a declared
  `portal` route goes to the module, any other path is an asset. **From `KF_HOST_API_VERSION` 4** `moduleUrl()` returns
  the portal's route when the active persona is the portal's (the rule `isPortalPersona`, written once in the host); a
  module that calls its API from the portal checks `apiVersion >= 4`; on `3` the staff route and the `403` above stay.
- **The host page API** — `sdk/ui/kf-host.d.ts` (a pinned copy of the host's file,
  `sdk/catalog/SOURCES.json`; its source of truth is the host's `kfHost.ts`) — gives module bundles, through the page
  import map:
  - `@kf/ui`: `Badge`, `badgeVariants`, `Button`, `buttonVariants`, `Card`, `CardContent`, `CardDescription`,
    `CardFooter`, `CardHeader`, `CardTitle`, `Input`, `Label`, `Switch`, `Textarea`, `cn`; **from `KF_HOST_API_VERSION`
    3** (the primitives the AAS, utils, factory, planner and setup pages import, measured):
    `Accordion`, `AccordionContent`, `AccordionItem`, `AccordionTrigger`, `Checkbox`, `Collapsible`,
    `CollapsibleContent`, `CollapsibleTrigger`, `Command`, `CommandEmpty`, `CommandGroup`, `CommandInput`, `CommandItem`,
    `CommandList`, `ConfirmDialog`, `useConfirm`, `Dialog`, `DialogContent`, `DialogDescription`, `DialogFooter`,
    `DialogHeader`, `DialogTitle`, `Popover`, `PopoverContent`, `PopoverTrigger`, `Select`, `SelectContent`,
    `SelectItem`, `SelectTrigger`, `SelectValue`, `Sheet`, `SheetContent`, `SheetDescription`, `SheetFooter`,
    `SheetHeader`, `SheetTitle`, `Skeleton`, `Tabs`, `TabsContent`, `TabsList`, `TabsTrigger`, `Tooltip`,
    `TooltipContent`, `TooltipTrigger`; and the host's form binding (SDK 0.7.1): `Form`, `FormControl`,
    `FormDescription`, `FormField`, `FormItem`, `FormLabel`, `FormMessage`, `useFormField` — bound to the host's
    react-hook-form, so a module that uses them takes `useForm()` from `react-hook-form`, which the import map resolves
    to the host's own copy (`/_kf/react-hook-form.mjs`);
  - `@kf/host-sdk` (`KF_HOST_API_VERSION` 3; `apiVersion >= 2` for the texts, `>= 3` for the v3 `@kf/ui`): `apiVersion`, `reactVersion`, `http(path, init)` (same-origin
    JSON with the caller's own token; a non-2xx is thrown as `ApiError`), `ApiError`, `moduleUrl(module, path)`,
    `locale()`, `onLocaleChange(listener)` (returns the unsubscribe function), `t(module, key, {params, fallback})`
    (the module's own texts, user language → `en` → fallback → slug; module texts never go through the host's `t()`
    and are never reported as missing);
  - **host API 5 (protocol 2.0, delivered, §7.12):** `Slot({slug, ctx?, actions?, children?})` mounts a slot
    `m.{slug}.{key}` of the module whose page renders — only that module's own keys, declared in its approved
    manifest (the host knows the page's module from the route `/m/{slug}/{page}`; any other key renders nothing).
    Kind A contributions render in the page, with the host's React, each in its own error boundary, `ctx` cut to the
    slot's `publicMembers` and `actions` to its `actions`; kinds B and R reach the owner through `children({layers,
    resources})`. `useSlotContributions(slug)` → `{mountable, declaration, components, layers, resources}`: for kind B
    **the host fetches** each layer's `source` (the contributor's declared GET route, the user's token) and hands the
    data over — the owner never calls the contributor. The bus of kind D in the page is on **`props.module.onSlotEvent(slug, name, listener)` / `props.module.emitSlotEvent(slug,
    name, payload)`**, bound to the calling module (§7.12; the payload a structured clone, functions are refused, a throwing
    listener isolated); the **global** `onSlotEvent` / `emitSlotEvent` of `@kf/host-sdk` **always refuse** (2026-11-10). `useLiveField(field, initial, options?)` → `{value, setValue, focus, blur,
    peer, isLockedByPeer}` is **reserved** in API 5 (an inert local field until the host binds it to the page's
    collaboration room). A module that uses any of them checks `apiVersion >= 5`;
  - `@kf/surfaces` (types only): `ModelSurfaceRow`.
  Build module bundles with `react`, `react/jsx-runtime`, `react-dom`, `react-dom/client`, `@kf/ui` and `@kf/host-sdk`
  **external** — and `react-hook-form` when the module uses `Form*` (the page import map has these seven specifiers).
- **`props.module` (protocol 2.0, delivered).** Every contribution and every module page gets
  from the host `props.module = {slug, name, moduleUrl(path), http(path, init), onSlotEvent, emitSlotEvent}` (the last two from host API 5, §7.12), frozen and built for that module:
  `moduleUrl(path)` is `moduleUrl(slug, path)`, and `http` sends **`X-KF-Calling-Module: {slug}`** on every call. On
  `/m/{target}/**` (declared routes and assets, staff and portal) the platform lets a call that names another module
  as its caller through only when that module is **live on the tenant** and its active version requires `{target}` (mandatory or `optional`);
  otherwise `403 peer-not-required`, `class: permanent`, nothing forwarded and no asset served, **with one text and no slug**: "The calling module named in X-KF-Calling-Module does not require this module (requires, mandatory or optional)." The check runs **after** the target is found live (an absent target is not told apart by it) (2026-11-10); a header that is not a
  slug is `400 validation`. **The limit:** a module's code runs in the page and can still call the global
  `http` or `fetch` without the header; such a call is protected only by the user's own right of the target's route, as
  before. The header is checked when present; it is not a sandbox. The global `moduleUrl(module, path)` stays for the
  modules on host API 1–4.
- **One network per module (delivered).** A module's exposed services are on its own network
  `kfmod-{slug}_platform`, with their alias `{service}.{slug}.kfmod` on it only; the platform's backend is attached to
  each module's network by the module runner. A module reaches the platform (`KF_PLATFORM_URL`), never another module:
  the public paths of a module (`/health`, `/ui/**`, the hook path) are no longer reachable from another module, and a
  call between modules goes through the platform (§15.9).

### 7.10 What a module registers (`surfaces[]`)

| key | rule |
|---|---|
| `slug` | a public surface (§7.1), or a slot of another module `m.{owner}.{key}` (§7.12, schema 3, the owner in `requires`) |
| `kind` | on a slot also `layer` (kind B: `layer` + `source`, no `entry`) or `data` (kind R: `data`, a JSON object ≤ 64 KiB, no `entry`); otherwise `react` (with `export`, an identifier) or `wc` (with `tag` `^[a-z][a-z0-9]*-[a-z0-9-]+$`, not used by another module; SHOULD start with `kf-`) |
| `entry`, `css` | under `/m/{slug}/`, without `..` or `//`; served by the module itself |
| `order` | 0–10000, default 100 (§7.7) |
| `label` | a key of `i18n.en` (§3.1): the column header, the tab trigger, the frame caption (defaults to the module name) |
| `flag` | one of the module's flags (§8): the contribution is served only while it is on; an undeclared key is a warning and counts as off |
| `key` | `^[a-z][a-z0-9-]{0,40}$`, unique per module and slug; default: the tag without `kf-`, or the export in kebab case. **On a surface of type `page` the `key` is the page** (§15.6): register it explicitly, equal to the manifest's `surfaces[].page` |
| `peer` | e.g. `{"react": "^19.2"}`, checked by the host before rendering |

**a public module is bound only to the release list:**
- a slug outside the release list — internal or unknown — is refused at registration with **`422`** and the **same
  answer** for both (`surfaces[i].slug: unknown surface`), so the answer never reveals an internal surface;
- when the release list is unavailable, a registration that declares surfaces is answered **`503` `transient`**
  (fail-closed; the SDK retries);
- at serving, the platform returns only contributions whose slug is in the release list (a row stored earlier for a
  surface that left the list is not served) and the host hands each contribution only the public members (§7.2).

**Serving and rendering:** `.mjs` MUST be served as `text/javascript` (browsers refuse module scripts served as
`application/octet-stream`); the SDK adapters serve the module's UI directory at `/ui/**` with explicit MIME types and
`Cache-Control: no-cache` (Java: `classpath:static/ui/`; TS: `serveUi`/`nodeHandler`; PHP: `Sapi::serveUi`). A React
contribution is imported natively and rendered in the host's React tree with `{ ctx, hooks, actions, callbacks,
module }`; a Web Component gets the properties `context`, `actions`, `callbacks`, `module` (no hooks) and MAY dispatch
`kf:callback` events (`{ name, args }`). Each contribution renders in an isolated wrapper (error boundary, skeleton,
10 s load timeout, peer check). Styling: import `sdk/ui/kf-theme.css` after Tailwind 4 (it maps the shadcn tokens to
the host's CSS variables).

### 7.11 The module's menu entries (`capabilities.nav`, protocol 1.1 — delivered)

A module brings its menu entries in its manifest — no SQL, no code in the platform. The platform
composes them, at run time, into the menu of their `surface`, in the group named by `group`, with the entries of the
platform itself (the base catalogue and the code contributions), on the same rules: the entries of a group sorted on
`order`, then on the key.

| surface | the menu | an entry is served while |
|---|---|---|
| `staff` | the sidebar (`GET /api/v1/dashboard-menus/dictionaries`) | the module is INSTALLED with an active version, **live for the caller's tenant** (activated by it, running, not tampered) and its `flag`, when declared, is on for that tenant |
| `customer` | the customer portal (`GET /api/customer/v1/navigation`) | the same as `staff`, for the customer's tenant |
| `platform` | `/setup` (`GET /api/v1/admin/navigation`, platform admin) | the module is INSTALLED with an active version (the platform menu is not per tenant; `flag` is not evaluated) |

- **The key** of the entry in the menu is `m.{slug}.{key}`, its `source` is `MODULE` and its `module` the slug: a module
  cannot take the key of a platform entry or of another module.
- **The label** is a key of the module's texts (§3.1), served as `labelKey`; the host resolves it in the user's
  language, then `en`, then the slug — never the raw key.
- **The right** is presentation, as for every menu entry: the host hides an entry the user cannot use; the real gate
  stays the rights filter on the target route. On `customer` there is none: the portal's menu is filtered on the
  customer's identity.
- **The url** is a path of the host application: a page of the host, a page of the module itself
  (`/m/{slug}/{page}`, §15.6) or, on the `platform` surface, the module's setup section (`/setup/m/{slug}/{section}`).
- A deactivated module, a module uninstalled, or a flag turned off removes the entry at the next read of the menu;
  nothing is stored per entry.

### 7.12 The module slots (`capabilities.slots`, protocol 2.0 — contract fixed)


**State: delivered; the contract is fixed.** The shape below is written and typed at the
start (the Java SDK's `Manifest.SlotCapability`, the host's `SlotContract.ts`): the platform uses it inside the
host, and wave V3 implemented it for the external modules **without changing it**. A change after
publication is coordinated explicitly.

A **slot** is a surface defined by a module (the *owner*) on which other modules (the *contributors*) contribute, the
way a module contributes on a surface of the host (§7.1). The host's release list (§7.1) stays the host's; a slot is
the owner's.

**The declaration, by the owner — only under `schemaVersion` 3.** `capabilities.slots` is an array of closed objects
(an unknown key is refused); under `schemaVersion` 1–2 the key is refused (`requires "schemaVersion": 3`).

| key | kinds | rule |
|---|---|---|
| `key` | all | required; `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*\.v[1-9][0-9]*$`, at most 80 characters, unique in the manifest. **The major is the last segment** (`viewport.layers.v1`); a breaking change is a new key (`…v2`), never a new shape under the same key. |
| `kind` | all | required; `"A"` UI panels, `"B"` data layers rendered by the owner, `"D"` events and commands in the page, `"R"` catalog resources (data only). |
| `type` | all | required, one of the kind's: A `extension` \| `tab` \| `action`; B `layer`; D `events` \| `commands`; R `resource`. |
| `visibility` | all | required, no default: `"internal"` — only the modules of the internal class may contribute (built with the internal SDK; the class is never declared by the manifest); `"public"` — any module that requires the owner. |
| `label` | all | optional; a key of `i18n.en` (§3.1), the slot's name at approval. |
| `publicMembers` | A | optional; the members of the `ctx` the owner shows the contributor, listed to the admin at approval; names `^[a-z][A-Za-z0-9]{0,40}$`, distinct. |
| `actions` | A | optional; the names of the actions the owner gives the contributor (`^[a-z][a-z0-9-]{1,40}$`, distinct). |
| `layerKinds` | B | **required**, non-empty; each `^[a-z][a-z0-9-]{1,30}$`, distinct — the kinds of layer the owner knows how to render (`pin`, `highlight`, `heatmap`, `dimension`). |
| `events` | D, `type: events` | **required**, non-empty; the event names `^[a-z][a-z0-9-]{1,40}$`, distinct. Refused on `type: commands`. |
| `commands` | D, `type: commands` | **required**, non-empty; the command names `^[a-z][a-z0-9-]{1,40}$`, distinct. Refused on `type: events`. |
| `dataSchema` | R | **required**; the id of the schema the owner validates every contribution against: `m.{owner}.{name}.v{N}` where `{owner}` is the declaring module's slug. The key is `dataSchema`, not `schema`: `schema` is forbidden at any level of a manifest (§3.3), as for `tenantData.format`. |

A key of one kind on a slot of another kind is refused (`… only on kind B`).

**The public key** of a slot is `m.{owner}.{key}` — for example `m.kf-3d.viewport.layers.v1`. Segments may hold a
hyphen (the owner's slug does). The owner of a public key is the **second segment** (a module slug holds no dot); the rest
is the slot's `key`.

**The contribution.** A contributor declares an entry of `capabilities.surfaces[]` (§3.2, §7.10) with
`slug = "m.{owner}.{key}"`:

- kind **A**: `kind` `react` | `wc`, as on a surface of the host; it receives the `ctx` members the owner lists in
  `publicMembers` and the `actions` it names (§7.2);
- kind **B**: `kind: "layer"`, `layer` (one of the slot's `layerKinds`) and `source` — the path of a `GET` route the
  contributor declares. **The host fetches the data and hands it to the owner**; the owner never calls the contributor;
- kind **R**: `kind: "data"`, `data` — a JSON object of at most 64 KiB, **no code**, validated by the owner against the
  slot's `dataSchema`;
- kind **D**: the bus in the page, on `props.module.onSlotEvent` / `props.module.emitSlotEvent` (§7.9, `KF_HOST_API_VERSION` 5) (2026-11-10): the **owner** emits the slot's `events` and listens to its `commands`; a module of its `users` listens to `events` and emits `commands` (the direction follows the role, and the role is **re-verified at every delivery**). A name must match `^[a-z][a-z0-9-]{1,40}$` and be declared on the slot; anything else — an undeclared slot or name, the wrong direction, an `internal` slot the caller may not use, a slot of another kind — is refused with **one** text, `KF: the slot bus refused this call`, the same for each (no oracle). The global `onSlotEvent` / `emitSlotEvent` always refuse. **The limit:** a module's code runs in the page, in the same realm as the host and the others; the bus is bound to the caller, not sandboxed.

**Who may contribute.**

- Only a module that has the owner in its `requires` (mandatory or `optional`, §3.2).
- On an `internal` slot only the modules of the internal class: the class follows the SDK variant the module is built
  with (the external SDK = public, the internal SDK = internal), also for a `DEV` build; it is never declared by the
  manifest. The class comes only from the signed envelope (delivered): internal when the active
  version's envelope, re-verified now, is signed with a `KF_RELEASE` key of the publisher `katty-fashion` AND its
  statement says `sdk.variant: "internal"` on a tag of the internal line `sdk-internal-v*` (§14.5); a `DEV` build when
  its `kf-build.json` says `"internal"` and its owner is a development organization now. Everything else is public,
  without a refusal (§14.6).
- Any other module asking for an `internal` slot gets the **treatment identical** to a slot its owner's installed version
  does not declare: stored inactive, the same warning, the same text, the same field path, never served (
  no oracle, no way to learn that an internal slot exists).
- **What the page is told (`GET /api/v1/ui/module-texts`) (2026-11-10).** Every served slot declaration carries **`users: string[]`** (required): the live modules, other than the owner, that may use the slot now (the "used" rule); the owner is never in its own `users`; a `public` slot may have `users: []`; an `internal` slot with no users **is not served**. **Accepted remainder:** on a tenant with a live module of the internal class that requires the owner, the internal declaration is visible in the page — the page runs in one realm and the bus is not a sandbox.
- **Mounting** (`Slot`, `useSlotContributions`, `KF_HOST_API_VERSION` 5): a page mounts only the keys
  `m.{slug}.{key}` where `{slug}` is the slug of the module whose page it renders, **given by the host** (as
  `moduleUrl`), and the slot is declared in that module's approved manifest. Any other key renders nothing
 . **A contribution rendered inside a slot has a null page context** (2026-11-10): it cannot mount slots of its own (`Slot` renders nothing for it).
- **Rendering of kind A, by `type`** (2026-11-10): `extension` — as before; `tab` — the owner mounts `Slot` twice inside its own `Tabs` root, `part="trigger"` in the `TabsList` and `part="panel"` with `activeTab` (and `keepMounted`); the tab's value is `{module}.{key}`; a `tab` slot without `part` renders nothing; `action` — rendered bare, without a frame.

**The order of installation.**

- a contribution registered before its owner is installed — or on a slot, or a `layerKind`, that the owner's
  installed version does not have — is **stored inactive**, with a warning in the answer of the registration
  (`warnings[]`, the SDK's `registrationWarnings()`; the text: `surfaces[i].slug: "m.{owner}.{key}" is not a slot the
  installed version of its owner declares; the contribution is stored inactive and is not served until the owner
  declares it`), and is never served while inactive. It becomes active when the slot appears; the check is made again
  when the contributions are served, not frozen at the registration.
- an upgrade of the owner that makes a **non-additive change** under a slot key used by an installed module is
  refused at approval **and at rollback** (the rollback target is judged by the same, the dependency cycles, 2026-11-10) with **`409`**, naming the modules that use it, before any write. Non-additive means: the slot
  removed, a `layerKind` removed, or the major of the key changed; `kind`, `type` or `dataSchema` changed; the
  visibility narrowed from `public` to `internal`; a name removed from `publicMembers`, `actions`, `events` or
  `commands`. `optional` does not lift this refusal (§3.2).
- **Used** = at least one contribution of an installed module that may contribute on the slot: the owner is in its
  `requires` and its class matches the visibility. An inactive row of a module without the right does not block the
  upgrade.
- The asymmetry with the events is deliberate: an upgrade that removes a used `m.{owner}.after.*` event passes, and the
  subscription becomes orphaned.

**The limits.**

- There is **no kind C**: foreign code inside the owner's WebGL context does not exist. Kind R is data only.
- **The core does not contribute** to a module's slots: only modules register contributions.
- **Contributions run in the page**, with the host's React, without an iframe (`frame-ancestors 'none'`); the trust
  comes from the signature of the pack, not from an isolation the page does not have.
- The contract does not assume HTTP: the platform serves the same slots inside the host, without a network.

## 8. Feature flags

- A module declares its flags in the manifest (§3.4); the platform stores their state per module
  (`effective = override ?? default`) and syncs them on every install of a version, keeping the admin's override.
- Admin API (`api.v1.platform-modules.flags`): `GET /api/v1/platform/modules/{slug}/flags` →
  `[{key, label, description, defaultValue, override, enabled, overridden, updatedAt}]`;
  `PUT /api/v1/platform/modules/{slug}/flags/{key}` `{"enabled": true|false}`;
  `DELETE /api/v1/platform/modules/{slug}/flags/{key}/override`.
- **A module reads its flags**: every callback carries `kfflags` (§5.1), the effective values when it is sent; the
  module token MAY read `GET /api/v1/platform/modules/{slug}/flags` (`401` invalid token, `403` another module's token
  or a write).
- An SDK MUST read the flags once after a successful registration and **replace** them with `kfflags` before
  dispatching every callback: a flag absent from the newer `kfflags`, or whose value is not `true`, is **off**; an
  unknown key is off. Fixtures: `sdk/conformance/flags/`.
- Flags gate UI contributions (`surfaces[].flag`, §7.10).

## 9. The module's API routes and the signed context

### 9.1 Routes

A module's own API routes are declared in `capabilities.routes` (§3.2) and served **through the
platform** under `/m/{slug}/…`: the platform registers each `right` (`api.v1.m.{slug}.…`) when the module is installed
or activated, without a restart, binds it to the declared global `roles` (never to `TENANT_ADMIN`, which is
excluded from module routes automatically -- the development organisation's exception below is derived, not a binding),
and forwards an authorized call to the module:

- `GET /m/{slug}/**`: only declared routes; `POST|PUT|PATCH|DELETE /m/{slug}/**`: only declared routes (an undeclared
  path is `404` before the body is read);
- in this order: the module is live at the caller's tenant (`404`) → the route is declared (`404`) → the caller holds
  its right in the active tenant (`403`) → the body is JSON (`415`) and not too large (`413`) → the upstream is the
  module's registered `callbackUrl` origin + the path without `/m/{slug}` + the raw query (validated against SSRF);
- the request is built from scratch: **never** the user's `Authorization`, `Cookie` or `X-Active-Tenant-ID`; redirects
  are not followed; instead the platform sends the signed context (§9.2);
- the answer passes through only as JSON (`application/json`, `application/problem+json`) or empty, with its exact
  `Content-Type`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, and no other header of the module;
  errors carry `class`: `504` and an unreachable module (`502`) are `transient`; a non-JSON answer, a `1xx`/`3xx` or an
  answer over the limit is `502` `permanent`. Any other `5xx` of the module is `502` `transient`, **except** a `503`
  with an `application/problem+json` body, which reaches the user as the module wrote it (the module says why it cannot
  serve now, e.g. `secret-store-unreachable`). A hook or the platform calling a module route
  still reads every `5xx` as a failure of the module.
- **`audience: "member"`** (1.2; **delivered**): the rights filter makes the membership checks of
  any route, then **skips the match of the right** (the kernel's `RouteAccess.MEMBER`, `@MemberRoute`). The order
  stays: live (`404`) → declared (`404`) → **member of the active tenant** (`403`) → JSON (`415`) → size (`413`). The
  context's `rights` carry what the caller holds, possibly `[]`. An account without any membership has no active tenant
  and never reaches a `member` route. On the portal's prefix (`portal: true`, §7.9) the portal's gate comes first and
  the tenant is the customer's;
- **time bound**: the platform waits for the module's answer (its status and headers)
  **5 s** by default (`kf.modulesdk.api.timeout-ms`), then answers `504` `transient`. A route that is slow by nature
  (it reads every object of an upstream, or writes many) declares `timeoutSeconds` (1–60, §3.2): the platform then
  waits that long on that route only, whoever calls it (a user, a hook, the platform). Nothing else changes for the
  routes that declare nothing. The context the module may send back (§11.2) stays fresh for ±300 s, well over the cap.

**Note — reaching TENANT_ADMIN** (editorial): `TENANT_ADMIN` cannot be written in
`roles` (the SDKs refuse it locally, `ManifestCheck.FORBIDDEN_ROLES`, fixture `manifest/route-role-tenant-admin-refused`;
the platform at upload). TENANT_ADMIN receives every active right of the platform **except** the module rights
(`api.v1.m.*`), so a module right reaches TENANT_ADMIN **only** through a route declared `"admin": "tenant"`
(§9.5), in the tenants where the module is active. A route meant for "the tenant's roles and its administrator" therefore
declares its global `roles` **and** `"admin": "tenant"`, even when it is an ordinary data route, not a settings page
(KF-AAS-R3 does this on its status, preview and push routes). Example: kf-qc 0.1.1 declared its tenant routes with
`roles` only, so TENANT_ADMIN got `403` on the whole module; 0.1.2 adds `"admin": "tenant"` to its 21 tenant routes
and TENANT_ADMIN holds their 8 rights after the upgrade (the reverification of §9.1, no restart). An `audience: "member"`
route needs neither: any member of the tenant calls it, TENANT_ADMIN included, without the right being matched.

**Exception — the development organisation**: in a development organisation
(`is_dev`, the tenant that uploads a module without a key on its own route, §3.1 `devOf`), its `TENANT_ADMIN` holds **implicitly**
the rights of the routes of that organisation's **own DEV modules** (`devOwnerTenant` = the organisation) while the
module is live there (installed, running, not `TAMPERED`, activated), except the `admin: platform` routes. It is a right
**derived on every request, never a binding**: nothing is written to `role_right`, it does not appear on the role
screens, it cannot be delegated onto a custom role, and it disappears by itself on deactivation, uninstall, `TAMPERED`
or promotion to a global module — after the promotion TENANT_ADMIN holds only the `"admin": "tenant"` routes, as for any
global module. A global module (`UNVERIFIED` included) and an ordinary tenant are unchanged: the rule above applies. A
module developer therefore tries every route of a DEV module as TENANT_ADMIN in the development organisation, without
temporary roles; the `"admin": "tenant"` declaration still decides what TENANT_ADMIN holds once the module is global.

### 9.2 The signed context

The platform sends, **instead of the user's token**:

```
X-KF-Context:   base64url(no padding) of the context JSON
X-KF-Timestamp: <epoch seconds>
X-KF-Signature: hex(HMAC-SHA256(KF_HOOK_SECRET, timestamp + "." + X-KF-Context + "." + rawBody))
X-KF-Context-Proof: <the platform's own proof over X-KF-Context for this module>  
```

`X-KF-Context-Proof` (**delivered**) is opaque to the module: an HMAC under a key only the platform holds, over
the module's slug and the exact `X-KF-Context` text. `X-KF-Signature` is under the module's own key, so it proves the
request to the module but proves nothing to the platform; when the module calls the platform on behalf of this request
(§11.2), it sends `X-KF-Context` and `X-KF-Context-Proof` back **unchanged**, and the platform recomputes the proof. A
module MUST NOT alter, derive or log it; an SDK keeps it with the verified context. An older platform does not
send it.

The context JSON, keys **in this order**: `v, tenant, user, actor, locale, rights, method, path, query, ts,
requestId` (`actor` right after `user`), and from 1.2 **`person`** last, only on a `member` route. `v` is
`1`.

A user's request:

```json
{"v":1,"tenant":"0f1e2d3c-4b5a-4968-8776-655443322110","user":"oidc-sub-1","actor":{"type":"user","via":null,"hookId":null},"locale":"ro","rights":["api.v1.m.api-test.events.get"],"method":"GET","path":"/events","query":"limit=10","ts":1760000000,"requestId":"req-0001"}
```

A member's request on a route `audience: "member"` (1.2; **delivered**):

```json
{"v":1,"tenant":"0f1e2d3c-4b5a-4968-8776-655443322110","user":"oidc-sub-1","actor":{"type":"user","via":null,"hookId":null},"locale":"ro","rights":[],"method":"POST","path":"/tickets","query":null,"ts":1760000000,"requestId":"req-0201","person":{"email":"ana.pop@kf.ro","displayName":"Ana Pop"}}
```

A call of the platform itself, to a route the module declares in `provides` (1.1, §15.5):

```json
{"v":1,"tenant":"0f1e2d3c-4b5a-4968-8776-655443322110","user":null,"actor":{"type":"system","via":"platform","hookId":null},"locale":"ro","rights":[],"method":"GET","path":"/stations","query":null,"ts":1760000000,"requestId":"req-0101"}
```

A call of a tenant's hook as the system (§9.4):

```json
{"v":1,"tenant":"0f1e2d3c-4b5a-4968-8776-655443322110","user":null,"actor":{"type":"system","via":"hook","hookId":"3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b0a"},"locale":"ro","rights":[],"method":"POST","path":"/notes","query":null,"ts":1760000000,"requestId":"req-0004"}
```

| key | meaning |
|---|---|
| `tenant` | the caller's active tenant: the module MUST filter its data on it |
| `user` | the caller's OIDC subject (opaque; the same identifier as `kfactor`); `null` for the system |
| `actor` | who calls: `{"type": "user" \| "system", "via": "hook" \| "platform" \| "module:{slug}" \| null, "hookId": "<uuid>" \| null}` (`via: platform` from 1.1; `via: module:{slug}` from 2.0, a call between modules, §15.9) |
| `locale` | the caller's language, never null |
| `rights` | the caller's codes under `api.v1.m.{slug}.` in the active tenant, sorted; `[]` for the system |
| `method`, `path`, `query` | the request the module receives (`path` decoded, without `/m/{slug}`; `query` raw or `null`) |
| `ts` | equal to `X-KF-Timestamp` |
| `requestId` | the platform's id of this forward, for correlating the two logs |
| `person` | **(1.2, **delivered**)** only on a route `audience: "member"` (staff and portal): `{"email": <text \| null>, "displayName": <text \| null>}`, the platform user of the request (their e-mail and display name) at the time of the request. Absent on every other route and on a system context. The signature covers it (it is part of `X-KF-Context`). **A module MUST NOT authorize on it**: the identity stays `user`. An SDK before 0.8.0 ignores it (the context is read by key, `v` stays 1); SDK 0.8.0 reads it as `RequestContext.person()` (`null` when absent) and keeps the constructor with the eleven components |

A module (or its SDK) MUST, on every route outside its public paths (the hook path, `/ui/**`, `/health`):
1. require the three headers; verify the signature in constant time, with `ts` within ±300 s;
2. decode `X-KF-Context` (base64url of a JSON object), check `v` = 1 and `ts` = `X-KF-Timestamp`, `tenant`, `locale`,
   `method`, `path`, `requestId` as non-blank text, `user` and `query` as text or `null`, `rights` as a list of text;
3. check `actor`:
   - `user` ⇒ `user` non-blank, `via` and `hookId` `null`;
   - `system` ⇒ `user` **`null`**, `via` = `"hook"`, `hookId` non-blank (and `rights` empty);
   - `system` via **`platform`** (1.1; the platform calls a provided read, §15.5) ⇒ `user` **`null`**,
     `hookId` **`null`** and `rights` **empty**; any of them set MUST be refused. **Delivered**: the platform
     sends it on a provided read; the SDKs (from 0.7.0) accept and check it;
   - **`via: "module:{slug}"`** (2.0, delivered; `{slug}` of the module slug grammar
     `^[a-z0-9][a-z0-9-]{1,40}$`): a call between modules (§15.9) — `user` via `module:{slug}` ⇒ `user` non-blank and
     `hookId` `null` (the rights are the user's under this module's prefix); `system` via `module:{slug}` (a background
     job) ⇒ `user` **`null`**, `hookId` **`null`**, `rights` **empty**. Any other `via` text (`module:` with a
     bad slug, `module:` alone) MUST be refused;
   - **a context without `actor`** (an older platform) MUST be read as `user`;
   - an incoherent combination or an unknown `type` MUST be refused;
   - **`person`** (1.2), when present and not `null`: an object whose `email` and `displayName` are text or `null`
     (`context person.email must be text or null`); a system context that carries one MUST be refused (`a system
     context carries no person`); absent reads as none;
4. check that the context was signed for **this** method and path (a context captured on one route cannot be replayed
   on another within the 300 s window).
Any failure is answered `401` RFC 9457 `https://kf.ro/problems/invalid-context`, `class: permanent`, naming the reason
(never the secret). **A module MUST NOT deduce a user from a `system` context.** Fixtures: `sdk/conformance/context/`.

### 9.3 Delivery status

The forward, the context and its verification are **delivered**. **The platform MUST send
`actor` on every context**; before, the key is absent and reads as `user`.

### 9.4 `hookable` routes (delivered)

A route declared `"hookable": true` (only `POST`, `PUT`, `PATCH`, §3.2) **may be called by an ingress hook of the
tenant**, through the platform's proxy, **without a user and without any user's rights**: the context carries
`actor: {"type": "system", "via": "hook", "hookId": …}` and `user: null`. The capability is **shown at approval**. The
authorization of such a call is the module's declaration **and** the tenant's allowlist **and** the tenant's hook
. The platform's behaviour — the strict upload check, the approval badge, the catalog of hookable routes
and the dispatch — is **delivered**; the SDKs refuse the key locally on a method that does not write, the
platform at upload. A hookable route MUST work without `user` and MUST NOT assume a user's rights.

### 9.5 Administration routes (`routes[].admin`, 1.1 — delivered)

A route declared `"admin": "tenant"` is the module's administration inside a tenant: besides its `roles`, the platform
binds its right to **TENANT_ADMIN** in the tenants where the module is active (the one exception to "module routes are
excluded from TENANT_ADMIN", §9.1, made explicit by the declaration and shown at approval). A route declared
`"admin": "platform"` declares no `roles`: only the **platform administrator** holds its right (for the module's
platform-wide configuration, e.g. the platform secrets, §15.3). An admin route is never `hookable` (§3.2). The forward
and the context are those of §9.1–§9.2.

## 10. Errors

Every error of the module protocol (`/api/v1/platform/**`, `/m/**`, and the answers of a module's SDK to the platform)
is **RFC 9457** `application/problem+json` with `type`, `title`, `status`, `detail` and **`class`**: `permanent` or
`transient`. Other members MAY be added (`errors[]`, `warnings[]`, `module`, `key`, `params`, `state`,
`received`, `supported`).

- `class` says whether **repeating the same request** can succeed. A body without `class` (an older platform, a filter
  that writes its own body, no body) is read with the platform's table: `408`, `425`, `429` and every `5xx` are
  `transient`, every other status is `permanent`. An **unknown** `class` MUST be read as `permanent`. A network error
  (refused, unresolvable host, timeout) is `transient`.
- A client MUST retry **only** `transient` errors, with backoff, honouring `Retry-After`; a `permanent` one MUST stop
  the retry and name its cause. `401` and `409` are `permanent`.
- Problem types: `https://kf.ro/problems/{validation, conflict, not-found, unavailable, hook-vetoed, invalid-signature,
  invalid-envelope, unsupported-envelope-version, handler-failed, invalid-context}` among others.
- Fixtures: `sdk/conformance/errors/`.

## 11. Notifications and the module's audit

### 11.1 Notifications

- **Declared** (delivered): `capabilities.notifications[]` names each notification a module may send —
  `key`, `variables` (the template variables), `channels` (`in_app`, `email`), `subject` and `body` (keys of `i18n`,
  templates per language). The platform validates them at upload and keeps them with the module's version.
- **Sending** (**delivered**). A module sends only what it declared, with its own token:

  ```
  POST /api/v1/platform/modules/{slug}/notifications
  Authorization: Bearer <KF_MODULE_TOKEN>
  Content-Type:  application/json

  {
    "tenantId":       "<uuid>",                       required
    "key":            "author-changed",               required, the key AS DECLARED
    "recipients":     ["<OIDC subject>", …],          0–100; at least one recipient or one role
    "recipientRoles": ["TENANT_ADMIN", …],            optional (1.3), 1–10 role names
    "variables":      {"modelName": "Rochie"},        exactly the declared variables
    "deepLink":       "/models/42",                   optional, a relative path
    "channels":       ["in_app", "email"],            optional, default every declared channel
    "idempotencyKey": "<uuid>"                        optional (the SDKs always send one)
  }
  -> 202 {"accepted": <rows written>, "duplicates": <rows an earlier request with the same idempotencyKey wrote>}
  ```

  - **The key is the platform's.** The module MUST send the key exactly as it declared it (`^[a-z][a-z0-9_-]{0,40}$`:
    no dot, no `m.` prefix); the platform composes `m.<slug>.<key>` from the slug of the **token** (never from the
    body) and stores, shows and filters the notification under that name.
  - **The recipients** are OIDC subjects — the only identifier of a person a module sees (`user` of the signed context
    §9.2, `kfactor` of a callback §5.1) — each of which MUST be an active member of `tenantId`. A subject that two
    identity providers issued to two members of the tenant is refused (`ambiguous-recipient`), never guessed. There is
    no free e-mail address.
  - **`recipientRoles`** (1.3; **delivered**): the module names a role
    instead of people — the platform finds who holds it. 1 to 10 names of the **grantable system roles**:
    `BRAND_MANAGER`, `DESIGNER`, `OPERATOR`, `PRODUCTION_MANAGER`, `QC`, `TENANT_ADMIN`, `CLO_STATION` (a repeated name
    counts once). Any other value — `SUPER_ADMIN` included, a custom role, a wrong case — is refused
    `422 unknown-role`: a module never addresses the platform administrator. The platform resolves, **at sending
    time** and **only in `tenantId`**, the active members of each role (an active user, with an address, holding the
    role in the tenant), joins them with the members `recipients` names and keeps each person **once**; the
    preferences apply afterwards, as for `recipients`. A role without active members is no error (it adds no one;
    `accepted` may be `0`). More than **200** people resolved in all is refused `422 too-many-recipients`,
    nothing written. The answer is the same `{accepted, duplicates}`: **the module sees no subject**, neither in the
    answer nor anywhere else. At least one of `recipients` and `recipientRoles` names someone, else `422
    no-recipients`.
  - **`variables`** MUST be exactly the declared ones (every declared variable is required); each value a string, a
    number or a boolean.
  - **`deepLink`** MUST be a path of the platform: it starts with a single `/` (no `//`, no scheme, no `\`, no control
    character).
  - **`channels`** MUST be a non-empty subset of the declared channels; absent = every declared channel. The tenant's
    trigger matrix and each recipient's own preferences still apply: a channel they turned off writes no row (it is
    neither `accepted` nor an error).
  - **`idempotencyKey`** SHOULD be sent; a request repeated with the same key writes nothing new (its rows count as
    `duplicates`). A client that retries a `transient` answer MUST retry with the same key; the SDKs generate one per
    call and reuse it on every retry.
  - **The language is the platform's**: the module sends no language and no text. The platform renders the module's
    texts (`subject`, `body` keys of `i18n`, from the dictionary written at install — no tenant override) in each
    recipient's language (the recipient's choice → the tenant's language → `en`), Mustache over the variables, the
    body as Markdown.
  - **Refusals** (RFC 9457, every one `class: permanent`, nothing written), with a machine-readable `reason`:

    | status | `reason` | when |
    |---|---|---|
    | `401` | — | no token, an unknown token, or a person's JWT (the route is the modules' only) |
    | `403` | — | another module's token |
    | `404` | `not-live` | the module is not live for `tenantId` (not installed and running, or not activated by the tenant) |
    | `422` | `invalid-request` | no body, `tenantId` or `idempotencyKey` not a UUID, a blank subject; (1.3) `recipientRoles` empty, not a list of text, or more than 10 |
    | `422` | `invalid-key` | `key` outside the grammar (a dot, the `m.` prefix, …) |
    | `422` | `unknown-key` | the active version does not declare `key` |
    | `422` | `undeclared-channel` | a channel that is not `in_app`/`email`, one the key does not declare, or an empty list |
    | `422` | `undeclared-variable` | a variable the key does not declare |
    | `422` | `missing-variable` | a declared variable absent |
    | `422` | `invalid-variable` | a value that is not a string, a number or a boolean |
    | `422` | `no-recipients` / `too-many-recipients` | no recipient and no role; more than 100 recipients; more than 200 people resolved with `recipientRoles` (1.3) |
    | `422` | `unknown-role` | (1.3) a value of `recipientRoles` that is not a grantable system role (`SUPER_ADMIN` included) |
    | `422` | `invalid-deep-link` | `deepLink` not a relative path |
    | `422` | `not-a-member` | a subject that is not an active member of the tenant |
    | `422` | `ambiguous-recipient` | a subject behind which stand two members of the tenant |

    The problem `type` is `https://kf.ro/problems/notification-rejected` (`…/module-not-live` for the `404`).
  - The rows live in the platform's notification queue under `m.<slug>.<key>` and follow its retention (the terminal
    rows are kept 90 days). The tenant administrator sees the module's notifications in the trigger matrix as
    «module name · humanised key» (never the raw key), only while the module is live for the tenant; a deactivation
    hides them with their saved state, an uninstall deletes the matrix rows and the users' preferences of
    `m.<slug>.*`, an upgrade that drops a key deletes that key's; the queue keeps its rows as history.
- **The SDK API**: `KfModule.notifications()` → `Notifications.send(tenantId, key, recipients, variables[, options])`,
  the options `deepLink`, `channels`, `idempotencyKey` and, from SDK 0.9.0 (protocol 1.3), `recipientRoles` (Java
  `NotificationOptions`; `recipients` may then be empty). The SDK checks the shape locally
  first (the grammar of the key, at most 100 non-blank recipients, at least one recipient or one role, 1–10 non-blank
  role names in upper case, scalar values, a relative link, the channel names) and
  refuses, `permanent`, without any request — which roles exist is the platform's answer (`unknown-role`), as the
  global roles are at upload (§3.5); a `permanent` answer of the platform is the SDK's rejected error carrying
  the `reason`; a `transient` one is retried with the same request. Fixtures: `sdk/conformance/notifications/`.
- An older platform does not serve the route (`401` from the main chain).

### 11.2 The module's audit (delivered)

- The capability `audit` (§3.2): `[{"access": "write"}, {"access": "read-own"}]`.
  - `write` — the module writes rows to the platform's audit trail **in the tenant of the request** (proved by the
    context the platform forwarded to it), **attributed to the module's slug**; from a hook context
    (`actor.type = system`) the row's actor is `system:hook:<hookId>` and the human actor is `null`;
  - `read-own` — the module reads **only the rows it wrote** in that tenant.
- The wire form (**delivered**), with the module token and the context of the request the module is serving:

  ```
  POST /api/v1/platform/modules/{slug}/audit                                   access write    -> 201
       {"action": "NOTE_CREATED", "aggregateType": "Note", "aggregateRef": "42"}
  GET  /api/v1/platform/modules/{slug}/audit?aggregateType=Note[&aggregateRef=42][&limit=50]
                                                                               access read-own -> 200
       {"rows": [{"id", "aggregateType", "aggregateRef", "action", "actor", "performedAt", "requestId"}]}

  Authorization:      Bearer <KF_MODULE_TOKEN>
  X-KF-Context:       the context the platform forwarded, unchanged (§9.2)
  X-KF-Context-Proof: the platform's proof that came with it, unchanged (§9.2)
  ```

  - `action` `[A-Z][A-Z0-9_]{1,63}`; `aggregateType` the module's own type `[A-Za-z][A-Za-z0-9_.-]{0,49}`, stored as
    `m.<slug>.<type>` (it never passes for a platform type) and returned without the prefix; `aggregateRef` text, at
    most 255 characters; `limit` 1–200 (default 50); rows newest first.
  - The platform checks, in this order: the token belongs to `{slug}` (`401`/`403`); the active version declares the
    access (`403` `capability-not-declared`); the context is one the platform issued for this module (the proof) and
    `ts` is within ±300 s (`401` `invalid-context`); the module is live for the context's tenant (`403`
    `module-not-live`); the body or the query (`400` `validation`, `errors[]`). Every refusal is `class: permanent`
    and writes nothing.
  - The tenant administrator sees the rows in the tenant's audit views, under `m.<slug>.<type>`.
- The SDK API: `KfModule.audit()` → `AuditClient` with `record(context, AuditRecord(action, aggregateType,
  aggregateRef))` and `list(context, aggregateType, aggregateRef)` (the module's own rows, newest first; a `null`
  `aggregateRef` = every aggregate of the type). The SDK checks the record locally first and refuses, `permanent`,
  without any request, a context it did not verify or one that came without `X-KF-Context-Proof`. Platform refusals:
  `401` → the SDK's unauthorized error, `403` → its forbidden error. Fixtures: `sdk/conformance/audit/`.
- An older platform refuses the capability at upload and does not serve the routes.

## 12. Lifecycle and the admin API

| module action | effect |
|---|---|
| disable | hooks are skipped; fields are neither joined nor accepted; contributions are not served (§7.8); data is kept |
| enable | the module re-registers on boot; its fields, hooks and contributions are live again |
| upgrade | the same token and secret are injected again; the new version re-registers, replacing the previous registration; flags keep the admin's override |
| uninstall | fields become inactive; tables, data, registrations and the tenants' order overrides are kept; the module's texts are deleted from the platform dictionary. From 1.3 (**delivered**), **first**, while the module still runs: the platform's **export** of the module's data — for every tenant, what the platform keeps for the module (its `m_…` rows, its settings, its flag overrides) and, when the manifest declares `tenantData`, the module's own bundles (`GET /m/{slug}/admin/export`, §15.8) — kept in the platform's bucket for 90 days (`module-exports/{slug}/…`); then the platform stops the runtime and uninstalls. When the export of a **running** module fails, the uninstall is refused `502 module-export-failed` (`reason` machine-readable; `class: transient` when the module or the store may answer on a retry, else `permanent`) and nothing changes: the module stays installed and started. A module **already stopped** cannot answer: its uninstall goes on with the platform's part only and records the export as failed when the manifest declares `tenantData` (the Delete then asks for `skipExport`). **The module's files go into the same archive**: for a version that declares `storage`, every object under `kf-modules/{slug}/{tenant}/` of every exported tenant, read by the platform from its bucket, at `files/<key>`; over 50 000 files or 2 GiB (configurable) the export fails (`files-too-large`), without an archive |
| delete | the platform drops the module's `m_…` tables and removes its registry, hooks, registration, deliveries, flags and order overrides. From 1.3 (**delivered**): refused `409 module-export-required` (`class: permanent`) unless a successful export of the installed version exists; the platform administrator may pass `skipExport=true` explicitly, which is audited. After these guards, and before the registry goes, the platform **empties the module's volumes** through the runner (`DELETE /v1/runtimes/{slug}/data`, `compose down -v`: a reinstall starts with an empty database) and **empties the module's files** in the platform's bucket (`kf-modules/{slug}/`, every tenant; §15.7). A volume the runner cannot remove → `409 conflict`, `reason: purge-failed`; a runner that does not answer in time → `503 module-runtime-unavailable`; the storage down or keeping an object → `503 module-storage-unavailable` (`class: transient`, `Retry-After`). On any refusal nothing else changes: the module stays `UNINSTALLED` with its tables, export and guard, and the Delete can be asked again (both purges are idempotent). Without the runner (the externally managed runtime) the volumes cannot be removed: the Delete goes on and its `DELETED` entry says "volumes not removed". Uninstall and Stop never touch the volumes or the bucket. For a module whose installed version declares `storage`, the export the guard asks for must have **carried the files**: an export made before files were exported is refused like a missing one (`409 module-export-required`) |
| rollback | (**delivered**) the platform re-installs a previously installed version (`SUPERSEDED`), approved and signed, re-verified against today's trust store (§14.6). It re-applies, exactly as at install, the texts, the flags (the admin's override kept), the rights, the notifications and the registry projected on the old manifest: hooks and surfaces the old version does not declare are removed, fields it does not declare become inactive with their data kept. The same token and secret are injected again, as at upgrade. A dependent module whose `requires` range the old version does not satisfy blocks it (`409`) — a **mandatory** one
only: an optional dependent blocks nothing (2.0, §3.5). The same rule holds for an upgrade at approval (2.0, hole (a)). The **data floor** applies (below) |

**The data floor at rollback.** The migrations of a module's own database only go forward, and the platform
cannot see that database, so only the module's author knows whether an older version runs on the newer schema. The
author says it in the signed manifest with `rollbackFloor` (§3.1): version V declares `rollbackFloor = F` when every
version ≥ F of the module runs correctly on the data left by V; between F and V the migrations of the module's own
database are only additive (expand). The platform computes **H = the highest version of the module ever installed** and
**refuses a rollback to a version below `rollbackFloor(H)`**; when H declares no `rollbackFloor`, the floor is H itself
(no rollback below H). The refusal names the floor and H. Because the floor is read from H, not from the version
installed now, a rollback followed by another rollback cannot go below what the newest data allows.

What the floor asks of a module's migrations (expand/contract): a version that may be rolled back to F adds columns,
tables and indexes that the versions ≥ F ignore, and never drops, renames or retypes what they read; the contracting
step (dropping what is no longer read) ships in a later version whose `rollbackFloor` moves up past the versions that
still read it. The floor is the author's promise: the platform enforces only what is declared and never inspects the
module's database. The extension tables of `kf_modules` need no floor: the platform never drops their columns nor
changes their type, and fields the old version does not declare become inactive.

Example (KF-AAS-R3-Module, which keeps its own Postgres; its manifest is not changed here): 1.3.0 adds a
nullable column in an expand-only migration and declares `"rollbackFloor": "1.2.0"`; once 1.3.0 has been installed,
a rollback to 1.2.x is allowed and a rollback to 1.1.0 is refused. If 1.4.0 then drops a column that 1.2.x reads, it
declares `"rollbackFloor": "1.4.0"` (or omits the key): after 1.4.0 has been installed, H = 1.4.0 and no rollback below
it is allowed, even from 1.3.0.

Admin reads: `GET /api/v1/platform/modules/{slug}/registrations` (the hooks, the fields with `storageTable`, `column`,
`active`, the surfaces) and `GET /api/v1/platform/modules/{slug}/deliveries?limit=50`.

Every upload, approval, install, upgrade and reinstall verifies the signed pack, and the platform re-verifies what is
installed at boot, hourly and on demand (§14.6).

## 13. Conformance and the SDKs

- **The shared suite**: `sdk/conformance/` holds language-neutral JSON fixtures (format:
  `sdk/conformance/README.md`, `fixture.schema.json`) on the areas `errors`, `registration`, `manifest`, `signature`,
  `envelope`, `before`, `flags`, `context`, `audit`, `notifications`. **Every SDK passes every fixture**: Java
  (`ConformanceSuiteTest`), TS (`test/conformance.test.mjs`), PHP (`tests/conformance.php`). A runner fails on an
  unknown area, a failing fixture, a fixture it cannot run, or zero fixtures; there is no skip. CI
  (`.github/workflows/sdk.yml`) runs `java-conformance`, `ts-conformance`, `php-conformance`, `provenance` and
  `catalog-check` on every push.
- **One implementation by hand**: the Java SDK. TS and PHP are produced from one Java commit by `/kf-sdk-derive`
  (`.claude/skills/kf-sdk-derive/SKILL.md`): every derived file carries `kf-sdk-derived-from: sdk/java@<sha> v<version>`,
  the commit that brings them carries `Sdk-Derived-From: <sha>`, and `package.json`/`composer.json` carry the pom's
  version. `sdk/tools/sdk-provenance-check.mjs` checks all of it in CI, without Claude.
- **Platform vocabularies are generated, not derived**: the surfaces (`Surfaces.java`, `surfaces.generated.ts`,
  `Surfaces.php`, and the list in §7.1) from `sdk/catalog/surfaces.public.json` (`sdk/tools/gen-vocabularies.mjs`,
  `gen-protocol.mjs`); the pinned copies of the platform's files are listed with their sha in
  `sdk/catalog/SOURCES.json` (`sdk/tools/catalog-sync.mjs`).
- A fixture enters with the Java change that needs it (order). A rule fixed only here (§7.6) has no fixture.
- **Protocol 1.2 in the SDKs**: the SDKs 0.8.0 read and check locally the 1.2 keys of the manifest
  (`manifest/audience-*`, `portal-*`, `activation-*`, `storage-*`, `hook-tenant-after-delete-valid`,
  `contract12-absent-reads-defaults`) and `person` in the context (`context/person-*`). The clients of the 1.2
  services — `StorageClient` (§15.7), the handler of `tenant.after.delete` (§6.1), the pinned release list with
  `app.overlay`, the pinned hook catalog schema (the point is the constant `HookEvents.TENANT_AFTER_DELETE`), the
  generated vocabularies, `kf-host.d.ts` v4 — are **delivered**, with the shared fixtures `storage/*` and
  `envelope/tenant-after-delete-*`, which also turns the statuses of this document to "delivered".
- **Protocol 1.2.1 in the SDKs**: no fixture. The reads client and its fixtures (`reads/*`) are
  port-agnostic — the port is a parameter, the filters are passed as given — so two new ports change nothing a fixture
  can check. Their filter rules are in the Java tests artifact's `FakePlatform` (`FakePlatformQcReadsTest`), where a
  module tests its reads.
- **Protocol 1.3 in the SDKs** (SDK 0.9.0): the fixtures `notifications/send-recipient-roles`,
  `send-roles-only`, `unknown-role-422`, `too-many-recipients-422`, `role-name-refused-locally`,
  `no-recipients-no-roles-refused-locally`, `envelope/tenant-after-soft-delete-*`, `envelope/tenant-after-restore-valid`,
  `reads/tenant-v1-list-platform` and `reads/system-context-list-other-port-refused-locally`. `TenantDataExport` is
  checked in each language against `kf-feedback`'s own import fixture (its `contentSha256`). `ctx.version` (§7.3) has
  no fixture: the host gives it, the SDKs carry it as a type of the pinned catalog.
- **Protocol 1.3.1 in the SDKs** (SDK 0.9.1): no fixture, as 1.2.1. The reads client
  is port-agnostic, so `model.v1` without `ids` and `model-version.garment-image.v1` change nothing a fixture can check;
  their rules are in the Java tests artifact's `FakePlatform` (`FakePlatformDemoPortsTest`). `models.detail.overview.cards`
  and its `ctx` (`model`, `version`, `href`) come with the pinned catalog and the generated vocabularies.
- **Protocol 2.1 in the SDKs** (SDK 0.10.0-dev): the fixtures `manifest/flows-valid`, `flows-invalid-key`,
  `flows-invalid-path`, `flows-duplicate`, `flows-invalid-schema2` (§17.1). The pack's files (§17.2,
  `ManifestCheck.checkPack`) have no fixture — the manifest area carries no pack — and are fixed by `ManifestFlowsTest`
  and its derived twins.
- **The signed pack (§14)** is not part of the SDKs: the operator's tool `sdk/tools/kf-module.mjs` and the shared
  vectors `sdk/tools/fixtures/module-signature/` define it; CI runs the job `module-signature` (the tool's tests and
  `kf-module verify` on the fixture pack).

## 14. The signed pack

**Delivered:** the format and the check at upload, the tool `sdk/tools/kf-module.mjs`; the checks at
approval, install, upgrade and reinstall and the inputs on digests; the re-verification at boot, hourly and on
demand. **This is not a wire change:** the manifest (§3, still `schemaVersion` 2), the callback envelope (§5)
and the signed context (§9.2) are unchanged, and so is the version table at the top of this document. A signature
cannot sit in what it signs, so the manifest gets no field for it; it is covered whole by `manifestSha256` and
`files[]`. Every module needs a signature accepted by the platform's trust store; a pack without one is
refused at upload (`unsigned`), except on the dev organization. **A dev build (§3.1 `devOf`) is never
signed:** `kf-module sign` and `kf-module release-check` refuse it with the reason `dev-build`, before any key is opened
(unchanged — the release key stays with the operator and signs releases only).

### 14.1 The envelope and the statement

`kf-module.sig`, at the root of the pack, is a **DSSE** envelope (Dead Simple Signing Envelope, in-toto / Sigstore):

```json
{ "payloadType": "application/vnd.kf.module-statement+json",
  "payload": "<base64 of the statement bytes>",
  "signatures": [ { "keyid": "kf-release-2026-1", "sig": "<base64>" } ] }
```

- exactly **one** signature; base64 standard, padded; `sig` = Ed25519 over the PAE
  `"DSSEv1" SP LEN(type) SP type SP LEN(payload) SP payload` (LEN = decimal byte length). The payload bytes are signed
  verbatim: no JSON canonicalisation. The **statement digest** = sha256 of the payload bytes.
- the statement, `kf-module-statement/1`: `slug`, `version` (= the manifest's), `manifestSha256` (sha256 of the bytes
  of `kf-module-conf.json`), `files[]` (`path`, `size`, `sha256`), `contentSha256`, `images[]` (`{service, ref}`, the
  `image:` references of the compose, on digests), `source {repo, commit, tag}` and `sdk {tag, commit, variant}` (copied
  from `kf-build.json`; `variant` from the amendment of 2026-11-09 — a statement signed before has no `variant` and
  stays valid, its class is public), `signedAt` (UTC). The statement does not name the key: the envelope does.

### 14.2 The canonical tree and `contentSha256`

- The signature covers the **tree the platform extracts**, not the zip bytes: what Docker builds is what was checked.
- Paths: `/`-separated, relative, no empty segment, no `.` or `..`, no `\`, no control character, already in Unicode
  NFC; regular files only (no link, no directory entry with content); no two names equal after `toLowerCase`; a name
  repeated in the zip is refused. Paths are refused, never normalised (`unsafe-path`).
- `files[]` = every file except `kf-module.sig`, sorted on the UTF-8 bytes of the path;
  `contentSha256` = sha256 over `path "\n" size "\n" sha256 "\n"` of each file, in that order. Because
  `kf-module.sig` is outside it, `contentSha256` is the same for the pack that was tested unsigned and for the pack
  signed for release (`kf-module sign --resign` changes only `kf-module.sig`).

### 14.3 Reserved names (at the root of the pack)

| name | rule |
|---|---|
| `kf-module.sig` | the envelope; a regular file at the root, the only file outside `files[]` |
| `kf-build.json` | the build provenance (§14.5); inside `files[]`, so it is signed |
| `docker-compose.kf.yml` | written by the platform at install (§2); a pack that carries it is refused (`reserved-file`) |

### 14.4 The trust store

- **Where:** `module-trust/trust-store.json` in the platform image (classpath; the public keys of Katty-Fashion) plus
  one optional extra file mounted read-only (`MODULE_TRUST_EXTRA_FILE`, `/kf/module-trust/…`) that may only **add**
  third-party keys and **restrict** (a stricter key status, revoked statements). Never in the database, never through
  the UI. The platform holds public keys only and never signs.
- **Format** `kf-module-trust/1`: `keys[]` with `keyId` (`^[a-z0-9][a-z0-9.-]{2,63}$`, unique), `publisher`,
  `scope: release`, `status` (`active` / `retired` / `revoked`), `namespaces` (slug prefixes the key may sign),
  `publicKey` (base64 of the Ed25519 SPKI DER), `addedAt`; plus `revokedStatements[]` (statement digests). The extra
  file is `kf-module-trust-extra/1`: `keys[]`, `keyStatus {keyId → status}`, `revokedStatements[]`. A fragment
  written by `kf-module keygen` is one `keys[]` entry plus its `fingerprint` (`sha256:` of the SPKI), which readers
  ignore.
- **Publishers and prefixes:** the publisher comes from the key, never from the manifest's `author`. `katty-fashion`
  is Katty-Fashion (trust level `KF_RELEASE`); any other publisher is a third party (`THIRD_PARTY`) and MUST NOT hold
  a prefix that could cover `kf-` (`""`, `k`, `kf`, `kf-`, `kf-…`). The key's `namespaces` must cover the slug
  (`key-not-for-namespace`).
- **States:** `active` signs new code; `retired` still verifies what is already approved (install of an approved
  version, reinstall, the boot/hourly re-verification, rollback) but no new upload or approval (`key-retired`);
  `revoked` is refused everywhere (`key-revoked`). A digest in `revokedStatements` revokes one release without the key
  (`statement-revoked`). Rotation: add the new key `active`, set the old one `retired`.
- **Identity**: a module is its slug plus the publisher of the key that signed its first installed version;
  another publisher on the same slug is another module (`identity-mismatch`). A rotation inside one publisher stays an
  upgrade.

### 14.5 `kf-build.json` and the inputs on digests

```json
{ "format": "kf-build/1", "slug": "kf-aas-r3", "version": "1.2.0",
  "source": { "repo": "Katty-Fashion/kf-platform-modules", "commit": "<40 hex>", "tag": "kf-aas-r3-v1.2.0",
              "dirty": false, "tagSigned": true },
  "sdk": { "tag": "sdk-v0.6.0", "commit": "<40 hex>", "variant": "external" },
  "baseImages": [ "eclipse-temurin:21-jre@sha256:<64 hex>" ],
  "builtAt": "2026-10-21T12:00:00Z", "tools": { "node": "v22.x", "java": "21.x" } }
```

- written by the module's build (`kf-module build-info`) in every build, with the real state; a release build
  (`build.sh <version> --release`, `kf-module release-check`) refuses a dirty tree, a HEAD that is not the tag
  `<slug>-v<version>`, an SDK that is not on a tag of its line (`sdk-v*` for `external`, `sdk-internal-v*` for
  `internal`) and an input that is not on a digest;
- **`sdk.variant`:** `"external"` — the public SDK, tags `sdk-v<semver>` — or `"internal"` — the
  internal SDK, its own tag line `sdk-internal-v<semver>`. `build-info` writes it from the SDK the module is built with;
  the tool copies it into the statement (`sdk {tag, commit, variant}`). `release-check` and `sign` refuse
  (`build-provenance`) a missing `sdk.variant` or another value, `"internal"` on a tag outside `sdk-internal-v*`, and
  `"external"` on a tag outside `sdk-v*`. **At upload** the platform refuses (`422 build-provenance`) a `kf-build.json`
  whose `sdk.variant` differs from the statement's (as for `sdk.commit`), and `"internal"` on a tag outside
  `sdk-internal-v*`; a statement without `variant` (signed before) passes and is public;
- **inputs on digests:** every `FROM` of the Dockerfile of a service with `build` — except a stage of the same file
  and `scratch` — and every `image:` MUST be `…@sha256:<64 hex>`; a variable (`${…}`) is refused, it cannot be pinned
  (`digest-required`). `baseImages[]` = the external `FROM` references; `statement.images[]` = the `image:` references;
- **signing** (`kf-module sign`) refuses a pack whose `kf-build.json` is missing, `dirty`, not on `<slug>-v<version>`,
  not on a tag of its `sdk.variant`'s line, or whose `baseImages` differ from the Dockerfiles (`build-provenance`); in the module's git
  repository it also requires the tag `<slug>-v<version>` to point at `source.commit` and, when the tag is signed
  (`git tag -s`), `git verify-tag` to be green. A signed tag is the operator's choice at each release
  (`source.tagSigned`).

### 14.6 What the platform verifies, and when

| moment | what | key state accepted |
|---|---|---|
| upload | the envelope, the key, the statement, the extracted tree; nothing is stored on a refusal | `active` |
| approval | sha256 of the stored zip = the one uploaded (`pack-changed`), the envelope against the trust store **of now**, the identity | `active` |
| install, upgrade, reinstall | the extracted `installed/{slug}/{version}/` tree before any credential or override is written; a refusal leaves the version `FAILED` and the previous one active | `active`, `retired` |
| boot, hourly, on demand | every installed version: the stored zip, the envelope against the trust store of now, the installed tree, the manifest in the database; a failure marks the module `TAMPERED` and stops it | `active`, `retired` |
| on the host | what runs: `kf-module verify-runtime --slug <slug>` by the operator (the installed tree, the labels `kf.module.slug`/`kf.module.version`, the pinned base under each built image, a read-only rootfs for `KF_RELEASE`); the platform does not see the containers | `active`, `retired` |

The order of the checks is fixed and identical in the tool and on the platform, so the same pack gives the same
reason: `unsigned` → `reserved-file` → the envelope (`bad-envelope`) → the key (`unknown-key`, `key-revoked`) → the
signature (`bad-signature`) → the statement (`bad-envelope`) → `statement-revoked` → `key-retired` (new code) →
`key-not-for-namespace` → the tree (`unsafe-path`) → the manifest (`statement-mismatch`: `manifestSha256`, then
`slug`/`version`) → the files (`content-mismatch: <path>`).

**The class at re-verification (delivered).** The class of a module (internal or public)
is computed only by the platform, from the envelope of its **active** version re-verified against the trust store of
now: internal only when the envelope verifies, the key is a `KF_RELEASE` key of the publisher `katty-fashion` (as the
verification of now says, never a stored column), and the statement says `sdk.variant: "internal"` on a tag
`sdk-internal-v*`. A `DEV` build is internal only when the `kf-build.json` of the tree noted at upload says
`"internal"` AND its owner is a development organization now. Anything else is public, without a refusal: no variant,
`"external"`, a `THIRD_PARTY` key, an envelope that no longer verifies, any failure. The class follows the active
version: an upgrade or a rollback onto another variant changes it at the next computation; nothing is cached. Neither
the manifest, nor the `trust_level` column, nor the trust store's extra file can make a module internal. **Shown, not
decided, at approval:** the version's computed class is `signature.trustClass` (`internal` \| `public`) in the admin API
and in the signature summary.

### 14.7 The problem `module-signature`

A refusal is `422` with `type` `https://kf.ro/problems/module-signature`, `title` "Module signature refused",
`class: permanent`, `reason`, `detail` (a path or a field of the pack, never a server path), plus `errors:
["<reason>: <detail>"]` and `warnings: []`. Reasons: `unsigned`, `bad-envelope`, `unknown-key`, `key-revoked`,
`key-retired`, `key-not-for-namespace`, `bad-signature`, `statement-mismatch`, `content-mismatch`,
`statement-revoked`, `unsafe-path`, `reserved-file`, `identity-mismatch`; `pack-changed`, `digest-required`,
`build-provenance`.

### 14.8 The tool and the vectors

- `node sdk/tools/kf-module.mjs` (Node ≥ 22, no dependency): `keygen` (the release key, PKCS#8 encrypted with a
  passphrase read from the terminal or the first line of stdin — never from an option or the environment), `sign`
  (`--key`, `--resign`), `verify --trust`, `inspect`, `release-check`, `build-info`, `verify-runtime`, `dev-build`.
- **The SDK variant (delivered):** `build-info` writes `sdk.variant` (`external` on an `sdk-v*` tag,
  `internal` on an `sdk-internal-v*` one); `release-check` and `sign` accept an SDK tag of either line
  (`^sdk(-internal)?-v<semver>$`) when it matches the variant, and refuse the mismatches of §14.5 (`build-provenance`);
  `sign` copies `sdk.variant` into the statement; `inspect` shows it. The shared vectors carry a pack built with the
  internal SDK.
- **The dev build**: `kf-module dev-build <zip|dir> [--out <zip>]` derives `<slug>-dev-<version>.zip`
  deterministically — `slug` → `<slug>-dev` with `devOf: <slug>` right after it, the prefixes `/m/<slug>/` and
  `api.v1.m.<slug>.` rewritten in every text of `capabilities`, " (dev)" on the texts of `name`, `nav[].label` and
  `surfaces[].label` in every language, `kf-build.json` with `devBuildOf {slug, version, packSha256, contentSha256}` of
  the source, no `kf-module.sig`, every other file byte for byte. A source that is already a dev build is refused
  (`REFUSED dev-build`). `sign` and `release-check` refuse a dev build (`devOf`, or a slug `*-dev`) with the reason
  `dev-build`, before the passphrase, writing nothing; `inspect` shows `dev build  <devOf>@dev` and the `devBuildOf`
  row; `verify` answers `unsigned`, as for any unsigned pack. A module's `build.sh <version> --dev-identity` writes the
  dev build beside the normal zip.
- **On the host**: the module runner refuses an unsigned tree
  (`KF_RUNNER_ALLOW_UNSIGNED`, global, `false` by default and never set in a shared configuration), with one exception:
  for a slug `<base>-dev` only, the platform adds `devOwner` (the owning organization) to `PUT /v1/runtimes/{slug}`, and
  the runner raises the unsigned tree when that organization is listed on the host in `KF_RUNNER_DEV_ORGANIZATIONS`
  (empty by default: no exception) and the tree's manifest is that slug and version with `devOf: <base>`. Anything else
  unsigned is refused (`signature`). The runner's trust stays `UNSIGNED`.
- **A module that has a dev build reads its slug at run time** (§3.1): `KF_MODULE_SLUG` (§2) in the service and
  `module.slug` given by the host in the UI, for its routes, rights, texts, bundle and links; KF-AAS-R3 does from 0.2.13
  (`ModuleIdentity`, `ui/src/lib/moduleSlug.ts`, `build.sh <version> --dev-identity`).
- The shared vectors `sdk/tools/fixtures/module-signature/` (`vectors.json`, `kf-sigtest-0.1.0.zip`, the TEST keys,
  the test trust store) are replayed by the tool's tests and by the platform (`ModuleSignatureVectorsTest`, from a
  pinned copy): the same `contentSha256`, PAE and verdict on both sides. CI: the job `module-signature`
  (`.github/workflows/sdk.yml`).

## 15. The platform services of protocol 1.1, 1.2 and 1.3

What a module asks of the platform besides its hooks and routes, added in 1.1. Every call
below goes **from the module to the platform** with the same three headers as the audit (§11.2) — the module's token,
the context the platform forwarded and its proof, unchanged — and the platform checks, **in this order**: the token
belongs to `{slug}` (`401`/`403`); the active version declares the capability (`403` `capability-not-declared`); the
context is one the platform issued for this module and `ts` is within ±300 s (`401` `invalid-context`); the module is
live for the context's tenant (`403` `module-not-live`); then the part's own checks. Every refusal is RFC 9457 with
`class: permanent`, except `503` (`transient`) and the `503 secret-store-rejected` of §15.3 (`permanent`). The tenant is
always the context's, never another. Every part below is **delivered** by the plan named in its title; the SDK clients
came (SDK 0.7.0).

### 15.1 Published reads (`reads`) — delivered (SPI, ports, client)

**A module reads objects, not snapshots.** A port follows a platform entity in the shape of its owning aggregate; the
vocabulary of an integration (AAS keys, rows, pairs) is built by the module. The list of ports, their fields, filters
and rights was **decided by the operator on 2026-10-21**:
`docs/modules/specifications/10-citirile.md` of the superproject is the field-level source.

```
GET /api/v1/platform/modules/{slug}/reads/{port}?<filters>&page=<n>&size=<n>   -> 200 {items, page, size, total}
GET /api/v1/platform/modules/{slug}/reads/{port}/{id}[?include=a,b]            -> 200 the object
```

- `port` matches `^[a-z][a-z0-9-]{1,40}(\.[a-z][a-z0-9-]{1,40})?\.v[0-9]+$` and is declared in
  `capabilities.reads`.
- Paging: `page` from 0, `size` default 20, at most 200, a fixed order by `id` ascending. A list requires its mandatory
  filters; an unknown filter is `400`.
- The list filter `ids` takes its values comma-separated, **at most 200** (equal to the largest
  page): more is a `400` of the filters; the SDKs refuse it locally.
- After the common checks: **the platform right of the port, held by the context's user in the context's tenant**
  (`403` `insufficient-rights`; one right per port); the filters (`400`); the object visible in the tenant
  (`404`; the visibility of the equivalent platform read route: RLS, and for a customer user only their objects).
- **The system context** (`actor.type = system`, `user: null`, `rights: []`) is accepted only on the ports marked
  `system` below, only on the object operation and only with the base fields (no `include`).
- Heavy sub-collections are returned only when asked by name in `include=`.
- Values: JSON numbers with the source type, never normalized; instants ISO-8601, dates `yyyy-MM-dd`; `null` kept.

| port | operations | right | system |
|---|---|---|---|
| `model.v1` (`modelOwner` and the list without `ids`: 1.3.1, delivered in 1.3.1, BE 9b952ec09) | object; list `ids`, or none for a user holding the right (a customer user: `ids`*) | `api.v1.models.get` | yes (object) |
| `model-version.v1` | object (+`include`); list `ids` | `api.v1.models.get` | yes (object, base fields) |
| `document.v1` | list `modelVersionId`*, `type`; object | `api.v1.documents.get` | no |
| `file.public-url.v1` | object `{fileId}` | the parent object's (`api.v1.models.get`) | no |
| `order.v1` | object (+`include`); list `modelVersionId`, `status` or none | `api.v1.orders.get` | no |
| `order-costing.v1` | object `{orderId}` (per line) | `api.v1.orders.get` | no |
| `purchase-order.v1` | list `originOrderId`*; object | `api.v1.purchase-orders.get` | no |
| `supplier.v1` | list `ids`; object | `api.v1.suppliers.get` | no |
| `customer.v1` | list `ids`; object (`{id, name, internal}` only) | `api.v1.customers.get` | no |
| `production-batch.v1` | list `orderId`* | `api.v1.production.get` | no |
| `waste-report.v1` | list `orderId`* | `api.v1.waste-reports.get` | no |
| `tech-process.v1` | list `modelVersionId` or none; object | `api.v1.tech-process.get` | no |
| `technician.v1` | list `active`; object (`displayName`, no email, no user ids) | `api.v1.technicians.get` | no |
| `technician-shift.v1` | list | `api.v1.planner.get` | no |
| `tenant.v1` (1.2; **delivered**; the list without `ids`: 1.3, **delivered**) | object `/{id}`; list `ids`* (≤ 200); **the platform's actor**: list without `ids` | the code of the platform route that lists the tenants | the list only (1.3) |
| `component.v1` (1.2.1; **delivered**) | object `/{id}`; list `ids` (≤ 200) **or** `q`* (one of them, never both), `componentType` | `api.v1.components.get` | no |
| `inventory-roll.v1` (1.2.1; **delivered**) | object `/{id}`; list `componentId` **or** `ids` (≤ 200)* (one of them) | `api.inventory.stock.get` (the derived code of `GET /api/inventory/stock/{componentId}/entries`, measured) | no |
| `model-version.garment-image.v1` (1.3.1; **delivered in 1.3.1, BE 9b952ec09**) | object `/{versionId}`; list `ids`* (≤ 200) | `api.v1.models.get` | no |

`*` = a mandatory filter. `tenant.v1` returns `{id, name}` with the visibility of the equivalent platform route: the
platform administrator sees every tenant, any other user only their tenants.

From 1.3 (**delivered**), `tenant.v1` returns `{id, name, deletedAt}` —
`deletedAt` the instant of the tenant's soft-delete (§6.1), `null` for an active tenant — and **the platform's actor**
may list it without `ids` (`?page=&size=`, `size` ≤ 200, ordered by `id`): every tenant, soft-deleted ones included.
The platform's actor is the platform's own context (`actor.type = system`, `via = platform`, §9.2, e.g. on the
platform's call of `admin/export`, §15.8) or a user holding the platform route's right on a route the module declares
`admin: platform` (§9.5) — the module's platform-wide pages. This is the one list the system context may read (the
SDKs refuse any other locally). Anyone else who lists without `ids` gets today's `400` with `errors.ids =
"required"`: a module never enumerates the tenants for an ordinary user.

`component.v1` (1.2.1, owner `component`) returns, in this order, `id` (number), `name`, `componentType` (`MATERIAL` |
`ACCESSORY` | `PACKAGING`), `materialType` (text or `null`), `unit` (text or `null`), `colorSpec` (`{colorType, name,
hexValue, pantoneCode, patternName}` or `null`), `desiredCuttableWidth` (number or `null`), `status` (`PENDING` |
`APPROVED` | `REJECTED` | `ARCHIVED`). Its list takes `ids` **or** `q` — `q` is trimmed, 2 to 100 characters, and keeps
the objects whose name contains it, whatever the case — with an optional `componentType` (one value). Without either,
`400` with `errors.q = "required (or ids)"`; both, `q` too short or too long, two types or an unknown type are `400` on
the field.

`inventory-roll.v1` (1.2.1, owner `inventory`) returns, in this order, `id`, `componentId`, `quantity` (number; a
consumption line is negative), `um` (text or `null`), `rolWidth` (number or `null`), `sku` (text or `null`), `source` (`MANUAL` |
`PURCHASE_ORDER` | `PRODUCTION` | `CUSTOMER_SUPPLIED`), `sourceRefId` (number or `null`), `state`. **`state` is computed
by the platform**: `CONSUMED` when `quantity <= 0`, else `RESERVED` when an order holds the roll, else `AVAILABLE` (the
order itself is not published). Its list takes `componentId` **or** `ids` — with both, the material's rolls among the
ids; without either, `400` with `errors.componentId = "required (or ids)"`. There is no `createdAt`: the platform's roll
does not carry one (a consumer would add it additively, as any field). Both ports are closed to the customer portal.

From 1.3.1 (delivered in 1.3.1, BE 9b952ec09), `model.v1` carries **`modelOwner`** (text or `null`) after
`styleNumber`, on the object and on the list items: the free text of the model form, not an identity — it does
not come from the platform's users and is never composed from a person's name (stays). It is a base field, so the
system context reads it on the object. A **user holding `api.v1.models.get`** in the context's tenant may list
`model.v1` **without `ids`** (`?page=&size=`, `size` ≤ 200, ordered by `id`): every model visible in the tenant, the
items in the list's shape (no subjects) with `modelOwner`. The system context still reads only objects (`403
insufficient-rights`); a customer user of the portal still gets `400` with `errors.ids = "required"` and, with
`ids`, only their models. No new filter: an unknown one is `400`; with `ids` the list is unchanged.

`model-version.garment-image.v1` (1.3.1, delivered in 1.3.1, BE 9b952ec09; owner `model`) returns, per model
version, `{id, url, expiresAt}`: `id` the version's id, `url` a **presigned GET URL** of the version's garment image
on the platform's public storage endpoint (for the user's browser, not for the module's server), valid **10
minutes**, and `expiresAt` the instant it expires (ISO-8601); `url` and `expiresAt` are `null` when the version has
no image or its stored key is not under the version's own image prefix. The object is `/{versionId}`; the list takes
**`ids`, mandatory** (at most 200; without it, `400` with `errors.ids = "required"`), for a page with one card per
model. Right `api.v1.models.get` (the roles of the host's route that serves the image); **not** a system port (a URL
is for a person's browser). Open to the customer portal with the guard of the version's owner: another customer's
version is `404` on the object and absent from the list. It has **no effect on the stored object**: a local
signature only, no ACL, no write, the bucket stays private. The port is separate from `model-version.v1` for the
reason: a URL that gives access to bytes is asked for explicitly and declared on its own in
`capabilities.reads`; `file.public-url.v1` (`public-read`, links that do not expire) is unchanged.

People are exposed only as opaque OIDC subjects (`createdBySubject`, `technicianSubject` on
the object, a) or a composed `displayName` (a); `file.public-url.v1` makes the object public-read and is called
explicitly, guarded on the parent object (a). A platform port SHOULD gain a field additively when a consumer appears;
a breaking change is a new major (`.v2`). A port is published when its provider exists in the platform (`ModuleReadPorts`,
, which replaced the empty hand-written `ModuleReadPortCatalog`); the platform refuses at upload a port it does not
publish.

### 15.2 Settings (`settings`) — delivered (platform, client)

**Settings carry no data.** A setting is a closed scalar — `BOOLEAN`, `NUMBER` or `ENUM` — that changes how the module
behaves in a tenant (a switch, a limit, a mode). Free text, lists, references to records and secrets are **data**: they
live on the module's surfaces (the platform-wide records in the module's `setup` section, the per-tenant selections on
its tenant-management card) or in `secrets` (§15.3). The rule is applied mechanically: the SDKs refuse any other
type locally, the platform at upload (the text of the architecture rule comes through `/kf-design-arch`).

- Per tenant; edited by the TENANT_ADMIN in the module's area; the value is validated against the declaration (type,
  `values`, `min..max`); the default applies until set.
- `GET /api/v1/platform/modules/{slug}/settings` → `200 {"<key>": <effective value>, …}` for the context's tenant (every
  declared key, defaults included).

### 15.3 Secrets (`secrets`) — delivered (platform, client; `external` 0.7.2)

A secret is declared by name and scope; its value is written through the platform and stored in the platform's vault
under the module's path, never in the manifest, the module's settings or a log.

```
PUT  /api/v1/platform/modules/{slug}/secrets/{name}   {"value": "<text>"}   -> 200 {"ref": "vault://<mount>/kf-modules/<slug>/<platform|tenantId>/<name>"}
POST /api/v1/platform/modules/{slug}/secrets/resolve  {"ref": "vault://…"}   -> 200 {"value": "<text>"}
```

- `PUT` only when the context's `rights` contain the secret's `writeRight` (`403` otherwise); a `platform` secret is
  written through a route with `admin: platform` (§9.5), a `tenant` secret lands under the context's tenant.
- `resolve` only for a reference of this module: a `tenant` reference only of the context's tenant (`403` otherwise).
- The platform and the SDKs MUST NOT log a value; a reference MAY be stored by the module (in its settings records or
  its database) and resolved when needed.
- **An existing reference** (SDK 0.7.2). When a `platform` secret declares `external`
  prefixes (§3.2), the module MAY keep, instead of a value it writes, a reference the platform already holds — e.g.
  `vault://platform/aas/kf-r3/client-secret`, the pointer of the platform's own AAS client — and resolve it with the
  same `POST …/secrets/resolve`. The value then passes through nobody but that resolve. The reference MUST be
  `vault://` + a path of at least two segments of `[A-Za-z0-9._-]` (none empty, none starting with a dot), with no
  `kf-modules` segment, at most 512 characters, strictly under a declared prefix. The platform writes an audit row
  `MODULE_SECRET_EXTERNAL_RESOLVED` at every such resolve, in the context's tenant (the module, the declaring secret, the
  prefix — never the value nor the reference); a reference no declaration of the active version covers is
  `403 external-ref-not-declared`; a traversal or another shape stays `403 forbidden`. The SDKs refuse locally a
  reference no prefix of the manifest covers (`… is under no prefix declared in capabilities.secrets[].external`) and
  expose the check (`SecretsClient.externalRefProblem(ref)`, null when the reference is acceptable).
- **The secret store**, on a write and on a resolve: not configured, not reachable or not logged into is
  `503 secret-store-unreachable`, `class: transient`; answering but refusing the path (a Vault `403`: its policy does
  not cover `kf-modules/<slug>/*`, or the existing reference) is `503 secret-store-rejected`, `class: permanent`, with a
  `detail` that names the policy and the path family, never the value nor the store's answer. A module passes either
  on to its user as a `503` with a problem body (§9.1). The store's policy must allow, for the value mode,
  create/update/read on `kf-modules/*` of its KV mount, and read on each existing reference a module keeps.

### 15.4 The module's own extension values — delivered

```
PATCH /api/v1/platform/modules/{slug}/extensions/{entity}/{id}   {"<field>": <value> | null, …}   -> 200 the module's values
```

Only the fields the module declares in `capabilities.fields[]` for `entity` (whatever their `editable`, §7.6), on an
entity visible in the context's tenant (`404` otherwise); a value of the wrong type is `400` (`errors["<field>"]`);
`null` clears. `entity` ∈ `model`, `order`, `customer`, `model_version` (§6.2). This is the only write a module makes on
platform objects; `reads` never writes.

Every such write runs a mutation pipeline: on `model`, `order` and `customer` the entity's own `*.update`, as a
`PUT` carrying only `extensions`; on `model_version` the **model's** `model.before.update` / `model.after.update`, with
`versions.{versionId}.extensions.{slug}.{field}` in the envelope (1.1; §5.1, §6.2). A veto is
`422` `hook-vetoed` and nothing is written; a patch is ignored on `model_version`. The answer is unchanged (`200` the
module's values).

### 15.5 Provided reads (`provides`) — delivered

Some platform features read data that a module owns (today `factory.stations.v1`, the stations matched by
`techprocess`). The platform keeps a catalog of such ports; a module declares `{port, path}` and serves the port on one
of its `GET` routes. The platform calls it through the forward of §9.1 with the context
`{"user": null, "actor": {"type": "system", "via": "platform", "hookId": null}, "rights": []}` in the tenant concerned
(§9.2); the module serves it without a user and MUST NOT assume a user's rights. The shape of each provided port is
fixed by the plan that adds it to the catalog (for `factory.stations.v1`).

A `404` from the module on a provided read means **nothing configured** for that tenant (e.g. KF-AAS-R3's
`factory-shell-not-bound`): the platform reports it as `NOT_CONFIGURED`, permanent, without the module's body (1.1). Any other `4xx` stays `MODULE_REFUSED` (permanent); a `5xx`, an unreachable module or a slow
one is `MODULE_UNAVAILABLE` (transient).

**Parameters (additive, no protocol version change).** A provided read may carry
parameters. The platform sends them as the **query string** of the `GET` path the module declared in `provides` (the
path itself stays literal; the platform never puts a parameter in it): keys **sorted**, keys and values URL-encoded in
UTF-8 (`application/x-www-form-urlencoded`, a space as `+`), joined with `&`; the context's `query` carries the same
text (§9.2). A read without parameters sends no query at all (`query: null`), exactly as before. A key is non-blank;
neither a key nor a value holds a control character (the platform refuses them before calling). The module reads the
parameters of its port as documented below; a parameter it cannot read, or a required one missing, is a `4xx`
(`MODULE_REFUSED`, permanent). The answer is a closed JSON object: an unknown key, a wrong type or a second JSON value
makes the whole answer `INVALID_ANSWER` (permanent).

| port | parameters | answer (`200 application/json`) |
|---|---|---|
| `factory.stations.v1` | none | `{"stations": [{"name", "cellRole", "functions": [..], "complete"}], "warnings": [..]}` |
| `inventory.roll-availability.v1` | `componentId` (whole number) and optional `forCustomerId` (whole number): the material's available rolls; **or** `entryId` (whole number): the material of one ledger row | `{"rolls": [{"inventoryEntryId", "componentId", "remainingLength", "rolWidth"?, "sku"?, "ownerCustomerId"?}], "entryComponentId"?}` |
| `purchaseorder.order-purchases.v1` | `orderId` (whole number, required) | `{"components": [{"componentId", "orderedQuantity", "receivedQuantity"}], "latestExpectedDeliveryDate"?}` |

`inventory.roll-availability.v1` (who reads it: `order`, the material overview and the reservations). With
`componentId`: one entry per **physical roll** with something left, `remainingLength` the net of the ledger for that
roll (positive), in mm; with `forCustomerId`, only our own (unowned) rolls plus the rolls that customer owns;
`entryComponentId` absent or `null`. With `entryId`: `rolls` empty, `entryComponentId` the material of that row, or
`null` when the row is not in the tenant. `inventoryEntryId`, `componentId`, `ownerCustomerId` and `entryComponentId`
are whole numbers, `remainingLength` and `rolWidth` numbers, `sku` text; the optional keys may be `null`.

```
GET /stock/rolls?componentId=7&forCustomerId=3        actor = {type: system, via: platform}
200 {"rolls": [{"inventoryEntryId": 41, "componentId": 7, "remainingLength": 12500.0,
                "rolWidth": 1500.0, "sku": "B-12", "ownerCustomerId": null}], "entryComponentId": null}
```

`purchaseorder.order-purchases.v1` (who reads it: `order`, the material needs and the derived material date): what the
purchase orders launched **from** that order say about it; a `REJECTED` purchase order never counts. `components`: per
component, the sums of the expected and received quantities (mm for a material, the component's unit otherwise); a
component with no line is absent. `latestExpectedDeliveryDate`: the latest expected delivery date among those purchase
orders (ISO `YYYY-MM-DD`), absent or `null` when none has one.

```
GET /purchases/by-order?orderId=12                      actor = {type: system, via: platform}
200 {"components": [{"componentId": 7, "orderedQuantity": 50000.0, "receivedQuantity": 0.0}],
     "latestExpectedDeliveryDate": "2026-12-01"}
```

How the platform uses an absence of these two (the module only has to answer honestly): the displays (the material
overview, the needs, the derived date) show nothing and log; a reservation of a roll answers `503`
`https://kf.ro/problems/supply-unavailable` and writes nothing; the consumption of the reservations when an order goes
into production is retried later. Today `inventory` and `purchaseorder` serve both ports in process; a module that
declares one in `provides` (Supply & Stock) takes over for the tenants where it is live.

### 15.6 The module page (`surfaces[].page`) — delivered

A contribution to a surface of type `page` (the host's catalog) declares `page`; the host opens it at
`/m/{slug}/{page}`, in its own layout, with the same contribution contract as the other surfaces (§7.2). The menu entry
that leads there is a `nav[]` entry (§7.11) whose `url` is that path.

- **Two `page` surfaces** (§7.1): `module.pages` — the page at `/m/{slug}/{page}[/…rest]` in the staff shell, `ctx`
  `{ page, path, query }` (`path` = the segments after the page, `query` = one value per key), actions `navigate`,
  `toast`, `setTitle` (the page heading); `setup.sections` — the module's section of `/setup` at
  `/setup/m/{slug}/{section}`, only for the platform administrator, `ctx` `{ section }`, the same actions.
- **Which contribution is the page:** the one of module `{slug}` on that surface whose registered `key` (§7.10) is the
  page. The manifest declares `page` (checked at upload against the surface's type, once per surface and module); the
  registration carries the same value as `key`.
- **Not live, not there:** the contributions are served only while the module is live for the tenant (§7); a page the
  module does not serve is the host's **404** (a contribution that fails to render is reported on the page instead).
  A setup section the module does not serve for the active organization is a notice in the setup shell.

### 15.7 Storage (`storage`) — 1.2; **delivered**

A module that declares `capabilities.storage` (§3.2) uploads and reads files **through the platform**, in the bucket of
the platform's files, under its own prefix and the context's tenant. The calls carry the three headers of §15 and pass
its common checks (the token, the capability declared — `403` `capability-not-declared` —, the context, the module live
for the context's tenant).

| request | effect | answers |
|---|---|---|
| `POST /api/v1/platform/modules/{slug}/storage/upload-url` `{"contentType", "size"}` | the platform makes the key `kf-modules/{slug}/{tenant}/{uuid}.{ext}` (the extension from the declared type) and presigns a `PUT` for 10 minutes, with `Content-Type` and `Content-Length = size` signed | `200 {"key", "url", "method": "PUT", "headers", "expiresAt"}`; `400` (a type the module does not declare, `size` ≤ 0 or above `maxBytes`); `403` `capability-not-declared` |
| `POST /api/v1/platform/modules/{slug}/storage/download-url` `{"key"}` | presigns a `GET` for 60 minutes | `200 {"url", "expiresAt"}`; `403` for a key outside `kf-modules/{slug}/{the context's tenant}/` or with `..`; `404` the object does not exist |

- **The exception of the platform triage:** when the context was issued for a route the module declares
  `admin: "platform"` (the platform recognises it by the context's `method` and `path`), any key under
  `kf-modules/{slug}/` is accepted for a download.
- The module never chooses the key, never lists and never deletes an object. The objects of an uninstalled module
  stay, like its data (§12). **A deleted module's objects go with it**: the
  platform's Delete empties `kf-modules/{slug}/` for every tenant — the prefix ends in `/`, so `kf-modules/{slug}-dev/`
  is not touched — after the module's volumes are emptied and before its registry row goes. The exports
  (`module-exports/{slug}/…`) and the tenant dumps stay. When the storage cannot be listed or keeps an object the Delete
  is refused with `503 module-storage-unavailable` (`class: transient`, `Retry-After`, with `slug` and `remaining`,
  never a key), the module stays `UNINSTALLED` and the Delete can be asked again (§12). The bucket is touched only when
  some version of the module in the registry declares `storage`. A module's bundles carry only the keys of its files
  (`screenshotKey`, `imageKey`); **the platform's export carries the files** (§15.8),
  and `POST /api/v1/platform/modules/{slug}/exports/restore-files` puts them back at their keys after a Delete and a new
  install, so the module's import of its bundles finds them.
- **When a tenant is deleted** the platform purges `kf-modules/{slug}/{tenantId}/` for every installed module that
  declares `storage` — after the commit, best-effort, the failures logged — through the kernel port
  `TenantDataTeardownContributor`. The module can no longer call the platform for a deleted tenant: the platform deletes
  its files, the module deletes its rows on `tenant.after.delete` (§6.1).

### 15.8 Tenant data: export and import (`tenantData`) — 1.3; **delivered**

**What a module keeps in its own database leaves with it, re-importable.** The platform cannot read a module's
database (§3.3), so when it must keep a copy of a tenant's data — at the module's uninstall (§12) and in the dump that
comes before a tenant's hard delete (§6.1) — it asks the module. A module that declares `tenantData` (§3.1) serves:

```
GET /m/{slug}/admin/export?tenantId=<uuid>&part=<n>      (the module's route, declared admin: platform, timeoutSeconds ≤ 60)
X-KF-Context: … actor {type: system, via: platform}     (the platform's own context, §9.2; tenant = tenantId)
-> 200 the bundle (application/json)
```

- The route is a route of the manifest like any other (`routes[]`: `{"method": "GET", "path": "/m/{slug}/admin/export",
  "right": "api.v1.m.{slug}.admin.export.get", "admin": "platform", "timeoutSeconds": 60}`): the platform calls it with
  its own context, whose `tenant` is the tenant exported, and the module reads only that tenant. `part` starts at `1`;
  the platform asks part `1`, reads `parts` and asks the others in order.
- **The bundle** — the shape of the `<slug>/tenant-data/v<N>` bundles the modules import today, generalised:

  ```json
  { "schema": "kf-feedback/tenant-data/v1", "tenantId": "<uuid>", "exportedAt": "2026-10-30T18:00:00Z",
    "part": 1, "parts": 1,
    "tickets": [ … ],                                   the module's own content, at the top level
    "counts": { "tickets": 34, "withScreenshot": 33 },  the module's tallies of this part
    "contentSha256": "a55243f5…" }
  ```

  The common keys are required: `schema` = the manifest's `tenantData.format`; `tenantId` = the tenant asked (the
  platform refuses a bundle of another tenant); `exportedAt` ISO-8601; `part`, `parts` with `1 ≤ part ≤ parts`;
  `counts`; `contentSha256`. Everything else is the module's content, beside them; `source` and `warnings` are
  informative.
- **`contentSha256`** = SHA-256 (lowercase hex) of the canonical JSON of the bundle **without** `exportedAt`, `source`,
  `warnings` and `contentSha256`: object keys sorted at every level (by UTF-16 code unit), compact, UTF-8, the values
  as written (an integer as digits; a bundle that must hash the same in every language carries decimals as strings).
  The same form the modules' imports recompute today (a different hash is their `422 bundle-altered`).
- **Re-import, unchanged**: `POST /m/{slug}/admin/import?mode=dry-run|apply` with the bundle as body (the module's
  existing route, in the bundle's tenant) takes each part back and answers the same `counts`. An export the module's
  own import refuses is a defect of the module.
- **Refusals**: the module answers a problem (§10) when it cannot export (`503`, `transient`, retried by the platform;
  anything else `permanent`). At the uninstall of a running module a failed export refuses the uninstall
  (`502 module-export-failed`, nothing changes, §12); a module already stopped is not asked, its uninstall goes on and
  records the export as failed. Before a hard delete it stops the hard delete.
- **What the platform keeps**: the bundles, with its own part (the module's `m_…` rows, its settings and flag overrides
  per tenant), in its bucket — `module-exports/{slug}/{timestamp}.zip` at the uninstall, inside the tenant's dump
  before a hard delete — for **90 days**, downloadable by the platform administrator only.
- **The module's files**: the bundles name
  files by their keys only; the platform, the only writer under `kf-modules/{slug}/` (§15.7), copies the files itself.
  For a version that declares `storage`, the uninstall's archive holds every object under `kf-modules/{slug}/{tenant}/`
  of every exported tenant at `files/<the whole key>`, each with its `sha256` and `bytes` in the manifest's `files[]`,
  and the manifest gains `storage: {prefix, objects, bytes}` (per tenant `storage: {objects, bytes}`). The manifest's
  `contentSha256` covers them through `files[]`; the bundles' hashes do not change. Over 50 000 files or 2 GiB the
  export fails (`files-too-large`). After a Delete and a new install, the platform administrator calls
  `POST /api/v1/platform/modules/{slug}/exports/restore-files?mode=dry-run|apply[&archive=<yyyyMMddHHmmss>]`: the
  platform reads the archive (the newest by default) from its bucket, verifies it whole before writing anything
  (`422 module-export-altered`; `409 module-export-without-files` for an archive made before files were exported), puts
  each file back at its own key, never overwrites one that is there, and audits it. Then the bundles are imported as
  before. The tenant's dump before a hard delete does not carry the files yet (a later quick).
- **The SDKs** (0.9.0): `TenantDataExport.bundle(schema, tenantId, part, parts, counts, content)` builds a bundle with
  its hash, `TenantDataExport.contentSha256(bundle)` recomputes it (Java; TS and PHP derived), `TenantDataExport.PATH`
  is the route's path under `/m/{slug}`. In the Java tests artifact, the module calls its own route with
  `FakePlatform.systemContext(tenant, "GET", "/admin/export")`.

### 15.9 Between modules: reads and commands (protocol 2.0 — delivered)

A module calls another module **only through the platform** (one network per module, §7.9); the platform checks the
declarations, the dependency and the class, carries the caller's context on, and audits both ends. This is a new protocol, not a direct call.

- **Declarations.** The owner offers `capabilities.peerReads[] {port, path, visibility}` and `capabilities.commands[]
  {name, path, method, visibility}`; the caller declares `capabilities.peers[] {owner, reads?, commands?}` with the
  owner in its `requires` (§3.2, schema 3). `visibility: internal` = offered only to modules of the internal class (the
  class follows the SDK variant, §14.6); for a public caller an internal port or command is refused **exactly**
  like one that does not exist (no oracle).
- **Routes** (the caller's token, its `X-KF-Context` + `X-KF-Context-Proof` as proved by the platform — from a request
  it serves, or from a delivery for a background job, §5.5):
  - a read: `GET /api/v1/platform/modules/{caller}/peers/{owner}/reads/{port}` (query passed on);
  - a command: `POST /api/v1/platform/modules/{caller}/peers/{owner}/commands/{name}` with a JSON body and a
    mandatory **`Idempotency-Key`**.
- **The order of the checks:** (1) the identity (`{caller}` = the token's slug), `peers` names the owner and the port
  or command, the context proved and fresh (±300 s), the caller live for the tenant; for a command, the key; (2) the
  owner in the caller's `requires`; (3) the owner installed, in range, live for the tenant; (4) the port or command
  offered to this caller; (5) a `user` context requires **the user to be a member of the context's tenant** (otherwise `403 peer-forbidden`, permanent) and holds the right of the owner's route (unless it is `audience: member`); for a command, both come **before** the key is recorded — a refused call never occupies a key (2026-11-10); a context that comes from the customer portal no longer calls another module as the user; then the forward. **At the owner** the platform signs the context of the caller: its tenant, user and locale,
  `actor.via = module:{caller}` (§9.2), for a user its rights under `api.v1.m.{owner}.`, for a background job
  `system via module:{caller}` with no user and no rights.
- **Errors** (RFC 9457, `type` `https://kf.ro/problems/<reason>`, `class`; the detail is the platform's sentence,
  never the owner's body):

| situation | status | reason | class |
|---|---|---|---|
| no token / another module's token | 401 / 403 | (the identity) | permanent |
| `peers` does not name the owner and the port/command | 403 | `capability-not-declared` | permanent |
| context missing, unproved or expired | 401 | `invalid-context` | permanent |
| the caller not live for the tenant | 403 | `module-not-live` | permanent |
| the owner not in `requires` | 403 | `peer-not-required` | permanent |
| the owner not installed, or outside the required range — **an absent optional dependency included** | 503 | `module-unavailable` | permanent |
| the owner installed, in range, stopped or not activated for the tenant | 503 | `module-unavailable` | transient |
| the port not offered (undeclared, or `internal` for a public caller) | 404 | `peer-port-unknown` ("The owner does not offer this port.") | permanent |
| the command not offered (same rule) | 404 | `peer-command-unknown` ("The owner does not offer this command.") | permanent |
| the user lacks the right of the owner's route | 403 | `peer-forbidden` | permanent |
| the user of a `user` context is not a member of its tenant (2026-11-10) | 403 | `peer-forbidden` | permanent |
| `Idempotency-Key` missing or outside `^[A-Za-z0-9._:-]{8,128}$` | 400 | `idempotency-key-required` | permanent |
| the body not JSON, over the limit, or of another type | 400 | `validation` | permanent |
| the same key with another body (canonical SHA-256), **or by another principal** (`system` / the user) (2026-11-10) | 409 | `idempotency-conflict` ("...with another body or by another principal.") | permanent |
| the same key again, the first `2xx` too large to store (> 64 KiB) (2026-11-10) | 409 | `idempotency-answer-not-stored` | permanent |
| the same key, still running | 409 | `idempotency-in-progress` | transient |
| the owner: `2xx` JSON | its status | (the body passes unchanged) | — |
| the owner: a `2xx` **without a body** (2026-11-10) | its status, no body and no `Content-Type` | — | — |
| the owner: `404` | 404 | `not-configured` (`NOT_CONFIGURED`) | permanent |
| the owner: another `4xx` | 422 | `module-refused` (`MODULE_REFUSED`) | permanent |
| the owner: `5xx`, timeout, unreachable | 503 | `module-unavailable` (`MODULE_UNAVAILABLE`) | transient |
| the owner: a `2xx` whose body is not JSON, a redirect (`3xx`), or an answer over the size limit (2026-11-10) | 502 | `invalid-answer` | permanent (a command's key is kept) |

- **`Idempotency-Key` (commands).** Per caller, owner, command and key: the first call runs and its answer is stored;
  the same key with the same body (canonical JSON, key order irrelevant) **replays** the stored answer without calling
  the owner again (a stored refusal is replayed as the same refusal); another body **or another principal** is `409 idempotency-conflict` (the key is bound to the principal: `system`, or the user; a replay never passes to another principal); a
  call still running is `409 idempotency-in-progress`. The key is **kept after any permanent refusal of the owner's answer** (`invalid-answer`, `module-refused`, `not-configured`: the same key replays the same refusal); a first answer too large to store makes the same key `409 idempotency-answer-not-stored`: use a new key to run the command again. Only a **transient** failure (`module-unavailable` transient) frees
  the key, so a retry executes (2026-11-10). A key is kept **7 days**. The rows are tenant-scoped.
- **Audit at both ends**, the same `requestId`: `MODULE_PEER_READ` / `MODULE_PEER_READ_RECEIVED` and
  `MODULE_PEER_COMMAND` / `MODULE_PEER_COMMAND_RECEIVED` (the second under the owner), written once the owner was called.
- **The SDKs** (Java, TS/PHP): `PeersClient` (reads and commands; an owner's `2xx` without a body is `Answer(status, null)`, `object()` on it a permanent error; `PeersClient.IDEMPOTENCY_ANSWER_NOT_STORED`; the key is mandatory, given by the module —
  `PeersClient.newIdempotencyKey()` makes one — and refused locally when missing or outside the grammar) and
  `EventsClient` (§6.3: `emit`, `on(owner, name)`); both take the `RequestContext` of a request or an after delivery's
  `DeliveryContext` (§5.5, `Envelope.deliveryContext()`). The refusals of the table are `PlatformRefusal`s, with the kinds
  `conflict` (409), `refused` (422), `invalid-answer` (502), `unavailable` (503) besides those of §15. `FakePlatform` (Java)
  answers both for a module's own tests (`peer(owner)`, `deliverModuleEvent`, `emittedEvents`).

## 16. The module steps of the order flow (protocol 2.0 — delivered)

**State: delivered.** The wire form below is fixed and the SDKs speak it (SDK
0.10.0-dev); the platform delivers every key of this section: `ORDER_SERVICES`
(`OrderServicesProtocolDelivery`, the platform) and `FLOW_RULES`, `FLOW_EVENTS`, `ORDERFLOW_STEP` (`FlowProtocolFeatures`, the platform), each contributor in the same commit as the core part that
applies its key (§16.7). A platform before them **refuses** the key at upload, approval, rollback and registration —
`422`, `class: permanent`, **with the text of the key** (§16.7). The order hooks of §16.2 were never reserved:
they are **delivered** (public, in the release list, subscribed like the model's points under
`schemaVersion` 2 or 3). The keys of
§16.3–§16.6 require `schemaVersion` 3; under 1–2 each manifest key is
refused (`capabilities.<key> requires "schemaVersion": 3`).

### 16.1 The normative list

| id | type | phase | what a module may contribute | `onFailure` default | visibility | order |
|---|---|---|---|---|---|---|
| `order.before.create` | `@HookPoint` | before | veto `{key, params}`; patch only on `extensions.{own slug}` | `allow` (`block` only when declared) | public | `priority`, then slug |
| `order.before.update` | `@HookPoint` | before | veto (ignored on the flow's status projection, answer A); patch only on `extensions.{own slug}` | `allow` | public | `priority`, then slug |
| `order.before.delete` | `@HookPoint` | before | veto | `allow` | public | `priority`, then slug |
| `order.after.create\|update\|delete` | `@HookPoint` | after-commit | receives | `allow` (at-least-once) | public | none |
| `order.field.changed` | `@HookPoint`, `fieldFilter: true` | after-commit | receives; the paths `status`, `entries`, `extensions.*` | `allow` | public | none |
| `workflow.flow-step-entered`, `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted` | event (outbox), not a `@HookPoint` | after-commit | receives | none (no `onFailure`) | public | none |
| `flowRules` | a module rule, forwarded as §15.5 | before (read only) | a condition of the step's advance, with an i18n reason | **fail-closed** | public | AND; every reason |
| `order.services.v1` | a platform service (§15), a write | synchronous, through `order.before/after.update` | the module's own service line with a cost | — (the error goes back to the module) | public | none (additive) |
| `orderflow.step` | a UI surface, type `extension` | — | the section of the step | module inactive → a step without actions | public | after the flow |
| `order.before.transition`, task, document, promotion | — | — | **closed in 2.0** | — | — | — |

The rows after the hook points require `schemaVersion` 3. The hook points of the first five rows are those of §6.1
(any `schemaVersion` from 2); the others are the
names of §16.7, delivered.

### 16.2 The order hooks (delivered — public)

- **The points.** `order.before.create`, `order.before.update`, `order.before.delete`, `order.after.create`,
  `order.after.update`, `order.after.delete` and `order.field.changed` are in the release catalog (`visibility: public`,
  `envelope: kf.change` v1, the field change `kf.field-change` v1). A module subscribes with
  `capabilities.hooks[{point, onFailure?}]` as for the model (§3.2, §4); no new manifest key. The `customer.*` points
  stay **internal**: a manifest that names one is refused exactly like a point that does not exist. The SDKs
  carry the ids as `HookEvents.ORDER_*` and the handlers as `kf.orders()` (the model's `ModelHooks` shape on the order).
- **What a before hook may answer.** `allow`; a **veto** `{decision: "reject", key, params}` (a key of `i18n.en`,
  §5.4); a **patch only on `extensions.{own slug}`** — the order's core fields carry `patch: []`, so a patch that names
  a core field is a failure of the hook (its `onFailure` applies; the SDKs refuse it locally, `500 permanent`). A patch
  on `order.before.delete` is refused as on the model (§5.4).
- **The intent of a status change**: `payload = {status: <new>}`, `before = {status: <old>}` — a
  before hook sees the status the order is moving to. The status values (`HookEvents.ORDER_STATUSES`) are `DRAFT`,
  `CONFIRMED`, `IN_PRODUCTION`, `SHIPPED`, `CANCELLED` and **`COMPLETED`** (terminal, reached
  only from a flow — at the end of the flow instance, from `SHIPPED`, or from `CONFIRMED` on an order without the
  production stage; never a target of the status PATCH). A line change carries
  `entries = [{id, modelVersionId, quantity?}]` before and after (sorted by id, no price, no service).
- **`order.field.changed`** (`fieldFilter: true`): one delivery per changed path, on `status`, `entries` and
  `extensions.{slug}.{field}`; `capabilities.hooks[].fields` narrows it (`fields is only allowed on
  model.field.changed, order.field.changed`).
- **Answer A — the veto on the status of an order with a flow.** On an order that runs a flow, the step's advance and
  the status projected by the target step (`behavesLike`) are one transaction; the condition of the advance is
  `flowRules` (§16.4), the only channel. A `reject` from `order.before.update` on **that projection** does not block: the
  platform logs `HOOK_REJECTED … ignored` (the precedent of "patch ignored" on `model_version`, §6.2). On an order
  without a flow, the veto on `order.before.update` keeps its effect.
- **Internal orders**: an order of the tenant's internal customer is an order like the others for the hooks;
  the projection of §16.4 says so with `order.internal = true` (and no `order.customerId`).

### 16.3 The events of the flow engine (`workflow.flow-*`)

- **The types** (the owning module's prefix, `DomainEventTypeNamingTest`): `workflow.flow-step-entered`,
  `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted`. They are events
  written to the outbox in the engine's transaction, **not** `@HookPoint`s (B10): delivered after the commit,
  **at-least-once**, signed (§5.3), CloudEvents with `kfeventid` (§5.1), 5 s × 5 attempts.
- **The subscription** is a `capabilities.hooks[{point}]` entry **without `onFailure`** (an event cannot block the
  step; `hooks[i].onFailure is not allowed on the flow event "…": it runs after the step and cannot block it`).
  **Delivered** with `ProtocolFeature.FLOW_EVENTS`: the engine's contributor (`FlowPublishedEvents`) counts
  only while the feature is delivered, as the third source of subscribable points next to the `@HookPoint` catalog and
  the module events (`PublishedPlatformEventCatalog`). The four types are **not** added to the generated hook
  catalog (`hook-points.release.json`, `GET` of the release catalog): they are accepted at upload and registration and
  delivered by their type.
- **`data`** is fixed; until then it is an object whose minimal named fields are `orderId`, `instanceId` and
  `stepKey` (every type carries `flowKey` and `flowVersion` too; `workflow.flow-line-promoted` adds `lineId`,
  `sourceVersionId`, `targetVersionId`; `workflow.flow-instance-completed` adds `finalStatus`). A receiver ignores the
  keys it does not know (§0). The SDKs read them as `FlowEvents.FlowEvent` (`orderId`, `instanceId`, `flowKey`,
  `flowVersion`, `stepKey`, `data`); `stepKey` may be absent only on `workflow.flow-instance-completed`.
- **Idempotency:** the module deduplicates on `kfeventid` (§5.5). A flow that comes back to a step delivers
  `workflow.flow-step-entered` again, with another `kfeventid`: a new entry.

### 16.4 `capabilities.flowRules` and the projection `order.flow-rule.v1`

- **The declaration:** `capabilities.flowRules[] {key, path, timeoutSeconds?, projection}`, a closed object; `key`
  `^[a-z][a-z0-9-]{1,40}$`, unique; `path` the path of a `GET` route this module declares (§3.2) **with literal segments only** (2026-11-10) (`path "<path>" must be a route with literal segments only (no {param} segment)`); `timeoutSeconds` a
  whole number 1–60, absent = **2**; `projection` = `"order.flow-rule.v1"` (the major is in the key). The platform's
  texts: `flowRules[i].key … must match ^[a-z][a-z0-9-]{1,40}$`, `.path … is not a GET route this module declares`,
  `.timeoutSeconds … must be a whole number from 1 to 60`, `.projection … must be "order.flow-rule.v1"`.
- **The call:** when a step that names the rule is asked to advance, the engine (business-core) calls
  `GET {path}?<the projection>` on the module, with the context **`system via platform`** (user `null`, rights `[]`,
  §9.2), within `timeoutSeconds`. The projection is the order as the rule needs it; **`order.v1` stays non-`system`**
  (§15.1) and a module with `flowRules` MUST NOT read the order through `order.v1` while it evaluates (it has no user
  to read it as, and the projection is the contract).
- **The projection `order.flow-rule.v1`** — query parameters, every value text, booleans `true|false`, an absent
  parameter = not applicable; the platform emits them in this order (built
  `OrderFlowRuleProjection`):

| parameter | required | source | meaning |
|---|---|---|---|
| `projection` | yes | platform | `order.flow-rule.v1` |
| `rule.key` | yes | manifest | the `flowRules[].key` evaluated |
| `order.id` | yes | core | the order's id |
| `order.status` | yes | core | the current status (§16.2, `COMPLETED` included) |
| `flow.key`, `flow.version` | yes | engine | the flow fixed when the instance started |
| `instance.id` | yes | engine | the flow instance |
| `step.key` | yes | engine | the current step |
| `step.to` | yes | engine | the step the advance asks for |
| `order.number` | no | core | the number shown (the order's name) |
| `order.internal` | no | core | `true` for an internal order |
| `order.customerId` | no | core | absent on an internal order |
| `step.behavesLike` | no | engine | the status the target step projects |
| `order.stages` | no | core | **repeated** — `order.stages[]`, one per stage ticked on the order, sorted: `development`, `sampling`, `production`; absent = none ticked |
| `line.id`, `line.modelId`, `line.modelVersionId`, `line.quantity` | no | engine | only on the steps of a line; `line.quantity` the sum over sizes |

- **Evolution:** an unknown parameter is ignored; adding one stays v1; renaming, removing or changing the meaning of
  one is `order.flow-rule.v2` — a new key, declared in `flowRules[].projection`. The query is at most **4 KiB**; it
  carries no price, cost or personal data. A rule that receives a `projection` it does not know, or misses a required
  parameter, answers `400` (the engine reads it as a block, below). The names of the engine's fields are confirmed by
  business-core at activation; a change before it is coordinated.
- **The answer:** `200 {"decision": "pass"}` or `200 {"decision": "block", "key": <a key of i18n.en>, "params"?:
  {…}}` — `key` mandatory on `block`, at most 20 `params`.
- **Fail-closed:** a timeout, a non-`2xx`, a body that is not JSON, an unknown `decision`, a `block` without a key, or
  a module stopped / not live / not activated for the tenant → the rule **blocks**, with the platform's reason in the
  ProblemDetail (`flow-rules-failed`, `failed[] = {rule, lineId?, messageKey, args, surface, source}`).
- **Several rules on a step = AND:** every rule must pass; the answer lists **every** reason, not only the first.
- **The administrator's override** (core): a platform or tenant administrator may pass a blocked rule with a
  reason; the override is audited.
- **Answer B — a disabled module with orders in a flow that names it:** the deactivation goes through (no `409`), the
  approval shows how many orders in a flow name the module; its steps are blocked (fail-closed) and the administrator
  may pass them, audited. Each instance keeps its pinned copy of the definition (B3); the modules a flow names are
  checked when the definition is published and when the instance starts (I6).
- **Delivered** with `ProtocolFeature.FLOW_RULES` (the engine calls the rule through
  `ModuleExternalRuleEvaluator`, bound in the same commit). The SDKs: `FlowRules.Projection.fromQuery` (the parsing, the
  refusals), `FlowRules.Decision.pass()` / `block(key, params)`, `Manifest.FlowRuleCapability`.

### 16.5 The surface `orderflow.step`

- A surface of type **`extension`**: the section of a step in the order's Process tab. The contribution:
  `capabilities.surfaces[{slug: "orderflow.step", kind: react|wc, key: <stepKey>, label?}]` — `key` is mandatory (the
  key of the step it renders, `^[a-z][a-z0-9-]{1,40}$`; `surfaces[i] on "orderflow.step" needs a "key": the key of the
  step it renders`); the registration names the same key (`Surface.key(stepKey)`).
- `ctx = {order, step, instance, line}` (`line` `null` outside the steps of a line); `actions = {navigate, toast}`; the
  public members are those of the release list when the surface enters it.
- It entered the host catalog **with its mounting**: the host mounts it in the Process tab
  (`ModuleStepSurface`, the location of `orderflow.step` in `surfaces/surfaces.catalog.json`) and adds it to the release
  list (§7.1) — no amendment. A module that is inactive renders nothing: the step shows "module inactive",
  without actions (fail-closed).
- **Delivered** with `ProtocolFeature.ORDERFLOW_STEP`.

### 16.6 The service `order.services.v1`

- **The capability:** `capabilities.services[] {port}`, a closed object; `port` = `"order.services.v1"` (the only one
  in 2.0), once (`services[i].port … must be "order.services.v1"`).
- **The routes** (the module's token, `X-KF-Context` + `X-KF-Context-Proof` of a request it serves, or of a delivery
  for a background job, §5.5):
  - upsert: `POST /api/v1/platform/modules/{slug}/services/order.services.v1` with
    `{orderId, entryId, name, cost: {amount, currency}, externalRef}` → `204`. `orderId`, `entryId` an id (text or a
    whole number); `name` 1–200 characters; `cost.amount` a number `≥ 0`; `cost.currency` ISO 4217
    (`^[A-Z]{3}$`); `externalRef` `^[A-Za-z0-9._:-]{1,128}$`;
  - delete: `DELETE /api/v1/platform/modules/{slug}/services/order.services.v1/{externalRef}?orderId=` → `204` (an
    absent line is not an error). A line of the order with that `externalRef` written by **another module** (and none of
    this module) is refused: **`409 order-service-line-owned-by-module`**, permanent, nothing removed, not audited, the
    other module not named (10 Nov 2026) — the same type the manual routes answer on a module's line. Until
    10 Nov 2026 it was a `204` without effect.
- **The natural key** is `(orderId, slug, externalRef)`: a second upsert with the same key updates the line, never
  doubles it. `source = slug` — taken from the token, never from the body; a module never touches another module's
  lines nor the manual ones. The write runs through `order.before/after.update`.
- **The checks, in order:** the common guard (the token's slug, `capabilities.services` naming the port, the context and
  its proof, the module live for the tenant) → the port published → `ORDER_SERVICES` delivered (else
  **`422 not-delivered`**, permanent, before any check of the content) → the user of the context holds
  `api.v1.orders.put` (a `system` context writes no line: `403 insufficient-rights`) → the body (`400 validation`,
  `errors` by path: `orderId`, `entryId`, `name`, `cost`, `cost.amount`, `cost.currency`, `externalRef`) → the provider
  (**`501 no-provider`**, permanent, on a platform without one; since the provider is `OrderServiceLinesAdapter`). An unknown port is `404 not-found`. Audited
  `MODULE_SERVICE_CALL` (port, operation, `orderId`, `externalRef`).
- **Lines stay** when the module stops and when the order is cancelled (ORD§7): they are core data with their
  `source`; the cost does not disappear, no new line appears. On `CANCELLED` (observed through `order.field.changed` on
  `status`) the module compensates by itself: the platform runs no saga.
- **Answer F — consumption after the commit.** A module that consumes stock or another resource on an order event does
  it **after the commit, at-least-once, idempotently** (on `kfeventid` and on its own key), never inside the order's
  transaction.
- **Answer G — `COMPLETED`.** The terminal status of §16.2; a module observes it on `order.field.changed` (`status`) and
  on `workflow.flow-instance-completed` (`finalStatus`).
- **Delivered** with `ProtocolFeature.ORDER_SERVICES` (together with the provider). The SDKs: `OrderServices.upsert(ctx, line)` /
  `delete(ctx, orderId, externalRef)` (`kf.orderServices()`), checked locally; `422 not-delivered` is a
  `PlatformRefusal` of kind `refused`, `501 no-provider` of kind `unavailable`, and **`409 order-service-line-owned-by-module` of kind `conflict`** (`reason()` = `order-service-line-owned-by-module`, `OrderServices.LINE_OWNED_BY_MODULE`) (2026-11-10).

**Answer E — documents and evidence of a module on a step** are reserved in 2.0 (the confirmations stay the core's
`flow_step_confirmation`). `order.before.transition`, a task, a document and the promotion of a line are **closed** in
2.0: a module cannot open them (the promotion only through `workflow.flow-line-promoted`).

### 16.7 The reserved names of protocol 2.0 (public list)

The list of note. A name on it is part of the protocol, but a platform that does not deliver it
**refuses** it — at upload, approval, rollback and registration — with `422`, `class: permanent`, and **the text of the
key**, above the generic refusal (`unknown hook point`, `not a capability`, `unknown surface`; for the
hooks). Today **every name of the list is delivered**; the refusal stays the answer of
a platform before them:

| name | where | delivered by (`ProtocolFeature`) |
|---|---|---|
| `capabilities.flowRules` | manifest | `FLOW_RULES` — **delivered** |
| `capabilities.services` (`order.services.v1`) | manifest; the route of §16.6 (`422 not-delivered`) | `ORDER_SERVICES` — **delivered** |
| the surface `orderflow.step` | `capabilities.surfaces[].slug`; registration `surfaces[].slug` | `ORDERFLOW_STEP` — **delivered** |
| `workflow.flow-step-entered`, `workflow.flow-step-completed`, `workflow.flow-instance-completed`, `workflow.flow-line-promoted` | `capabilities.hooks[].point`; registration `hooks[].event` | `FLOW_EVENTS` — **delivered** |
| `capabilities.flows` | manifest — **a capability of protocol 2.1** (§17; the form fixed) | `MODULE_FLOWS` — **delivered** |

The text: `{what} is part of protocol 2.0 but this platform does not deliver it yet (reserved); remove it or
install on a platform that delivers it`, where `{what}` is `kf-module-conf.json: capabilities.flowRules` (or
`…services`, `…flows`), `kf-module-conf.json: capabilities.hooks[i].point "workflow.flow-step-entered"`,
`kf-module-conf.json: capabilities.surfaces[i].slug "orderflow.step"`, at registration `hooks[i]: event
"workflow.flow-step-completed"` / `surfaces[i].slug "orderflow.step"`. The hooks `order.*` are **not** reserved
(§16.2). There is no `ORDER_HOOKS`.

**Activation.** A reserved name is activated **only** by a kernel contributor: a business-core `@Component` that
implements `ProtocolFeatureDelivery` and answers `true` for its feature, shipped **in the same commit** as the core part
of the key (the engine, the adapter of `OrderServiceLines`, the mounting of `orderflow.step`) — never by a
configuration flag. A key declared and not applied cannot exist (fail-closed).

**What an SDK does.** The SDKs 0.10.0 check the **grammar** of these keys locally (`ManifestCheck`) and never refuse a
manifest because a platform does not deliver a key (as `storage` in 1.2): the platform is the gate. `capabilities.flows`
is a capability of protocol 2.1 (§17): the SDKs 0.10.0-dev check its grammar like the other keys of this
list and never refuse it because a platform does not deliver `MODULE_FLOWS`.

## 17. The module flows (`capabilities.flows`, protocol 2.1)

**State: delivered.** The wire form below is fixed and the SDKs speak it (SDK 0.10.0-dev); the platform
delivers it with `ProtocolFeature.MODULE_FLOWS` (the platform: the kernel contributor ships in the same
commit as the compilation and the seed, never by a configuration flag). A platform that does not deliver it refuses `capabilities.flows` at upload,
approval and rollback with `422`, `class: permanent` and the text of the key (§16.7):
`kf-module-conf.json: capabilities.flows is part of protocol 2.0 but this platform does not deliver it yet (reserved);
remove it or install on a platform that delivers it`..

### 17.1 The declaration

`capabilities.flows[] {key, file, name}` — a closed object, **`schemaVersion` 3 only**, at most **20** per module:

| key | rule | the platform's text (the SDKs use the same) |
|---|---|---|
| — | under `schemaVersion` 1–2 | `capabilities.flows requires "schemaVersion": 3 (this manifest is schemaVersion 2)` |
| — | at most 20 | `capabilities.flows holds at most 20 flows (got 21)` |
| — | a closed object | `capabilities.flows[i]: "bpmn" is not a key of a module flow (known: key, file, name)` |
| `key` | `^[a-z][a-z0-9-]{2,62}$`, unique among the module's flows | `capabilities.flows[i].key "Twin" must match ^[a-z][a-z0-9-]{2,62}$`, `… is declared twice` |
| `file` | a `.bpmn` file **directly** under `flows/` (`^flows/[a-z0-9][a-z0-9._-]{0,99}\.bpmn$`), without `..`, unique | `capabilities.flows[i].file "flows/../x" must not contain ".."`, `… must be a .bpmn file directly under flows/ (…)`, `… is declared twice` |
| `name` | a key of `i18n.en` (the flow's name in the lists) | `capabilities.flows[i].name "…" is not defined in i18n.en` |

The `key` is the key of the **global flow definition** the platform makes of the file. The platform does **not**
prefix it: it SHOULD start with the module's slug (`kf-3d-digital-twin`), and a key already held by a global flow of the
platform (`origin` `seed` or `platform`) or by another module's flow is refused at upload and approval:
`capabilities.flows[i].key "…" is already the key of a global flow (…): use a key that starts with the module's slug`.

### 17.2 The file

- A BPMN 2.0 XML document, UTF-8, at the path `file` from the root of the pack (`flows/<name>.bpmn`), at most
  **262144 bytes** (256 KiB). The author edits it in Camunda Modeler or in the platform's editor and exports it.
- The platform refuses at upload a declared file that the pack does not hold or that is larger than the cap:
  `capabilities.flows[i].file "flows/x.bpmn" is not in the pack`,
  `capabilities.flows[i].file "flows/x.bpmn" is larger than 262144 bytes (got 300000)`. The pack's own limits and its
  path checks (zip-slip) apply as for every file. A file under `flows/` that no flow declares is ignored.
- The files are part of the signed tree (§14.2): a flow cannot change without a new signed version.

### 17.3 The content: the platform's flow vocabulary v1

A module flow is compiled by the **same compiler** as a flow uploaded in the editor, with the scope
`MODULE`. The document holds one `bpmn:process`; the DI (the diagram) is kept for the editor and carries no meaning.

| accepted | refused (each with its own message) |
|---|---|
| `startEvent`, `endEvent`, `userTask`, `serviceTask`, `exclusiveGateway`, `parallelGateway`, `callActivity` (only to `kf-fabricare`), the sequence flows; artefacts kept without meaning (`textAnnotation`, `association`, `group`), lanes ignored | `inclusiveGateway`, `complexGateway`, `eventBasedGateway`, `subProcess`, `transaction`, `adHocSubProcess`, `boundaryEvent`, `intermediateCatchEvent`, `intermediateThrowEvent`, generic `task`, `manualTask`, `scriptTask`, `businessRuleTask`, `sendTask`, `receiveTask` |

The keys `kf.*` live in `zeebe:properties`; the catalog is closed (a `kf.*` key outside it is an error; a property
without the `kf.` prefix is kept and ignored):

| key | on | values (v1) |
|---|---|---|
| `kf.requiresModule` | `bpmn:process` | slugs separated by spaces; **mandatory on a module flow and MUST include the module's own slug**; other slugs are modules the flow also needs (SHOULD be in `requires`) |
| `kf.stage` | userTask, serviceTask, callActivity (mandatory); gateway (optional) | `development`, `sampling`, `production` |
| `kf.surface` | userTask | `models.detail.tabs/<tab>` of the surfaces catalog v1 (`overview`, `composition`, `sizing`, `consumption`, `3d-studio`, `qa`, `docs-notes`, `cost-impact`, `bill-of-labour`), or `models.detail.tabs/<slug>:<key>` only for a slug named in `kf.requiresModule` |
| `kf.wizard` | userTask (intake steps, before the first step with `kf.surface`) | a list from `scope lines work quantities review`, no duplicate |
| `kf.behavesLike` | userTask, callActivity | `DRAFT`, `CONFIRMED`, `IN_PRODUCTION`, `SHIPPED` |
| `kf.assignee` | userTask, gateway | `role:<ROLE>`, an existing role |
| `kf.approval`, `kf.approvalRoles` | userTask | `^[a-z][a-z0-9-]{1,40}$`; existing roles, separated by commas |
| `kf.mode` | userTask | `rules+confirmations` |
| `kf.choice` | exclusiveGateway | `manual`, or `rule:<key>` of a rule the platform knows |
| `kf.rules` | userTask, callActivity | keys of rules **the platform's rule catalog knows** (separated by spaces); an unknown key refuses the flow. A module's own advance rules are `capabilities.flowRules` (§16.4) |
| `kf.action` | userTask | `promote` (at most one, on the `sampling` stage) |

The normative list of the vocabulary and of its messages is the platform's (`FlowVocabulary`, the compiler); a new element or key takes a new vocabulary version written in the compiled graph, so a published flow stays
readable.

### 17.4 What the platform does

- **Upload, approval and rollback**: the form (§17.1), the files (§17.2), then each file is **compiled**
  with the scope `MODULE` — the structure and the catalog (including `kf.rules` and `kf.requiresModule`). Any finding
  refuses the version: `422`, `class: permanent`, one line per finding,
  `capabilities.flows[i] (flows/x.bpmn): <code> <message>` (the codes `WF-E-*` of the compiler). So the seed at install
  finds only flows that compile.
- **Install, upgrade, rollback, repair** (the version becomes the installed one): each declared flow becomes
  a **global** flow definition — `tenant_id` absent, `origin = module`, `source_ref = module:<slug>`, the name from
  `name` — with a **new published version only when the file's sha-256 differs** from the latest one (idempotent: the
  same version installed again changes nothing). A flow the new version no longer declares is **archived**.
- **Visibility:** a tenant sees a module flow only while the module is active in it (the listing filters on the active
  modules, I6); it may start an order on it and **personalise it by fork** (a copy under the tenant, as for the
  platform's global flows). The platform's administrator reads and exports it but **cannot edit it**: the module's
  pack is its only source.
- **Uninstall:** the module's flows are **archived**, never deleted; a new install of the module brings them back
  (a new version only when the file changed). The flow instances already started keep running on their own snapshot of
  the definition; a step whose module is gone is blocked fail-closed (§16.4, answer B).

### 17.5 What an SDK does

The SDKs 0.10.0 check `capabilities.flows` locally with the texts of §17.1 (`ManifestCheck.validate`) and, given the
pack's file list, the presence and the size of each file (§17.2, `ManifestCheck.checkPack(manifest, files)`). They never
parse the BPMN (the platform compiles it) and never refuse the key because a platform does not deliver `MODULE_FLOWS`.
The typed read is `Manifest.FlowCapability(key, file, name)`. The fixtures: `conformance/manifest/flows-*`.
