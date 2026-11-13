// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ManifestCheck.java. Do not edit by hand: fix Java, then re-derive.
import { type Capabilities, Manifest, SlotKind, isObject, map, objects, slotVisibility, text, texts, wholeSeconds } from './manifest.js';
import { FlowEvents } from './flowEvents.js';
import { MODULE_EVENT_POINT, MODULE_EVENT_PREFIX } from './hookEvents.js';
import { SdkVersion } from './sdkVersion.js';
import { SURFACE_TYPES } from './surfaces.generated.js';

const KEY = /^[a-z][a-z0-9_.-]{0,119}$/;
const LANGUAGE_CODE = /^[a-z]{2,3}([-_][A-Za-z0-9]{2,8})?$/;
const NAME = /^[a-z][a-z0-9_]{0,40}$/;
const SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;
const NOTIFICATION_KEY = /^[a-z][a-z0-9_-]{0,40}$/;
const RIGHT_SEGMENT = /^[a-z][a-z0-9-]{0,40}$/;
/** Semver 2.0 (the platform's `SemVer`): `major.minor.patch[-pre][+build]`, no leading zeros. */
const SEMVER = new RegExp('^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)'
  + '(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?'
  + '(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$');
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const FIELD_TYPES = ['STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'LIST'];
const SURFACE_KINDS = ['react', 'wc'];
const CHANNELS = ['in_app', 'email'];
const ON_FAILURE = ['allow', 'block'];
const FORBIDDEN_ROLES = new Set(['TENANT_ADMIN', 'SUPER_ADMIN']);
const DDL_KEYS = new Set(['migrations', 'ddl', 'sql', 'schema']);
const DICTIONARY_KEYS = new Set(['dictionary', 'translations', 'writes']);
const MAX_ROLES_PER_ROUTE = 10;
const MAX_ROLE_NAME_LENGTH = 100;
const MAX_TEXT_LENGTH = 4000;
/** The key of a `nav` entry and of its group (protocol 1.1). */
const NAV_KEY = /^[a-z][a-z0-9-]{0,40}$/;
/** An icon name of the host's registry (Lucide, PascalCase). */
const NAV_ICON = /^[A-Z][A-Za-z0-9]{0,60}$/;
/** The shape of a platform right code (`api.v1.customers.get`, `api.customer.v1.get`). */
const PLATFORM_RIGHT = /^api(\.[a-z0-9][a-z0-9-]*){2,10}$/;
const MAX_NAV_ORDER = 10000;
const MAX_NAV_URL_LENGTH = 255;
/** A port id (protocol 1.1). */
const PORT = new RegExp(Manifest.PORT_ID);
/** The key of a setting and the name of a secret (protocol 1.1). */
const SETTING_KEY = /^[a-z][a-z0-9-]{1,40}$/;
/** A value of an `ENUM` setting: a code, not a text. */
const ENUM_VALUE = /^[a-z0-9][a-z0-9_-]{0,40}$/;
/** One prefix of `secrets[].external` (SDK 0.7.2). */
const SECRET_EXTERNAL_PREFIX = new RegExp(Manifest.SECRET_EXTERNAL_PREFIX);
/** The page of a `page` surface, under `/m/{slug}/`. */
const PAGE = /^[a-z][a-z0-9-]{0,40}$/;
/** The surface type that takes a `page` (the platform's `ModuleManifestValidator.PAGE_SURFACE_TYPE`). */
const PAGE_SURFACE_TYPE = 'page';
const SETTINGS_RULE = 'settings carry no data: BOOLEAN, NUMBER or ENUM';
/** One content type of `storage[].contentTypes` (protocol 1.2). */
const STORAGE_CONTENT_TYPE = new RegExp(Manifest.STORAGE_CONTENT_TYPE);
/** The key of a slot (protocol 2.0, §7.12): the major as the last segment. */
const SLOT_KEY = new RegExp(Manifest.SLOT_KEY);
/** A layer kind of a B slot (protocol 2.0). */
const SLOT_LAYER_KIND = /^[a-z][a-z0-9-]{1,30}$/;
/** An action, an event or a command of a slot (protocol 2.0). */
const SLOT_NAME = /^[a-z][a-z0-9-]{1,40}$/;
/** A public `ctx` member of an A slot (protocol 2.0). */
const SLOT_MEMBER = /^[a-z][A-Za-z0-9]{0,40}$/;
/** What follows `m.{owner}.` in the `dataSchema` of an R slot: `{name}.v{N}`. */
const SLOT_SCHEMA_TAIL = /^[a-z][a-z0-9-]{0,40}\.v[1-9][0-9]*$/;
/** Java's `\s+` (the ASCII whitespace), which splits a version range into its bounds. */
const JAVA_WHITESPACE = /[ \t\n\x0B\f\r]+/;
/** Java's `Character.isWhitespace(c) || Character.isISOControl(c)`, as a character class. */
const WHITESPACE_OR_CONTROL = /[\u0000-\u0020\u007f-\u009f\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/;
const FILE = 'kf-module-conf.json';
const HOOKABLE_RULE = 'hookable only on POST, PUT or PATCH';

type Tree = Record<string, unknown>;

/**
 * What the check found.
 * - `errors`: blocking findings, nothing is sent
 * - `warnings`: non-blocking findings, logged
 */
export class ManifestCheckResult {
  readonly errors: readonly string[];
  readonly warnings: readonly string[];

  constructor(errors: readonly string[], warnings: readonly string[]) {
    this.errors = Object.freeze([...errors]);
    this.warnings = Object.freeze([...warnings]);
  }

  ok(): boolean {
    return this.errors.length === 0;
  }
}

/**
 * The local check of `kf-module-conf.json` and of the registration against it, BEFORE the first request.
 *
 * The same rules the platform applies at upload (`ModuleManifestValidator`) and at registration
 * (`ManifestCapabilityGuard`), minus everything that needs the platform's catalogs (the extensible entities,
 * the hook points, the surfaces, the global roles, the configured languages): those stay the platform's. A violation
 * found here is a **permanent** error: `KfModule.start()` sends nothing and does not retry, so a module author sees the
 * cause in the module's own log instead of a 422 in the platform's journal.
 *
 * Messages are in English, start with the path of the element and name the rule, like the platform's.
 *
 * Declared in protocol 1.0 (operator decisions of 2026-10-19):
 * - `routes[].hookable`: a boolean, only on `POST`/`PUT`/`PATCH`; a non-boolean value or `true` on another
 *   method is refused, naming the route. The platform's strict check, the approval badge, the catalog and the dispatch
 *   are delivered by the platform.
 * - `audit`: a list of `{"access": "write" | "read-own"}`, each value at most once. Delivered by the platform.
 *
 * The optional top-level `description` is checked like `name`: a key defined in `i18n.en`; absent or
 * `null` means none.
 *
 * The optional top-level `rollbackFloor` is a semver 2.0 version at or below `version`
 * (semver precedence, not text order); absent or `null` means none. It is checked under every `schemaVersion`: it does
 * not depend on `i18n` or `capabilities`.
 *
 * The capability `nav` (protocol 1.1) is checked as far as the manifest alone allows: the surface, the
 * keys, the label key, the url, the icon name, the order, the flag, and the right (never on `customer`; under
 * `api.v1.m.` only the right of one of the module's routes). The groups of a surface and the platform's declared codes
 * are the platform's catalogs: checked at upload.
 *
 * The keys added to protocol 1.1 are checked as far as the manifest alone allows: `settings`
 * (closed objects; `BOOLEAN`, `NUMBER` or `ENUM` only -- "settings carry no data"; the default coherent with the type,
 * `values` and `min..max`; unique keys; at most `Manifest.MAX_SETTINGS`), `routes[].admin` (`tenant` | `platform`; no
 * roles on `platform`; never on a `hookable` route), `routes[].timeoutSeconds` (SDK 0.7.3: a whole number, 1 to
 * `Manifest.MAX_ROUTE_TIMEOUT_SECONDS`), `secrets` (closed objects, never a value; unique names; the scope;
 * `writeRight` = the right of a declared route, of an `admin: platform` route for a platform secret), `provides` (closed
 * objects; the port grammar; `path` = a declared GET route) and `surfaces[].page` (its grammar). A port id, in `reads`
 * and `provides`, matches `Manifest.PORT_ID`. Which ports the platform publishes or consumes and the extensible
 * entities (`model_version`) stay the platform's catalogs: checked at upload.
 *
 * The type of a surface (SDK 0.7.1) is checked against the release list this SDK pins (`SURFACE_TYPES`,
 * generated from `sdk/catalog/surfaces.public.json`), with the platform's rule and messages (`ModuleManifestValidator`): `page` only on a surface of type `page`, always on one, and each page of a surface once. A slug the pinned
 * list does not know is not judged here (a platform newer than this SDK may declare it): the platform decides at upload.
 *
 * Protocol 2.0: `schemaVersion` 3 is accepted, a strict superset of 2. The key it adds here,
 * `capabilities.slots` (sdk-protocol.md §7.12), is refused under 1–2 (`requires "schemaVersion": 3`) and
 * checked as far as the manifest alone allows.
 *
 * Protocol 2.1 (sdk-protocol.md §17): `capabilities.flows` (schema 3 only; closed objects; the key, the
 * file directly under `flows/` ending in `.bpmn`, the name a key of `i18n.en`; at most `Manifest.MAX_FLOWS`).
 * `checkPack` adds what only the pack shows: each file present and at most `Manifest.MAX_FLOW_FILE_BYTES` bytes. The
 * BPMN itself is the platform's (it compiles it at upload and approval); whether a platform delivers `MODULE_FLOWS` is
 * its gate, never the SDK's.
 */
export const ManifestCheck = {
  /** Translation key grammar (the platform's `ModuleManifestValidator.KEY`). */
  KEY,

  /**
   * The manifest and the files of its pack (protocol 2.1, sdk-protocol.md §17.2): the manifest's own rules
   * (`validate`), then each `capabilities.flows[].file` present in the pack and at most `Manifest.MAX_FLOW_FILE_BYTES`
   * bytes -- the platform's upload rule, so a module author sees it before the upload (`check-pack`).
   *
   * @param packFiles the pack's files, by their path from the pack's root (`flows/x.bpmn`), with their size in bytes
   *                  (a `Map` or a plain object); null = an empty pack
   */
  checkPack(manifestJson: string, packFiles: ReadonlyMap<string, number> | Readonly<Record<string, number>> | null): ManifestCheckResult {
    const errors: string[] = [];
    const warnings: string[] = [];
    const root = parseOrReport(manifestJson, errors);
    if (root == null) return new ManifestCheckResult(errors, warnings);
    validateTree(root, new Set(), errors, warnings);
    const files = packFiles == null ? new Map<string, number>()
      : packFiles instanceof Map ? packFiles : new Map(Object.entries(packFiles));
    packFlowFiles(root, files, errors);
    return new ManifestCheckResult(errors, warnings);
  },

  /**
   * The manifest alone.
   *
   * @param knownLanguages the languages the platform serves, when the caller knows them; empty = unknown (no language
   *                       warning). A language outside a non-empty set is a WARNING, never an error
   */
  validate(manifestJson: string, knownLanguages: Iterable<string> = []): ManifestCheckResult {
    const errors: string[] = [];
    const warnings: string[] = [];
    const root = parseOrReport(manifestJson, errors);
    if (root != null) validateTree(root, new Set(knownLanguages), errors, warnings);
    return new ManifestCheckResult(errors, warnings);
  },

  /**
   * The manifest and the registration body (`KfModule.registrationBody()`): the manifest's own rules, then
   * registration ⊆ manifest (the platform's rule without its catalog) and every label key with an `en` text
   * (`en` mandatory).
   */
  check(manifestJson: string, registrationBody: Tree | null, knownLanguages: Iterable<string> = []): ManifestCheckResult {
    const errors: string[] = [];
    const warnings: string[] = [];
    const root = parseOrReport(manifestJson, errors);
    if (root == null) return new ManifestCheckResult(errors, warnings);
    validateTree(root, new Set(knownLanguages), errors, warnings);
    checkRegistration(Manifest.of(root), registrationBody ?? {}, errors);
    return new ManifestCheckResult(errors, warnings);
  },
} as const;

function parseOrReport(json: string, errors: string[]): Tree | null {
  try {
    return Manifest.tree(json);
  } catch (e) {
    errors.push(e instanceof Error ? e.message : String(e));
    return null;
  }
}

// ---- the manifest ----------------------------------------------------------------------------------------

function validateTree(root: Tree, knownLanguages: Set<string>, errors: string[], warnings: string[]): void {
  const rawVersion = root.schemaVersion;
  let schemaVersion: number;
  if (rawVersion == null) {
    schemaVersion = 1;
  } else if (typeof rawVersion === 'number' && Number.isInteger(rawVersion)) {
    schemaVersion = rawVersion;
  } else {
    errors.push(`${FILE}: "schemaVersion" must be an integer`);
    return;
  }
  if (schemaVersion > SdkVersion.PROTOCOL_SCHEMA_VERSION || schemaVersion < 1) {
    errors.push(`${FILE}: "schemaVersion" ${schemaVersion} is not supported by kf-module-sdk ${SdkVersion.SDK_VERSION}`
      + ` (supported: 1..${SdkVersion.PROTOCOL_SCHEMA_VERSION})`);
    return;
  }
  rollbackFloor(root, errors);
  activation(root, errors);
  // Protocol 2.0: requires and featureFlags[].requiresModules, under every schemaVersion.
  requires(root, schemaVersion, errors);
  flagRequiresModules(root, schemaVersion, errors);
  if (schemaVersion < 2) {
    for (const block of ['i18n', 'capabilities']) {
      if (has(root, block)) errors.push(`${FILE}: "${block}" requires "schemaVersion": 2 (this manifest is schemaVersion 1)`);
    }
    warnings.push(`${FILE}: schemaVersion 1 declares no capabilities; only the platform catalog limits the registration`
      + ' (upgrade to "schemaVersion": 2)');
    return;
  }

  const slug = text(root.slug);
  if (slug == null || !SLUG.test(slug)) {
    errors.push(`${FILE}: "slug" must match ${SLUG.source} (got ${quoted(root.slug)})`);
  }
  if (typeof root.platform !== 'string' || root.platform.trim() === '') {
    errors.push(`${FILE}: "platform" is required for "schemaVersion": 2 (e.g. ">=4.1")`);
  }
  forbiddenKeys(root, '', errors);
  const enKeys = i18n(root.i18n, knownLanguages, errors, warnings);

  if (typeof root.name === 'string' && root.name.trim() !== '') {
    requireKey(`${FILE}: "name"`, root.name, enKeys, errors);
  }
  // description: optional, the same shape as name; absent or null = none
  if (has(root, 'description') && root.description != null) {
    requireKey(`${FILE}: "description"`, text(root.description), enKeys, errors);
  }
  for (const flag of objects(root.featureFlags)) {
    const key = text(flag.key);
    const at = `${FILE}: featureFlags["${key}"]`;
    if (typeof flag.label === 'string' && flag.label.trim() !== '') requireKey(`${at}.label`, flag.label, enKeys, errors);
    if (has(flag, 'description') && flag.description != null) {
      requireKey(`${at}.description`, text(flag.description), enKeys, errors);
    }
  }

  if (!has(root, 'capabilities') || root.capabilities == null) return;
  if (!isObject(root.capabilities)) {
    errors.push(`${FILE}: "capabilities" must be an object`);
    return;
  }
  const caps = map(root.capabilities);
  for (const [key, value] of Object.entries(caps)) {
    const rule = forbiddenRule(key);
    if (rule != null) {
      errors.push(`${FILE}: capabilities.${key} is not allowed: ${rule}`);
    } else if (!Manifest.CAPABILITY_KEYS.includes(key)) {
      errors.push(`${FILE}: capabilities.${key} is not a capability this SDK knows (known: ${Manifest.CAPABILITY_KEYS.join(', ')})`);
    } else if (value != null && !Array.isArray(value)) {
      errors.push(`${FILE}: capabilities.${key} must be an array`);
    }
  }
  fields(list(caps.fields), enKeys, errors);
  hooks(root, schemaVersion, list(caps.hooks), errors);
  routes(slug, list(caps.routes), errors);
  surfaces(slug, root, schemaVersion, list(caps.surfaces), enKeys, errors);
  notifications(list(caps.notifications), enKeys, errors);
  reads(list(caps.reads), errors);
  audit(list(caps.audit), errors);
  nav(slug, root, list(caps.nav), enKeys, errors);
  settings(list(caps.settings), enKeys, errors);
  secrets(root, list(caps.secrets), errors);
  provides(root, list(caps.provides), errors);
  storage(caps.storage, errors);
  if (caps.slots != null) {
    if (schemaVersion < 3) {
      errors.push(`${FILE}: capabilities.slots requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
    } else {
      slots(slug, list(caps.slots), enKeys, errors);
    }
  }
  // Protocol 2.0 (the platform's rules and messages): between modules, schema 3 only.
  let betweenModules = false;
  for (const key of ['peerReads', 'commands', 'events', 'peers']) {
    if (caps[key] == null) continue;
    if (schemaVersion < 3) {
      errors.push(`${FILE}: capabilities.${key} requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
    } else {
      betweenModules = true;
    }
  }
  if (betweenModules) {
    peerReads(slug, root, list(caps.peerReads), errors);
    commands(root, list(caps.commands), errors);
    events(list(caps.events), errors);
    peers(slug, root, list(caps.peers), errors);
  }
  // Protocol 2.0 (§16): the keys of the order flow, schema 3 only, the platform's rules and messages.
  // Only the grammar: whether this platform delivers them is the platform's gate, never the SDK's.
  if (caps.flowRules != null) {
    if (schemaVersion < 3) {
      errors.push(`${FILE}: capabilities.flowRules requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
    } else {
      flowRules(root, list(caps.flowRules), errors);
    }
  }
  if (caps.services != null) {
    if (schemaVersion < 3) {
      errors.push(`${FILE}: capabilities.services requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
    } else {
      services(list(caps.services), errors);
    }
  }
  // Protocol 2.1 (§17): the module flows, schema 3 only, the platform's rules and messages.
  if (caps.flows != null) {
    if (schemaVersion < 3) {
      errors.push(`${FILE}: capabilities.flows requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
    } else {
      flows(list(caps.flows), enKeys, errors);
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------
// The module flows (protocol 2.1, sdk-protocol.md §17; the platform's ModuleManifestValidator)
// ---------------------------------------------------------------------------------------------------------------

/** The key of a module flow (§17.1). */
const FLOW_KEY = new RegExp(Manifest.FLOW_KEY);
/** The file of a module flow (§17.1). */
const FLOW_FILE = new RegExp(Manifest.FLOW_FILE);

/**
 * `capabilities.flows[]` (§17.1): closed objects; `key` unique, `Manifest.FLOW_KEY`; `file` unique, without `..`,
 * directly under `flows/` and ending in `.bpmn`; `name` a key of `i18n.en`; at most `Manifest.MAX_FLOWS`.
 */
function flows(items: unknown[], enKeys: Set<string>, errors: string[]): void {
  if (items.length > Manifest.MAX_FLOWS) {
    errors.push(`${FILE}: capabilities.flows holds at most ${Manifest.MAX_FLOWS} flows (got ${items.length})`);
  }
  const keys = new Set<string>();
  const files = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.flows[${i}]`;
    const f = closedObject(at, item, Manifest.FLOW_KEYS, 'a module flow', errors);
    if (f == null) return;
    const key = text(f.key);
    if (key == null || !FLOW_KEY.test(key)) {
      errors.push(`${at}.key ${quoted(f.key)} must match ${Manifest.FLOW_KEY}`);
    } else if (keys.has(key)) {
      errors.push(`${at}.key "${key}" is declared twice`);
    } else {
      keys.add(key);
    }
    const file = text(f.file);
    if (file != null && file.includes('..')) {
      errors.push(`${at}.file "${file}" must not contain ".."`);
    } else if (file == null || !FLOW_FILE.test(file)) {
      errors.push(`${at}.file ${quoted(f.file)} must be a .bpmn file directly under ${Manifest.FLOWS_DIRECTORY} (${Manifest.FLOW_FILE})`);
    } else if (files.has(file)) {
      errors.push(`${at}.file "${file}" is declared twice`);
    } else {
      files.add(file);
    }
    requireKey(`${at}.name`, text(f.name), enKeys, errors);
  });
}

/** `checkPack`: each well-formed `flows[].file` present in the pack and not over the cap (§17.2). */
function packFlowFiles(root: Tree, packFiles: ReadonlyMap<string, number>, errors: string[]): void {
  if (!isObject(root.capabilities)) return;
  const items = list(map(root.capabilities).flows);
  items.forEach((raw, i) => {
    if (!isObject(raw)) return;
    const file = text(map(raw).file);
    if (file == null || file.includes('..') || !FLOW_FILE.test(file)) return; // reported above
    const at = `${FILE}: capabilities.flows[${i}].file "${file}"`;
    const size = packFiles.get(file);
    if (size == null) {
      errors.push(`${at} is not in the pack`);
    } else if (size > Manifest.MAX_FLOW_FILE_BYTES) {
      errors.push(`${at} is larger than ${Manifest.MAX_FLOW_FILE_BYTES} bytes (got ${size})`);
    }
  });
}

// ---------------------------------------------------------------------------------------------------------------
// The order flow (protocol 2.0, sdk-protocol.md §16; the platform's ModuleManifestValidator)
// ---------------------------------------------------------------------------------------------------------------

/** The key of a step rendered by `orderflow.step` (§16.5) and of a flow rule (§16.4). */
const STEP_KEY = /^[a-z][a-z0-9-]{1,40}$/;

/**
 * `capabilities.flowRules[]` (§16.4): closed objects; `key` a name, unique; `path` a declared GET route;
 * `timeoutSeconds` 1..60 when present; `projection` = `order.flow-rule.v1`.
 */
/** The platform's refusal of a declared path with a `{param}` segment. */
function literalPath(at: string, path: string, errors: string[]): void {
  errors.push(`${at}.path ${quoted(path)} must be a route with literal segments only (no {param} segment)`);
}

function flowRules(root: Tree, items: unknown[], errors: string[]): void {
  const getPaths = declaredPaths(root, 'GET');
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.flowRules[${i}]`;
    const r = closedObject(at, item, Manifest.FLOW_RULE_KEYS, 'a flow rule', errors);
    if (r == null) return;
    const key = text(r.key);
    if (key == null || !PEER_NAME.test(key)) {
      errors.push(`${at}.key ${quoted(r.key)} must match ${PEER_NAME.source}`);
    } else if (seen.has(key)) {
      errors.push(`${at}.key "${key}" is declared twice`);
    } else {
      seen.add(key);
    }
    const path = text(r.path);
    if (path == null || !getPaths.has(path)) {
      errors.push(`${at}.path ${quoted(r.path)} is not a GET route this module declares (capabilities.routes[] with method GET)`);} else if (path.includes('{')) {
      literalPath(at, path, errors); //
    }
    if (r.timeoutSeconds != null) {
      const seconds = wholeSeconds(r.timeoutSeconds);
      if (seconds == null || seconds < 1 || seconds > Manifest.MAX_FLOW_RULE_TIMEOUT_SECONDS) {
        errors.push(`${at}.timeoutSeconds ${quoted(r.timeoutSeconds)} must be a whole number from 1 to ${Manifest.MAX_FLOW_RULE_TIMEOUT_SECONDS}`);
      }
    }
    if (r.projection !== Manifest.FLOW_RULE_PROJECTION) {
      errors.push(`${at}.projection ${quoted(r.projection)} must be "${Manifest.FLOW_RULE_PROJECTION}"`);
    }
  });
}

/** `capabilities.services[]` (§16.6): closed objects; `port` = `order.services.v1`, once. */
function services(items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.services[${i}]`;
    const sv = closedObject(at, item, Manifest.SERVICE_KEYS, 'a service', errors);
    if (sv == null) return;
    const port = text(sv.port);
    if (port !== Manifest.SERVICES_PORT) {
      errors.push(`${at}.port ${quoted(sv.port)} must be "${Manifest.SERVICES_PORT}"`);
    } else if (seen.has(port)) {
      errors.push(`${at}.port "${port}" is declared twice`);
    } else {
      seen.add(port);
    }
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Between modules (protocol 2.0, sdk-protocol.md §15.9 / §6.3; the platform's ModuleManifestValidator)
// ---------------------------------------------------------------------------------------------------------------

/** The name of a command or an event (and of a peer's command): `^[a-z][a-z0-9-]{1,40}$`. */
export const PEER_NAME = /^[a-z][a-z0-9-]{1,40}$/;
const PEER_PORT_TAIL = '[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*';

/** The grammar of a port offered by `owner`: `m.{owner}.{name}.v{N}`. */
export function peerPort(owner: string | null): RegExp {
  return new RegExp(`^m\\.${quoteRegExp(owner ?? '')}\\.${PEER_PORT_TAIL}$`);
}

/** Java's `Pattern.quote` in a pattern's text: `\Q…\E`, so the source reads like Java's. */
function quoteRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** The pattern's text as Java prints it (`Pattern.pattern()`): `^m\.\Qslug\E\.…$`. */
function javaPattern(owner: string): string {
  return `^m\\.\\Q${owner}\\E\\.${PEER_PORT_TAIL}$`;
}

function closedObject(at: string, raw: unknown, keys: readonly string[], what: string, errors: string[]): Tree | null {
  if (!isObject(raw)) {
    errors.push(`${at} must be an object`);
    return null;
  }
  const object = map(raw);
  for (const key of Object.keys(object)) {
    if (!keys.includes(key)) {
      errors.push(`${at}: "${key}" is not a key of ${what} (known: ${keys.join(', ')})`);
    }
  }
  return object;
}

function visibility(at: string, object: Tree, errors: string[]): void {
  const v = object.visibility;
  if (typeof v !== 'string' || !Manifest.PEER_VISIBILITIES.includes(v)) {
    errors.push(`${at}.visibility ${quoted(v)} must be one of ${Manifest.PEER_VISIBILITIES.join(', ')} (required, no default)`);
  }
}

/** The paths of the routes this manifest declares with `method`. */
function declaredPaths(root: Tree, method: string): Set<string> {
  const paths = new Set<string>();
  if (!isObject(root.capabilities)) return paths;
  for (const route of objects(map(root.capabilities).routes)) {
    if (method === text(route.method) && text(route.path) != null) paths.add(text(route.path) as string);
  }
  return paths;
}

function peerReads(slug: string | null, root: Tree, items: unknown[], errors: string[]): void {
  const own = peerPort(slug);
  const getPaths = declaredPaths(root, 'GET');
  const seen = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    const at = `${FILE}: capabilities.peerReads[${i}]`;
    const p = closedObject(at, items[i], Manifest.PEER_READ_KEYS, 'a peer read', errors);
    if (p == null) continue;
    const port = text(p.port);
    if (port == null || !own.test(port)) {
      errors.push(`${at}.port ${quoted(p.port)} must be m.${slug}.{name}.v{N} (must match ${javaPattern(slug ?? '')})`);
    } else if (seen.has(port)) {
      errors.push(`${at}.port "${port}" is declared twice`);
    } else {
      seen.add(port);
    }
    const path = text(p.path);
    if (path == null || !getPaths.has(path)) {
      errors.push(`${at}.path ${quoted(p.path)} is not a GET route this module declares (capabilities.routes[] with method GET)`);} else if (path.includes('{')) {
      literalPath(at, path, errors); //
    }
    visibility(at, p, errors);
  }
}

function commands(root: Tree, items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    const at = `${FILE}: capabilities.commands[${i}]`;
    const c = closedObject(at, items[i], Manifest.COMMAND_KEYS, 'a command', errors);
    if (c == null) continue;
    const name = text(c.name);
    if (name == null || !PEER_NAME.test(name)) {
      errors.push(`${at}.name ${quoted(c.name)} must match ${PEER_NAME.source}`);
    } else if (seen.has(name)) {
      errors.push(`${at}.name "${name}" is declared twice`);
    } else {
      seen.add(name);
    }
    const method = text(c.method);
    if (method == null || !Manifest.COMMAND_METHODS.includes(method)) {
      errors.push(`${at}.method ${quoted(c.method)} must be one of ${Manifest.COMMAND_METHODS.join(', ')}`);
    } else {
      const path = text(c.path);
      if (path == null || !declaredPaths(root, method).has(path)) {
        errors.push(`${at}.path ${quoted(c.path)} is not a ${method} route this module declares (capabilities.routes[] with method ${method})`);} else if (path.includes('{')) {
        literalPath(at, path, errors); //
      }
    }
    visibility(at, c, errors);
  }
}

function events(items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    const at = `${FILE}: capabilities.events[${i}]`;
    const e = closedObject(at, items[i], Manifest.EVENT_KEYS, 'an event', errors);
    if (e == null) continue;
    const name = text(e.name);
    if (name == null || !PEER_NAME.test(name)) {
      errors.push(`${at}.name ${quoted(e.name)} must match ${PEER_NAME.source}`);
    } else if (seen.has(name)) {
      errors.push(`${at}.name "${name}" is declared twice`);
    } else {
      seen.add(name);
    }
    visibility(at, e, errors);
  }
}

function peers(slug: string | null, root: Tree, items: unknown[], errors: string[]): void {
  const required = new Set<string>();
  for (const entry of objects(root.requires)) {
    if (typeof entry.slug === 'string') required.add(entry.slug);
  }
  const owners = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    const at = `${FILE}: capabilities.peers[${i}]`;
    const use = closedObject(at, items[i], Manifest.PEER_USE_KEYS, 'a use of another module', errors);
    if (use == null) continue;
    let owner = text(use.owner);
    if (owner == null || !SLUG.test(owner)) {
      errors.push(`${at}.owner ${quoted(use.owner)} is not a module slug (must match ${SLUG.source})`);
      owner = null;
    } else if (owner === slug) {
      errors.push(`${at}.owner "${owner}" is this module itself`);
    } else if (owners.has(owner)) {
      errors.push(`${at}.owner "${owner}" is declared twice`);
    } else {
      owners.add(owner);
      if (!required.has(owner)) {
        errors.push(`${at}.owner "${owner}" is not in requires (declare it there, mandatory or optional)`);
      }
    }
    const readNames = peerNames(`${at}.reads`, use.reads, owner == null ? null : { test: peerPort(owner), source: javaPattern(owner) }, errors);
    const commandNames = peerNames(`${at}.commands`, use.commands, { test: PEER_NAME, source: PEER_NAME.source }, errors);
    if (readNames === 0 && commandNames === 0) {
      errors.push(`${at} must name at least one of reads, commands`);
    }
  }
}

function peerNames(at: string, names: unknown, grammar: { test: RegExp; source: string } | null, errors: string[]): number {
  if (names == null) return 0;
  if (!Array.isArray(names)) {
    errors.push(`${at} must be an array`);
    return 0;
  }
  const seen = new Set<string>();
  let valid = 0;
  for (let i = 0; i < names.length; i++) {
    const name = names[i];
    if (typeof name !== 'string' || (grammar != null && !grammar.test.test(name))) {
      errors.push(`${at}[${i}] ${quoted(name)}${grammar == null ? ' must be a string' : ` must match ${grammar.source}`}`);
    } else if (seen.has(name)) {
      errors.push(`${at}[${i}] "${name}" is named twice`);
    } else {
      seen.add(name);
      valid++;
    }
  }
  return valid;
}

/**
 * `rollbackFloor`: optional; a semver at or below `version`. When `version` itself is not
 * semver, only the floor's own shape is checked (the version is the platform's check at upload).
 */
function rollbackFloor(root: Tree, errors: string[]): void {
  if (!has(root, 'rollbackFloor') || root.rollbackFloor == null) return;
  const raw = root.rollbackFloor;
  if (typeof raw !== 'string' || !SEMVER.test(raw)) {
    errors.push(`${FILE}: "rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got ${quoted(raw)})`);
    return;
  }
  const floor = raw;
  const version = text(root.version);
  if (version != null && SEMVER.test(version) && compareSemver(floor, version) > 0) {
    errors.push(`${FILE}: "rollbackFloor" "${floor}" is above "version" "${version}"`
      + ': the floor is the oldest version that runs on this version\'s data, so at most the version itself');
  }
}

/**
 * Semver 2.0 precedence of two versions that match `SEMVER` (the platform's `SemVer.compareTo`): the numeric core
 * first, then a pre-release below its release, then the pre-release identifiers one by one (numeric below
 * alphanumeric, numeric by value, alphanumeric by text, a shorter list below a longer one). The build part is ignored.
 * Numbers are compared by length, then by text: semver forbids leading zeros, so no size limit applies.
 */
function compareSemver(a: string, b: string): number {
  const ma = SEMVER.exec(a);
  const mb = SEMVER.exec(b);
  if (ma == null || mb == null) throw new Error(`not semver: ${a}, ${b}`);
  for (let i = 1; i <= 3; i++) {
    const c = compareNumeric(ma[i], mb[i]);
    if (c !== 0) return c;
  }
  const preA = ma[4];
  const preB = mb[4];
  if (preA == null || preB == null) {
    if (preA == null && preB == null) return 0;
    return preA == null ? 1 : -1;
  }
  const idsA = preA.split('.');
  const idsB = preB.split('.');
  for (let i = 0; i < Math.min(idsA.length, idsB.length); i++) {
    const numA = /^\d+$/.test(idsA[i]);
    const numB = /^\d+$/.test(idsB[i]);
    let c: number;
    if (numA && numB) c = compareNumeric(idsA[i], idsB[i]);
    else if (numA) c = -1;
    else if (numB) c = 1;
    else c = idsA[i] < idsB[i] ? -1 : idsA[i] > idsB[i] ? 1 : 0;
    if (c !== 0) return c;
  }
  return Math.sign(idsA.length - idsB.length);
}

function compareNumeric(a: string, b: string): number {
  return a.length !== b.length ? Math.sign(a.length - b.length) : a < b ? -1 : a > b ? 1 : 0;
}

function forbiddenKeys(object: Tree, prefix: string, errors: string[]): void {
  for (const key of Object.keys(object)) {
    const rule = forbiddenRule(key);
    if (rule != null) errors.push(`${FILE}: "${prefix}${key}" is not allowed: ${rule}`);
  }
}

function forbiddenRule(key: string): string | null {
  const lower = key.toLowerCase();
  if (DDL_KEYS.has(lower)) {
    return 'a module cannot run DDL outside kf_modules; extension tables are declared in capabilities.fields and created by the platform';
  }
  if (DICTIONARY_KEYS.has(lower)) {
    return 'a module cannot write the platform dictionary; texts are declared in "i18n" and the platform writes them at install';
  }
  return null;
}

/** The `en` keys with a non-blank text. */
function i18n(raw: unknown, knownLanguages: Set<string>, errors: string[], warnings: string[]): Set<string> {
  const enKeys = new Set<string>();
  if (raw == null) {
    errors.push(`${FILE}: "i18n" is required for "schemaVersion": 2 and must contain "en"`);
    return enKeys;
  }
  if (!isObject(raw)) {
    errors.push(`${FILE}: "i18n" must be an object of language -> (key -> text)`);
    return enKeys;
  }
  const languages = raw;
  if (!has(languages, 'en')) {
    errors.push(`${FILE}: "i18n" must contain "en": every text needs an English version`);
  }
  const unknown = new Set<string>();
  for (const [lang, value] of Object.entries(languages)) {
    const at = `${FILE}: i18n.${lang}`;
    if (!LANGUAGE_CODE.test(lang)) {
      errors.push(`${at} is not a language code (expected e.g. "en", "ro", "pt-BR")`);
      continue;
    }
    if (!isObject(value)) {
      errors.push(`${at} must be an object of key -> text`);
      continue;
    }
    if (knownLanguages.size > 0 && !knownLanguages.has(lang)) unknown.add(lang);
    for (const [key, entry] of Object.entries(value)) {
      if (!KEY.test(key)) {
        errors.push(`${at}.${key}: a key must match ${KEY.source}`);
      } else if (typeof entry !== 'string') {
        errors.push(`${at}.${key} must be a string`);
      } else if (entry.trim() === '') {
        errors.push(`${at}.${key} must not be blank`);
      } else if (entry.length > MAX_TEXT_LENGTH) {
        errors.push(`${at}.${key} exceeds ${MAX_TEXT_LENGTH} characters`);
      } else if (lang === 'en') {
        enKeys.add(key);
      }
    }
  }
  for (const [lang, value] of Object.entries(languages)) {
    if (lang === 'en' || !LANGUAGE_CODE.test(lang)) continue;
    for (const key of Object.keys(map(value))) {
      if (KEY.test(key) && !enKeys.has(key)) {
        errors.push(`${FILE}: i18n.${lang}.${key} has no "en" text; every key must be defined in "en"`);
      }
    }
  }
  if (unknown.size > 0) {
    warnings.push(`${FILE}: i18n declares ${[...unknown].sort().join(', ')}, not served by this platform`
      + ` (served: ${[...knownLanguages].sort().join(', ')}); those texts stay in the manifest`);
  }
  return enKeys;
}

function requireKey(at: string, key: string | null, enKeys: Set<string>, errors: string[]): void {
  if (key == null || key.trim() === '') {
    errors.push(`${at} is required: a translation key defined in i18n.en`);
  } else if (!KEY.test(key)) {
    errors.push(`${at} "${key}" is not a translation key (must match ${KEY.source}); in schemaVersion 2 texts live in "i18n"`);
  } else if (!enKeys.has(key)) {
    errors.push(`${at} "${key}" is not defined in i18n.en`);
  }
}

function fields(items: unknown[], enKeys: Set<string>, errors: string[]): void {
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.fields[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const f = item;
    const entity = text(f.entity);
    const name = text(f.name);
    if (entity == null || entity.trim() === '') {
      errors.push(`${at}.entity is required (model, order, customer, ...: an extensible entity of the platform)`);
    }
    if (name == null || !NAME.test(name)) {
      errors.push(`${at}.name must match ${NAME.source} (got ${quoted(f.name)})`);
    } else if (entity != null) {
      const id = `${entity}.${name}`;
      if (seen.has(id)) errors.push(`${at}: field "${id}" is declared twice`);
      seen.add(id);
    }
    const table = text(f.table);
    if (table != null && table.includes('.')) {
      errors.push(`${at}.table ${quoted(table)} is not allowed: a module cannot run DDL outside kf_modules`);
    } else if (table == null || !NAME.test(table)) {
      errors.push(`${at}.table must match ${NAME.source} (got ${quoted(f.table)})`);
    }
    if (!FIELD_TYPES.includes(text(f.type) as string)) {
      errors.push(`${at}.type must be one of ${FIELD_TYPES.join(', ')} (got ${quoted(f.type)})`);
    }
    requireKey(`${at}.label`, text(f.label), enKeys, errors);
    // editable, when present, is a JSON boolean (null is refused too)
    if (has(f, 'editable') && typeof f.editable !== 'boolean') {
      errors.push(`${at}.editable must be a boolean (got ${quoted(f.editable)})`);
    }
  });
}

function hooks(root: Tree, schemaVersion: number, items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.hooks[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const h = item;
    const point = text(h.point);
    if (point != null && point.startsWith(MODULE_EVENT_PREFIX)) {
      moduleEventHook(root, schemaVersion, at, point, h, seen, errors); //
      return;
    }
    if (point == null || point.trim() === '') {
      errors.push(`${at}.point is required (a hook point id, e.g. model.before.update)`);
    } else if (seen.has(point)) {
      errors.push(`${at}.point "${point}" is declared twice`);
    } else {
      seen.add(point);
    }
    if (h.onFailure != null && point != null && FlowEvents.TYPES.includes(point)) {
      // protocol 2.0 (§16.3, the platform's text): an event of the flow engine blocks nothing
      errors.push(`${at}.onFailure is not allowed on the flow event "${point}": it runs after the step and cannot block it`);
    } else if (h.onFailure != null) {
      const onFailure = text(h.onFailure);
      if (!ON_FAILURE.includes(onFailure as string)) {
        errors.push(`${at}.onFailure must be one of allow, block (got ${quoted(h.onFailure)})`);
      } else if (point != null && !point.includes('.before.')) {
        errors.push(`${at}.onFailure only on before points: "${point}" runs after the mutation and cannot block it`);
      }
    }
  });
}

/**
 * `capabilities.hooks[i]` on `m.{owner}.after.{name}` (the platform's `validateModuleEventHook`):
 * schema 3, the grammar, the owner in `requires` (mandatory or optional), no `onFailure`; the point declared once.
 */
function moduleEventHook(root: Tree, schemaVersion: number, at: string, point: string, hook: Tree, seen: Set<string>,
                         errors: string[]): void {
  if (schemaVersion < 3) {
    errors.push(`${at}.point ${quoted(point)}: an event of a module requires "schemaVersion": 3`);
    return;
  }
  const parsed = MODULE_EVENT_POINT.exec(point);
  if (parsed == null) {
    errors.push(`${at}.point ${quoted(point)} is not an event of a module (m.{owner}.after.{name})`);
    return;
  }
  const owner = parsed[1];
  if (seen.has(point)) errors.push(`${at}.point "${point}" is declared twice`);
  else seen.add(point);
  const required = Array.isArray(root.requires)
    && root.requires.some((r) => isObject(r) && text(r.slug) === owner);
  if (!required) {
    errors.push(`${at}.point "${point}" is an event of ${owner}, which this manifest does not require (requires, mandatory or optional)`);
  }
  if (hook.onFailure != null) {
    errors.push(`${at}.onFailure is not allowed on an event of a module: it runs after the fact`);
  }
}

function routes(slug: string | null, items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  const prefix = `/m/${slug}/`;
  const rightPrefix = `api.v1.m.${slug}.`;
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.routes[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const r = item;
    const method = text(r.method);
    const path = text(r.path);
    const route = `${at} (${method} ${path})`;
    if (method == null || !HTTP_METHODS.includes(method)) {
      errors.push(`${route}.method must be one of ${HTTP_METHODS.join(', ')}`);
    }
    if (path == null || !path.startsWith(prefix) || path.length === prefix.length || path.endsWith('/')
      || path.includes('..') || path.includes('//') || path.includes('?') || path.includes('#')
      || path.includes('*') || path.includes('%')) {
      errors.push(`${route}.path must be under ${prefix} without "..", "//", "%", "*", a query, a fragment or a trailing "/"`);
    }
    const right = text(r.right);
    if (right == null || !right.startsWith(rightPrefix)) {
      errors.push(`${route}.right must be in ${rightPrefix}* (got ${quoted(r.right)})`);
    } else {
      const segments = right.substring(rightPrefix.length).split('.');
      const ok = segments.length >= 1 && segments.length <= 4 && segments.every((s) => RIGHT_SEGMENT.test(s));
      if (!ok) {
        errors.push(`${route}.right ${quoted(right)} must be ${rightPrefix} followed by 1 to 4 segments matching ${RIGHT_SEGMENT.source}`);
      }
    }
    if (method != null && path != null) {
      const id = `${method} ${path}`;
      if (seen.has(id)) errors.push(`${route} is declared twice`);
      seen.add(id);
    }
    roles(route, r, errors);
    admin(route, r, errors);
    timeoutSeconds(route, r, errors);
    audience(route, r, errors);
    portal(route, r, errors);
    // hookable is a boolean, false when absent, and only on the methods that write
    if (has(r, 'hookable')) {
      const hookable = r.hookable;
      if (typeof hookable !== 'boolean') {
        errors.push(`${route}.hookable must be a boolean (got ${quoted(hookable)})`);
      } else if (hookable && !Manifest.HOOKABLE_METHODS.includes(method as string)) {
        errors.push(`${route}.hookable: ${HOOKABLE_RULE} (a hook calls a route that writes; ${method} cannot be hookable)`);
      }
    }
  });
}

/**
 * `routes[].admin` (protocol 1.1): `tenant` or `platform`; a `platform` route declares no roles (only the
 * platform administrator holds its right); never on a `hookable` route (a hook calls as the system, an admin route is a
 * person's).
 */
function admin(route: string, r: Tree, errors: string[]): void {
  if (r.admin == null) return;
  const value = r.admin;
  if (!Manifest.ROUTE_ADMIN.includes(text(value) as string)) {
    errors.push(`${route}.admin must be one of tenant, platform (got ${quoted(value)})`);
    return;
  }
  if (value === 'platform' && Array.isArray(r.roles) && r.roles.length > 0) {
    errors.push(`${route}.admin "platform" declares no roles: only the platform administrator holds the right`);
  }
  if (r.hookable === true) {
    errors.push(`${route}: admin and hookable exclude each other (a hook calls a route as the system; an admin `
      + 'route is a person\'s)');
  }
}

/**
 * `activation` (protocol 1.2): `"tenant"` or `"platform"`; absent or `null` = `tenant`. Checked
 * under every `schemaVersion`, like `rollbackFloor`. The platform's text (`ModuleManifestValidator`).
 */
function activation(root: Tree, errors: string[]): void {
  const value = root.activation;
  if (value == null || value === 'tenant' || value === 'platform') return;
  errors.push(`${FILE}: activation must be "tenant" or "platform" (got ${quoted(value)})`);
}

/**
 * `capabilities.storage` (protocol 1.2): at most `Manifest.MAX_STORAGE_ELEMENTS` element, a
 * closed object `{contentTypes, maxBytes}`; `contentTypes` 1 to `Manifest.MAX_STORAGE_CONTENT_TYPES` distinct
 * `type/subtype` without a wildcard (`Manifest.STORAGE_CONTENT_TYPE`); `maxBytes` a whole number from 1 to
 * `Manifest.MAX_STORAGE_BYTES`. The platform's texts (`ModuleManifestValidator`).
 */
function storage(raw: unknown, errors: string[]): void {
  if (!Array.isArray(raw)) return; // absent = []; a non-array is reported with the capabilities
  if (raw.length > Manifest.MAX_STORAGE_ELEMENTS) {
    errors.push(`${FILE}: capabilities.storage holds at most one element`);
  }
  raw.forEach((element, i) => {
    const at = `${FILE}: capabilities.storage[${i}]`;
    if (!isObject(element)) {
      errors.push(`${at} must be an object`);
      return;
    }
    for (const key of Object.keys(element)) {
      if (!Manifest.STORAGE_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a storage element (known: ${Manifest.STORAGE_KEYS.join(', ')})`);
      }
    }
    if (!contentTypes(element.contentTypes)) {
      errors.push(`${at}.contentTypes must be 1 to ${Manifest.MAX_STORAGE_CONTENT_TYPES} distinct type/subtype values, `
        + 'without wildcards');
    }
    const bytes = wholeSeconds(element.maxBytes);
    if (bytes === null || bytes < 1 || bytes > Manifest.MAX_STORAGE_BYTES) {
      errors.push(`${at}.maxBytes must be a whole number of bytes between 1 and ${Manifest.MAX_STORAGE_BYTES} `
        + `(got ${quoted(element.maxBytes)})`);
    }
  });
}

function contentTypes(raw: unknown): boolean {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > Manifest.MAX_STORAGE_CONTENT_TYPES) return false;
  const seen = new Set<unknown>();
  for (const type of raw) {
    if (typeof type !== 'string' || !STORAGE_CONTENT_TYPE.test(type) || seen.has(type)) return false;
    seen.add(type);
  }
  return true;
}

/**
 * `routes[].audience` (protocol 1.2): `"member"` -- any member of the active tenant calls the
 * route; absent or `null` = an ordinary route. A member route declares no `roles` (a non-empty list), no `admin` and is
 * not `hookable`: no role is proposed for it, it is not an administration and a hook does not call it. The platform's
 * texts.
 */
function audience(route: string, r: Tree, errors: string[]): void {
  const value = r.audience;
  if (value == null) return;
  if (value !== Manifest.ROUTE_AUDIENCE_MEMBER) {
    errors.push(`${route}.audience must be "member" (got ${quoted(value)})`);
    return;
  }
  const roles = Array.isArray(r.roles) && r.roles.length > 0;
  if (roles || r.admin != null || r.hookable === true) {
    errors.push(`${route}: audience member excludes roles, admin and hookable (any member of the tenant calls it)`);
  }
}

/**
 * `routes[].portal` (protocol 1.2): when present, a JSON boolean (`null` is refused), and
 * `true` only on `audience: member`; absent = `false`. The platform's text.
 */
function portal(route: string, r: Tree, errors: string[]): void {
  if (!has(r, 'portal')) return;
  const value = r.portal;
  if (typeof value !== 'boolean') {
    errors.push(`${route}.portal must be a boolean, and only on audience member (got ${quoted(value)})`);
  } else if (value && r.audience !== Manifest.ROUTE_AUDIENCE_MEMBER) {
    errors.push(`${route}.portal must be a boolean, and only on audience member (got true, audience `
      + `${quoted(r.audience)})`);
  }
}

/**
 * `routes[].timeoutSeconds` (SDK 0.7.3): when present and not `null`, a JSON whole number
 * from 1 to `Manifest.MAX_ROUTE_TIMEOUT_SECONDS` -- how long the platform waits for the module's answer on this route.
 * The platform's rule and text (`ModuleManifestValidator`).
 */
function timeoutSeconds(route: string, r: Tree, errors: string[]): void {
  const value = r.timeoutSeconds;
  if (value == null) return;
  const seconds = wholeSeconds(value);
  if (seconds === null || seconds < 1 || seconds > Manifest.MAX_ROUTE_TIMEOUT_SECONDS) {
    errors.push(`${route}.timeoutSeconds must be a whole number of seconds between 1 and `
      + `${Manifest.MAX_ROUTE_TIMEOUT_SECONDS} (got ${quoted(value)})`);
  }
}

function roles(route: string, r: Tree, errors: string[]): void {
  if (!has(r, 'roles') || r.roles == null) return; // absent = [] = no role
  if (!Array.isArray(r.roles)) {
    errors.push(`${route}.roles must be an array of role names`);
    return;
  }
  const at = `${route}.roles`;
  if (r.roles.length > MAX_ROLES_PER_ROUTE) {
    errors.push(`${at} declares ${r.roles.length} roles; at most ${MAX_ROLES_PER_ROUTE} are allowed per route`);
  }
  const seen = new Set<string>();
  for (const o of r.roles) {
    if (typeof o !== 'string' || o.trim() === '') {
      errors.push(`${at}: a role name must be a non-empty string`);
    } else if (o.length > MAX_ROLE_NAME_LENGTH) {
      errors.push(`${at}: a role name exceeds ${MAX_ROLE_NAME_LENGTH} characters`);
    } else if (/[\u0000-\u001f\u007f-\u009f]/.test(o)) {
      errors.push(`${at}: a role name must not contain control characters`);
    } else if (seen.has(o)) {
      errors.push(`${at}: "${o}" is declared twice`);
    } else {
      seen.add(o);
      if (FORBIDDEN_ROLES.has(o.toUpperCase())) {
        errors.push(`${at}: "${o}" cannot be declared: module routes are excluded from TENANT_ADMIN and never name SUPER_ADMIN`);
      }
    }
    // whether the name is an existing global role is the platform's check (a warning at upload)
  }
}

/** The kinds a contribution to a slot of a module may have (§7.12): A react|wc, B layer, R data. */
const SLOT_CONTRIBUTION_KINDS = ['react', 'wc', 'layer', 'data'];

function surfaces(
  moduleSlug: string | null,
  root: Record<string, unknown>,
  schemaVersion: number,
  items: unknown[],
  enKeys: Set<string>,
  errors: string[],
): void {
  const pages = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.surfaces[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const s = item;
    if (typeof s.slug === 'string' && s.slug.startsWith('m.')) {
      // protocol 2.0 (§7.12): a contribution to the slot of another module, the platform's rules and
      // messages (ModuleManifestValidator); whether the owner declares it is, the platform's at registration
      slotContribution(at, moduleSlug, root, schemaVersion, s.slug, s.kind, errors);
      if (s.label != null) requireKey(`${at}.label`, text(s.label), enKeys, errors);
      return;
    }
    if (typeof s.slug !== 'string' || s.slug.trim() === '') errors.push(`${at}.slug is required`);
    const kind = text(s.kind) as string;
    if (SLOT_CONTRIBUTION_KINDS.includes(kind) && !SURFACE_KINDS.includes(kind)) {
      errors.push(`${at}.kind "${kind}" is only for a slot of a module (m.{owner}.{key}); a host surface takes react or wc`);
    } else if (!SURFACE_KINDS.includes(kind)) {
      errors.push(`${at}.kind must be one of react, wc (got ${quoted(s.kind)})`);
    }
    if (s.label != null) requireKey(`${at}.label`, text(s.label), enKeys, errors);
    if (s.slug === Manifest.ORDERFLOW_STEP) {
      // protocol 2.0 (§16.5, the platform's text): the contribution names the step it renders
      if (s.key == null || (typeof s.key === 'string' && s.key.trim() === '')) {
        errors.push(`${at} on "${Manifest.ORDERFLOW_STEP}" needs a "key": the key of the step it renders`);
      } else if (!(typeof s.key === 'string' && STEP_KEY.test(s.key))) {
        errors.push(`${at}.key ${quoted(s.key)} must match ${STEP_KEY.source}`);
      }
    }
    // protocol 1.1: the page of a page surface — its grammar, then (0.7.1) the type of the
    // surface in the pinned release list
    if (s.page != null && !(typeof s.page === 'string' && PAGE.test(s.page))) {
      errors.push(`${at}.page ${quoted(s.page)} must match ${PAGE.source}`);
      return;
    }
    pageType(at, typeof s.slug === 'string' ? s.slug : null, (s.page ?? null) as string | null, moduleSlug, pages, errors);
  });
}

/**
 * A contribution to `m.{owner}.{key}` (§7.12): schema 3 only; a well-formed key whose owner is the second segment; never the module itself; the owner in `requires` (mandatory or optional); a kind of the protocol.
 */
function slotContribution(
  at: string,
  moduleSlug: string | null,
  root: Record<string, unknown>,
  schemaVersion: number,
  slotSlug: string,
  kind: unknown,
  errors: string[],
): void {
  if (schemaVersion < 3) {
    errors.push(
      `${at}.slug "${slotSlug}": a contribution to a slot of a module requires "schemaVersion": 3 ` +
        `(this manifest is schemaVersion ${schemaVersion})`,
    );
    return;
  }
  const rest = slotSlug.substring(2);
  const dot = rest.indexOf('.');
  const owner = dot > 0 ? rest.substring(0, dot) : null;
  const key = dot > 0 ? rest.substring(dot + 1) : null;
  if (owner == null || key == null || !SLUG.test(owner) || key.length > Manifest.MAX_SLOT_KEY_LENGTH || !SLOT_KEY.test(key)) {
    errors.push(
      `${at}.slug "${slotSlug}" is not a slot key m.{owner}.{key} (the owner is a module slug, the ` +
        'key ends with its major, e.g. m.kf-3d.viewport.layers.v1)',
    );
  } else if (owner === moduleSlug) {
    errors.push(`${at}.slug "${slotSlug}" is a slot of this module itself; a module does not contribute to its own slots`);
  } else if (!requiredSlugs(root).has(owner)) {
    errors.push(`${at}.slug "${slotSlug}": the owner "${owner}" is not in requires (declare it there, mandatory or optional)`);
  }
  if (!SLOT_CONTRIBUTION_KINDS.includes(text(kind) as string)) {
    errors.push(`${at}.kind must be one of react, wc, layer, data (got ${quoted(kind)})`);
  }
}

/** The slugs of `requires[]`, mandatory or optional. */
function requiredSlugs(root: Record<string, unknown>): Set<string> {
  const required = new Set<string>();
  if (Array.isArray(root.requires)) {
    for (const entry of root.requires) {
      if (isObject(entry) && typeof entry.slug === 'string') required.add(entry.slug);
    }
  }
  return required;
}

/**
 * `surfaces[].page` against the type of its surface (0.7.1; the platform's rule): only a
 * surface of type `page` takes one, it always does, and one module serves each page once (the host opens
 * `/m/{slug}/{page}`). A surface the pinned release list does not declare is left to the platform.
 */
function pageType(at: string, surfaceSlug: string | null, page: string | null, moduleSlug: string | null, pages: Set<string>,
  errors: string[]): void {
  const type = surfaceSlug == null || !Object.prototype.hasOwnProperty.call(SURFACE_TYPES, surfaceSlug)
    ? null
    : (SURFACE_TYPES as Readonly<Record<string, string>>)[surfaceSlug];
  if (type == null) {
    return;
  }
  if (type !== PAGE_SURFACE_TYPE) {
    if (page != null) {
      errors.push(`${at}.page: page only on surfaces of type page (${quoted(surfaceSlug)} is of type ${type})`);
    }
    return;
  }
  if (page == null) {
    errors.push(`${at}.page is required on a surface of type page (${quoted(surfaceSlug)}): the host `
      + `opens it at /m/${moduleSlug}/<page>`);
    return;
  }
  const seen = `${surfaceSlug}|${page}`;
  if (pages.has(seen)) {
    errors.push(`${at}.page "${page}" is declared twice on ${quoted(surfaceSlug)}`);
  } else {
    pages.add(seen);
  }
}

function notifications(items: unknown[], enKeys: Set<string>, errors: string[]): void {
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.notifications[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const n = item;
    const key = text(n.key);
    if (key == null || !NOTIFICATION_KEY.test(key)) {
      errors.push(`${at}.key ${quoted(n.key)} must match ${NOTIFICATION_KEY.source}`);
    } else if (seen.has(key)) {
      errors.push(`${at}.key "${key}" is declared twice`);
    } else {
      seen.add(key);
    }
    const channels = texts(n.channels);
    if (channels.length === 0) errors.push(`${at}.channels must not be empty (one or more of in_app, email)`);
    for (const channel of channels) {
      if (!CHANNELS.includes(channel)) errors.push(`${at}.channels: "${channel}" is not a channel (one of in_app, email)`);
    }
    requireKey(`${at}.subject`, text(n.subject), enKeys, errors);
    requireKey(`${at}.body`, text(n.body), enKeys, errors);
  });
}

function reads(items: unknown[], errors: string[]): void {
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.reads[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const r = item;
    if (typeof r.port !== 'string' || r.port.trim() === '') {
      errors.push(`${at}.port is required`);
    } else if (!PORT.test(r.port)) {
      errors.push(`${at}.port "${r.port}" must match ${PORT.source} (a port id: entity, optional `
        + 'sub-segment, major version, e.g. model.v1)');
    }
    if (r.access != null && r.access !== 'read') {
      errors.push(`${at}.access must be absent or "read": no module writes the platform's data`);
    }
  });
}

/** `[{"access": "write" | "read-own"}]`, each value at most once. */
function audit(items: unknown[], errors: string[]): void {
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.audit[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object {"access": "write" | "read-own"}`);
      return;
    }
    const access = text(item.access);
    if (access == null || !Manifest.AUDIT_ACCESS.includes(access)) {
      errors.push(`${at}.access must be one of write, read-own (got ${quoted(item.access)})`);
    } else if (seen.has(access)) {
      errors.push(`${at}.access "${access}" is declared twice`);
    } else {
      seen.add(access);
    }
  });
}

/**
 * `nav` (protocol 1.1): the module's menu entries, each a closed object. Checked here: the
 * surface, the shape of the group and of the key, a key once per surface, the label key, the url (an in-app path), the
 * icon name, the order, the flag (one of the module's), and the right -- never on `customer`, and a right under
 * `api.v1.m.` only when it is the right of one of the module's own routes. Whether the group is a group of the surface
 * and whether a platform code is declared stay the platform's check at upload.
 */
function nav(slug: string | null, root: Tree, items: unknown[], enKeys: Set<string>, errors: string[]): void {
  const routeRights = new Set<string>();
  for (const route of objects(map(root.capabilities).routes)) {
    const right = text(route.right);
    if (right != null) routeRights.add(right);
  }
  const flags = new Set<string>();
  for (const flag of objects(root.featureFlags)) {
    const key = text(flag.key);
    if (key != null) flags.add(key);
  }
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.nav[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const n = item;
    for (const key of Object.keys(n)) {
      if (!Manifest.NAV_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a nav entry (known: ${Manifest.NAV_KEYS.join(', ')})`);
      }
    }
    const surface = text(n.surface);
    if (surface == null || !Manifest.NAV_SURFACES.includes(surface)) {
      errors.push(`${at}.surface must be one of ${Manifest.NAV_SURFACES.join(', ')} (got ${quoted(n.surface)})`);
    }
    const group = text(n.group);
    if (group == null || !NAV_KEY.test(group)) {
      errors.push(`${at}.group must match ${NAV_KEY.source} (got ${quoted(n.group)})`);
    }
    const key = text(n.key);
    if (key == null || !NAV_KEY.test(key)) {
      errors.push(`${at}.key must match ${NAV_KEY.source} (got ${quoted(n.key)})`);
    } else if (surface != null) {
      const id = `${surface} ${key}`;
      if (seen.has(id)) errors.push(`${at}.key "${key}" is declared twice on the surface ${surface}`);
      seen.add(id);
    }
    requireKey(`${at}.label`, text(n.label), enKeys, errors);
    if (!isInAppPath(n.url)) {
      errors.push(`${at}.url must be an in-app path: it starts with "/" and has no "//", "..", "%", `
        + `backslash, whitespace, scheme, query or fragment (got ${quoted(n.url)})`);
    }
    if (n.icon != null && !(typeof n.icon === 'string' && NAV_ICON.test(n.icon))) {
      errors.push(`${at}.icon must be the name of an icon of the host, ${NAV_ICON.source} (e.g. "Boxes"; got ${quoted(n.icon)})`);
    }
    if (n.order != null && !(typeof n.order === 'number' && Number.isInteger(n.order) && n.order >= 0
      && n.order <= MAX_NAV_ORDER)) {
      errors.push(`${at}.order must be an integer from 0 to ${MAX_NAV_ORDER} (got ${quoted(n.order)})`);
    }
    if (n.flag != null) {
      const flag = text(n.flag);
      if (flag == null || !flags.has(flag)) {
        errors.push(`${at}.flag ${quoted(n.flag)} is not a feature flag of this module (featureFlags[].key)`);
      }
    }
    if (n.right != null) navRight(at, slug, surface, n.right, routeRights, errors);
  });
}

function navRight(at: string, slug: string | null, surface: string | null, raw: unknown, routeRights: Set<string>,
  errors: string[]): void {
  if (surface === 'customer') {
    errors.push(`${at}.right is not allowed on the customer surface: the customer menu is filtered on the `
      + "customer's identity, not on rights");
    return;
  }
  const right = text(raw);
  if (right != null && right.startsWith('api.v1.m.')) {
    if (!routeRights.has(right)) {
      errors.push(`${at}.right "${right}" is not the right of a route this module declares `
        + `(capabilities.routes[].right of api.v1.m.${slug}.*)`);
    }
  } else if (right == null || !PLATFORM_RIGHT.test(right)) {
    errors.push(`${at}.right ${quoted(raw)} is not a right code: the right of one of the module's routes `
      + `or a code the platform declares (${PLATFORM_RIGHT.source})`);
  }
}

/** @internal A menu url the host may render as an in-app link (the platform's `NavigationContributionRegistry` rule). */
/**
 * `settings` (protocol 1.1): closed scalars only. Each a closed object
 * `{key, type, default, label, description?, values?, min?, max?}`; `type` `BOOLEAN`, `NUMBER` or `ENUM` ("settings
 * carry no data": no free text, list, reference or secret); the default coherent with the type; `values` only and
 * always on `ENUM`; `min`/`max` only on `NUMBER`; unique keys; at most `Manifest.MAX_SETTINGS`.
 */
function settings(items: unknown[], enKeys: Set<string>, errors: string[]): void {
  if (items.length > Manifest.MAX_SETTINGS) {
    errors.push(`${FILE}: capabilities.settings declares ${items.length} settings; at most ${Manifest.MAX_SETTINGS} settings are allowed`);
  }
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.settings[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const s = item;
    for (const key of Object.keys(s)) {
      if (!Manifest.SETTING_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a setting (known: ${Manifest.SETTING_KEYS.join(', ')})`);
      }
    }
    const key = text(s.key);
    if (key == null || !SETTING_KEY.test(key)) {
      errors.push(`${at}.key must match ${SETTING_KEY.source} (got ${quoted(s.key)})`);
    } else if (seen.has(key)) {
      errors.push(`${at}.key "${key}" is declared twice`);
    } else {
      seen.add(key);
    }
    requireKey(`${at}.label`, text(s.label), enKeys, errors);
    if (s.description != null) requireKey(`${at}.description`, text(s.description), enKeys, errors);
    const type = text(s.type);
    if (!Manifest.SETTING_TYPES.includes(type as string)) {
      errors.push(`${at}.type ${quoted(s.type)} is not allowed: ${SETTINGS_RULE}`
        + ' (free text, lists, references and secrets are data: they live on surfaces or in secrets)');
      return;
    }
    if (type !== 'ENUM' && s.values != null) errors.push(`${at}.values only on an ENUM setting`);
    if (type !== 'NUMBER' && (s.min != null || s.max != null)) errors.push(`${at}.min and .max only on a NUMBER setting`);
    const def = s.default;
    if (def == null) {
      errors.push(`${at}.default is required (the value before the tenant administrator sets one)`);
      return;
    }
    if (type === 'BOOLEAN') {
      if (typeof def !== 'boolean') errors.push(`${at}.default must be a boolean (got ${quoted(def)})`);
    } else if (type === 'NUMBER') {
      numberSetting(at, s, def, errors);
    } else {
      enumSetting(at, s, def, errors);
    }
  });
}

function numberSetting(at: string, s: Tree, def: unknown, errors: string[]): void {
  let min: number | null = null;
  let max: number | null = null;
  if (s.min != null) {
    if (typeof s.min === 'number') min = s.min;
    else errors.push(`${at}.min must be a number (got ${quoted(s.min)})`);
  }
  if (s.max != null) {
    if (typeof s.max === 'number') max = s.max;
    else errors.push(`${at}.max must be a number (got ${quoted(s.max)})`);
  }
  if (min != null && max != null && min > max) errors.push(`${at}.min must not be above .max`);
  if (typeof def !== 'number') {
    errors.push(`${at}.default must be a number (got ${quoted(def)})`);
    return;
  }
  if ((min != null && def < min) || (max != null && def > max)) {
    errors.push(`${at}.default must be between min and max (got ${def})`);
  }
}

function enumSetting(at: string, s: Tree, def: unknown, errors: string[]): void {
  if (!Array.isArray(s.values) || s.values.length === 0) {
    errors.push(`${at}.values must be a non-empty list of codes on an ENUM setting (a closed choice)`);
    return;
  }
  const seen = new Set<string>();
  for (const v of s.values) {
    if (typeof v !== 'string' || !ENUM_VALUE.test(v)) {
      errors.push(`${at}.values: ${quoted(v)} must match ${ENUM_VALUE.source}`);
    } else if (seen.has(v)) {
      errors.push(`${at}.values: "${v}" is declared twice`);
    } else {
      seen.add(v);
    }
  }
  if (typeof def !== 'string' || !seen.has(def)) {
    errors.push(`${at}.default must be one of the values (got ${quoted(def)})`);
  }
}

/**
 * `secrets` (protocol 1.1): closed objects `{name, scope, writeRight}` -- a value never
 * travels in the manifest; unique names; `scope` `platform` or `tenant`; `writeRight` the right of a declared route, and
 * for a `platform` secret a route with `admin: platform`; `external` (SDK 0.7.2): see
 * `secretExternal`.
 */
function secrets(root: Tree, items: unknown[], errors: string[]): void {
  const adminByRight = new Map<string, unknown>();
  for (const route of objects(map(root.capabilities).routes)) {
    const right = text(route.right);
    if (right != null) adminByRight.set(right, route.admin == null ? '' : route.admin);
  }
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.secrets[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const s = item;
    for (const key of Object.keys(s)) {
      if (!Manifest.SECRET_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a secret (known: ${Manifest.SECRET_KEYS.join(', ')}; a value is `
          + 'written through the platform, never in the manifest)');
      }
    }
    const name = text(s.name);
    if (name == null || !SETTING_KEY.test(name)) {
      errors.push(`${at}.name must match ${SETTING_KEY.source} (got ${quoted(s.name)})`);
    } else if (seen.has(name)) {
      errors.push(`${at}.name "${name}" is declared twice`);
    } else {
      seen.add(name);
    }
    const scope = text(s.scope);
    if (!Manifest.SECRET_SCOPES.includes(scope as string)) {
      errors.push(`${at}.scope must be one of platform, tenant (got ${quoted(s.scope)})`);
    }
    const writeRight = text(s.writeRight);
    if (writeRight == null || !adminByRight.has(writeRight)) {
      errors.push(`${at}.writeRight ${quoted(s.writeRight)} is not the right of a route this module declares `
        + '(capabilities.routes[].right)');
    } else if (scope === 'platform' && adminByRight.get(writeRight) !== 'platform') {
      errors.push(`${at}.writeRight "${writeRight}": a platform secret is written only through a route `
        + 'with "admin": "platform"');
    }
    if (Object.prototype.hasOwnProperty.call(s, 'external')) secretExternal(at, scope, s.external, errors);
  });
}

/**
 * `secrets[].external` (SDK 0.7.2): a non-empty array of at most `Manifest.SECRET_MAX_EXTERNAL`
 * distinct prefixes of `Manifest.SECRET_EXTERNAL_PREFIX`, none reaching the modules' secrets (`kf-modules`), only on a
 * `platform` secret -- the platform's rules and texts.
 */
function secretExternal(at: string, scope: string | null, external: unknown, errors: string[]): void {
  const max = Manifest.SECRET_MAX_EXTERNAL;
  if (!Array.isArray(external) || external.length === 0 || external.length > max) {
    errors.push(`${at}.external must be a non-empty array of at most ${max} prefixes`);
    if (!Array.isArray(external)) return;
  }
  if (scope !== 'platform') errors.push(`${at}.external is allowed only on a platform secret`);
  const seen = new Set<string>();
  external.forEach((item: unknown, j: number) => {
    const prefix = text(item);
    if (prefix == null || !SECRET_EXTERNAL_PREFIX.test(prefix)) {
      errors.push(`${at}.external[${j}] must match ${Manifest.SECRET_EXTERNAL_PREFIX} (got ${quoted(item)})`);
    } else if (prefix.split('/').includes('kf-modules')) {
      errors.push(`${at}.external[${j}] "${prefix}" must not reach the modules' secrets (kf-modules)`);
    } else if (seen.has(prefix)) {
      errors.push(`${at}.external[${j}] "${prefix}" is declared twice`);
    } else {
      seen.add(prefix);
    }
  });
}

/**
 * `provides` (protocol 1.1): closed objects `{port, path}`; the port grammar and once per
 * port; `path` the path of a GET route this module declares. Whether the platform consumes the port is its catalog.
 */
function provides(root: Tree, items: unknown[], errors: string[]): void {
  const getPaths = new Set<string>();
  for (const route of objects(map(root.capabilities).routes)) {
    if (route.method === 'GET' && typeof route.path === 'string') getPaths.add(route.path);
  }
  const seen = new Set<string>();
  items.forEach((item, i) => {
    const at = `${FILE}: capabilities.provides[${i}]`;
    if (!isObject(item)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const p = item;
    for (const key of Object.keys(p)) {
      if (!Manifest.PROVIDE_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a provided read (known: ${Manifest.PROVIDE_KEYS.join(', ')})`);
      }
    }
    const port = text(p.port);
    if (port == null || !PORT.test(port)) {
      errors.push(`${at}.port ${quoted(p.port)} must match ${PORT.source}`);
    } else if (seen.has(port)) {
      errors.push(`${at}.port "${port}" is declared twice`);
    } else {
      seen.add(port);
    }
    const path = text(p.path);
    if (path == null || !getPaths.has(path)) {
      errors.push(`${at}.path ${quoted(p.path)} is not a GET route this module declares `
        + '(capabilities.routes[] with method GET)');
    }
  });
}

/**
 * `capabilities.slots` (protocol 2.0, `schemaVersion` 3 only, sdk-protocol.md §7.12): closed objects; the key
 * grammar (`Manifest.SLOT_KEY`, at most `Manifest.MAX_SLOT_KEY_LENGTH` characters, the major as the last segment),
 * unique; `kind` A/B/D/R and a `type` of the kind; `visibility` mandatory (`internal` | `public`); `label` a key of
 * `i18n.en`; the keys of one kind only on that kind; `layerKinds` on B, `events` / `commands` on D after its type,
 * `dataSchema` on R (`m.{owner}.{name}.v{N}`, the owner being this module).
 */
function slots(slug: string | null, items: unknown[], enKeys: Set<string>, errors: string[]): void {
  const seen = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    const at = `${FILE}: capabilities.slots[${i}]`;
    if (!isObject(items[i])) {
      errors.push(`${at} must be an object`);
      continue;
    }
    const s = map(items[i]);
    for (const key of Object.keys(s)) {
      if (!Manifest.SLOT_KEYS.includes(key)) {
        errors.push(`${at}: "${key}" is not a key of a slot (known: ${Manifest.SLOT_KEYS.join(', ')})`);
      }
    }
    const key = text(s.key);
    if (key == null || key.length > Manifest.MAX_SLOT_KEY_LENGTH || !SLOT_KEY.test(key)) {
      errors.push(`${at}.key ${quoted(s.key)} must match ${SLOT_KEY.source} (at most ${Manifest.MAX_SLOT_KEY_LENGTH}`
        + ' characters; the major is the last segment, e.g. viewport.layers.v1)');
    } else if (seen.has(key)) {
      errors.push(`${at}.key "${key}" is declared twice`);
    } else {
      seen.add(key);
    }
    const kind = SlotKind.of(s.kind);
    if (kind == null) {
      errors.push(`${at}.kind must be "A", "B", "D" or "R" (got ${quoted(s.kind)})`);
    } else if (typeof s.type !== 'string' || !kind.types.includes(s.type)) {
      errors.push(`${at}.type must be one of [${kind.types.join(', ')}] on kind ${kind.name} (got ${quoted(s.type)})`);
    }
    if (slotVisibility(s.visibility) == null) {
      errors.push(`${at}.visibility must be "internal" or "public" (got ${quoted(s.visibility)})`
        + ': the owner chooses it, there is no default');
    }
    if (has(s, 'label') && s.label != null) {
      requireKey(`${at}.label`, text(s.label), enKeys, errors);
    }
    if (kind == null) continue;
    for (const other of SlotKind.values()) {
      if (other === kind) continue;
      for (const own of other.ownKeys) {
        if (has(s, own) && !kind.ownKeys.includes(own)) {
          errors.push(`${at}.${own} is only on kind ${other.name}`);
        }
      }
    }
    switch (kind) {
      case SlotKind.A:
        if (has(s, 'publicMembers')) names(`${at}.publicMembers`, s.publicMembers, SLOT_MEMBER, false, errors);
        if (has(s, 'actions')) names(`${at}.actions`, s.actions, SLOT_NAME, false, errors);
        break;
      case SlotKind.B:
        names(`${at}.layerKinds`, s.layerKinds, SLOT_LAYER_KIND, true, errors);
        break;
      case SlotKind.D: {
        const events = s.type === 'events';
        const own = events ? 'events' : 'commands';
        const foreign = events ? 'commands' : 'events';
        names(`${at}.${own}`, s[own], SLOT_NAME, true, errors);
        if (has(s, foreign)) {
          errors.push(`${at}.${foreign} is not allowed on a slot of type ${String(s.type)}`);
        }
        break;
      }
      case SlotKind.R: {
        const schema = text(s.dataSchema);
        if (schema == null || slug == null
          || !schema.startsWith(`m.${slug}.`)
          || !SLOT_SCHEMA_TAIL.test(schema.substring(slug.length + 3))) {
          errors.push(`${at}.dataSchema ${quoted(s.dataSchema)} is required on kind R: the id `
            + 'm.{owner}.{name}.v{N} of the schema the owner validates the data against, the owner '
            + `being this module ("${slug}")`);
        }
        break;
      }
    }
  }
}

/** A list of distinct names, each matching `grammar`; `required` = present and non-empty. */
function names(at: string, raw: unknown, grammar: RegExp, required: boolean, errors: string[]): void {
  if (raw == null && !required) return;
  if (!Array.isArray(raw) || (required && raw.length === 0)) {
    errors.push(`${at} is required: a non-empty array of names matching ${grammar.source}`);
    return;
  }
  const seen = new Set<unknown>();
  for (const name of raw) {
    if (typeof name !== 'string' || !grammar.test(name)) {
      errors.push(`${at}: ${quoted(name)} must match ${grammar.source}`);
    } else if (seen.has(name)) {
      errors.push(`${at}: "${name}" is declared twice`);
    } else {
      seen.add(name);
    }
  }
}

/**
 * `requires` (protocol 2.0): the shape the platform checks at upload
 * (`ModulePackValidator.validateRequires`), with its messages -- a slug, never the module itself, once, a
 * non-empty range in the platform's grammar (`VersionRange`); `requires[i].optional` only under `schemaVersion` 3, and
 * then a boolean. Whether the required module exists, its range is satisfied, a cycle appears or holds are the
 * platform's (they need the registry). Checked under every `schemaVersion`.
 */
function requires(root: Tree, schemaVersion: number, errors: string[]): void {
  const items = root.requires;
  if (!Array.isArray(items)) return;
  const self = text(root.slug);
  const seen = new Set<string>();
  for (let i = 0; i < items.length; i++) {
    if (!isObject(items[i])) {
      errors.push(`${FILE}: "requires" contains a non-object entry`);
      continue;
    }
    const entry = map(items[i]);
    if (has(entry, 'optional')) {
      if (schemaVersion < 3) {
        errors.push(`${FILE}: "requires[${i}].optional" requires "schemaVersion": 3 (this manifest is `
          + `schemaVersion ${schemaVersion})`);
      } else if (typeof entry.optional !== 'boolean') {
        errors.push(`${FILE}: "requires[${i}].optional" must be a boolean (got ${jsonish(entry.optional)})`);
      }
    }
    const slug = text(entry.slug);
    if (slug == null || !SLUG.test(slug)) {
      errors.push(`${FILE}: "requires" slug must match ${SLUG.source} but was ${quoted(entry.slug)}`);
      continue;
    }
    if (slug === self) errors.push(`${FILE}: a module cannot require itself ("${slug}")`);
    if (seen.has(slug)) errors.push(`${FILE}: duplicate "requires" entry for "${slug}"`);
    seen.add(slug);
    const range = text(entry.versionRange);
    if (range == null || range.trim() === '') {
      errors.push(`${FILE}: "requires" entry for "${slug}" needs a non-empty "versionRange"`);
      continue;
    }
    const problem = versionRangeProblem(range);
    if (problem != null) errors.push(`${FILE}: "requires" entry for "${slug}": ${problem}`);
  }
}

/**
 * `featureFlags[i].requiresModules` (protocol 2.0): only under `schemaVersion` 3; an array of module
 * slugs, never the module itself, each one declared in `requires` (mandatory or optional). The platform's messages
 * (`ModuleManifestValidator`).
 */
function flagRequiresModules(root: Tree, schemaVersion: number, errors: string[]): void {
  const flags = root.featureFlags;
  if (!Array.isArray(flags)) return;
  const self = text(root.slug);
  const required = new Set<string>();
  for (const entry of objects(root.requires)) {
    if (typeof entry.slug === 'string') required.add(entry.slug);
  }
  for (let i = 0; i < flags.length; i++) {
    if (!isObject(flags[i])) continue;
    const flag = map(flags[i]);
    if (!has(flag, 'requiresModules') || flag.requiresModules == null) continue;
    const at = `${FILE}: "featureFlags[${i}].requiresModules"`;
    if (schemaVersion < 3) {
      errors.push(`${at} requires "schemaVersion": 3 (this manifest is schemaVersion ${schemaVersion})`);
      continue;
    }
    const modules = flag.requiresModules;
    if (!Array.isArray(modules)) {
      errors.push(`${at} must be an array of module slugs`);
      continue;
    }
    for (const module of modules) {
      if (typeof module !== 'string' || !SLUG.test(module)) {
        errors.push(`${at}: ${jsonish(module)} is not a module slug (must match ${SLUG.source})`);
      } else if (module === self) {
        errors.push(`${at}: "${module}" is this module itself`);
      } else if (!required.has(module)) {
        errors.push(`${at}: "${module}" is not in requires (declare it there, mandatory or optional)`);
      }
    }
  }
}

/** The platform's `VersionRange.EXPECTED_FORM`, named in every parse error. */
const VERSION_RANGE_EXPECTED_FORM = 'expected one of: "*", "1.2.3", ">=1.2.3", ">1.2.3", "<=1.2.3", "<1.2.3", '
  + 'or two bounds separated by a space such as ">=1.2.0 <2.0.0"';

/**
 * The platform's grammar of `requires[].versionRange` (`VersionRange.parse`), copied: `*` or blank = any; one or
 * two space-separated bounds `>=`, `>`, `<=`, `<`, `=` or a bare version (exact); at most one lower and one upper bound,
 * an exact version alone; the bounds must not cross.
 *
 * @returns the platform's message when `raw` is not a range, else `null`
 */
function versionRangeProblem(raw: string | null): string | null {
  const value = raw == null ? '' : raw.trim();
  if (value === '' || value === '*') return null;
  let lower: string | null = null;
  let lowerInclusive = false;
  let upper: string | null = null;
  let upperInclusive = false;
  for (const token of value.split(JAVA_WHITESPACE)) {
    if (token === '') continue;
    let side: 'lower' | 'upper' | 'exact';
    let version: string;
    let inclusive: boolean;
    if (token.startsWith('>=')) { side = 'lower'; version = token.substring(2); inclusive = true; }
    else if (token.startsWith('<=')) { side = 'upper'; version = token.substring(2); inclusive = true; }
    else if (token.startsWith('>')) { side = 'lower'; version = token.substring(1); inclusive = false; }
    else if (token.startsWith('<')) { side = 'upper'; version = token.substring(1); inclusive = false; }
    else if (token.startsWith('=')) { side = 'exact'; version = token.substring(1); inclusive = true; }
    else { side = 'exact'; version = token; inclusive = true; }
    version = version.trim();
    if (!SEMVER.test(version)) {
      return rangeMalformed(raw, `'${version}' is not a semantic version`);
    }
    switch (side) {
      case 'lower':
        if (lower != null) return rangeMalformed(raw, 'two lower bounds');
        lower = version;
        lowerInclusive = inclusive;
        break;
      case 'upper':
        if (upper != null) return rangeMalformed(raw, 'two upper bounds');
        upper = version;
        upperInclusive = inclusive;
        break;
      default:
        if (lower != null || upper != null) {
          return rangeMalformed(raw, 'an exact version combined with another bound');
        }
        lower = version;
        upper = version;
        lowerInclusive = true;
        upperInclusive = true;
    }
  }
  if (lower != null && upper != null) {
    const comparison = compareSemver(lower, upper);
    if (comparison > 0 || (comparison === 0 && !(lowerInclusive && upperInclusive))) {
      return `version range is impossible: lower bound ${lowerInclusive ? '>=' : '>'}${lower}`
        + ` is above upper bound ${upperInclusive ? '<=' : '<'}${upper}`
        + ', so no version could ever satisfy it';
    }
  }
  return null;
}

function rangeMalformed(raw: string | null, why: string): string {
  return `version range '${raw}' could not be parsed (${why}); ${VERSION_RANGE_EXPECTED_FORM}`;
}

/** A value as JSON-ish text for a message: a string quoted, anything else as it prints. */
function jsonish(value: unknown): string {
  return quoted(value);
}

export function isInAppPath(raw: unknown): boolean {
  if (typeof raw !== 'string' || raw.length > MAX_NAV_URL_LENGTH || !raw.startsWith('/')) return false;
  if (raw.includes('//') || raw.includes('..') || raw.includes('%') || raw.includes('\\')
    || raw.includes('?') || raw.includes('#')) {
    return false;
  }
  return !WHITESPACE_OR_CONTROL.test(raw);
}

// ---- registration ⊆ manifest -----------------------------------------------------------------------------

function checkRegistration(manifest: Manifest, body: Tree, errors: string[]): void {
  const hookList = objects(body.hooks);
  if (!manifest.usesKeys()) {
    hookList.forEach((h, i) => {
      if (h.onFailure === 'block') {
        errors.push(`registration: hooks[${i}]: onFailure "block" requires a capability, which a schemaVersion 1 manifest`
          + ' cannot declare; upgrade the module to manifest schemaVersion 2');
      }
    });
    return;
  }
  const caps: Capabilities = manifest.capabilities;
  const enKeys = new Set(Object.keys(manifest.i18n.en ?? {}));

  hookList.forEach((h, i) => {
    const event = text(h.event);
    const at = `registration: hooks[${i}]`;
    const declared = caps.hooks.find((c) => event === c.point);
    if (declared == null) {
      errors.push(`${at}: event "${event}" is not declared in kf-module-conf.json capabilities.hooks (declared: ${declaredPoints(caps)})`);
      return;
    }
    if (h.onFailure === 'block' && declared.onFailure !== 'block') {
      errors.push(`${at}: onFailure "block" on ${event} requires the capability declared in kf-module-conf.json`
        + ' (capabilities.hooks[].onFailure = "block")');
    }
  });

  objects(body.fields).forEach((f, i) => {
    const entity = text(f.entity) ?? 'model';
    const name = text(f.name);
    const declared = caps.fields.some((c) => entity === c.entity && name === c.name && f.type === c.type && f.table === c.table);
    if (!declared) {
      errors.push(`registration: fields[${i}]: field "${entity}.${name}" (${f.type}, table ${f.table}) is not declared in`
        + ' kf-module-conf.json capabilities.fields');
    }
    labelKey(`registration: fields[${i}]`, f.label, enKeys, errors);
  });

  objects(body.surfaces).forEach((s, i) => {
    const declared = caps.surfaces.some((c) => s.slug === c.slug && s.kind === c.kind);
    if (!declared) {
      errors.push(`registration: surfaces[${i}]: surface "${s.slug}" (${s.kind}) is not declared in kf-module-conf.json capabilities.surfaces`);
    } else if (s.slug === Manifest.ORDERFLOW_STEP) {
      // protocol 2.0 (§16.5): the step the contribution renders, as declared
      const key = s.key;
      if (typeof key !== 'string' || key.trim() === '') {
        errors.push(`registration: surfaces[${i}] on "${Manifest.ORDERFLOW_STEP}" needs a "key": the key of the step it renders`);
      } else if (!caps.surfaces.some((c) => c.slug === Manifest.ORDERFLOW_STEP && c.key === key)) {
        errors.push(`registration: surfaces[${i}]: the step "${key}" of "${Manifest.ORDERFLOW_STEP}" is not declared in`
          + ' kf-module-conf.json capabilities.surfaces');
      }
    }
    labelKey(`registration: surfaces[${i}]`, s.label, enKeys, errors);
  });
}

/** a key used at registration has an `en` text in the manifest; an absent label stays legal. */
function labelKey(at: string, label: unknown, enKeys: Set<string>, errors: string[]): void {
  if (label == null) return;
  if (typeof label !== 'string' || !enKeys.has(label)) {
    errors.push(`${at}: label ${quoted(label)} is not a key of the manifest i18n block with an "en" text`);
  }
}

function declaredPoints(caps: Capabilities): string {
  const points = caps.hooks.map((h) => h.point).filter((p): p is string => p != null).join(', ');
  return points === '' ? 'none' : points;
}

function list(o: unknown): unknown[] {
  return Array.isArray(o) ? [...o] : [];
}

function has(o: Tree, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(o, key);
}

function quoted(value: unknown): string {
  return value == null ? 'null' : typeof value === 'string' ? `"${value}"` : typeof value === 'object' ? JSON.stringify(value) : String(value);
}
