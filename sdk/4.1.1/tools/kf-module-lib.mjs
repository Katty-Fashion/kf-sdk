// kf-module-lib: the ONE definition, in Node, of the signed module pack — the canonical tree,
// contentSha256, the statement `kf-module-statement/1`, PAE and the DSSE envelope, the trust store
// `kf-module-trust/1` — plus a minimal zip reader/writer. The platform's verifier (the platform,
// modulesdk/lifecycle/{domain,infrastructure}/signature) implements the same rules, and the shared vectors of
// fixtures/module-signature/ prove that both give the same contentSha256, the same PAE and the same verdict.
//
// Node >= 22, only node: modules (Ed25519 is native: crypto.sign(null, …)). The CLI is kf-module.mjs;
// added the release key (generateReleaseKey, loadEncryptedKey, readPassphrase: encrypted PKCS#8, never a passphrase
// from arguments or the environment), the release checks (kf-build.json, digests, the source tag) and verify-runtime.
//
// What the platform never does with it: sign. The platform holds public keys only (G1).
import {
  createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign as edSign, verify as edVerify,
} from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, lstatSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { inflateRawSync } from 'node:zlib';

export const STATEMENT_FORMAT = 'kf-module-statement/1';
export const PAYLOAD_TYPE = 'application/vnd.kf.module-statement+json';
export const TRUST_FORMAT = 'kf-module-trust/1';
/** The envelope, at the root of the pack; the only file left out of `files[]`. */
export const SIGNATURE_FILE = 'kf-module.sig';
/** The build provenance (source and SDK commits), a reserved name at the root. */
export const BUILD_INFO_FILE = 'kf-build.json';
export const MANIFEST_FILE = 'kf-module-conf.json';
/** Written by the platform beside the module's compose at install: a pack never carries it. */
export const RUNTIME_OVERRIDE_FILE = 'docker-compose.kf.yml';
/** The publisher of Katty-Fashion's keys. Any other publisher is a third party and may not cover `kf-*`. */
export const KF_PUBLISHER = 'katty-fashion';
export const RESERVED_PREFIX = 'kf-';

/** UPLOAD (and approving a new version) wants an `active` key; EXISTING accepts `active` or `retired`. */
export const KEY_USE = Object.freeze({ UPLOAD: 'upload', EXISTING: 'existing' });
export const TRUST = Object.freeze({ KF_RELEASE: 'KF_RELEASE', THIRD_PARTY: 'THIRD_PARTY' });
export const KEY_STATUS = Object.freeze(['active', 'retired', 'revoked']);

/** The `reason` of a refusal: the same words as the platform's 422 `module-signature`. */
export const REASONS = Object.freeze([
  'unsigned', 'bad-envelope', 'unknown-key', 'key-revoked', 'key-retired', 'key-not-for-namespace', 'bad-signature',
  'statement-mismatch', 'content-mismatch', 'statement-revoked', 'unsafe-path', 'reserved-file', 'identity-mismatch',
  // variant B (on the platform, in the tool): inputs on digests, the build provenance
  'digest-required', 'build-provenance',
]);

const PKCS8_ED25519_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
const SPKI_ED25519_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');
const HEX64 = /^[0-9a-f]{64}$/;
const KEY_ID = /^[a-z0-9][a-z0-9.-]{2,63}$/;
const NAMESPACE = /^[a-z0-9-]*$/;
/** Standard base64, padded, nothing else: the same strict shape the Java side accepts. */
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** A refusal with its machine-readable reason; `detail` names a path or a field of the pack, never a server path. */
export class SignatureError extends Error {
  constructor(reason, detail) {
    super(`${reason}: ${detail}`);
    if (!REASONS.includes(reason)) throw new Error(`unknown signature reason ${reason}`);
    this.reason = reason;
    this.detail = detail;
  }
}

export function sha256hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

/** Order on the UTF-8 bytes of the path (not UTF-16 code units, not a locale). */
export function compareUtf8(a, b) {
  return Buffer.compare(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

// ---------------------------------------------------------------------------
// The canonical tree
// ---------------------------------------------------------------------------

/**
 * Why a path cannot be in a pack, or `null`. Refused, never normalised: `/`-separated, relative, no empty segment,
 * no `.` or `..`, no `\`, no control character (< 0x20, 0x7F), already in Unicode NFC.
 */
export function pathProblem(path) {
  if (typeof path !== 'string' || path.length === 0) return 'empty path';
  if (path.startsWith('/')) return 'absolute path';
  if (path.includes('\\')) return 'backslash in the path';
  for (let i = 0; i < path.length; i++) {
    const c = path.charCodeAt(i);
    if (c < 0x20 || c === 0x7f) return 'control character in the path';
  }
  if (path.normalize('NFC') !== path) return 'the path is not in Unicode NFC';
  for (const segment of path.split('/')) {
    if (segment === '') return 'empty path segment';
    if (segment === '.' || segment === '..') return `"${segment}" segment`;
  }
  return null;
}

/**
 * The canonical list of a tree (`Map<path, Buffer>`): `{path, size, sha256}` sorted on the UTF-8 bytes of the path,
 * without the root names in `exclude` (by default only `kf-module.sig`). Throws `unsafe-path` for a path the rules
 * refuse and for two paths equal after `toLowerCase` (a case-insensitive filesystem would merge them).
 */
export function canonicalFiles(tree, { exclude = [SIGNATURE_FILE] } = {}) {
  const files = [];
  for (const [path, data] of tree) {
    if (exclude.includes(path)) continue;
    const problem = pathProblem(path);
    if (problem) throw new SignatureError('unsafe-path', `${JSON.stringify(path)}: ${problem}`);
    files.push({ path, size: data.length, sha256: sha256hex(data) });
  }
  files.sort((a, b) => compareUtf8(a.path, b.path));
  const seen = new Map();
  for (const f of files) {
    const folded = f.path.toLowerCase();
    if (seen.has(folded)) {
      throw new SignatureError('unsafe-path',
        `${JSON.stringify(seen.get(folded))} and ${JSON.stringify(f.path)} differ only by case`);
    }
    seen.set(folded, f.path);
  }
  return files;
}

/** sha256 over `path + "\n" + size + "\n" + sha256 + "\n"` of every file, in canonical order. */
export function contentSha256(files) {
  const hash = createHash('sha256');
  for (const f of files) hash.update(`${f.path}\n${f.size}\n${f.sha256}\n`, 'utf8');
  return hash.digest('hex');
}

/** The tree of a directory as `Map<path, Buffer>`. A symbolic link or a special file is refused (`unsafe-path`). */
export function readTree(dir) {
  const tree = new Map();
  const walk = (abs, rel) => {
    for (const name of readdirSync(abs)) {
      const childAbs = join(abs, name);
      const childRel = rel === '' ? name : `${rel}/${name}`;
      const st = lstatSync(childAbs);
      if (st.isSymbolicLink()) throw new SignatureError('unsafe-path', `${JSON.stringify(childRel)}: symbolic link`);
      if (st.isDirectory()) walk(childAbs, childRel);
      else if (st.isFile()) tree.set(childRel, readFileSync(childAbs));
      else throw new SignatureError('unsafe-path', `${JSON.stringify(childRel)}: not a regular file`);
    }
  };
  walk(dir, '');
  return tree;
}

// ---------------------------------------------------------------------------
// The statement, PAE and the envelope
// ---------------------------------------------------------------------------

function readJson(bytes, what) {
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch (e) {
    throw new Error(`${what} is not valid JSON: ${e.message}`);
  }
}

/** UTC, to the second: `2026-10-21T12:00:00Z`. */
export function nowUtc() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/**
 * The statement bytes of a tree (`Map<path, Buffer>`): what is signed, verbatim (no canonicalisation). Deterministic
 * for the same tree and the same `signedAt`. `source` and `sdk` come from `kf-build.json` when the pack has one.
 * `images[]` is empty unless given (the digest-pinned images of variant B arrive).
 */
export function buildStatement(tree, { signedAt = nowUtc(), images = [] } = {}) {
  const manifest = tree.get(MANIFEST_FILE);
  if (!manifest) throw new Error(`the pack has no ${MANIFEST_FILE} at its root`);
  for (const path of tree.keys()) {
    if (path === RUNTIME_OVERRIDE_FILE || path.startsWith(`${RUNTIME_OVERRIDE_FILE}/`)) {
      throw new SignatureError('reserved-file', `${RUNTIME_OVERRIDE_FILE} is written by the platform; a pack must not carry it`);
    }
    if (path.startsWith(`${SIGNATURE_FILE}/`)) {
      throw new SignatureError('reserved-file', `${SIGNATURE_FILE} must be a regular file at the root of the pack`);
    }
  }
  const conf = readJson(manifest, MANIFEST_FILE);
  if (conf === null || typeof conf !== 'object' || typeof conf.slug !== 'string' || typeof conf.version !== 'string') {
    throw new Error(`${MANIFEST_FILE} must declare "slug" and "version" as strings`);
  }
  const build = tree.has(BUILD_INFO_FILE) ? readJson(tree.get(BUILD_INFO_FILE), BUILD_INFO_FILE) : {};
  const files = canonicalFiles(tree);
  const statement = {
    format: STATEMENT_FORMAT,
    slug: conf.slug,
    version: conf.version,
    manifestSha256: sha256hex(manifest),
    files,
    contentSha256: contentSha256(files),
    images,
    source: { repo: build?.source?.repo ?? null, commit: build?.source?.commit ?? null, tag: build?.source?.tag ?? null },
    sdk: {
      tag: build?.sdk?.tag ?? null,
      commit: build?.sdk?.commit ?? null,
      // the SDK variant, copied from kf-build.json; absent from a build before it (the
      // statement then has no variant and the module is public), so the vectors signed before stay byte-identical
      ...(build?.sdk?.variant !== undefined ? { variant: build.sdk.variant } : {}),
    },
    signedAt,
  };
  return Buffer.from(`${JSON.stringify(statement, null, 2)}\n`, 'utf8');
}

/** DSSE v1 pre-authentication encoding: `"DSSEv1" SP LEN(type) SP type SP LEN(payload) SP payload`, LEN in bytes. */
export function pae(payloadType, payload) {
  const type = Buffer.from(payloadType, 'utf8');
  return Buffer.concat([
    Buffer.from(`DSSEv1 ${type.length} `, 'utf8'), type, Buffer.from(` ${payload.length} `, 'utf8'), payload,
  ]);
}

/** An Ed25519 private key from its 32-byte seed (hex), through the fixed PKCS#8 prefix. */
export function keyFromSeed(seedHex) {
  const seed = Buffer.from(seedHex, 'hex');
  if (seed.length !== 32) throw new Error('an Ed25519 seed is 32 bytes');
  return createPrivateKey({ key: Buffer.concat([PKCS8_ED25519_PREFIX, seed]), format: 'der', type: 'pkcs8' });
}

/** base64 of the SPKI DER of the public half — the `publicKey` of a trust-store entry. */
export function publicKeyBase64(privateKey) {
  return createPublicKey(privateKey).export({ format: 'der', type: 'spki' }).toString('base64');
}

/**
 * A TEST key of fixtures/module-signature/keys.json. Refused for any other file: the real keys are encrypted and
 * offline: `kf-module keygen` and `sign --key`.
 */
export function testKey(keysFile, keyId) {
  const doc = readJson(readFileSync(keysFile), keysFile);
  if (typeof doc?._warning !== 'string' || !doc._warning.startsWith('TEST KEYS ONLY')) {
    throw new Error(`${keysFile} is not a file of TEST keys; only the test keys of the fixtures are accepted here`);
  }
  const entry = (doc.keys ?? []).find((k) => k.keyId === keyId);
  if (!entry) throw new Error(`no test key ${keyId} in ${keysFile}`);
  const privateKey = keyFromSeed(entry.seedHex);
  if (publicKeyBase64(privateKey) !== entry.publicKey) throw new Error(`test key ${keyId}: the seed does not give the listed publicKey`);
  return { keyId, privateKey };
}

/**
 * The keyIds of the TEST keys (fixtures/module-signature/keys.json) with their trust-store entry: `--key-seed-file`
 * accepts only these (a seed is never a release key). Returns `{keyId, privateKey, entry}`.
 */
export function fixtureTestKey(keysFile, keyId, { fixtureKeys, fixtureStore }) {
  const key = testKey(keysFile, keyId);
  const fixture = readJson(readFileSync(fixtureKeys), fixtureKeys);
  const known = (fixture.keys ?? []).find((k) => k.keyId === keyId);
  if (!known || known.publicKey !== publicKeyBase64(key.privateKey)) {
    throw new Error(`--key-seed-file accepts only the TEST keys of fixtures/module-signature/keys.json; ${keyId} is not one of them`);
  }
  const entry = (readJson(readFileSync(fixtureStore), fixtureStore).keys ?? []).find((k) => k.keyId === keyId);
  if (!entry) throw new Error(`the TEST key ${keyId} is in no test trust store (kf-test-unknown signs only the unknown-key case)`);
  return { ...key, entry };
}

// ---------------------------------------------------------------------------
// The release key: encrypted PKCS#8, generated and used offline by the operator
// ---------------------------------------------------------------------------

/** The shortest passphrase keygen accepts. */
export const MIN_PASSPHRASE = 12;
const ENCRYPTED_PEM = '-----BEGIN ENCRYPTED PRIVATE KEY-----';

/** `sha256:` + hex of the SPKI DER: the fingerprint printed by keygen and kept in the fragment. */
export function fingerprint(publicKeyB64) {
  return `sha256:${sha256hex(Buffer.from(publicKeyB64, 'base64'))}`;
}

/**
 * A new Ed25519 release key: the private half as PKCS#8 PEM encrypted with the passphrase (PBES2, AES-256-CBC), and
 * the public fragment — a trust-store entry (`kf-module-trust/1`) plus its `fingerprint`. Validates the fragment with
 * the trust-store rules (keyId, publisher, namespaces; a third party never covers `kf-`).
 */
export function generateReleaseKey({ keyId, publisher, namespaces, passphrase, addedAt = nowUtc().slice(0, 10) }) {
  if (typeof passphrase !== 'string' || passphrase.length < MIN_PASSPHRASE) {
    throw new Error(`the passphrase must have at least ${MIN_PASSPHRASE} characters`);
  }
  const { publicKey, privateKey } = generateKeyPairSync('ed25519', {
    publicKeyEncoding: { type: 'spki', format: 'der' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem', cipher: 'aes-256-cbc', passphrase },
  });
  const publicB64 = publicKey.toString('base64');
  const fragment = {
    keyId, publisher, scope: 'release', status: 'active', namespaces, publicKey: publicB64, addedAt,
    fingerprint: fingerprint(publicB64),
  };
  parseTrustStore(trustStoreFromFragments([fragment]));
  return { pem: privateKey, fragment };
}

/** A trust store (`kf-module-trust/1`) holding public fragments (their `fingerprint` is ignored by every reader). */
export function trustStoreFromFragments(fragments, { revokedStatements = [] } = {}) {
  return { format: TRUST_FORMAT, keys: fragments.map((f) => ({ ...f })), revokedStatements };
}

/**
 * The private key of an ENCRYPTED PKCS#8 PEM file. An unencrypted key is refused (a release key is never stored in
 * clear); a wrong passphrase gives an error that names neither the passphrase nor the key material.
 */
export function loadEncryptedKey(file, passphrase) {
  const text = readFileSync(file, 'utf8');
  if (!text.includes(ENCRYPTED_PEM)) {
    throw new Error(`${basename(file)} is not an encrypted PKCS#8 key (${ENCRYPTED_PEM}); a release key is never kept in clear`);
  }
  let key;
  try {
    key = createPrivateKey({ key: text, format: 'pem', passphrase });
  } catch {
    throw new Error(`the passphrase does not open ${basename(file)}`);
  }
  if (key.asymmetricKeyType !== 'ed25519') throw new Error(`${basename(file)} is not an Ed25519 key`);
  return key;
}

/**
 * Reads the passphrase: from the terminal without echo (and asks twice with `confirm`), or — without a TTY — from the
 * first line of stdin (the confirmation on the second). Never from arguments or the environment: there is no option
 * for it, and the CLI refuses any unknown option.
 */
export async function readPassphrase({ confirm = false, input = process.stdin, output = process.stderr } = {}) {
  if (input.isTTY) {
    const first = await promptHidden(input, output, 'Passphrase: ');
    if (first === '') throw new Error('no passphrase given');
    if (confirm && (await promptHidden(input, output, 'Repeat the passphrase: ')) !== first) {
      throw new Error('the passphrases do not match');
    }
    return first;
  }
  const chunks = [];
  for await (const chunk of input) chunks.push(Buffer.from(chunk));
  const lines = Buffer.concat(chunks).toString('utf8').split('\n').map((l) => l.replace(/\r$/, ''));
  const first = lines[0] ?? '';
  if (first === '') {
    throw new Error('no passphrase: without a terminal it is the first line of stdin (never an argument or the environment)');
  }
  if (confirm && lines[1] !== first) throw new Error('the passphrases do not match (stdin: the passphrase, then its confirmation)');
  return first;
}

/** One line from a TTY in raw mode, echoing nothing. Ctrl-C aborts. */
function promptHidden(input, output, question) {
  return new Promise((resolve, reject) => {
    output.write(question);
    let value = '';
    const finish = (error) => {
      input.setRawMode(false);
      input.pause();
      input.removeListener('data', onData);
      output.write('\n');
      if (error) reject(error);
      else resolve(value);
    };
    const onData = (chunk) => {
      for (const ch of chunk.toString('utf8')) {
        if (ch === '\r' || ch === '\n') return finish();
        if (ch === '\u0003' || ch === '\u0004') return finish(new Error('interrupted'));
        if (ch === '\u007f' || ch === '\b') value = value.slice(0, -1);
        else value += ch;
      }
      return undefined;
    };
    input.setRawMode(true);
    input.resume();
    input.on('data', onData);
  });
}

/** The statement (parsed, NOT verified) of an envelope, or `null`: what `--resign` compares the new one with. */
export function unverifiedStatement(envelopeBytes) {
  try {
    const env = JSON.parse(envelopeBytes.toString('utf8'));
    return { keyId: env.signatures?.[0]?.keyid ?? null, statement: JSON.parse(Buffer.from(env.payload, 'base64').toString('utf8')) };
  } catch {
    return null;
  }
}

/** The envelope bytes for a statement, signed by `key` (`{keyId, privateKey}`). */
export function signStatement(statement, key) {
  const sig = edSign(null, pae(PAYLOAD_TYPE, statement), key.privateKey);
  const envelope = {
    payloadType: PAYLOAD_TYPE,
    payload: statement.toString('base64'),
    signatures: [{ keyid: key.keyId, sig: sig.toString('base64') }],
  };
  return Buffer.from(`${JSON.stringify(envelope, null, 2)}\n`, 'utf8');
}

/** The tree with its `kf-module.sig` (an existing one is replaced). */
export function signEntries(tree, { key, signedAt, images } = {}) {
  const unsigned = new Map([...tree].filter(([path]) => path !== SIGNATURE_FILE));
  const envelope = signStatement(buildStatement(unsigned, { signedAt, images }), key);
  return new Map([...unsigned, [SIGNATURE_FILE, envelope]]);
}

/** Signs a directory in place: writes `kf-module.sig` at its root. Returns the envelope bytes. */
export function signTree(dir, options) {
  const envelope = signEntries(readTree(dir), options).get(SIGNATURE_FILE);
  writeFileSync(join(dir, SIGNATURE_FILE), envelope);
  return envelope;
}

// ---------------------------------------------------------------------------
// The trust store
// ---------------------------------------------------------------------------

/**
 * Parses and validates a trust store (`kf-module-trust/1`). Throws an Error naming the entry for: another format, a
 * keyId outside `^[a-z0-9][a-z0-9.-]{2,63}$` or repeated, a scope other than `release`, an unknown status, a public
 * key that is not an Ed25519 SPKI, and a third party (publisher ≠ katty-fashion) whose prefix could cover `kf-*`
 * (`"kf-".startsWith(p)` or `p.startsWith("kf-")`; `""` included).
 */
export function parseTrustStore(text) {
  const doc = typeof text === 'string' ? JSON.parse(text) : text;
  if (doc?.format !== TRUST_FORMAT) throw new Error(`trust store: format must be ${TRUST_FORMAT}`);
  if (!Array.isArray(doc.keys)) throw new Error('trust store: "keys" must be an array');
  const keys = new Map();
  for (const [i, k] of doc.keys.entries()) {
    const id = typeof k?.keyId === 'string' ? k.keyId : `#${i}`;
    const fail = (what) => { throw new Error(`trust store: key ${id}: ${what}`); };
    if (typeof k?.keyId !== 'string' || !KEY_ID.test(k.keyId)) fail(`keyId must match ${KEY_ID}`);
    if (keys.has(k.keyId)) throw new Error(`trust store: duplicate keyId ${k.keyId}`);
    if (typeof k.publisher !== 'string' || k.publisher.trim() === '') fail('publisher is required');
    if (k.scope !== 'release') fail('scope must be "release"');
    if (!KEY_STATUS.includes(k.status)) fail(`status must be one of ${KEY_STATUS.join(', ')}`);
    if (!Array.isArray(k.namespaces) || k.namespaces.length === 0
      || !k.namespaces.every((p) => typeof p === 'string' && NAMESPACE.test(p))) {
      fail('namespaces must be a non-empty array of slug prefixes');
    }
    if (k.publisher !== KF_PUBLISHER) {
      for (const p of k.namespaces) {
        if (RESERVED_PREFIX.startsWith(p) || p.startsWith(RESERVED_PREFIX)) {
          fail(`publisher ${k.publisher} may not cover the reserved prefix ${RESERVED_PREFIX} (namespace ${JSON.stringify(p)})`);
        }
      }
    }
    if (typeof k.publicKey !== 'string' || !BASE64.test(k.publicKey)) fail('publicKey must be base64');
    const der = Buffer.from(k.publicKey, 'base64');
    if (der.length !== 44 || !der.subarray(0, 12).equals(SPKI_ED25519_PREFIX)) fail('publicKey is not an Ed25519 SPKI');
    if (typeof k.addedAt !== 'string') fail('addedAt is required');
    keys.set(k.keyId, {
      keyId: k.keyId, publisher: k.publisher, scope: k.scope, status: k.status, namespaces: [...k.namespaces],
      addedAt: k.addedAt, publicKey: createPublicKey({ key: der, format: 'der', type: 'spki' }),
    });
  }
  const revoked = doc.revokedStatements ?? [];
  if (!Array.isArray(revoked) || !revoked.every((d) => typeof d === 'string' && HEX64.test(d))) {
    throw new Error('trust store: revokedStatements must be an array of sha256 hex digests');
  }
  return { keys, revokedStatements: new Set(revoked) };
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const nullableString = (v) => v === null || v === undefined || typeof v === 'string';

function parseStatement(payload) {
  const bad = (what) => new SignatureError('bad-envelope', `statement: ${what}`);
  let s;
  try {
    s = JSON.parse(payload.toString('utf8'));
  } catch {
    throw bad('the payload is not JSON');
  }
  if (!isObject(s)) throw bad('the payload is not a JSON object');
  if (s.format !== STATEMENT_FORMAT) throw bad(`format must be ${STATEMENT_FORMAT}`);
  for (const field of ['slug', 'version', 'signedAt']) {
    if (typeof s[field] !== 'string' || s[field] === '') throw bad(`${field} is required`);
  }
  for (const field of ['manifestSha256', 'contentSha256']) {
    if (typeof s[field] !== 'string' || !HEX64.test(s[field])) throw bad(`${field} must be a sha256 hex digest`);
  }
  if (!Array.isArray(s.files)) throw bad('files must be an array');
  const paths = new Set();
  for (const f of s.files) {
    if (!isObject(f) || typeof f.path !== 'string' || !Number.isSafeInteger(f.size) || f.size < 0
      || typeof f.sha256 !== 'string' || !HEX64.test(f.sha256)) {
      throw bad('every file needs path, size and sha256');
    }
    if (paths.has(f.path)) throw bad(`file ${JSON.stringify(f.path)} is listed twice`);
    paths.add(f.path);
  }
  if (!Array.isArray(s.images) || !s.images.every((i) => isObject(i) && typeof i.service === 'string' && typeof i.ref === 'string')) {
    throw bad('images must be an array of {service, ref}');
  }
  if (!isObject(s.source) || !['repo', 'commit', 'tag'].every((k) => nullableString(s.source[k]))) throw bad('source must be {repo, commit, tag}');
  if (!isObject(s.sdk) || !['tag', 'commit'].every((k) => nullableString(s.sdk[k]))) throw bad('sdk must be {tag, commit}');
  return s;
}

/**
 * Verifies the envelope bytes against the trust store, for a use. Order (the same in Java): the envelope's shape,
 * the key (unknown, revoked), the signature, the statement, a revoked statement, a retired key at upload, the prefix.
 * Returns `{statement, statementBytes, statementSha256, keyId, publisher, trust}`.
 */
export function verifyEnvelope(bytes, store, { use = KEY_USE.UPLOAD } = {}) {
  let env;
  try {
    env = JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new SignatureError('bad-envelope', `${SIGNATURE_FILE} is not JSON`);
  }
  if (!isObject(env)) throw new SignatureError('bad-envelope', `${SIGNATURE_FILE} is not a JSON object`);
  if (env.payloadType !== PAYLOAD_TYPE) throw new SignatureError('bad-envelope', `payloadType must be ${PAYLOAD_TYPE}`);
  if (typeof env.payload !== 'string' || !BASE64.test(env.payload)) throw new SignatureError('bad-envelope', 'payload must be base64');
  if (!Array.isArray(env.signatures) || env.signatures.length !== 1) {
    throw new SignatureError('bad-envelope', 'signatures must hold exactly one entry');
  }
  const entry = env.signatures[0];
  if (!isObject(entry) || typeof entry.keyid !== 'string' || typeof entry.sig !== 'string' || !BASE64.test(entry.sig)) {
    throw new SignatureError('bad-envelope', 'signatures[0] must be {keyid, sig}');
  }
  const key = store.keys.get(entry.keyid);
  if (!key) throw new SignatureError('unknown-key', `key ${entry.keyid} is not in the trust store`);
  if (key.status === 'revoked') throw new SignatureError('key-revoked', `key ${key.keyId} is revoked`);
  const payload = Buffer.from(env.payload, 'base64');
  const sig = Buffer.from(entry.sig, 'base64');
  if (sig.length !== 64 || !edVerify(null, pae(PAYLOAD_TYPE, payload), key.publicKey, sig)) {
    throw new SignatureError('bad-signature', `the signature of key ${key.keyId} does not verify`);
  }
  const statement = parseStatement(payload);
  const statementSha256 = sha256hex(payload);
  if (store.revokedStatements.has(statementSha256)) {
    throw new SignatureError('statement-revoked', `statement ${statementSha256} is revoked`);
  }
  if (use === KEY_USE.UPLOAD && key.status === 'retired') {
    throw new SignatureError('key-retired', `key ${key.keyId} is retired: it still verifies what is installed, but signs no new upload`);
  }
  if (!key.namespaces.some((p) => statement.slug.startsWith(p))) {
    throw new SignatureError('key-not-for-namespace',
      `key ${key.keyId} (publisher ${key.publisher}) does not cover the slug ${statement.slug}`);
  }
  return {
    statement, statementBytes: payload, statementSha256, keyId: key.keyId, publisher: key.publisher,
    trust: key.publisher === KF_PUBLISHER ? TRUST.KF_RELEASE : TRUST.THIRD_PARTY,
  };
}

/** The first difference between the signed list and the tree, in canonical order, or `null`. */
function firstDifference(signed, actual) {
  const expected = new Map(signed.map((f) => [f.path, f]));
  const present = new Map(actual.map((f) => [f.path, f]));
  const all = [...new Set([...expected.keys(), ...present.keys()])].sort(compareUtf8);
  for (const path of all) {
    const e = expected.get(path);
    const a = present.get(path);
    if (!a) return `${path} (missing)`;
    if (!e) return `${path} (unexpected)`;
    if (e.size !== a.size || e.sha256 !== a.sha256) return path;
  }
  return null;
}

/**
 * Verifies a whole tree (`Map<path, Buffer>`, as extracted): the envelope at its root, then the tree against the
 * statement. Throws a SignatureError with the reason; returns the verdict of verifyEnvelope plus `contentSha256` and
 * `files`. The order is the README's and Java's, so the same case gives the same reason on both sides.
 */
export function verifyTree(tree, store, { use = KEY_USE.UPLOAD } = {}) {
  const paths = [...tree.keys()];
  const sigNested = paths.some((p) => p.startsWith(`${SIGNATURE_FILE}/`));
  if (!tree.has(SIGNATURE_FILE) && !sigNested) {
    throw new SignatureError('unsigned', `the pack has no ${SIGNATURE_FILE} at its root`);
  }
  if (sigNested) throw new SignatureError('reserved-file', `${SIGNATURE_FILE} must be a regular file at the root of the pack`);
  if (paths.some((p) => p === RUNTIME_OVERRIDE_FILE || p.startsWith(`${RUNTIME_OVERRIDE_FILE}/`))) {
    throw new SignatureError('reserved-file', `${RUNTIME_OVERRIDE_FILE} is written by the platform at install; a pack must not carry it`);
  }
  const verdict = verifyEnvelope(tree.get(SIGNATURE_FILE), store, { use });
  const { statement } = verdict;
  const files = canonicalFiles(tree);
  const manifest = tree.get(MANIFEST_FILE);
  if (manifest) {
    if (sha256hex(manifest) !== statement.manifestSha256) {
      throw new SignatureError('statement-mismatch', `manifestSha256: ${MANIFEST_FILE} is not the manifest that was signed`);
    }
    let conf = null;
    try {
      conf = JSON.parse(manifest.toString('utf8'));
    } catch {
      conf = null; // the platform's validator reports an unreadable manifest; the signature covers its bytes anyway
    }
    for (const field of ['slug', 'version']) {
      if (isObject(conf) && typeof conf[field] === 'string' && conf[field] !== statement[field]) {
        throw new SignatureError('statement-mismatch',
          `${field}: the manifest declares ${JSON.stringify(conf[field])}, the statement ${JSON.stringify(statement[field])}`);
      }
    }
  }
  const difference = firstDifference(statement.files, files);
  if (difference) throw new SignatureError('content-mismatch', difference);
  const content = contentSha256(files);
  if (content !== statement.contentSha256) {
    throw new SignatureError('content-mismatch', 'contentSha256: the statement does not hash its own file list');
  }
  return { ...verdict, contentSha256: content, files };
}

// ---------------------------------------------------------------------------
// The build inputs and the provenance (variant B): the same rule as the platform's
// BuildInputsCheck — every FROM outside the file's own stages and every `image:` pinned on @sha256:<64 hex>,
// a variable refused (it cannot be pinned); kf-build.json `kf-build/1` coherent with the pack.
// ---------------------------------------------------------------------------

export const BUILD_FORMAT = 'kf-build/1';
export const COMPOSE_FILE = 'docker-compose.yml';
const PINNED = /@sha256:[0-9a-f]{64}$/;
const SHA40 = /^[0-9a-f]{40}$/;
/**
 * An SDK tag of either line: `sdk-v<semver>` -- the public SDK, variant `external` -- or
 * `sdk-internal-v<semver>` -- the internal SDK, variant `internal` (kf-platform-modules README).
 */
export const SDK_TAG = /^sdk(-internal)?-v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
/** The SDK variants of `kf-build.json` `sdk.variant` and of the statement (sdk-protocol.md §14.5). */
export const SDK_VARIANTS = ['external', 'internal'];

/** The variant of an SDK tag's line: `internal` for `sdk-internal-v*`, `external` for `sdk-v*`, null otherwise. */
export function sdkVariantOfTag(tag) {
  if (typeof tag !== 'string' || !SDK_TAG.test(tag)) return null;
  return tag.startsWith('sdk-internal-v') ? 'internal' : 'external';
}

const lines = (text) => text.replace(/\r\n?/g, '\n').split('\n');

/**
 * The FROM lines of a Dockerfile: `{bases, finalBase, problems}`. `bases` = the external references (a stage name of
 * the same file and `scratch` are not images); `finalBase` = the external image the LAST stage starts from (through
 * stage references), the one verify-runtime compares layers with; `problems` = unpinned references and variables,
 * each naming the file.
 */
export function dockerfileBases(text, file = 'Dockerfile') {
  const joined = [];
  let pending = '';
  for (const raw of lines(text)) {
    if (pending === '' && /^\s*#/.test(raw)) continue;
    if (/\\\s*$/.test(raw)) {
      pending += `${raw.replace(/\\\s*$/, '')} `;
      continue;
    }
    joined.push(pending + raw);
    pending = '';
  }
  if (pending) joined.push(pending);
  const stages = new Map(); // lowercase stage name -> resolved external base
  const bases = [];
  const problems = [];
  let finalBase = null;
  for (const line of joined) {
    const m = /^\s*FROM\s+(.*)$/i.exec(line);
    if (!m) continue;
    const tokens = m[1].trim().split(/\s+/).filter((t) => !t.startsWith('--'));
    const ref = tokens[0] ?? '';
    const as = tokens.length >= 3 && tokens[1].toLowerCase() === 'as' ? tokens[2].toLowerCase() : null;
    let resolved;
    if (ref.includes('$')) {
      problems.push(`${file}: FROM ${ref} uses a variable and cannot be pinned on a digest`);
      resolved = null;
    } else if (stages.has(ref.toLowerCase())) {
      resolved = stages.get(ref.toLowerCase());
    } else if (ref.toLowerCase() === 'scratch') {
      resolved = 'scratch';
    } else {
      if (!PINNED.test(ref)) problems.push(`${file}: FROM ${ref} is not pinned on a digest (@sha256:<64 hex>)`);
      if (!bases.includes(ref)) bases.push(ref);
      resolved = ref;
    }
    if (as) stages.set(as, resolved);
    finalBase = resolved;
  }
  if (!joined.some((l) => /^\s*FROM\s/i.test(l))) problems.push(`${file}: no FROM`);
  return { bases, finalBase, problems };
}

const unquote = (v) => {
  const t = v.trim();
  return (t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'")) ? t.slice(1, -1) : t;
};
const stripComment = (v) => (/^\s*["']/.test(v) ? v : v.replace(/\s+#.*$/, ''));
const indentOf = (l) => l.length - l.trimStart().length;

/**
 * The services of a compose file, read for what B needs — `{name, image, build: {context, dockerfile} | null}` —
 * from the block form the modules use (`services:` → `<name>:` → `image:` / `build: <context>` or `build:` with
 * `context:` / `dockerfile:`, or the flow form `build: {context: ., dockerfile: X}`). Not a general YAML parser.
 */
export function composeServices(text) {
  const all = lines(text).filter((l) => l.trim() !== '' && !/^\s*#/.test(l));
  const start = all.findIndex((l) => /^services:\s*$/.test(l));
  if (start < 0) return [];
  const body = [];
  for (const l of all.slice(start + 1)) {
    if (indentOf(l) === 0) break;
    body.push(l);
  }
  const services = [];
  if (body.length === 0) return services;
  const serviceIndent = indentOf(body[0]);
  let current = null;
  let keyIndent = null;
  let inBuild = null;
  for (const l of body) {
    const indent = indentOf(l);
    const text = stripComment(l.trim());
    if (indent === serviceIndent) {
      const m = /^([^:\s]+):\s*$/.exec(text);
      current = m ? { name: unquote(m[1]), image: null, build: null } : null;
      if (current) services.push(current);
      keyIndent = null;
      inBuild = null;
      continue;
    }
    if (!current) continue;
    if (keyIndent === null) keyIndent = indent;
    if (indent === keyIndent) {
      inBuild = null;
      const m = /^([A-Za-z_]+):\s*(.*)$/.exec(text);
      if (!m) continue;
      if (m[1] === 'image') current.image = unquote(m[2]);
      if (m[1] === 'build') {
        const value = m[2].trim();
        if (value === '') {
          current.build = { context: '.', dockerfile: 'Dockerfile' };
          inBuild = current.build;
        } else if (value.startsWith('{')) {
          current.build = { context: '.', dockerfile: 'Dockerfile' };
          for (const part of value.replace(/^\{|\}$/g, '').split(',')) {
            const [k, ...v] = part.split(':');
            if (k.trim() === 'context') current.build.context = unquote(v.join(':'));
            if (k.trim() === 'dockerfile') current.build.dockerfile = unquote(v.join(':'));
          }
        } else {
          current.build = { context: unquote(value), dockerfile: 'Dockerfile' };
        }
      }
    } else if (indent > keyIndent && inBuild) {
      const m = /^(context|dockerfile):\s*(.*)$/.exec(text);
      if (m) inBuild[m[1]] = unquote(m[2]);
    }
  }
  return services;
}

/** `a/./b/../c` → `a/c`; `null` when it leaves the root. */
function normalizeRelative(path) {
  const out = [];
  for (const seg of path.split('/')) {
    if (seg === '' || seg === '.') continue;
    if (seg === '..') {
      if (out.length === 0) return null;
      out.pop();
    } else out.push(seg);
  }
  return out.join('/');
}

/**
 * The build inputs of a pack or a module directory, read through `read(path) → Buffer | undefined`:
 * `{services, images, baseImages, problems}`. `images` = the `image:` references (service → ref) of services without
 * `build` — a service with both names its built image with `image:`; `baseImages` = the external FROM references of the
 * Dockerfiles of the services with `build`, sorted; `problems` = what keeps it from being pinned.
 */
export function buildInputs(read) {
  const problems = [];
  const images = [];
  const bases = new Set();
  const compose = read(COMPOSE_FILE);
  if (!compose) return { services: [], images, baseImages: [], problems: [`the pack has no ${COMPOSE_FILE}`] };
  const services = composeServices(compose.toString('utf8'));
  for (const svc of services) {
    if (svc.build) {
      const path = normalizeRelative(`${svc.build.context}/${svc.build.dockerfile}`);
      const dockerfile = path === null ? undefined : read(path);
      if (!dockerfile) {
        problems.push(`service ${svc.name}: ${path ?? `${svc.build.context}/${svc.build.dockerfile}`} is not in the pack`);
        continue;
      }
      const r = dockerfileBases(dockerfile.toString('utf8'), path);
      svc.dockerfile = path;
      svc.finalBase = r.finalBase;
      for (const b of r.bases) bases.add(b);
      problems.push(...r.problems);
    } else if (svc.image !== null) {
      if (svc.image.includes('$')) problems.push(`service ${svc.name}: image ${svc.image} uses a variable and cannot be pinned on a digest`);
      else if (!PINNED.test(svc.image)) problems.push(`service ${svc.name}: image ${svc.image} is not pinned on a digest (@sha256:<64 hex>)`);
      else images.push({ service: svc.name, ref: svc.image });
    } else {
      problems.push(`service ${svc.name}: neither build nor image`);
    }
  }
  images.sort((a, b) => compareUtf8(a.service, b.service));
  return { services, images, baseImages: [...bases].sort(compareUtf8), problems };
}

/**
 * kf-build.json of a pack checked against its manifest and its Dockerfiles (`build-provenance`): format `kf-build/1`,
 * `slug`/`version` = the manifest (when present), `dirty` false (`source.dirty`, or the top-level `dirty` of the
 * fixtures), `source.commit` and `sdk.commit` 40 hex, `source.tag` = `<slug>-v<version>`, `sdk.tag` an SDK
 * tag of either line, `sdk.variant` `external` or `internal` and the tag on its line (§14.5: `internal` only on
 * `sdk-internal-v*`, `external` only on `sdk-v*`), `baseImages` (when present) = the FROM references found. Returns
 * the parsed object.
 */
export function checkBuildInfo(tree, { slug, version, baseImages }) {
  const bad = (detail) => new SignatureError('build-provenance', detail);
  const raw = tree.get(BUILD_INFO_FILE);
  if (!raw) throw bad(`the pack has no ${BUILD_INFO_FILE} (build.sh writes it; a release is built with --release)`);
  let b;
  try {
    b = JSON.parse(raw.toString('utf8'));
  } catch {
    throw bad(`${BUILD_INFO_FILE} is not JSON`);
  }
  if (!isObject(b) || b.format !== BUILD_FORMAT) throw bad(`${BUILD_INFO_FILE}: format must be ${BUILD_FORMAT}`);
  for (const [field, expected] of [['slug', slug], ['version', version]]) {
    if (b[field] !== undefined && b[field] !== expected) throw bad(`${field}: ${BUILD_INFO_FILE} says ${JSON.stringify(b[field])}, the manifest ${JSON.stringify(expected)}`);
  }
  if (!isObject(b.source)) throw bad(`${BUILD_INFO_FILE}: source is required`);
  const dirty = [b.source.dirty, b.dirty].filter((v) => v !== undefined);
  if (dirty.length === 0 || dirty.some((v) => v !== false)) {
    throw bad('dirty: the pack was not built from a clean tree (source.dirty must be false)');
  }
  if (typeof b.source.commit !== 'string' || !SHA40.test(b.source.commit)) throw bad('source.commit must be a 40-hex commit');
  const tag = `${slug}-v${version}`;
  if (b.source.tag !== tag) throw bad(`source.tag: a release is built from the tag ${tag} (${BUILD_INFO_FILE} says ${JSON.stringify(b.source.tag ?? null)})`);
  if (!isObject(b.sdk) || typeof b.sdk.tag !== 'string' || !SDK_TAG.test(b.sdk.tag)) {
    throw bad(`sdk.tag: a release pins the SDK on an sdk-v* or sdk-internal-v* tag (${BUILD_INFO_FILE} says ${JSON.stringify(b.sdk?.tag ?? null)})`);
  }
  if (!SDK_VARIANTS.includes(b.sdk.variant)) {
    throw bad(`sdk.variant must be "external" or "internal" (${BUILD_INFO_FILE} says ${JSON.stringify(b.sdk.variant ?? null)}; kf-module build-info writes it)`);
  }
  if (sdkVariantOfTag(b.sdk.tag) !== b.sdk.variant) {
    const line = b.sdk.variant === 'internal' ? 'sdk-internal-v*' : 'sdk-v*';
    throw bad(`sdk.variant "${b.sdk.variant}" is not on a tag of its line ${line} (sdk.tag ${b.sdk.tag})`);
  }
  if (typeof b.sdk.commit !== 'string' || !SHA40.test(b.sdk.commit)) throw bad('sdk.commit must be a 40-hex commit');
  if (b.baseImages !== undefined) {
    const listed = Array.isArray(b.baseImages) ? [...new Set(b.baseImages)].sort(compareUtf8) : null;
    if (!listed || listed.join('\n') !== baseImages.join('\n')) {
      throw bad(`baseImages: ${BUILD_INFO_FILE} lists ${JSON.stringify(b.baseImages)}, the pack's Dockerfiles ${JSON.stringify(baseImages)}`);
    }
  }
  return b;
}

// ---------------------------------------------------------------------------
// git (the tool runs on the operator's machine; every call is git with an argument list, never a shell)
// ---------------------------------------------------------------------------

export function git(root, args, { allowFail = false } = {}) {
  const r = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8' });
  if (r.status !== 0) {
    if (allowFail) return null;
    throw new Error(`git ${args.join(' ')}: ${(r.stderr || r.error?.message || '').trim()}`);
  }
  return r.stdout.trimEnd(); // not trim(): a porcelain status line starts with a space
}

/** The top of the git working tree holding `dir`, or `null`. */
export function gitRoot(dir) {
  return git(dir, ['rev-parse', '--show-toplevel'], { allowFail: true });
}

/**
 * A tag of `repo`: `{commit, annotated, signed, verified}` or `null` when there is none. `signed` = the tag object
 * carries an SSH or PGP signature; `verified` = `git verify-tag` is green (with the repo's own configuration).
 */
export function tagInfo(repo, tag) {
  const commit = git(repo, ['rev-parse', '--verify', '--quiet', `refs/tags/${tag}^{commit}`], { allowFail: true });
  if (!commit) return null;
  const annotated = git(repo, ['cat-file', '-t', `refs/tags/${tag}`], { allowFail: true }) === 'tag';
  const signed = annotated && /-----BEGIN (SSH|PGP) SIGNATURE-----/.test(git(repo, ['cat-file', 'tag', `refs/tags/${tag}`], { allowFail: true }) ?? '');
  const verified = signed && spawnSync('git', ['-C', repo, 'verify-tag', tag], { encoding: 'utf8' }).status === 0;
  return { commit, annotated, signed, verified };
}

/** `owner/name` for GitHub, `host/path` elsewhere; never the credentials of the URL; `null` for a local path. */
export function repoName(url) {
  if (!url) return null;
  let host;
  let path;
  const scp = /^[^@/]+@([^:]+):(.+)$/.exec(url);
  if (scp) {
    [, host, path] = scp;
  } else {
    try {
      const u = new URL(url);
      if (!u.host) return null;
      host = u.host;
      path = u.pathname;
    } catch {
      return null;
    }
  }
  path = path.replace(/^\/+/, '').replace(/\.git$/, '').replace(/\/+$/, '');
  return host === 'github.com' ? path : `${host}/${path}`;
}

/** `node` and `java` versions of the build machine (`java` null when absent). */
export function toolVersions() {
  const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
  const v = java.status === 0 ? /version "([^"]+)"/.exec(`${java.stderr}${java.stdout}`)?.[1] ?? null : null;
  return { node: process.version, java: v };
}

/** A module directory as a `read(path)` for buildInputs: only the files it asks for, never the whole tree. */
export function moduleReader(moduleDir) {
  return (path) => {
    if (pathProblem(path)) return undefined;
    const abs = join(moduleDir, ...path.split('/'));
    return existsSync(abs) && lstatSync(abs).isFile() ? readFileSync(abs) : undefined;
  };
}

function readModuleManifest(moduleDir) {
  const file = join(moduleDir, MANIFEST_FILE);
  if (!existsSync(file)) throw new Error(`${moduleDir} has no ${MANIFEST_FILE}`);
  const conf = readJson(readFileSync(file), MANIFEST_FILE);
  if (typeof conf?.slug !== 'string') throw new Error(`${MANIFEST_FILE} has no slug`);
  return conf;
}

/**
 * Before a release build (`build.sh <version> --release`): `{slug, repo, head, tag, sdk, baseImages, problems[]}`;
 * `problems` empty = the build may go on. Checks: the manifest declares `version`; nothing uncommitted or untracked in
 * the repository; HEAD is the tag `<slug>-v<version>` (and a signed tag verifies); `sdkRef` is an SDK tag of either
 * line (`sdk-v*`, `sdk-internal-v*`) of `sdkRepo` (and verifies when signed); the module's FROM / `image:` are
 * on digests. `sdk.variant` = the line of the tag.
 */
export function releaseCheck({ moduleDir, version, sdkRef, sdkRepo }) {
  const problems = [];
  const conf = readModuleManifest(moduleDir);
  refuseDevBuild(conf, 'a release is built from the module itself');
  const slug = conf.slug;
  if (conf.version !== version) problems.push(`${MANIFEST_FILE} declares ${conf.version}, the release is ${version} (set the version, commit, tag)`);
  const repo = gitRoot(moduleDir);
  if (!repo) throw new Error(`${moduleDir} is not in a git repository`);
  const status = git(repo, ['status', '--porcelain', '--untracked-files=all']);
  for (const line of status.split('\n').filter(Boolean)) problems.push(`uncommitted: ${line.slice(3)} (${line.slice(0, 2).trim()})`);
  const head = git(repo, ['rev-parse', 'HEAD']);
  const tagName = `${slug}-v${version}`;
  const tag = tagInfo(repo, tagName);
  if (!tag || tag.commit !== head) {
    problems.push(`HEAD ${head.slice(0, 12)} is not the tag ${tagName} (${tag ? `the tag is on ${tag.commit.slice(0, 12)}` : 'no such tag'}): build a release from a clean checkout of the tag`);
  } else if (tag.signed && !tag.verified) {
    problems.push(`the signature of the tag ${tagName} does not verify (git verify-tag)`);
  }
  let sdk = null;
  if (!SDK_TAG.test(sdkRef)) {
    problems.push(`SDK_REF ${sdkRef} is not an sdk-v* or sdk-internal-v* tag: a release pins the SDK on a tag (KF_SDK_REF=<sha> is for a dev build)`);
  } else {
    const sRepo = sdkRepo ?? repo;
    const s = tagInfo(sRepo, sdkRef);
    if (!s) problems.push(`the SDK tag ${sdkRef} is not found in ${sRepo}`);
    else if (s.signed && !s.verified) problems.push(`the signature of the SDK tag ${sdkRef} does not verify (git verify-tag)`);
    else sdk = { tag: sdkRef, commit: s.commit, signed: s.signed, variant: sdkVariantOfTag(sdkRef) };
  }
  const inputs = buildInputs(moduleReader(moduleDir));
  problems.push(...inputs.problems);
  return { slug, version, repo, head, tag: tag?.commit === head ? tagName : null, tagSigned: Boolean(tag?.verified), sdk, baseImages: inputs.baseImages, problems };
}

/**
 * The provenance written into the pack (`kf-build.json`), in both build modes, with the REAL state (a dev build of a
 * dirty tree says `dirty: true`, which `sign` refuses). Deterministic for the same tree, refs and `builtAt`.
 * `sdk.variant`: the line of the SDK tag (`sdk-internal-v*` → `internal`, `sdk-v*` → `external`); for a dev
 * build on a sha, `sdkVariant` (default `external`). A `sdkVariant` given with a tag of the other line is refused.
 */
export function buildInfo({ moduleDir, version, sdkRef, sdkRepo, sdkVariant, builtAt = nowUtc(), tools = toolVersions() }) {
  if (sdkVariant !== undefined && !SDK_VARIANTS.includes(sdkVariant)) {
    throw new Error(`--sdk-variant must be external or internal (got ${sdkVariant})`);
  }
  const conf = readModuleManifest(moduleDir);
  const slug = conf.slug;
  const repo = gitRoot(moduleDir);
  if (!repo) throw new Error(`${moduleDir} is not in a git repository`);
  const head = git(repo, ['rev-parse', 'HEAD']);
  const dirty = git(repo, ['status', '--porcelain', '--untracked-files=all']) !== '';
  const tagName = `${slug}-v${version}`;
  const tag = tagInfo(repo, tagName);
  const onTag = Boolean(tag && tag.commit === head);
  const sRepo = sdkRepo ?? repo;
  let sdk;
  const sdkTag = SDK_TAG.test(sdkRef) ? tagInfo(sRepo, sdkRef) : null;
  if (sdkTag) {
    const variant = sdkVariantOfTag(sdkRef);
    if (sdkVariant !== undefined && sdkVariant !== variant) {
      throw new Error(`--sdk-variant ${sdkVariant} does not match the SDK tag ${sdkRef} (variant ${variant})`);
    }
    sdk = { tag: sdkRef, commit: sdkTag.commit, variant };
  } else {
    const commit = git(sRepo, ['rev-parse', '--verify', '--quiet', `${sdkRef}^{commit}`], { allowFail: true });
    if (!commit) throw new Error(`the SDK ref ${sdkRef} is not found in ${sRepo}`);
    sdk = { tag: null, commit, variant: sdkVariant ?? 'external' };
  }
  return {
    format: BUILD_FORMAT,
    slug,
    version,
    source: {
      repo: repoName(git(repo, ['remote', 'get-url', 'origin'], { allowFail: true })),
      commit: head,
      tag: onTag ? tagName : null,
      dirty,
      tagSigned: Boolean(onTag && tag.verified),
    },
    sdk,
    baseImages: buildInputs(moduleReader(moduleDir)).baseImages,
    builtAt,
    tools,
  };
}

// ---------------------------------------------------------------------------
// Zip (stored or deflated entries, no zip64, no encryption)
// ---------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(data) {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/**
 * Reads a zip into `Map<name, Buffer>` (directory entries skipped), from the central directory. A name that appears
 * twice is refused — the platform refuses it too, and a tree with two versions of one file has no single meaning.
 * Names are read as UTF-8, like the platform's java.util.zip.
 */
export function readZip(buffer) {
  let eocd = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 0xffff); i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a zip archive (no end of central directory)');
  const count = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  if (count === 0xffff || offset === 0xffffffff) throw new Error('zip64 archives are not supported');
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error('corrupt central directory');
    const flags = buffer.readUInt16LE(offset + 8);
    const method = buffer.readUInt16LE(offset + 10);
    const crc = buffer.readUInt32LE(offset + 16);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const size = buffer.readUInt32LE(offset + 24);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const local = buffer.readUInt32LE(offset + 42);
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    offset += 46 + nameLength + extraLength + commentLength;
    if (flags & 1) throw new Error(`entry ${name}: encrypted entries are not supported`);
    if (name.endsWith('/')) continue;
    if (entries.has(name)) throw new Error(`duplicate entry ${JSON.stringify(name)} in the archive`);
    if (buffer.readUInt32LE(local) !== 0x04034b50) throw new Error(`entry ${name}: corrupt local header`);
    const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    const raw = buffer.subarray(start, start + compressedSize);
    let data;
    if (method === 0) data = Buffer.from(raw);
    else if (method === 8) data = inflateRawSync(raw);
    else throw new Error(`entry ${name}: compression method ${method} is not supported`);
    if (data.length !== size || crc32(data) !== crc) throw new Error(`entry ${name}: size or CRC mismatch`);
    entries.set(name, data);
  }
  return entries;
}

/**
 * Writes a zip of `[{name, data}]`, STORED (no compression) and with a fixed timestamp: the same input gives the same
 * bytes on any Node and zlib version, which is what lets the fixture zip be compared byte for byte. Sizes and CRC are
 * in the local headers, so a streaming reader (the platform's ZipInputStream) reads it. `allowDuplicates` exists only
 * to build the hostile archives of the tests.
 */
export function writeZip(entries, { allowDuplicates = false } = {}) {
  const locals = [];
  const centrals = [];
  const names = new Set();
  let offset = 0;
  for (const { name, data } of entries) {
    if (!allowDuplicates && names.has(name)) throw new Error(`duplicate entry ${name}`);
    names.add(name);
    const nameBytes = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt16LE(0, 10); // time 00:00:00
    local.writeUInt16LE(0x21, 12); // date 1980-01-01
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4); // version made by
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, data);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const centralBytes = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBytes, end]);
}

/** A tree as zip entries, in canonical (UTF-8 byte) order. */
export function treeToZip(tree) {
  return writeZip([...tree.keys()].sort(compareUtf8).map((name) => ({ name, data: tree.get(name) })));
}

// ---------------------------------------------------------------------------
// The dev build of a signed module (remade)
// ---------------------------------------------------------------------------

/** The suffix of a dev build's slug: `<slug>-dev`, declared with `devOf: <slug>` (the platform's DevBuildIdentity). */
export const DEV_BUILD_SUFFIX = '-dev';
/** A module slug, as the platform reads it (`ModuleSlug.PATTERN`): at most 41 characters. */
const MODULE_SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;
/** The suffix every label of a dev build carries, in every language. */
const DEV_LABEL_SUFFIX = ' (dev)';

/** A refusal of `dev-build` (exit 1 in the CLI, `REFUSED dev-build: <detail>`). */
export class DevBuildError extends Error {
  constructor(detail) {
    super(`dev-build: ${detail}`);
    this.reason = 'dev-build';
    this.detail = detail;
  }
}

/**
 * The dev-build notation of a manifest (`kd-mod@dev`), or `null` for an ordinary module. A dev build is a manifest with
 * `devOf`, or a slug ending in `-dev` (the suffix is reserved: the platform refuses a `*-dev` slug without `devOf`).
 */
export function devBuildNotation(conf) {
  const slug = typeof conf?.slug === 'string' ? conf.slug : null;
  const isDev = conf?.devOf !== undefined || (slug !== null && slug.endsWith(DEV_BUILD_SUFFIX));
  if (!isDev) return null;
  const base = typeof conf.devOf === 'string' ? conf.devOf : slug.slice(0, -DEV_BUILD_SUFFIX.length);
  return `${base}@dev`;
}

/**
 * (unchanged): a dev build is never signed and never released. Throws a
 * `DevBuildError` (reason `dev-build`) naming the build; `what` says which step refused it.
 */
export function refuseDevBuild(conf, what) {
  const notation = devBuildNotation(conf);
  if (notation === null) return;
  throw new DevBuildError(`${conf.slug} is a dev build (${notation}${conf.devOf === undefined ? ', without devOf' : ''}): ${what}; a dev build is`
    + ' installed unsigned on a development organization only and is never signed (sign the pack of the module itself)');
}

/**
 * Derives the dev build of a module from its pack (a zip `Buffer`, or a tree `Map<path, Buffer>`), deterministically:
 * - the manifest: `slug` -> `<slug>-dev`, `devOf: <slug>` right after it; in every text value under `capabilities`
 *   the exact prefixes `/m/<slug>/` -> `/m/<slug>-dev/` (also `/setup/m/<slug>/...`) and `api.v1.m.<slug>.` ->
 *   `api.v1.m.<slug>-dev.`; `tenantData.format` is a contract of the module's code and stays;
 * - the texts referred to by `name`, `capabilities.nav[].label` and `capabilities.surfaces[].label` get " (dev)" in
 *   every language of `i18n`;
 * - `kf-build.json` (if present): `slug` rewritten and `devBuildOf {slug, version, packSha256, contentSha256}` of the
 *   source (`packSha256` = the zip given, or the zip `treeToZip` writes for a tree);
 * - `kf-module.sig` never reaches the output; every other file is copied byte for byte.
 * Refused (`DevBuildError`): a source already a dev build (slug `*-dev` or `devOf`), a derived slug over 41
 * characters, an output in which a prefix of the base remains.
 *
 * @returns `{slug, devOf, version, fileName, zip, tree, source: {packSha256, contentSha256}}`
 */
export function deriveDevBuild(pack) {
  const sourceZip = Buffer.isBuffer(pack) ? pack : treeToZip(pack);
  const source = Buffer.isBuffer(pack) ? readZip(pack) : new Map(pack);
  const raw = source.get(MANIFEST_FILE);
  if (!raw) throw new DevBuildError(`the pack has no ${MANIFEST_FILE}`);
  const conf = readJson(raw, MANIFEST_FILE);
  const base = conf?.slug;
  if (typeof base !== 'string' || !MODULE_SLUG.test(base)) throw new DevBuildError(`${MANIFEST_FILE} has no valid slug`);
  if (conf.devOf !== undefined || base.endsWith(DEV_BUILD_SUFFIX)) {
    throw new DevBuildError(`${base} is already a dev build${conf.devOf !== undefined ? ` (devOf ${JSON.stringify(conf.devOf)})` : ''}: derive from the module's own pack`);
  }
  const slug = `${base}${DEV_BUILD_SUFFIX}`;
  if (!MODULE_SLUG.test(slug)) {
    throw new DevBuildError(`the dev build slug ${slug} is ${slug.length} characters long; a slug has at most 41`);
  }
  const version = conf.version;
  if (typeof version !== 'string') throw new DevBuildError(`${MANIFEST_FILE} has no version`);

  const unsigned = new Map([...source].filter(([path]) => path !== SIGNATURE_FILE));
  const sourceContent = contentSha256(canonicalFiles(unsigned));
  const packSha256 = sha256hex(sourceZip);

  // the manifest
  const routePrefix = `/m/${base}/`;
  const rightPrefix = `api.v1.m.${base}.`;
  const rewrite = (node) => {
    if (typeof node === 'string') {
      return node.split(routePrefix).join(`/m/${slug}/`).split(rightPrefix).join(`api.v1.m.${slug}.`);
    }
    if (Array.isArray(node)) return node.map(rewrite);
    if (node && typeof node === 'object') return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, rewrite(v)]));
    return node;
  };
  const derived = {};
  for (const [key, value] of Object.entries(conf)) {
    if (key === 'slug') {
      derived.slug = slug;
      derived.devOf = base;
    } else if (key === 'capabilities') {
      derived.capabilities = rewrite(value);
    } else {
      derived[key] = value;
    }
  }
  const labelled = new Set();
  if (typeof conf.name === 'string') labelled.add(conf.name);
  for (const list of ['nav', 'surfaces']) {
    for (const entry of Array.isArray(conf.capabilities?.[list]) ? conf.capabilities[list] : []) {
      if (typeof entry?.label === 'string') labelled.add(entry.label);
    }
  }
  if (derived.i18n && typeof derived.i18n === 'object') {
    derived.i18n = Object.fromEntries(Object.entries(derived.i18n).map(([language, texts]) => [language,
      texts && typeof texts === 'object'
        ? Object.fromEntries(Object.entries(texts).map(([key, text]) => [key,
          labelled.has(key) && typeof text === 'string' && !text.endsWith(DEV_LABEL_SUFFIX) ? `${text}${DEV_LABEL_SUFFIX}` : text]))
        : texts]));
  }
  const left = [];
  const scan = (node, at) => {
    if (typeof node === 'string') {
      if (node.includes(routePrefix) || node.includes(rightPrefix)) left.push(`${at} = ${node}`);
    } else if (Array.isArray(node)) node.forEach((v, i) => scan(v, `${at}[${i}]`));
    else if (node && typeof node === 'object') for (const [k, v] of Object.entries(node)) scan(v, `${at}.${k}`);
  };
  scan(derived.capabilities, 'capabilities');
  if (left.length) throw new DevBuildError(`a prefix of ${base} remains in the output: ${left.join('; ')}`);

  const out = new Map(unsigned);
  out.set(MANIFEST_FILE, Buffer.from(`${JSON.stringify(derived, null, 2)}\n`, 'utf8'));

  // the provenance
  const buildRaw = unsigned.get(BUILD_INFO_FILE);
  if (buildRaw) {
    const info = readJson(buildRaw, BUILD_INFO_FILE);
    const rewritten = {};
    for (const [key, value] of Object.entries(info ?? {})) rewritten[key] = key === 'slug' ? slug : value;
    if (!('slug' in rewritten)) rewritten.slug = slug;
    rewritten.devBuildOf = { slug: base, version, packSha256, contentSha256: sourceContent };
    out.set(BUILD_INFO_FILE, Buffer.from(`${JSON.stringify(rewritten, null, 2)}\n`, 'utf8'));
  }

  return {
    slug, devOf: base, version, fileName: `${slug}-${version}.zip`, zip: treeToZip(out), tree: out,
    source: { packSha256, contentSha256: sourceContent },
  };
}
