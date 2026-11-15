// kf-module-runtime: what RUNS on the host, checked by the operator. The platform does not
// see the containers; until the runtime observer exists, `kf-module verify-runtime --slug <slug>` is the
// operator's step after `docker compose -p kfmod-<slug> … up` (design-MODULE.md §3.1 step 9).
//
// For every running container of the compose project `kfmod-<slug>` it checks, one row each (OK or FAIL + the reason):
//   - the installed tree in its working directory (com.docker.compose.project.working_dir, without the platform's
//     docker-compose.kf.yml) against its kf-module.sig and the trust store given (`existing` use: a retired key still
//     verifies what is installed) — a file changed on the host is `content-mismatch: <path>`;
//   - the labels kf.module.slug = the slug and kf.module.version = the version of the signed statement;
//   - a service with `build`: the layers of the pinned base image of its Dockerfile (`docker image inspect
//     <ref@sha256>`) are a prefix of the layers of the service's image (an image rebuilt from another base fails);
//     a service with `image:`: the image carries the pinned digest;
//   - KF_RELEASE: HostConfig.ReadonlyRootfs = true.
//
// docker is called through a RUNNER — by default execFileSync('docker', args), an argument list, never a shell — that
// the tests replace with fixed outputs (fixtures/module-signature/runtime/). Root on the host can still lie to docker;
// this is the operator's check, not an attestation.
import { execFileSync } from 'node:child_process';
import {
  KEY_USE, RUNTIME_OVERRIDE_FILE, SignatureError, TRUST, buildInputs, moduleReader, readTree, verifyTree,
} from './kf-module-lib.mjs';

const SLUG = /^[a-z0-9][a-z0-9-]{0,62}$/;
const PROJECT = /^[a-z0-9][a-z0-9_-]{0,80}$/;
const CONTAINER_ID = /^[0-9a-f]{12,64}$/;
const IMAGE_ID = /^sha256:[0-9a-f]{64}$/;

/** The default runner: the docker CLI with an argument list (no shell), its stdout as text. */
export function dockerRunner(args) {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
}

const firstLine = (e) => `${e?.stderr || e?.message || e}`.trim().split('\n')[0];

/** The installed tree of a working directory against its signature: the verdict, or `{error}`. */
function checkTree(dir, store) {
  try {
    const tree = readTree(dir);
    tree.delete(RUNTIME_OVERRIDE_FILE);
    return verifyTree(tree, store, { use: KEY_USE.EXISTING });
  } catch (e) {
    return { error: e instanceof SignatureError ? `${e.reason}: ${e.detail}` : e.message };
  }
}

function inspectImage(runner, ref) {
  const doc = JSON.parse(runner(['image', 'inspect', ref]));
  if (!Array.isArray(doc) || doc.length !== 1) throw new Error(`docker image inspect ${ref}: not one image`);
  return doc[0];
}

/**
 * Checks the running containers of `project` (default `kfmod-<slug>`) against the trust store. Returns
 * `{ok, rows}`; `ok` is false on any FAIL row. Throws before calling docker when the slug or the project is not one.
 */
export function verifyRuntime({ slug, store, project = `kfmod-${slug}`, runner = dockerRunner }) {
  if (typeof slug !== 'string' || !SLUG.test(slug)) throw new Error(`--slug ${JSON.stringify(slug)} is not a module slug`);
  if (typeof project !== 'string' || !PROJECT.test(project)) throw new Error(`--project ${JSON.stringify(project)} is not a compose project name`);
  const rows = [];
  const ok = (m) => rows.push(`OK   ${m}`);
  const fail = (m) => rows.push(`FAIL ${m}`);
  const done = () => ({ ok: rows.length > 0 && rows.every((r) => r.startsWith('OK')), rows });

  let ids;
  try {
    ids = runner(['ps', '--filter', `label=com.docker.compose.project=${project}`, '--format', '{{.ID}}'])
      .split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  } catch (e) {
    fail(`docker ps: ${firstLine(e)}`);
    return done();
  }
  if (ids.length === 0) {
    fail(`project ${project}: not running (no running container labelled com.docker.compose.project=${project})`);
    return done();
  }
  const bad = ids.find((id) => !CONTAINER_ID.test(id));
  if (bad) {
    fail(`docker ps: unexpected container id ${JSON.stringify(bad)}`);
    return done();
  }
  const containers = JSON.parse(runner(['container', 'inspect', ...ids]));
  ok(`project ${project}: ${containers.length} running container(s)`);

  const trees = new Map();
  for (const c of containers) {
    const labels = c.Config?.Labels ?? {};
    const service = labels['com.docker.compose.service'] ?? '?';
    const name = `${service} (${String(c.Name ?? c.Id ?? '').replace(/^\//, '')})`;

    // 1. the installed tree, against kf-module.sig and the trust store
    const dir = labels['com.docker.compose.project.working_dir'];
    let verdict = null;
    if (!dir) {
      fail(`${name}: tree: no com.docker.compose.project.working_dir label`);
    } else {
      if (!trees.has(dir)) trees.set(dir, checkTree(dir, store));
      const v = trees.get(dir);
      if (v.error) fail(`${name}: tree ${dir}: ${v.error}`);
      else {
        verdict = v;
        ok(`${name}: tree ${dir} is the signed pack (${v.trust}, ${v.publisher}, key ${v.keyId}, contentSha256 ${v.contentSha256})`);
      }
    }

    // 2. the labels written by the platform's override
    if (labels['kf.module.slug'] === slug) ok(`${name}: label kf.module.slug = ${slug}`);
    else fail(`${name}: label kf.module.slug = ${JSON.stringify(labels['kf.module.slug'] ?? null)}, expected ${slug}`);
    const version = labels['kf.module.version'];
    if (!verdict) fail(`${name}: label kf.module.version = ${JSON.stringify(version ?? null)}: no verified statement to compare with`);
    else if (version === verdict.statement.version) ok(`${name}: label kf.module.version = ${version} (the signed statement)`);
    else fail(`${name}: label kf.module.version = ${JSON.stringify(version ?? null)}, the signed statement says ${verdict.statement.version}`);

    // 3. the image: built from the pinned base, or pulled on the pinned digest
    if (verdict) {
      const svc = buildInputs(moduleReader(dir)).services.find((s) => s.name === service);
      if (!svc) {
        fail(`${name}: image: the service ${service} is not in the signed docker-compose.yml`);
      } else if (!IMAGE_ID.test(String(c.Image ?? ''))) {
        fail(`${name}: image: unexpected image id ${JSON.stringify(c.Image ?? null)}`);
      } else if (svc.build) {
        const base = svc.finalBase;
        if (base === 'scratch') ok(`${name}: base: FROM scratch`);
        else if (!base) fail(`${name}: base: no pinned base in ${svc.dockerfile ?? 'the Dockerfile'}`);
        else {
          try {
            const baseLayers = inspectImage(runner, base).RootFS?.Layers ?? [];
            const imageLayers = inspectImage(runner, c.Image).RootFS?.Layers ?? [];
            if (baseLayers.length > 0 && baseLayers.every((l, i) => imageLayers[i] === l)) {
              ok(`${name}: base ${base}: its ${baseLayers.length} layer(s) are the prefix of the image ${c.Image.slice(0, 19)}`);
            } else {
              fail(`${name}: base ${base}: its layers are not a prefix of the image ${c.Image.slice(0, 19)} (rebuilt from another base?)`);
            }
          } catch (e) {
            fail(`${name}: base ${base}: cannot compare (${firstLine(e)}; docker pull ${base})`);
          }
        }
      } else if (svc.image) {
        const digest = svc.image.split('@')[1];
        try {
          const repoDigests = inspectImage(runner, c.Image).RepoDigests ?? [];
          if (digest && repoDigests.some((d) => d.endsWith(`@${digest}`))) ok(`${name}: image ${svc.image}: the pinned digest`);
          else fail(`${name}: image ${svc.image}: the running image does not carry the pinned digest`);
        } catch (e) {
          fail(`${name}: image ${svc.image}: cannot inspect (${firstLine(e)})`);
        }
      }
    }

    // 4. the read-only rootfs of a KF_RELEASE module
    if (c.HostConfig?.ReadonlyRootfs === true) ok(`${name}: read-only rootfs`);
    else if (verdict && verdict.trust !== TRUST.KF_RELEASE) ok(`${name}: read-only rootfs not required for ${verdict.trust} (writable)`);
    else fail(`${name}: read-only rootfs: HostConfig.ReadonlyRootfs is false (required for KF_RELEASE)`);
  }
  return done();
}
