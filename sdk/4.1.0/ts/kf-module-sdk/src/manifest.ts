// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java. Do not edit by hand: fix Java, then re-derive.
import { readFileSync } from 'node:fs';

/**
 * One declared feature flag; `default` is false when omitted.
 * `requiresModules`: protocol 2.0 (`schemaVersion` 3): the flag is on for a tenant only while each of
 * these modules (declared in `requires`) is live there; never null (`[]`, the shape before SDK 0.10.0).
 */
export interface FeatureFlag {
  readonly key: string | null;
  readonly label: string | null;
  readonly description: string | null;
  readonly defaultValue: boolean;
  readonly requiresModules: readonly string[];
}

/**
 * One declared dependency. `optional` (protocol 2.0, `schemaVersion` 3): an optional dependency
 * blocks nothing in either direction -- absent, stopped or outside its range it counts as absent; a platform before
 * protocol 2.0 reads every dependency as mandatory. Absent or not `true` = mandatory (`false`, the shape before SDK
 * 0.10.0).
 */
export interface Requirement {
  readonly slug: string | null;
  readonly versionRange: string | null;
  readonly optional: boolean;
}

/**
 * A field on an extensible entity (`model`, `order`, `customer`).
 * `editable`: whether the HOST may render the field generically, when no live contribution of the module is mounted on
 * the form; absent = `false`. A rule of rendering, not of writing: a field that is not
 * editable is still written by the module's own contribution, a hook patch or the module's API.
 */
export interface FieldCapability {
  readonly entity: string | null;
  readonly name: string | null;
  readonly type: string | null;
  readonly table: string | null;
  readonly label: string | null;
  readonly editable: boolean;
}

/** A hook point; `onFailure` `allow` (default) or `block`, only on before points. */
export interface HookCapability {
  readonly point: string | null;
  readonly onFailure: string | null;
}

/**
 * An API route the module serves under `/m/{slug}/`.
 * `roles`: global role names the right is proposed for; never null, absent = `[]`.
 * `hookable`: whether a platform hook may call it as the system; absent = `false`.
 * `admin`: protocol 1.1: `tenant` (the platform also binds the right to TENANT_ADMIN in the tenants where
 * the module is active) or `platform` (`roles` empty: only the platform administrator holds it); never on a `hookable`
 * route; `null` when absent.
 * `timeoutSeconds`: SDK 0.7.3: how long the platform waits for the module's answer on this
 * route, 1 to `Manifest.MAX_ROUTE_TIMEOUT_SECONDS` seconds; `null` when absent (the platform's default, 5 s).
 */
export interface RouteCapability {
  readonly method: string | null;
  readonly path: string | null;
  readonly right: string | null;
  readonly roles: readonly string[];
  readonly hookable: boolean;
  readonly admin: string | null;
  readonly timeoutSeconds: number | null;
  /**
   * Protocol 1.2: `Manifest.ROUTE_AUDIENCE_MEMBER` -- any member of the active tenant calls the
   * route, with any role (the rights filter checks the membership and skips the match of the right); never with `roles`,
   * `admin` or `hookable`; `null` when absent (an ordinary route).
   */
  readonly audience: string | null;
  /**
   * Protocol 1.2: the route is also served on the customer portal, at `/api/customer/v1/m/{slug}{rest of the path}`,
   * with the customer's tenant; only on `audience: member`; absent = `false`.
   */
  readonly portal: boolean;
}

/**
 * The module's storage of files through the platform (protocol 1.2; sdk-protocol.md §15.7):
 * the platform presigns an upload under `kf-modules/{slug}/{tenant}/` and a download of a key there, and purges the
 * prefix when the tenant is deleted. At most `Manifest.MAX_STORAGE_ELEMENTS` element.
 * `contentTypes`: the types the module uploads: 1 to `Manifest.MAX_STORAGE_CONTENT_TYPES` distinct `type/subtype`
 * (`Manifest.STORAGE_CONTENT_TYPE`), never a wildcard; never null.
 * `maxBytes`: the largest object, 1 to `Manifest.MAX_STORAGE_BYTES` bytes; `null` when absent or not a whole number that
 * fits a 32-bit int (the check reports it).
 */
export interface StorageCapability {
  readonly contentTypes: readonly string[];
  readonly maxBytes: number | null;
}

/**
 * How a module is activated (protocol 1.2; the top-level `activation`): per tenant (`"tenant"`,
 * the default when absent or `null`) or on the whole platform (`"platform"`: live in every tenant, existing and new,
 * while it is installed with an active version, started, not tampered and not deactivated; no per-tenant activation is
 * asked for or possible). The value is the one of the manifest.
 */
export const Activation = Object.freeze({ TENANT: 'tenant', PLATFORM: 'platform' } as const);
export type Activation = (typeof Activation)[keyof typeof Activation];

/** @internal `activation` as read (protocol 1.2): absent, `null` or not a known value = `tenant`. */
export function activation(value: unknown): Activation {
  return value === Activation.PLATFORM ? Activation.PLATFORM : Activation.TENANT;
}

/**
 * A UI surface the module contributes to.
 * `page`: protocol 1.1: on a surface of type `page` (the host's catalog), the module page opened
 * at `/m/{slug}/{page}`, `^[a-z][a-z0-9-]{0,40}$`; `null` when absent.
 * `key`: protocol 2.0 (§16.5): on `orderflow.step`, the key of the step the contribution renders (mandatory there);
 * `null` when absent.
 */
export interface SurfaceCapability {
  readonly slug: string | null;
  readonly kind: string | null;
  readonly label: string | null;
  readonly page: string | null;
  readonly key: string | null;
}

/** A notification the module may send. */
export interface NotificationCapability {
  readonly key: string | null;
  readonly variables: readonly string[];
  readonly channels: readonly string[];
  readonly subject: string | null;
  readonly body: string | null;
}

/** A read through a port the platform publishes. */
export interface ReadCapability {
  readonly port: string | null;
  readonly access: string | null;
}

/**
 * One access to the platform's audit trail: `write` (rows in the request's tenant, attributed to the
 * module) or `read-own` (only the module's own rows). Declared in protocol 1.0, implemented by the platform (`AuditClient`).
 */
export interface AuditCapability {
  readonly access: string | null;
}

/**
 * One menu entry of the module (protocol 1.1): the platform composes it into the menu of
 * `surface` while the module is live there, under the public key `m.{slug}.{key}`.
 * `surface`: `staff` (the sidebar), `customer` (the portal) or `platform` (`/setup`).
 * `group`: the key of a group of that menu (the platform's list, checked at upload); the SDK checks only the shape
 * `^[a-z][a-z0-9-]{0,40}$`.
 * `key`: `^[a-z][a-z0-9-]{0,40}$`, unique per module and surface.
 * `label`: a key of `i18n.en`, resolved by the host in the user's language.
 * `url`: an in-app path: starts with `/`, without `//`, `..`, `%`, a backslash, whitespace, a scheme, a query or a
 * fragment.
 * `icon`: optional, the name of an icon of the host's registry (PascalCase); `null` when absent.
 * `right`: optional, the right of one of the module's own routes or a code the platform declares; never on `customer`;
 * `null` when absent. Presentation only: the target route keeps its own gate.
 * `order`: 0 to 10000, `Manifest.NAV_DEFAULT_ORDER` (100) when absent.
 * `flag`: optional, one of the module's `featureFlags`: the entry is shown only while it is on; `null` when absent.
 */
export interface NavCapability {
  readonly surface: string | null;
  readonly group: string | null;
  readonly key: string | null;
  readonly label: string | null;
  readonly url: string | null;
  readonly icon: string | null;
  readonly right: string | null;
  readonly order: number;
  readonly flag: string | null;
}

/**
 * One setting of the module (protocol 1.1): a closed scalar the TENANT_ADMIN edits
 * per tenant in the module's area, read by the module with `GET .../settings`. Settings carry no data: `BOOLEAN`,
 * `NUMBER` or `ENUM` only (no free text, list, reference or secret); data lives on surfaces.
 * `key`: `^[a-z][a-z0-9-]{1,40}$`, unique.
 * `type`: `BOOLEAN`, `NUMBER` or `ENUM`.
 * `defaultValue`: the JSON `default`: a boolean, a number in `min..max`, or one of `values`.
 * `label`: a key of `i18n.en`.
 * `description`: optional, a key of `i18n.en`; `null` when absent.
 * `values`: the closed values of an `ENUM`; `[]` otherwise.
 * `min`: optional lower bound of a `NUMBER`; `null` when absent.
 * `max`: optional upper bound of a `NUMBER`; `null` when absent.
 */
export interface SettingCapability {
  readonly key: string | null;
  readonly type: string | null;
  readonly defaultValue: unknown;
  readonly label: string | null;
  readonly description: string | null;
  readonly values: readonly string[];
  readonly min: number | null;
  readonly max: number | null;
}

/**
 * One secret of the module (protocol 1.1): the manifest names it, never its value. The
 * platform stores the value in its vault under the module's path and gives the module a `vault://` reference.
 * `name`: `^[a-z][a-z0-9-]{1,40}$`, unique.
 * `scope`: `platform` (one value) or `tenant` (one value per tenant).
 * `writeRight`: the right of one of the module's routes; a `platform` secret only through a route with
 * `admin: platform`.
 * `external`: the prefixes (`platform/aas/`) under which the module may keep an EXISTING reference `vault://<prefix>…`
 * for this secret instead of a value it writes; the platform resolves it and audits every resolve (SDK 0.7.2); only on a `platform` secret; absent = `[]`.
 */
export interface SecretCapability {
  readonly name: string | null;
  readonly scope: string | null;
  readonly writeRight: string | null;
  readonly external: readonly string[];
}

/**
 * One read the module serves to the platform (protocol 1.1): the platform calls `path` (a GET
 * route of the module) with the actor `{type: system, via: platform}`.
 * `port`: a port of the platform's catalog of provided reads (`Manifest.PORT_ID`), e.g. `factory.stations.v1`.
 * `path`: the path of a GET route this module declares.
 */
export interface ProvideCapability {
  readonly port: string | null;
  readonly path: string | null;
}

/**
 * The kind of a slot (protocol 2.0, sdk-protocol.md §7.12): `A` UI panels, `B` data layers rendered by the owner,
 * `D` events and commands in the page, `R` catalog resources (data only). There is no kind C (foreign code in the
 * owner's WebGL context).
 */
export class SlotKind {
  static readonly A = new SlotKind('A', ['extension', 'tab', 'action'], ['publicMembers', 'actions']);
  static readonly B = new SlotKind('B', ['layer'], ['layerKinds']);
  static readonly D = new SlotKind('D', ['events', 'commands'], ['events', 'commands']);
  static readonly R = new SlotKind('R', ['resource'], ['dataSchema']);

  private constructor(
    /** The kind's name, the value in the manifest. */
    readonly name: 'A' | 'B' | 'D' | 'R',
    /** The types a slot of this kind may declare. */
    readonly types: readonly string[],
    /** The keys only this kind may carry. */
    readonly ownKeys: readonly string[],
  ) {
    Object.freeze(types);
    Object.freeze(ownKeys);
  }

  /** Every kind, in the declared order. */
  static values(): readonly SlotKind[] {
    return [SlotKind.A, SlotKind.B, SlotKind.D, SlotKind.R];
  }

  /** The kind of a manifest value; `null` for a value that is not one (the check reports it). */
  static of(value: unknown): SlotKind | null {
    return SlotKind.values().find((k) => k.name === value) ?? null;
  }

  toString(): string {
    return this.name;
  }

  /** The value in the manifest (what a JSON view of the typed manifest shows). */
  toJSON(): string {
    return this.name;
  }
}

/**
 * Who may contribute to a slot (protocol 2.0), chosen by the owner, without a default: `internal` only the modules
 * of the class `KF_RELEASE`, `public` any module that requires the owner. The value is the one of the manifest.
 */
export const SlotVisibility = Object.freeze({ INTERNAL: 'internal', PUBLIC: 'public' } as const);
export type SlotVisibility = (typeof SlotVisibility)[keyof typeof SlotVisibility];

/** @internal The visibility of a manifest value; `null` for a value that is not one (the check reports it). */
export function slotVisibility(value: unknown): SlotVisibility | null {
  return value === SlotVisibility.INTERNAL || value === SlotVisibility.PUBLIC ? value : null;
}

/**
 * One slot the module defines for other modules (protocol 2.0, `schemaVersion` 3 only, sdk-protocol.md §7.12,
 *). A contributor declares `surfaces[].slug = "m.{owner}.{key}"`.
 * - `key`: the versioned key (`Manifest.SLOT_KEY`), the major as the last segment
 * - `kind`: A, B, D or R; `null` when absent or unknown (the check reports it)
 * - `type`: one of the kind's types (`SlotKind.types`)
 * - `visibility`: `internal` or `public`; `null` when absent (the check refuses it)
 * - `label`: optional, a key of `i18n.en`; `null` when absent
 * - `publicMembers`: A: the `ctx` members shown to the contributor; never null
 * - `actions`: A: the actions given to the contributor; never null
 * - `layerKinds`: B: the layer kinds the owner renders; never null
 * - `events`: D (`type: events`): the event names; never null
 * - `commands`: D (`type: commands`): the command names; never null
 * - `dataSchema`: R: the schema id `m.{owner}.{name}.v{N}` the owner validates the data against; `null` otherwise. Not
 *   `schema`: that key is forbidden in a manifest (§3.3)
 */
export interface SlotCapability {
  readonly key: string | null;
  readonly kind: SlotKind | null;
  readonly type: string | null;
  readonly visibility: SlotVisibility | null;
  readonly label: string | null;
  readonly publicMembers: readonly string[];
  readonly actions: readonly string[];
  readonly layerKinds: readonly string[];
  readonly events: readonly string[];
  readonly commands: readonly string[];
  readonly dataSchema: string | null;
}

/**
 * A read this module OFFERS to other modules (protocol 2.0, `capabilities.peerReads[]`, sdk-protocol.md §15.9).
 *
 * - `port`: `m.{self}.{name}.v{N}`
 * - `path`: a `GET` route this manifest declares
 * - `visibility`: `internal` (the internal class only) or `public`
 */
export interface PeerReadCapability {
  readonly port: string | null;
  readonly path: string | null;
  readonly visibility: string | null;
}

/** A command this module OFFERS (`capabilities.commands[]`, §15.9): `method path` a declared route. */
export interface CommandCapability {
  readonly name: string | null;
  readonly path: string | null;
  readonly method: string | null;
  readonly visibility: string | null;
}

/** An event this module EMITS (`capabilities.events[]`, §6.3): the point `m.{self}.after.{name}`. */
export interface EventCapability {
  readonly name: string | null;
  readonly visibility: string | null;
}

/**
 * A rule of a module step in the order flow (protocol 2.0, `capabilities.flowRules[]`, sdk-protocol.md §16.4): the engine calls `GET {path}?<order.flow-rule.v1>` before the step advances.
 * `key` `^[a-z][a-z0-9-]{1,40}$`, unique; `path` a `GET` route this manifest declares; `timeoutSeconds` 1..60, `null` =
 * the default 2 s ({@link Manifest.effectiveTimeoutSeconds}); `projection` `order.flow-rule.v1`.
 */
export interface FlowRuleCapability {
  readonly key: string | null;
  readonly path: string | null;
  readonly timeoutSeconds: number | null;
  readonly projection: string | null;
}

/** A platform service this module calls (`capabilities.services[]`, §16.6): `order.services.v1`. */
export interface ServiceCapability {
  readonly port: string | null;
}

/**
 * A baseline flow this module brings (protocol 2.1, `capabilities.flows[]`, sdk-protocol.md §17): a BPMN
 * file in the pack that the platform compiles at upload and approval and publishes as a global flow (`origin = module`)
 * at install and upgrade, visible to the tenants where the module is active.
 * `key` `^[a-z][a-z0-9-]{2,62}$`, unique; the key of the global definition (the platform does not prefix it, so it
 * SHOULD start with the module's slug); `file` `flows/<name>.bpmn`, directly under `flows/`, at most 262144 bytes;
 * `name` a key of `i18n.en`: the flow's name.
 */
export interface FlowCapability {
  readonly key: string | null;
  readonly file: string | null;
  readonly name: string | null;
}

/** What this module CALLS of another one (`capabilities.peers[]`, §15.9); the owner is in `requires`. */
export interface PeerUseCapability {
  readonly owner: string | null;
  readonly reads: readonly string[];
  readonly commands: readonly string[];
}

/** The `capabilities` block; every list is never null. */
export interface Capabilities {
  readonly fields: readonly FieldCapability[];
  readonly hooks: readonly HookCapability[];
  readonly routes: readonly RouteCapability[];
  readonly surfaces: readonly SurfaceCapability[];
  readonly notifications: readonly NotificationCapability[];
  readonly reads: readonly ReadCapability[];
  readonly audit: readonly AuditCapability[];
  readonly nav: readonly NavCapability[];
  readonly settings: readonly SettingCapability[];
  readonly secrets: readonly SecretCapability[];
  readonly provides: readonly ProvideCapability[];
  readonly storage: readonly StorageCapability[];
  /** Protocol 2.0: the slots the module defines; `[]` when absent. */
  readonly slots: readonly SlotCapability[];
  /** Protocol 2.0: the reads offered to other modules; `[]` when absent. */
  readonly peerReads: readonly PeerReadCapability[];
  /** Protocol 2.0: the commands offered to other modules; `[]` when absent. */
  readonly commands: readonly CommandCapability[];
  /** Protocol 2.0: the events this module emits; `[]` when absent. */
  readonly events: readonly EventCapability[];
  /** Protocol 2.0: what this module calls of other modules; `[]` when absent. */
  readonly peers: readonly PeerUseCapability[];
  /** Protocol 2.0 (§16.4): the rules of the module's steps in the order flow; `[]` when absent. */
  readonly flowRules: readonly FlowRuleCapability[];
  /** Protocol 2.0 (§16.6): the platform services the module calls; `[]` when absent. */
  readonly services: readonly ServiceCapability[];
  /** Protocol 2.1 (§17): the module's baseline flows; `[]` when absent. */
  readonly flows: readonly FlowCapability[];
}

type Tree = Record<string, unknown>;

/**
 * The typed `kf-module-conf.json` (manifest v2; `schemaVersion` 2):
 *
 *   {
 *     "schemaVersion": 2, "slug": "api-test", "name": "module.name", "description": "module.description",
 *     "version": "0.5.0", "rollbackFloor": "0.4.0", "platform": ">=4.1",
 *     "featureFlags": [ { "key": "author-tab", "label": "flag.author-tab.label", "default": false } ],
 *     "requires": [ { "slug": "other", "versionRange": ">=1.0.0" } ],
 *     "i18n": { "en": { "module.name": "API Test", ... }, "ro": { ... } },
 *     "capabilities": {
 *       "fields":        [ { "entity": "order", "name": "priority_code", "type": "STRING", "table": "orders_extra", "label": "field.priority", "editable": true } ],
 *       "hooks":         [ { "point": "model.before.update", "onFailure": "block" } ],
 *       "routes":        [ { "method": "POST", "path": "/m/api-test/api/notes", "right": "api.v1.m.api-test.notes.post", "roles": ["QC"], "hookable": true },
 *                          { "method": "PUT", "path": "/m/api-test/admin/keys", "right": "api.v1.m.api-test.admin.keys.put", "admin": "platform" } ],
 *       "surfaces":      [ { "slug": "models.detail.tabs", "kind": "react", "label": "tab.label" } ],
 *       "notifications": [ { "key": "author-changed", "variables": ["modelName"], "channels": ["in_app"], "subject": "n.s", "body": "n.b" } ],
 *       "reads":         [ { "port": "<published id>" } ],
 *       "audit":         [ { "access": "write" }, { "access": "read-own" } ],
 *       "nav":           [ { "surface": "staff", "group": "admin-utils", "key": "notes", "label": "nav.notes", "url": "/modules", "icon": "Boxes", "right": "api.v1.m.api-test.notes.post", "order": 20, "flag": "author-tab" } ],
 *       "settings":      [ { "key": "push-on-save", "type": "BOOLEAN", "default": false, "label": "setting.push" } ],
 *       "secrets":       [ { "name": "r3-client-secret", "scope": "platform", "writeRight": "api.v1.m.api-test.admin.keys.put", "external": ["platform/aas/"] } ],
 *       "provides":      [ { "port": "factory.stations.v1", "path": "/m/api-test/stations" } ],
 *       "storage":       [ { "contentTypes": ["image/jpeg", "image/png"], "maxBytes": 5242880 } ],
 *       "slots":         [ { "key": "viewport.layers.v1", "kind": "B", "type": "layer", "visibility": "public", "layerKinds": ["pin"] } ]
 *     }
 *   }
 *
 * **Reading is lenient, checking is not.** This class reads what it can and never throws on a shape error: an absent
 * list is `[]`, a non-list is `[]`, `roles` absent is `[]` (no role), `hookable` and `editable` absent or not a
 * boolean are `false`. Every shape error is reported by `ManifestCheck`, which works on the raw JSON, so a manifest the
 * check refuses never reaches the platform.
 *
 * Two capabilities are **declared in protocol 1.0** (operator decisions of 2026-10-19, DECIZII-2026-10-16):
 * - `routes[].hookable`: a route a platform hook may call as the system; boolean, default `false`, only
 *   on `POST`/`PUT`/`PATCH`. Delivered by the platform (the strict check at upload, the approval badge, the
 *   catalog of hookable routes and the dispatch); a platform before it ignores the key.
 * - `audit`: `[{"access": "write" | "read-own"}]`, each at most once. Declared in protocol 1.0, delivered by
 *   the platform (accepted at upload and shown at approval); an older platform refuses it as an
 *   unknown capability.
 *
 * **Added in protocol 1.1** (additive, no new `schemaVersion`): `nav`, the module's menu
 * entries on the staff sidebar, the customer portal menu or the platform menu (`NavCapability`). An older platform refuses the key at upload (an unknown capability).
 *
 * **Added in protocol 1.1** (additive, delivered by the platform in the plan
 * named for each, sdk-protocol.md §15): `settings` (`SettingCapability`: closed scalars only, the rule "settings carry
 * no data"), `routes[].admin` (`RouteCapability.admin`), `secrets` (`SecretCapability`: names and
 * scopes, values only by reference), `provides` (`ProvideCapability`: reads the module serves to the platform) and
 * `surfaces[].page` (`SurfaceCapability.page`). A port id (`reads[].port`, `provides[].port`) matches
 * `Manifest.PORT_ID`. Absent keys read as `[]` / `null`: a 1.0 manifest reads unchanged.
 *
 * **Added in protocol 2.0** (`schemaVersion` 3, a strict superset of 2): `slots`
 * (`SlotCapability`: the slots a module defines for other modules, sdk-protocol.md §7.12). Refused under
 * `schemaVersion` 1–2 by `ManifestCheck`; absent reads as `[]`.
 */
export class Manifest {
  /** The capability keys this SDK knows, in the documented order (`audit`, delivered). */
  static readonly CAPABILITY_KEYS: readonly string[] = Object.freeze(['fields', 'hooks', 'routes', 'surfaces', 'notifications', 'reads', 'audit', 'nav', 'settings',
    'secrets', 'provides', 'storage', 'slots', 'peerReads', 'commands', 'events', 'peers', 'flowRules', 'services', 'flows']);

  /**
   * The keys of `capabilities.flowRules[]` (protocol 2.0, sdk-protocol.md §16.4; a closed object). Delivered by the
   * platform with `FLOW_RULES`; a platform before it refuses the key at upload. The SDK checks only the grammar
   * (the platform is the gate).
   */
  static readonly FLOW_RULE_KEYS: readonly string[] = Object.freeze(['key', 'path', 'timeoutSeconds', 'projection']);
  /** The keys of `capabilities.services[]` (protocol 2.0, §16.6; a closed object). */
  static readonly SERVICE_KEYS: readonly string[] = Object.freeze(['port']);
  /** The only projection of a flow rule in protocol 2.0 (§16.4): the major is in the key. */
  static readonly FLOW_RULE_PROJECTION = 'order.flow-rule.v1';
  /** The only port of `capabilities.services` in protocol 2.0 (§16.6). */
  static readonly SERVICES_PORT = 'order.services.v1';
  /** The longest a flow rule may take, in seconds (§16.4). */
  static readonly MAX_FLOW_RULE_TIMEOUT_SECONDS = 60;
  /** How long the engine waits for a flow rule that declares no `timeoutSeconds` (§16.4). */
  static readonly DEFAULT_FLOW_RULE_TIMEOUT_SECONDS = 2;
  /** The surface of a module step in the order flow (§16.5): its contribution names the step in `key`. */
  static readonly ORDERFLOW_STEP = 'orderflow.step';

  /**
   * The keys of `capabilities.flows[]` (protocol 2.1, sdk-protocol.md §17; a closed object): the module's
   * baseline flows, a BPMN file each, in the pack. Delivered by the platform with `MODULE_FLOWS`; until then a platform
   * refuses the key at upload (§16.7). The SDK checks only the grammar and, on a pack, the files.
   */
  static readonly FLOW_KEYS: readonly string[] = Object.freeze(['key', 'file', 'name']);
  /** The key of a module flow (§17.1): the key of the global flow definition the platform makes of it. */
  static readonly FLOW_KEY = '^[a-z][a-z0-9-]{2,62}$';
  /** The file of a module flow (§17.1): directly under `flows/` in the pack, a `.bpmn`. */
  static readonly FLOW_FILE = '^flows/[a-z0-9][a-z0-9._-]{0,99}\\.bpmn$';
  /** The directory of the module flows in the pack (§17.2). */
  static readonly FLOWS_DIRECTORY = 'flows/';
  /** The largest BPMN file of a module flow, in bytes (§17.2): 256 KiB. */
  static readonly MAX_FLOW_FILE_BYTES = 262_144;
  /** The most flows one module declares (§17.1). */
  static readonly MAX_FLOWS = 20;

  /** A flow rule's `timeoutSeconds`, or the default when absent. Java: `FlowRuleCapability.effectiveTimeoutSeconds()`. */
  static effectiveTimeoutSeconds(rule: FlowRuleCapability): number {
    return rule.timeoutSeconds == null ? Manifest.DEFAULT_FLOW_RULE_TIMEOUT_SECONDS : rule.timeoutSeconds;
  }

  /** The visibilities of a port, a command or an event offered to other modules (protocol 2.0, required). */
  static readonly PEER_VISIBILITIES: readonly string[] = Object.freeze(['internal', 'public']);

  /** The methods of a command between modules (protocol 2.0, sdk-protocol.md §15.9). */
  static readonly COMMAND_METHODS: readonly string[] = Object.freeze(['POST', 'PUT', 'DELETE']);

  /** The keys of `capabilities.peerReads[]` (a closed object). */
  static readonly PEER_READ_KEYS: readonly string[] = Object.freeze(['port', 'path', 'visibility']);
  /** The keys of `capabilities.commands[]` (a closed object). */
  static readonly COMMAND_KEYS: readonly string[] = Object.freeze(['name', 'path', 'method', 'visibility']);
  /** The keys of `capabilities.events[]` (a closed object). */
  static readonly EVENT_KEYS: readonly string[] = Object.freeze(['name', 'visibility']);
  /** The keys of `capabilities.peers[]` (a closed object). */
  static readonly PEER_USE_KEYS: readonly string[] = Object.freeze(['owner', 'reads', 'commands']);

  /**
   * The grammar of a port id (protocol 1.1): an entity, one optional sub-segment, a major version
   * (`model.v1`, `file.public-url.v1`, `factory.stations.v1`).
   */
  static readonly PORT_ID = '^[a-z][a-z0-9-]{1,40}(\\.[a-z][a-z0-9-]{1,40})?\\.v[0-9]+$';

  /** The types of a setting: closed scalars only, settings carry no data (protocol 1.1). */
  static readonly SETTING_TYPES: readonly string[] = Object.freeze(['BOOLEAN', 'NUMBER', 'ENUM']);

  /** The keys of a setting: a closed object. */
  static readonly SETTING_KEYS: readonly string[] = Object.freeze(['key', 'type', 'default', 'label', 'description', 'values', 'min', 'max']);

  /** At most this many settings per module (protocol 1.1). */
  static readonly MAX_SETTINGS = 30;

  /** The values of `routes[].admin` (protocol 1.1). */
  static readonly ROUTE_ADMIN: readonly string[] = Object.freeze(['tenant', 'platform']);

  /**
   * The one value of `routes[].audience` (protocol 1.2): any member of the active tenant, with
   * any role, calls the route; absent = an ordinary route (the caller holds its right).
   */
  static readonly ROUTE_AUDIENCE_MEMBER = 'member';

  /** The keys of a `storage` element: a closed object (protocol 1.2). */
  static readonly STORAGE_KEYS: readonly string[] = Object.freeze(['contentTypes', 'maxBytes']);

  /** At most this many `storage` elements (protocol 1.2). */
  static readonly MAX_STORAGE_ELEMENTS = 1;

  /** At most this many content types in one `storage` element (protocol 1.2). */
  static readonly MAX_STORAGE_CONTENT_TYPES = 10;

  /** The largest object a module may declare in `storage[].maxBytes`: 10 MiB (protocol 1.2). */
  static readonly MAX_STORAGE_BYTES = 10_485_760;

  /**
   * One content type of `storage[].contentTypes` (protocol 1.2): `type/subtype` in lower case, the characters of an
   * RFC 6838 restricted name, without parameters and without a wildcard (`image/png`, `image/svg+xml`).
   */
  static readonly STORAGE_CONTENT_TYPE = '^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$';

  /**
   * The longest a route may ask the platform to wait for its answer (`routes[].timeoutSeconds`, SDK 0.7.3): a whole number of seconds from 1 to this cap; absent = the platform's default (5 s).
   */
  static readonly MAX_ROUTE_TIMEOUT_SECONDS = 60;

  /** The scopes of a secret (protocol 1.1). */
  static readonly SECRET_SCOPES: readonly string[] = Object.freeze(['platform', 'tenant']);

  /** The keys of a secret: a closed object (a value never travels in the manifest). */
  static readonly SECRET_KEYS: readonly string[] = Object.freeze(['name', 'scope', 'writeRight', 'external']);

  /**
   * One prefix of `secrets[].external` (SDK 0.7.2): a vault path -- the mount and at least one
   * segment more -- ending with `/` (`platform/aas/`).
   */
  static readonly SECRET_EXTERNAL_PREFIX: string = '^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$';

  /** At most this many prefixes in one secret's `external`. */
  static readonly SECRET_MAX_EXTERNAL: number = 5;

  /** The keys of a provided read: a closed object. */
  static readonly PROVIDE_KEYS: readonly string[] = Object.freeze(['port', 'path']);

  /** The menus a `nav` entry may join (protocol 1.1). */
  static readonly NAV_SURFACES: readonly string[] = Object.freeze(['staff', 'customer', 'platform']);

  /** The keys of a `nav` entry: a closed object. */
  static readonly NAV_KEYS: readonly string[] = Object.freeze(['surface', 'group', 'key', 'label', 'url', 'icon', 'right', 'order', 'flag']);

  /** The position of a `nav` entry in its group when `order` is absent (as `surfaces`, §7.7). */
  static readonly NAV_DEFAULT_ORDER = 100;

  /** The methods a `hookable` route may have. */
  static readonly HOOKABLE_METHODS: readonly string[] = Object.freeze(['POST', 'PUT', 'PATCH']);

  /** The values of `audit[].access`. */
  static readonly AUDIT_ACCESS: readonly string[] = Object.freeze(['write', 'read-own']);

  /**
   * The grammar of a slot key (protocol 2.0, sdk-protocol.md §7.12): dot-separated lower-case segments, the
   * major as the last segment (`viewport.layers.v1`); at most `Manifest.MAX_SLOT_KEY_LENGTH` characters. The public key
   * is `m.{owner}.{key}`.
   */
  static readonly SLOT_KEY = '^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*$';

  /** The longest slot key (protocol 2.0). */
  static readonly MAX_SLOT_KEY_LENGTH = 80;

  /** The keys of a slot: a closed object (protocol 2.0, §7.12). */
  static readonly SLOT_KEYS: readonly string[] = Object.freeze(['key', 'kind', 'type', 'visibility', 'label', 'publicMembers',
    'actions', 'layerKinds', 'events', 'commands', 'dataSchema']);

  /**
   * @param schemaVersion the declared `schemaVersion`, `1` when absent
   * @param slug          the module identity
   * @param name          the name; a translation key under `schemaVersion` 2
   * @param description   optional: what the module does, shown by the platform when present; the same
   *                      shape as `name` (a translation key under `schemaVersion` 2); `null` when absent
   * @param version       the module's semver
   * @param rollbackFloor optional: the oldest version of the module that runs correctly on the
   *                      data (the schema of the module's own database) this version leaves behind; a semver at or below
   *                      `version`, `null` when absent (or not a string). Version V declares `rollbackFloor = F` when
   *                      every version ≥ F of the module runs correctly on the data left by V; between F and V the
   *                      migrations of the module's own database are only additive (expand). The platform computes
   *                      H = the highest version ever installed and refuses a rollback to a version below
   *                      `rollbackFloor(H)`; when H declares no floor, the floor is H itself (sdk-protocol.md §3.1, §12)
   * @param author        optional
   * @param platform      the platform versions the module runs on (mandatory under 2)
   * @param featureFlags  the declared flags
   * @param requires      the modules this one depends on
   * @param i18n          `language -> (key -> text)`; `en` mandatory under 2
   * @param capabilities  what the module asks of the platform; never null
   * @param activation    protocol 1.2: per tenant (the default) or on the whole platform; never null
   */
  constructor(
    readonly schemaVersion: number,
    readonly slug: string | null,
    readonly name: string | null,
    readonly description: string | null,
    readonly version: string | null,
    readonly rollbackFloor: string | null,
    readonly author: string | null,
    readonly platform: string | null,
    readonly featureFlags: readonly FeatureFlag[],
    readonly requires: readonly Requirement[],
    readonly i18n: Readonly<Record<string, Readonly<Record<string, string>>>>,
    readonly capabilities: Capabilities,
    readonly activation: Activation = Activation.TENANT,
  ) {}

  /** Reads `kf-module-conf.json`. */
  static read(path: string): Manifest {
    return Manifest.parse(readFileSync(path, 'utf8'));
  }

  /**
   * Parses a manifest leniently (see the class comment).
   *
   * @throws Error when the text is not a JSON object at all
   */
  static parse(json: string): Manifest {
    return Manifest.of(Manifest.tree(json));
  }

  /** @internal The raw JSON tree, as `ManifestCheck` reads it. */
  static tree(json: string): Tree {
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch (e) {
      throw new Error(`kf-module-conf.json is not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
    }
    if (!isObject(parsed)) throw new Error('kf-module-conf.json must be a JSON object');
    return parsed;
  }

  /** @internal */
  static of(root: Tree): Manifest {
    const caps = map(root.capabilities);
    const capabilities: Capabilities = Object.freeze({
      fields: Object.freeze(objects(caps.fields).map((f) => Object.freeze({
        entity: text(f.entity), name: text(f.name), type: text(f.type), table: text(f.table), label: text(f.label),
        editable: f.editable === true,
      }))),
      hooks: Object.freeze(objects(caps.hooks).map((h) => Object.freeze({ point: text(h.point), onFailure: text(h.onFailure) }))),
      routes: Object.freeze(objects(caps.routes).map((r) => Object.freeze({
        method: text(r.method), path: text(r.path), right: text(r.right), roles: texts(r.roles), hookable: r.hookable === true,
        admin: text(r.admin), timeoutSeconds: wholeSeconds(r.timeoutSeconds), audience: text(r.audience),
        portal: r.portal === true,
      }))),
      surfaces: Object.freeze(objects(caps.surfaces).map((s) => Object.freeze({
        slug: text(s.slug), kind: text(s.kind), label: text(s.label), page: text(s.page), key: text(s.key),
      }))),
      notifications: Object.freeze(objects(caps.notifications).map((n) => Object.freeze({
        key: text(n.key), variables: texts(n.variables), channels: texts(n.channels), subject: text(n.subject), body: text(n.body),
      }))),
      reads: Object.freeze(objects(caps.reads).map((r) => Object.freeze({ port: text(r.port), access: text(r.access) }))),
      audit: Object.freeze(objects(caps.audit).map((a) => Object.freeze({ access: text(a.access) }))),
      nav: Object.freeze(objects(caps.nav).map((n) => Object.freeze({
        surface: text(n.surface), group: text(n.group), key: text(n.key), label: text(n.label), url: text(n.url),
        icon: text(n.icon), right: text(n.right),
        order: typeof n.order === 'number' ? Math.trunc(n.order) : Manifest.NAV_DEFAULT_ORDER, flag: text(n.flag),
      }))),
      settings: Object.freeze(objects(caps.settings).map((s) => Object.freeze({
        key: text(s.key), type: text(s.type), defaultValue: s.default === undefined ? null : s.default,
        label: text(s.label), description: text(s.description), values: texts(s.values),
        min: typeof s.min === 'number' ? s.min : null,
        max: typeof s.max === 'number' ? s.max : null,
      }))),
      secrets: Object.freeze(objects(caps.secrets).map((s) => Object.freeze({
        name: text(s.name), scope: text(s.scope), writeRight: text(s.writeRight), external: texts(s.external),
      }))),
      provides: Object.freeze(objects(caps.provides).map((p) => Object.freeze({ port: text(p.port), path: text(p.path) }))),
      storage: Object.freeze(objects(caps.storage).map((s) => Object.freeze({
        contentTypes: texts(s.contentTypes), maxBytes: wholeSeconds(s.maxBytes),
      }))),
      slots: Object.freeze(objects(caps.slots).map((s) => Object.freeze({
        key: text(s.key), kind: SlotKind.of(s.kind), type: text(s.type), visibility: slotVisibility(s.visibility),
        label: text(s.label), publicMembers: texts(s.publicMembers), actions: texts(s.actions),
        layerKinds: texts(s.layerKinds), events: texts(s.events), commands: texts(s.commands),
        dataSchema: text(s.dataSchema),
      }))),
      peerReads: Object.freeze(objects(caps.peerReads).map((p) => Object.freeze({
        port: text(p.port), path: text(p.path), visibility: text(p.visibility),
      }))),
      commands: Object.freeze(objects(caps.commands).map((c) => Object.freeze({
        name: text(c.name), path: text(c.path), method: text(c.method), visibility: text(c.visibility),
      }))),
      events: Object.freeze(objects(caps.events).map((e) => Object.freeze({ name: text(e.name), visibility: text(e.visibility) }))),
      peers: Object.freeze(objects(caps.peers).map((p) => Object.freeze({
        owner: text(p.owner), reads: texts(p.reads), commands: texts(p.commands),
      }))),
      flowRules: Object.freeze(objects(caps.flowRules).map((r) => Object.freeze({
        key: text(r.key), path: text(r.path), timeoutSeconds: wholeSeconds(r.timeoutSeconds), projection: text(r.projection),
      }))),
      services: Object.freeze(objects(caps.services).map((sv) => Object.freeze({ port: text(sv.port) }))),
      flows: Object.freeze(objects(caps.flows).map((f) => Object.freeze({
        key: text(f.key), file: text(f.file), name: text(f.name),
      }))),
    });
    const i18n: Record<string, Readonly<Record<string, string>>> = {};
    for (const [lang, entries] of Object.entries(map(root.i18n))) {
      const t: Record<string, string> = {};
      for (const [k, v] of Object.entries(map(entries))) if (typeof v === 'string') t[k] = v;
      i18n[lang] = Object.freeze(t);
    }
    return new Manifest(
      typeof root.schemaVersion === 'number' ? Math.trunc(root.schemaVersion) : 1,
      text(root.slug), text(root.name), text(root.description), text(root.version),
      text(root.rollbackFloor), text(root.author), text(root.platform),
      Object.freeze(objects(root.featureFlags).map((f) => Object.freeze({
        key: text(f.key), label: text(f.label), description: text(f.description), defaultValue: f.default === true,
        requiresModules: texts(f.requiresModules),
      }))),
      Object.freeze(objects(root.requires).map((r) => Object.freeze({
        slug: text(r.slug), versionRange: text(r.versionRange), optional: r.optional === true,
      }))),
      Object.freeze(i18n),
      capabilities,
      activation(root.activation),
    );
  }

  /** Whether the text fields are translation keys (`schemaVersion` 2 and above). */
  usesKeys(): boolean {
    return this.schemaVersion >= 2;
  }

  /** The `en` text of a key, or null. */
  enText(key: string | null): string | null {
    const en = this.i18n.en;
    return en == null || key == null ? null : (en[key] ?? null);
  }
}

// ---- lenient readers --------------------------------------------------------------------------------------

/** @internal */
export function isObject(o: unknown): o is Tree {
  return o !== null && typeof o === 'object' && !Array.isArray(o);
}

/** @internal */
export function map(o: unknown): Tree {
  return isObject(o) ? o : {};
}

/** @internal The objects of a list; a non-list is empty and non-object elements are skipped (the check reports both). */
export function objects(o: unknown): Tree[] {
  return Array.isArray(o) ? o.filter(isObject) : [];
}

/** @internal */
export function texts(o: unknown): readonly string[] {
  return Object.freeze(Array.isArray(o) ? o.filter((e): e is string => typeof e === 'string') : []);
}

/** @internal */
export function text(o: unknown): string | null {
  return typeof o === 'string' ? o : null;
}

/**
 * `routes[].timeoutSeconds` as read (SDK 0.7.3): a JSON whole number that fits a 32-bit int, else `null`. Its range is
 * `ManifestCheck`'s rule, not the reader's.
 * @internal
 */
export function wholeSeconds(o: unknown): number | null {
  return typeof o === 'number' && Number.isInteger(o) && o >= -2147483648 && o <= 2147483647 ? o : null;
}
