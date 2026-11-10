#!/usr/bin/env bash
# Builds the kf-demo-model-card pack: dist/kf-demo-model-card-<version>.zip
#
#   ./build.sh 0.1.0              # a dev build: any tree, any SDK ref; kf-build.json records the real state
#   ./build.sh 0.1.0 --release    # a RELEASE build (plan 214-03, Î-S4 a): refused unless the inputs are pinned
#
# The two modes (the tutorial, chapter 9):
#   dev       builds whatever is checked out. The pack carries kf-build.json with the real state (dirty, the commit,
#             the SDK ref), so it can be tested on the dev organization but `kf-module sign` refuses it.
#   --release before anything is built, `node sdk/tools/kf-module.mjs release-check` refuses: a dirty tree (anything
#             uncommitted or untracked in the repository), a HEAD that is not the tag kf-demo-model-card-v<version> (a signed tag
#             must verify), an SDK_REF that is not an sdk-v* tag (a sha is for dev builds), the manifest at another
#             version, and a FROM / image: without @sha256. SKIP_TESTS=1 is refused (a release runs the tests).
# Both modes stage kf-build.json (`kf-module build-info`: kf-build/1, slug, version, source {repo, commit, tag, dirty,
# tagSigned}, sdk {tag, commit}, baseImages, builtAt, tools) at the root of the zip.
#
# After a release build (design §3.1): test the zip on the stack (dev organization, Î-S5) and note its contentSha256
# (`node sdk/tools/kf-module.mjs inspect dist/<zip>`), then sign it offline with the release key and check it:
#   node sdk/tools/kf-module.mjs sign dist/kf-demo-model-card-<version>.zip --key <offline>/kf-release-2026-1.key
#   node sdk/tools/kf-module.mjs verify dist/kf-demo-model-card-<version>.zip --trust <the platform's trust-store.json>
#
# The module SDK is PINNED to SDK_REF, a tag sdk-vX.Y.Z of kf-platform-modules (this repo; README "Versionarea pe
# tag"). The SDK is exported read-only with `git archive` into .sdk/ (gitignored) and built there; the repo's working
# tree is never used, so a change under sdk/ reaches the module only through a new tag and a new SDK_REF here.
#   KF_SDK_REF   overrides SDK_REF (a commit sha for a dev build, or another tag to try)
#   KF_SDK_REPO  the path of kf-platform-modules (default: this repo, ../..)
#   SKIP_TESTS=1 packages without running the module's tests (dev builds only)
#
# Steps: [release-check] → export the pinned SDK → install ro.kf:kf-module-sdk:<version at the tag> into ~/.m2 (as
# modules/api-test of the prototype) → build the UI contributions (ui/, esbuild; react, @kf/ui, @kf/host-sdk external =
# the host's window.__KF__) into static/ui/ → jar (tests run) → stage (+ kf-build.json) → zip.
# Zip root: kf-module-conf.json (version replaced), docker-compose.yml, Dockerfile, app.jar, kf-build.json.
set -euo pipefail

usage() {
  echo "usage: ./build.sh <semver version> [--release], e.g. ./build.sh 0.1.0 or ./build.sh 0.1.0 --release" >&2
  exit 1
}

VERSION="${1:-}"
MODE="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || usage
[[ -z "$MODE" || "$MODE" == "--release" ]] || usage
[[ $# -le 2 ]] || usage
RELEASE=0
[[ "$MODE" == "--release" ]] && RELEASE=1

HERE="$(cd "$(dirname "$0")" && pwd)"
# sdk-v0.8.0 (plan 218-10): protocol 1.2 -- routes[].audience/portal, activation, capabilities.storage (StorageClient),
# person in the context, tenant.after.delete (TenantHooks). sdk-v0.9.0 (plan 222-13): protocol 1.3 -- the
# tenant-data export (TenantDataExport, GET /admin/export) and tenant.v1 listed by the platform's actor (admin/tenants).
# sdk-v0.9.1 (quick 261102-module-demo-ports): protocol 1.3.1 -- model.v1 with modelOwner and listed without ids by
# staff, the port model-version.garment-image.v1 (a presigned URL of 10 minutes), the surface
# models.detail.overview.cards with ctx.href; delivered by platform 6.1 (the manifest's "platform": ">=6.1").
# Bump it here to move the module to a newer SDK.
SDK_REF="${KF_SDK_REF:-sdk-v0.9.1}"
SDK_REPO="${KF_SDK_REPO:-$HERE/../..}"
KF_MODULE="$SDK_REPO/sdk/tools/kf-module.mjs"
VENDOR="$HERE/.sdk"
JAVA_SDK="$VENDOR/sdk/java/kf-module-sdk"
DIST="$HERE/dist"
STAGE="$DIST/stage-$VERSION"
ZIP_NAME="kf-demo-model-card-$VERSION.zip"
SLUG="kf-demo-model-card"

if [[ "$RELEASE" == "1" ]]; then
  echo "==> release build $SLUG $VERSION: release-check (clean tree, tag $SLUG-v$VERSION, SDK on an sdk-v* tag, digests)"
  if [[ "${SKIP_TESTS:-}" == "1" ]]; then
    echo "SKIP_TESTS=1 is refused with --release: a release runs the module's tests" >&2
    exit 1
  fi
  node "$KF_MODULE" release-check --module-dir "$HERE" --version "$VERSION" --sdk-ref "$SDK_REF" --sdk-repo "$SDK_REPO"
else
  echo "==> dev build $SLUG $VERSION (kf-build.json records the real state; kf-module sign refuses a dev build)"
fi

echo "==> module SDK: kf-platform-modules@$SDK_REF (read-only export into .sdk/)"
if ! SDK_SHA="$(git -C "$SDK_REPO" rev-parse --verify --quiet "$SDK_REF^{commit}")"; then
  echo "SDK ref $SDK_REF not found in $SDK_REPO." >&2
  echo "The tag is created by node sdk/tools/release-tag.mjs (kf-platform-modules README, \"Versionarea pe tag\");" >&2
  echo "until it exists, build on a commit: KF_SDK_REF=<sha> ./build.sh $VERSION" >&2
  exit 1
fi
if [[ "$(cat "$VENDOR/REF" 2>/dev/null)" != "$SDK_SHA" ]]; then
  rm -rf "$VENDOR" && mkdir -p "$VENDOR"
  git -C "$SDK_REPO" archive "$SDK_SHA" sdk/java/kf-module-sdk sdk/ui sdk/ts/kf-module-sdk/src/surfaces.generated.ts \
    | tar -x -C "$VENDOR"
  echo "$SDK_SHA" > "$VENDOR/REF"
fi
if [[ "$(git -C "$SDK_REPO" rev-parse --verify --quiet HEAD)" != "$SDK_SHA" ]] \
   && ! git -C "$SDK_REPO" diff --quiet "$SDK_SHA" HEAD -- sdk 2>/dev/null; then
  echo "   note: sdk/ moved in $SDK_REPO since $SDK_REF; this build stays on ${SDK_SHA:0:12}" >&2
fi
# the SDK version at the pin: the <version> right after <artifactId>kf-module-sdk</artifactId> in its pom
SDK_VERSION="$(tr -d '\r' < "$JAVA_SDK/pom.xml" | awk '/<artifactId>kf-module-sdk<\/artifactId>/ { found = 1; next }
  found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }')"
if [[ ! "$SDK_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "no kf-module-sdk <version> in the pom at $SDK_REF" >&2
  exit 1
fi

echo "==> installing ro.kf:kf-module-sdk:$SDK_VERSION into ~/.m2 (from the export of $SDK_REF)"
(cd "$JAVA_SDK" && chmod +x mvnw && ./mvnw -q -B install -DskipTests)

echo "==> building the UI contributions (ui/ -> static/ui/)"
UI_OUT="$HERE/service/src/main/resources/static/ui"
(cd "$HERE/ui" && npm ci --no-fund --no-audit --silent && KF_SDK_DIR="$VENDOR" npm run --silent build)
rm -rf "$UI_OUT"
mkdir -p "$UI_OUT"
cp "$HERE/ui/dist/surfaces.mjs" "$UI_OUT/"
# the module's own stylesheet (quick 261030-css-module; theme from the pinned SDK: KF_SDK_DIR=.sdk)
cp "$HERE/ui/dist/surfaces.css" "$UI_OUT/"

echo "==> building the jar ($VERSION, SDK $SDK_VERSION)"
MVN_TESTS=()
[[ "${SKIP_TESTS:-}" == "1" ]] && MVN_TESTS=(-DskipTests)
(cd "$HERE/service" && chmod +x mvnw && ./mvnw -q -B clean package "${MVN_TESTS[@]}" -Drevision="$VERSION" \
  -Dkf-module-sdk.version="$SDK_VERSION")

echo "==> staging"
rm -rf "$STAGE" "$DIST/$ZIP_NAME"
mkdir -p "$STAGE"
sed -E "0,/\"version\"/s/(\"version\"[[:space:]]*:[[:space:]]*)\"[^\"]*\"/\1\"$VERSION\"/" "$HERE/kf-module-conf.json" > "$STAGE/kf-module-conf.json"
node --input-type=module - "$STAGE/kf-module-conf.json" "$VERSION" "$SLUG" <<'JSEOF'
import { readFileSync } from 'node:fs';
const [file, version, slug] = process.argv.slice(2);
const conf = JSON.parse(readFileSync(file, 'utf8'));
const errors = [];
if (conf.version !== version) errors.push(`version is ${conf.version}, expected ${version}`);
if (conf.slug !== slug) errors.push(`slug is ${conf.slug}, expected ${slug}`);
if (conf.schemaVersion !== 2) errors.push('schemaVersion must be 2');
if (errors.length) { for (const e of errors) console.error(`kf-module-conf.json: ${e}`); process.exit(1); }
console.log(`   manifest ok: ${conf.slug} ${conf.version} (the full check: ManifestTest, and the platform's at upload)`);
JSEOF
cp "$HERE/docker-compose.yml" "$HERE/Dockerfile" "$STAGE/"
cp "$HERE/service/target/kf-demo-model-card.jar" "$STAGE/app.jar"
# the build provenance, in both modes (the build outputs are gitignored, so "dirty" is the state of the sources)
node "$KF_MODULE" build-info --module-dir "$HERE" --version "$VERSION" --sdk-ref "$SDK_REF" --sdk-repo "$SDK_REPO" \
  --out "$STAGE/kf-build.json"

echo "==> zipping"
PY=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import zipfile" >/dev/null 2>&1; then PY="$candidate"; break; fi
done
if [[ -n "$PY" ]]; then
  # run from inside the stage dir with relative paths (works for Windows python under Git Bash too)
  (cd "$STAGE" && "$PY" - "$ZIP_NAME" <<'PYEOF'
import os, sys, zipfile
out = os.path.join("..", sys.argv[1])
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("."):
        for f in sorted(files):
            path = os.path.join(root, f)
            z.write(path, os.path.relpath(path, ".").replace(os.sep, "/"))
    print("\n".join("   " + n for n in z.namelist()))
PYEOF
  )
else
  # fallback: PowerShell (Windows without python)
  (cd "$STAGE" && powershell.exe -NoProfile -Command "Compress-Archive -Path * -DestinationPath ..\\$ZIP_NAME -Force")
fi

rm -rf "$STAGE"
echo "==> $DIST/$ZIP_NAME (SDK $SDK_REF = ${SDK_SHA:0:12}, ro.kf:kf-module-sdk:$SDK_VERSION)"
node "$KF_MODULE" inspect "$DIST/$ZIP_NAME" | sed 's/^/   /'
if [[ "$RELEASE" == "1" ]]; then
  echo "==> next: test this zip on the dev organization, then sign it offline (README \"Build de release și semnarea\"):"
  echo "   node sdk/tools/kf-module.mjs sign modules/kf-demo-model-card/dist/$ZIP_NAME --key <offline>/kf-release-2026-1.key"
else
  echo "==> dev build: kf-module sign refuses it; a release is ./build.sh $VERSION --release from a clean checkout of $SLUG-v$VERSION"
fi
