#!/usr/bin/env bash
# Builds the test module api-peer-caller-test (phase 230, plan 230-32, protocol 2.0, manifest schemaVersion 3):
#
#   KF_SDK_REF=<sha of kf-platform-modules> ./build.sh 0.0.1
#
# produces dist/api-peer-caller-test-<version>.zip. The caller of the proof of D-230-15: requires the owner as OPTIONAL, contributes to its slot,
# reads its port, calls its command (with and without key) and subscribes to its event; /probe reports all of it.
#
# A TEST module: never signed, never installed by the build (D-230-04: no SDK tag during phase 230). Its pack is
# uploaded by the operator only on the LOCAL stack (230-33): the development organization (/modules/dev, unsigned) or the
# local runner with KF_RUNNER_ALLOW_UNSIGNED=true, set back to false after the proof. Never on a shared environment.
#
# The module SDK is PINNED to KF_SDK_REF (a COMMITTED sha of this repo, mandatory: there is no tag of protocol 2.0
# before sdk-v4.1.0), exported read-only with `git archive` into .sdk/ and installed into ~/.m2 from there.
#   KF_SDK_REPO  the path of kf-platform-modules (default: ../../..);  SKIP_TESTS=1 packages without the tests
set -euo pipefail

VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ || $# -ne 1 ]]; then
  echo "usage: KF_SDK_REF=<sha> ./build.sh <semver version>, e.g. ./build.sh 0.0.1" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK_REF="${KF_SDK_REF:?KF_SDK_REF is required: the committed sha of kf-platform-modules whose SDK the module is built on (no tag in phase 230, D-230-04)}"
SDK_REPO="${KF_SDK_REPO:-$HERE/../../..}"
KF_MODULE="$SDK_REPO/sdk/tools/kf-module.mjs"
VENDOR="$HERE/.sdk"
JAVA_SDK="$VENDOR/sdk/java/kf-module-sdk"
DIST="$HERE/dist"
SLUG="api-peer-caller-test"

echo "==> module SDK: kf-platform-modules@$SDK_REF (read-only export into .sdk/)"
SDK_SHA="$(git -C "$SDK_REPO" rev-parse --verify --quiet "$SDK_REF^{commit}")" || {
  echo "SDK ref $SDK_REF not found in $SDK_REPO" >&2
  exit 1
}
if [[ "$(cat "$VENDOR/REF" 2>/dev/null)" != "$SDK_SHA" ]]; then
  rm -rf "$VENDOR" && mkdir -p "$VENDOR"
  git -C "$SDK_REPO" archive "$SDK_SHA" sdk/java/kf-module-sdk | tar -x -C "$VENDOR"
  echo "$SDK_SHA" > "$VENDOR/REF"
fi
SDK_VERSION="$(tr -d '\r' < "$JAVA_SDK/pom.xml" | awk '/<artifactId>kf-module-sdk<\/artifactId>/ { found = 1; next }
  found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }')"
[[ "$SDK_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]] || { echo "no kf-module-sdk <version> at $SDK_REF" >&2; exit 1; }

echo "==> installing ro.kf:kf-module-sdk:$SDK_VERSION into ~/.m2 (from the export of $SDK_REF)"
(cd "$JAVA_SDK" && chmod +x mvnw && ./mvnw -q -B install -DskipTests)

echo "==> checking the UI bundle (node --check, smoke test with stubbed host modules)"
node --check "$HERE/service/src/main/resources/static/ui/surfaces.mjs"
if [[ "${SKIP_TESTS:-}" != "1" ]]; then
  (cd "$HERE/service/src/test/ui" && node --import ./loader.mjs --test surfaces.smoke.test.mjs)
fi

echo "==> building the jar ($VERSION, SDK $SDK_VERSION)"
MVN_TESTS=()
[[ "${SKIP_TESTS:-}" == "1" ]] && MVN_TESTS=(-DskipTests)
(cd "$HERE/service" && chmod +x mvnw && ./mvnw -q -B clean package "${MVN_TESTS[@]}" -Drevision="$VERSION" \
  -Dkf-module-sdk.version="$SDK_VERSION")

PY=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import zipfile" >/dev/null 2>&1; then PY="$candidate"; break; fi
done
[[ -n "$PY" ]] || { echo "python is needed to write the zip" >&2; exit 1; }

ZIP="$SLUG-$VERSION.zip"
STAGE="$DIST/stage"
rm -rf "$STAGE" "$DIST/$ZIP"
mkdir -p "$STAGE"
node --input-type=module - "$HERE/kf-module-conf.json" "$STAGE/kf-module-conf.json" "$VERSION" "$SLUG" <<'JSEOF'
import { readFileSync, writeFileSync } from 'node:fs';
const [src, out, version, slug] = process.argv.slice(2);
const conf = JSON.parse(readFileSync(src, 'utf8'));
conf.version = version;
if (conf.slug !== slug || conf.schemaVersion !== 3) { console.error('kf-module-conf.json: slug/schemaVersion 3'); process.exit(1); }
writeFileSync(out, JSON.stringify(conf, null, 2) + '\n');
console.log(`   manifest: ${conf.slug} ${conf.version} (schemaVersion 3)`);
JSEOF
cp "$HERE/docker-compose.yml" "$HERE/Dockerfile" "$STAGE/"
cp "$HERE/service/target/$SLUG.jar" "$STAGE/app.jar"
node "$KF_MODULE" build-info --module-dir "$HERE" --version "$VERSION" --sdk-ref "$SDK_REF" --sdk-repo "$SDK_REPO" \
  --out "$STAGE/kf-build.json"
(cd "$STAGE" && "$PY" - "$ZIP" <<'PYEOF'
import os, sys, zipfile
out = os.path.join("..", sys.argv[1])
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("."):
        for f in sorted(files):
            path = os.path.join(root, f)
            z.write(path, os.path.relpath(path, ".").replace(os.sep, "/"))
PYEOF
)
rm -rf "$STAGE"
echo "==> $DIST/$ZIP"
node "$KF_MODULE" inspect "$DIST/$ZIP" | sed 's/^/   /'
echo "==> SDK $SDK_REF = ${SDK_SHA:0:12}, ro.kf:kf-module-sdk:$SDK_VERSION. A test build, unsigned: local stack only (230-33)."
