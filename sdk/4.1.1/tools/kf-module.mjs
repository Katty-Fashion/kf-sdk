#!/usr/bin/env node
// kf-module: the operator's tool for signed module packs, in the format of kf-module-lib.mjs —
// the statement `kf-module-statement/1` in a DSSE envelope (Ed25519) over the canonical tree, written as
// `kf-module.sig` at the root of the pack. The platform verifies exactly the same thing on the tree it extracts
// (the platform, ModulePackSignatures) and never signs (G1).
//
//   node sdk/tools/kf-module.mjs keygen --key-id <id> --publisher <p> --namespaces <prefix,...> --out <dir> [--added-at <yyyy-mm-dd>]
//   node sdk/tools/kf-module.mjs sign <zip> --key <file.key> [--public <fragment.json>] [--resign] [--repo <dir>] [--out <zip>] [--signed-at <iso>]
//   node sdk/tools/kf-module.mjs sign <zip|dir> --key-seed-file <fixtures keys.json>#<keyId> [--resign] [--repo <dir>] [--out <zip>] [--signed-at <iso>]
//   node sdk/tools/kf-module.mjs verify <zip|dir> --trust <trust-store.json> [--use upload|existing]
//   node sdk/tools/kf-module.mjs inspect <zip|dir>
//   node sdk/tools/kf-module.mjs release-check --module-dir <d> --version <v> --sdk-ref <sdk-vX.Y.Z> [--sdk-repo <dir>]
//   node sdk/tools/kf-module.mjs build-info --module-dir <d> --version <v> --sdk-ref <ref> --out <file> [--sdk-repo <dir>] [--sdk-variant external|internal] [--built-at <iso>]
//   node sdk/tools/kf-module.mjs verify-runtime --slug <slug> --trust <trust-store.json> [--project kfmod-<slug>]
//   node sdk/tools/kf-module.mjs dev-build <zip|dir> [--out <zip>]
//
// keygen: the release key, ONCE, offline: `<out>/<keyId>.key` = PKCS#8 encrypted with the passphrase (never
//       overwritten, mode 0600 where the system has modes) and `<out>/<keyId>.public.json` = the public fragment, a
//       trust-store entry with its `fingerprint`. The passphrase is read from the terminal without echo (asked twice),
//       or, without a terminal, from the first two lines of stdin. There is NO option and NO environment variable for
//       it; any unknown option is refused.
// sign: the encrypted key (`--key`, its fragment beside it as <name>.public.json or `--public`), or one of the TEST keys
//       of the fixtures (`--key-seed-file`, refused for any other key). Before the key is opened it refuses (design
//       §3.1 step 4): a zip that is not `<slug>-<version>.zip`, repeated or unsafe names, no kf-build.json or
//       one that is dirty / not on the tag `<slug>-v<version>` / not on an `sdk-v*` tag, a FROM or `image:` without a
//       digest, a tag that does not point at source.commit (or a signed tag that does not verify) in the git
//       repository of the pack (`--repo`; outside a repository the tag check is reported as not done), a key whose
//       prefixes do not cover the slug. A signed pack is re-signed only with --resign, which changes nothing but
//       kf-module.sig: contentSha256 stays the same, which proves the release pack is the tested one. After signing it
//       verifies the result with the key's own entry and prints contentSha256 and the statement digest.
// verify: exit 0 and "OK" with the publisher (from the key, never from `author`), the trust level, contentSha256
//       and the statement digest; exit 1 and "REFUSED <reason>: <detail>" on stderr for a refusal; exit 2 for a usage
//       or I/O error.
// inspect: what a pack declares (statement, kf-build.json, inputs), WITHOUT verifying anything; a dev build is shown as
//       `<slug>@dev` with the source it derives from (`devBuildOf`).
// sign / release-check refuse a dev build (`devOf`, or a slug `*-dev`) with the reason `dev-build`, before any key is
//       opened: a dev build is never signed.
// release-check / build-info: called by a module's build.sh (`--release`): the checks before a release build and the
//       provenance file kf-build.json (`kf-build/1`), written in both build modes with the real state.
// verify-runtime: the operator, on the host, checks what runs (kf-module-runtime.mjs;).
// dev-build: the dev build of a module: `<slug>-dev-<version>.zip`, slug `<slug>-dev` with
//       `devOf: <slug>`, its routes, rights and labels its own, the code copied byte for byte, never signed
//       (deriveDevBuild in kf-module-lib.mjs). The platform installs it, unsigned, on a development organisation only.
import { existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMain, parseArgs } from './lib.mjs';
import {
  BUILD_INFO_FILE, DevBuildError, KEY_USE, MANIFEST_FILE, SIGNATURE_FILE, SignatureError, buildInfo, buildInputs, canonicalFiles,
  checkBuildInfo, contentSha256, deriveDevBuild, devBuildNotation, fixtureTestKey, generateReleaseKey, gitRoot, loadEncryptedKey, parseTrustStore,
  publicKeyBase64, readPassphrase, readTree, readZip, refuseDevBuild, releaseCheck, sha256hex, signEntries, tagInfo, treeToZip,
  trustStoreFromFragments, unverifiedStatement, verifyTree,
} from './kf-module-lib.mjs';
import { verifyRuntime } from './kf-module-runtime.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURES = join(HERE, 'fixtures', 'module-signature');
const FIXTURE_KEYS = join(FIXTURES, 'keys.json');
const FIXTURE_STORE = join(FIXTURES, 'trust-store.test.json');
/** Any Ed25519 SPKI (RFC 8032 TEST 1): only to validate a fragment's other fields before a key exists. */
const STAND_IN_SPKI = 'MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=';

const USAGE = `usage:
  kf-module keygen --key-id <id> --publisher <publisher> --namespaces <prefix,...> --out <dir> [--added-at <yyyy-mm-dd>]
  kf-module sign <zip> --key <file.key> [--public <fragment.json>] [--resign] [--repo <dir>] [--out <zip>] [--signed-at <iso>]
  kf-module sign <zip|dir> --key-seed-file <fixtures keys.json>#<keyId> [--resign] [--repo <dir>] [--out <zip>] [--signed-at <iso>]
  kf-module verify <zip|dir> --trust <trust-store.json> [--use upload|existing]
  kf-module inspect <zip|dir>
  kf-module release-check --module-dir <dir> --version <v> --sdk-ref <sdk-vX.Y.Z> [--sdk-repo <dir>]
  kf-module build-info --module-dir <dir> --version <v> --sdk-ref <ref> --out <file> [--sdk-repo <dir>] [--sdk-variant external|internal] [--built-at <iso>]
  kf-module verify-runtime --slug <slug> --trust <trust-store.json> [--project kfmod-<slug>]
  kf-module dev-build <zip|dir> [--out <zip>]
The passphrase is read from the terminal (no echo) or from the first line of stdin; never from an option.`;

/** A refusal of the tool itself (exit 1), beside the verifier's SignatureError. */
export class ReleaseError extends Error {
  constructor(reason, detail) {
    super(`${reason}: ${detail}`);
    this.reason = reason;
    this.detail = detail;
  }
}

function readPack(path) {
  return statSync(path).isDirectory() ? readTree(path) : readZip(readFileSync(path));
}

// ---------------------------------------------------------------------------
// keygen
// ---------------------------------------------------------------------------

export async function keygen(args, { passphrase = () => readPassphrase({ confirm: true }) } = {}) {
  for (const name of ['key-id', 'publisher', 'namespaces', 'out']) {
    if (!args[name]) throw new Error(`--${name} is required`);
  }
  const keyFile = join(args.out, `${args['key-id']}.key`);
  const fragmentFile = join(args.out, `${args['key-id']}.public.json`);
  for (const f of [keyFile, fragmentFile]) {
    if (existsSync(f)) throw new Error(`${f} exists; a key is never overwritten`);
  }
  if (!existsSync(args.out) || !statSync(args.out).isDirectory()) throw new Error(`--out ${args.out} is not a directory`);
  const namespaces = args.namespaces.split(',').map((p) => p.trim());
  // the fragment's rules (keyId, publisher, namespaces) are checked before the passphrase is asked, on a stand-in
  // public key; generateReleaseKey checks them again on the real one
  parseTrustStore(trustStoreFromFragments([{
    keyId: args['key-id'], publisher: args.publisher, scope: 'release', status: 'active', namespaces,
    publicKey: STAND_IN_SPKI, addedAt: args['added-at'] ?? '1970-01-01',
  }]));
  const { pem, fragment } = generateReleaseKey({
    keyId: args['key-id'], publisher: args.publisher, namespaces, passphrase: await passphrase(),
    ...(args['added-at'] ? { addedAt: args['added-at'] } : {}),
  });
  writeFileSync(keyFile, pem, { flag: 'wx', mode: 0o600 });
  writeFileSync(fragmentFile, `${JSON.stringify(fragment, null, 2)}\n`, { flag: 'wx' });
  return { keyFile, fragmentFile, fragment };
}

// ---------------------------------------------------------------------------
// sign
// ---------------------------------------------------------------------------

/** The fragment of `--key`: `--public`, or `<name>.public.json` beside `<name>.key`. */
function fragmentOf(args) {
  const path = args.public ?? (args.key.endsWith('.key') ? `${args.key.slice(0, -'.key'.length)}.public.json` : null);
  if (!path || !existsSync(path)) {
    throw new Error(`the public fragment of ${args.key} is needed (keyId, publisher, namespaces): put it beside the key as <name>.public.json or give --public`);
  }
  const fragment = JSON.parse(readFileSync(path, 'utf8'));
  parseTrustStore(trustStoreFromFragments([fragment]));
  return fragment;
}

/** The key's trust-store entry and a loader of its private half — the entry is known before the passphrase is asked. */
function keySource(args) {
  if (args.key) {
    const entry = fragmentOf(args);
    return {
      entry,
      load: async (passphrase) => {
        const privateKey = loadEncryptedKey(args.key, await passphrase());
        if (publicKeyBase64(privateKey) !== entry.publicKey) throw new Error(`the public fragment of ${entry.keyId} does not belong to ${args.key}`);
        return { keyId: entry.keyId, privateKey };
      },
    };
  }
  const spec = args['key-seed-file'];
  if (!spec.includes('#')) throw new Error('--key-seed-file <keys.json>#<keyId>');
  const hash = spec.lastIndexOf('#');
  const test = fixtureTestKey(spec.slice(0, hash), spec.slice(hash + 1), { fixtureKeys: FIXTURE_KEYS, fixtureStore: FIXTURE_STORE });
  return { entry: test.entry, load: async () => ({ keyId: test.keyId, privateKey: test.privateKey }) };
}

/**
 * The release checks of `sign`, in the order of design §3.1 step 4, before any key is opened: the zip (read, no
 * repeated name) → the tree (safe paths) → the manifest and the pack name → kf-build.json → the digests → the source
 * tag (and the SDK tag) in the git repository → the key's prefix. Returns what the signing needs.
 */
export function releaseChecks(target, tree, args, entry) {
  const unsigned = new Map([...tree].filter(([path]) => path !== SIGNATURE_FILE));
  canonicalFiles(unsigned); // unsafe-path
  const manifest = unsigned.get(MANIFEST_FILE);
  if (!manifest) throw new SignatureError('statement-mismatch', `the pack has no ${MANIFEST_FILE} at its root`);
  let conf;
  try {
    conf = JSON.parse(manifest.toString('utf8'));
  } catch {
    throw new SignatureError('statement-mismatch', `${MANIFEST_FILE} is not JSON`);
  }
  refuseDevBuild(conf, 'sign refuses it');
  const { slug, version } = conf ?? {};
  if (typeof slug !== 'string' || typeof version !== 'string') throw new SignatureError('statement-mismatch', `${MANIFEST_FILE} must declare slug and version`);
  const expectedName = `${slug}-${version}.zip`;
  for (const zip of [statSync(target).isDirectory() ? null : target, args.out ?? null].filter(Boolean)) {
    if (basename(zip) !== expectedName) {
      throw new ReleaseError('pack-name', `${basename(zip)}: the manifest declares ${slug} ${version}, so the pack is ${expectedName}`);
    }
  }
  const inputs = buildInputs((path) => unsigned.get(path));
  const build = checkBuildInfo(unsigned, { slug, version, baseImages: inputs.baseImages });
  if (inputs.problems.length) throw new SignatureError('digest-required', inputs.problems.join('; '));

  const tagName = `${slug}-v${version}`;
  const repo = args.repo ? resolve(args.repo) : gitRoot(dirname(resolve(target)));
  if (args.repo && gitRoot(repo) === null) throw new Error(`--repo ${args.repo} is not a git repository`);
  let sourceTag = 'not checked (the pack is not in a git repository; give --repo <the module repository>)';
  let sdkTag = 'not checked';
  if (repo) {
    const t = tagInfo(repo, tagName);
    if (!t) throw new ReleaseError('source-tag', `the tag ${tagName} is not found in ${repo}`);
    if (t.commit !== build.source.commit) {
      throw new ReleaseError('source-tag', `the tag ${tagName} points at ${t.commit.slice(0, 12)}, ${BUILD_INFO_FILE} at ${build.source.commit.slice(0, 12)}`);
    }
    if (t.signed && !t.verified) throw new ReleaseError('source-tag', `the signature of the tag ${tagName} does not verify (git verify-tag)`);
    sourceTag = `${tagName} -> ${t.commit.slice(0, 12)} (${t.signed ? 'signed, verified' : `${t.annotated ? 'annotated' : 'lightweight'}, unsigned`})`;
    const s = tagInfo(repo, build.sdk.tag);
    if (s) {
      if (s.commit !== build.sdk.commit) {
        throw new ReleaseError('sdk-ref', `the tag ${build.sdk.tag} points at ${s.commit.slice(0, 12)}, ${BUILD_INFO_FILE} at ${build.sdk.commit.slice(0, 12)}`);
      }
      if (s.signed && !s.verified) throw new ReleaseError('sdk-ref', `the signature of the tag ${build.sdk.tag} does not verify (git verify-tag)`);
      sdkTag = `${build.sdk.tag} -> ${s.commit.slice(0, 12)} (${s.signed ? 'signed, verified' : 'unsigned'})`;
    } else {
      sdkTag = `${build.sdk.tag}: not in ${repo}, not checked`;
    }
  }
  if (!entry.namespaces.some((p) => slug.startsWith(p))) {
    throw new SignatureError('key-not-for-namespace', `key ${entry.keyId} (publisher ${entry.publisher}) does not cover the slug ${slug}`);
  }
  return { unsigned, images: inputs.images, sourceTag, sdkTag, sdkVariant: build.sdk.variant };
}

/**
 * Signs a pack. Every check runs before the key is opened (the passphrase is asked last); the result is verified with
 * the key's own entry before anything is written. Returns `{keyId, publisher, trust, contentSha256, statementSha256,
 * previous, sourceTag, sdkTag}`.
 */
export async function sign(target, args, { passphrase = () => readPassphrase() } = {}) {
  if (Boolean(args.key) === Boolean(args['key-seed-file'])) {
    throw new Error('exactly one of --key <file.key> (the release key) or --key-seed-file <keys.json>#<keyId> (a TEST key)');
  }
  const directory = statSync(target).isDirectory();
  if (args.key && directory) throw new Error('the release key signs a zip, not a directory');
  let tree;
  try {
    tree = readPack(target);
  } catch (e) {
    if (e instanceof SignatureError) throw e;
    throw new SignatureError('unsafe-path', e.message);
  }
  const existing = tree.get(SIGNATURE_FILE);
  if (existing && !args.resign) {
    const was = unverifiedStatement(existing);
    throw new ReleaseError('already-signed', `${target} already has a ${SIGNATURE_FILE}${was?.keyId ? ` (key ${was.keyId})` : ''}; use --resign to replace the signature`);
  }
  if (!existing && args.resign) throw new ReleaseError('not-signed', `--resign: ${target} has no ${SIGNATURE_FILE} to replace`);
  const source = keySource(args);
  const checked = releaseChecks(target, tree, args, source.entry);
  const key = await source.load(passphrase);

  const signed = signEntries(checked.unsigned, { key, signedAt: args['signed-at'], images: checked.images });
  const verdict = verifyTree(signed, parseTrustStore(trustStoreFromFragments([{ ...source.entry, status: 'active' }])), { use: KEY_USE.UPLOAD });
  const previous = existing ? unverifiedStatement(existing) : null;
  if (previous && previous.statement?.contentSha256 !== verdict.contentSha256) {
    throw new ReleaseError('content-changed', `--resign: the previous statement covered ${previous.statement?.contentSha256}, this pack is ${verdict.contentSha256}`);
  }
  if (directory) writeFileSync(join(target, SIGNATURE_FILE), signed.get(SIGNATURE_FILE));
  if (!directory || args.out) writeFileSync(args.out ?? target, treeToZip(signed));
  return {
    keyId: verdict.keyId, publisher: verdict.publisher, trust: verdict.trust, contentSha256: verdict.contentSha256,
    statementSha256: verdict.statementSha256,
    previous: previous ? sha256hex(Buffer.from(JSON.parse(existing.toString('utf8')).payload, 'base64')) : null,
    sourceTag: checked.sourceTag, sdkTag: checked.sdkTag, sdkVariant: checked.sdkVariant, test: Boolean(args['key-seed-file']),
  };
}

// ---------------------------------------------------------------------------
// verify, inspect
// ---------------------------------------------------------------------------

export function verify(target, args) {
  if (!args.trust) throw new Error('--trust <trust-store.json> is required');
  const use = args.use ?? KEY_USE.UPLOAD;
  if (!Object.values(KEY_USE).includes(use)) throw new Error('--use must be upload or existing');
  return verifyTree(readPack(target), parseTrustStore(readFileSync(args.trust, 'utf8')), { use });
}

/** What a pack declares, read WITHOUT a trust store (nothing is verified): rows for the operator. */
export function inspect(target) {
  const tree = readPack(target);
  const rows = [];
  const manifest = tree.get(MANIFEST_FILE);
  let conf = null;
  try {
    conf = manifest ? JSON.parse(manifest.toString('utf8')) : null;
  } catch {
    conf = null;
  }
  rows.push(`${conf?.slug ?? '?'} ${conf?.version ?? '?'}  (${target})`);
  const notation = devBuildNotation(conf);
  if (notation !== null) {
    rows.push(`  dev build        ${notation} (slug ${conf.slug}, ${conf.devOf === undefined ? 'NO devOf: the platform refuses it' : `devOf ${conf.devOf}`}; never signed)`);
  }
  const unsigned = new Map([...tree].filter(([path]) => path !== SIGNATURE_FILE));
  let content = null;
  try {
    content = contentSha256(canonicalFiles(unsigned));
  } catch (e) {
    rows.push(`  tree             ${e.message}`);
  }
  const sig = tree.get(SIGNATURE_FILE);
  const parsed = sig ? unverifiedStatement(sig) : null;
  if (!sig) {
    rows.push('  signature        none (unsigned)');
    if (content) rows.push(`  contentSha256    ${content}`);
  } else if (!parsed) {
    rows.push(`  signature        ${SIGNATURE_FILE} is not a readable envelope`);
  } else {
    const s = parsed.statement;
    rows.push(`  key              ${parsed.keyId}`);
    rows.push(`  signedAt         ${s.signedAt}`);
    rows.push(`  contentSha256    ${s.contentSha256} (${s.contentSha256 === content ? 'matches the tree' : `DIFFERS: the tree is ${content}`})`);
    rows.push(`  statementSha256  ${sha256hex(Buffer.from(JSON.parse(sig.toString('utf8')).payload, 'base64'))}`);
    rows.push(`  images           ${(s.images ?? []).map((i) => `${i.service}=${i.ref}`).join(', ') || '-'}`);
  }
  const buildRaw = tree.get(BUILD_INFO_FILE);
  if (buildRaw) {
    try {
      const b = JSON.parse(buildRaw.toString('utf8'));
      const dirty = b.source?.dirty ?? b.dirty;
      rows.push(`  source           ${b.source?.repo ?? '-'}@${b.source?.commit ?? '-'} (tag ${b.source?.tag ?? '-'}${b.source?.tagSigned ? ', signed' : ''}; dirty ${dirty})`);
      rows.push(`  sdk              ${b.sdk?.tag ?? '-'} (${b.sdk?.commit ?? '-'}), variant ${b.sdk?.variant ?? '- (none: public)'}`);
      if (b.baseImages) rows.push(`  baseImages       ${b.baseImages.join(', ') || '-'}`);
      if (b.devBuildOf) {
        const d = b.devBuildOf;
        rows.push(`  devBuildOf       ${d.slug ?? '-'} ${d.version ?? '-'} (content ${d.contentSha256 ?? '-'}, pack ${d.packSha256 ?? '-'})`);
      }
      if (b.builtAt) rows.push(`  builtAt          ${b.builtAt}${b.tools ? ` (node ${b.tools.node ?? '-'}, java ${b.tools.java ?? '-'})` : ''}`);
    } catch {
      rows.push(`  ${BUILD_INFO_FILE}    not JSON`);
    }
  } else {
    rows.push(`  ${BUILD_INFO_FILE}    none`);
  }
  const inputs = buildInputs((path) => unsigned.get(path));
  rows.push(`  inputs           ${inputs.problems.length ? inputs.problems.join('; ') : `pinned (${inputs.baseImages.length} base image(s), ${inputs.images.length} image(s))`}`);
  rows.push('  (NOT verified: inspect reads the pack without a trust store; `verify --trust` decides)');
  return rows;
}

// ---------------------------------------------------------------------------
// release-check, build-info (called by a module's build.sh)
// ---------------------------------------------------------------------------

function moduleArgs(args, { out = false } = {}) {
  for (const name of ['module-dir', 'version', 'sdk-ref', ...(out ? ['out'] : [])]) {
    if (!args[name]) throw new Error(`--${name} is required`);
  }
  return {
    moduleDir: resolve(args['module-dir']), version: args.version, sdkRef: args['sdk-ref'],
    sdkRepo: args['sdk-repo'] ? resolve(args['sdk-repo']) : undefined,
  };
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

const COMMANDS = {
  keygen: { 'key-id': 'value', publisher: 'value', namespaces: 'value', out: 'value', 'added-at': 'value' },
  sign: { key: 'value', public: 'value', 'key-seed-file': 'value', resign: 'flag', out: 'value', 'signed-at': 'value', repo: 'value' },
  verify: { trust: 'value', use: 'value' },
  inspect: {},
  'release-check': { 'module-dir': 'value', version: 'value', 'sdk-ref': 'value', 'sdk-repo': 'value' },
  'build-info': { 'module-dir': 'value', version: 'value', 'sdk-ref': 'value', 'sdk-repo': 'value', out: 'value', 'built-at': 'value', 'sdk-variant': 'value' },
  'verify-runtime': { slug: 'value', trust: 'value', project: 'value' },
  'dev-build': { out: 'value' },
};
const TARGETED = new Set(['sign', 'verify', 'inspect', 'dev-build']);

async function run(command, target, args) {
  if (command === 'keygen') {
    const k = await keygen(args);
    console.log([
      `generated ${k.fragment.keyId} (${k.fragment.publisher}, namespaces ${k.fragment.namespaces.map((p) => JSON.stringify(p)).join(', ')})`,
      `  private key  ${k.keyFile}  (encrypted; keep it offline, never in a repo, a server or Vault)`,
      `  public       ${k.fragmentFile}  (the entry for module-trust/trust-store.json)`,
      `  fingerprint  ${k.fragment.fingerprint}`,
    ].join('\n'));
    return 0;
  }
  if (command === 'sign') {
    const s = await sign(target, args);
    console.log([
      `signed ${target}${args.out ? ` -> ${args.out}` : ''} with ${s.keyId} (${s.publisher}, ${s.trust})${s.test ? ' — TEST key' : ''}`,
      `  contentSha256    ${s.contentSha256}`,
      `  statementSha256  ${s.statementSha256}`,
      `  source tag       ${s.sourceTag}`,
      `  sdk tag          ${s.sdkTag}`,
      `  sdk variant      ${s.sdkVariant}`,
      ...(s.previous ? [`  re-signed; the previous statement was ${s.previous} (same contentSha256)`] : []),
      'Compare contentSha256 with the pack you tested; then: kf-module verify <zip> --trust <trust-store.json>',
    ].join('\n'));
    return 0;
  }
  if (command === 'inspect') {
    console.log(inspect(target).join('\n'));
    return 0;
  }
  if (command === 'release-check') {
    const r = releaseCheck(moduleArgs(args));
    if (r.problems.length) {
      for (const p of r.problems) console.error(`release-check: ${p}`);
      console.error(`release-check: REFUSED ${r.slug} ${r.version} (${r.problems.length} problem(s)); nothing was built`);
      return 1;
    }
    console.log([
      `release-check: OK ${r.slug} ${r.version}`,
      `  source  ${r.tag} -> ${r.head.slice(0, 12)}${r.tagSigned ? ' (signed, verified)' : ' (unsigned tag)'}, clean tree`,
      `  sdk     ${r.sdk.tag} -> ${r.sdk.commit.slice(0, 12)}${r.sdk.signed ? ' (signed, verified)' : ''}, variant ${r.sdk.variant}`,
      `  bases   ${r.baseImages.join(', ') || '-'}`,
    ].join('\n'));
    return 0;
  }
  if (command === 'build-info') {
    const info = buildInfo({
      ...moduleArgs(args, { out: true }),
      ...(args['built-at'] ? { builtAt: args['built-at'] } : {}),
      ...(args['sdk-variant'] ? { sdkVariant: args['sdk-variant'] } : {}),
    });
    writeFileSync(args.out, `${JSON.stringify(info, null, 2)}\n`);
    console.log(`build-info: ${args.out} (${info.slug} ${info.version}, source ${info.source.commit.slice(0, 12)}${info.source.tag ? ` = ${info.source.tag}` : ''}${info.source.dirty ? ', DIRTY' : ''}, sdk ${info.sdk.tag ?? info.sdk.commit.slice(0, 12)} ${info.sdk.variant})`);
    return 0;
  }
  if (command === 'dev-build') {
    const d = deriveDevBuild(statSync(target).isDirectory() ? readTree(target) : readFileSync(target));
    const out = args.out ?? join(statSync(target).isDirectory() ? dirname(resolve(target)) : dirname(target), d.fileName);
    writeFileSync(out, d.zip);
    console.log([
      `dev build ${d.devOf}@dev (slug ${d.slug}) ${d.version} -> ${out}`,
      `  devBuildOf       ${d.devOf} ${d.version}`,
      `  source pack      ${d.source.packSha256}`,
      `  source content   ${d.source.contentSha256}`,
      `  dev build        ${sha256hex(d.zip)} (unsigned: installed only on a development organization)`,
    ].join('\n'));
    return 0;
  }
  if (command === 'verify-runtime') {
    if (!args.slug) throw new Error('--slug <slug> is required');
    if (!args.trust) throw new Error('--trust <trust-store.json> is required (the module-trust/trust-store.json of the platform)');
    const r = verifyRuntime({
      slug: args.slug, store: parseTrustStore(readFileSync(args.trust, 'utf8')), ...(args.project ? { project: args.project } : {}),
    });
    console.log(r.rows.join('\n'));
    console.log(r.ok ? `verify-runtime: OK ${args.slug}` : `verify-runtime: FAILED ${args.slug} (${r.rows.filter((x) => x.startsWith('FAIL')).length} row(s))`);
    return r.ok ? 0 : 1;
  }
  const v = verify(target, args);
  console.log([
    `OK ${v.statement.slug} ${v.statement.version}`,
    `  publisher        ${v.publisher}`,
    `  trust            ${v.trust}`,
    `  key              ${v.keyId}`,
    `  contentSha256    ${v.contentSha256}`,
    `  statementSha256  ${v.statementSha256}`,
    `  source           ${v.statement.source.repo ?? '-'}@${v.statement.source.commit ?? '-'}`,
    `  sdk              ${v.statement.sdk.tag ?? '-'} (${v.statement.sdk.commit ?? '-'}), variant ${v.statement.sdk.variant ?? '- (none: public)'}`,
  ].join('\n'));
  return 0;
}

async function main(argv) {
  const [command, ...rest] = argv;
  if (!Object.hasOwn(COMMANDS, command ?? '')) {
    console.error(USAGE);
    return 2;
  }
  let target = null;
  let options = rest;
  if (TARGETED.has(command)) {
    [target, ...options] = rest;
    if (!target || target.startsWith('--')) {
      console.error(USAGE);
      return 2;
    }
  }
  try {
    return await run(command, target, parseArgs(options, COMMANDS[command]));
  } catch (e) {
    if (e instanceof SignatureError || e instanceof ReleaseError || e instanceof DevBuildError) {
      console.error(`REFUSED ${e.reason}: ${e.detail}`);
      return 1;
    }
    console.error(`kf-module ${command}: ${e.message}`);
    return 2;
  }
}

if (isMain(import.meta)) process.exitCode = await main(process.argv.slice(2));
