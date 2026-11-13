#!/usr/bin/env bash
# Builds the test module api-ui-test and its variants:
#
#   ./build.sh 0.1.0-dev.1 [0.1.0-dev.2]
#
# produces, in dist/:
#   api-ui-test-<version>.zip                   one React contribution per UI surface of protocol 1.1: module.pages
#                                               (page demo), setup.sections (servers) and
#                                               tenant-management.cards, orderflow.rail.sections and
#                                               orderflow.review.after; nav[] staff + platform
#   api-ui-test-<version>-page-pe-tab.zip       the same pack plus a surface models.detail.tabs with "page": refused at
#                                               upload, "page only on surfaces of type page (… is of type tab)"
#   api-ui-test-<version>-pagini-fara-page.zip  the same pack plus a surface module.pages without "page": refused at
#                                               upload, "page is required on a surface of type page"
#   api-ui-test-<crash version>.zip             (second argument, default: none) the same manifest at <crash version>,
#                                               whose compose sets API_UI_TEST_CRASH=1: the two order contributions are
#                                               registered on exports that throw while rendering
#
# The UI is one hand-written ES module (service/src/main/resources/static/ui/surfaces.mjs, no build step); it is
# syntax-checked and smoke-tested with node (service/src/test/ui) before the jar is built.
#
# A TEST module: uploaded WITHOUT a key on a development organization (ModuleDevService), never signed
# (docker/module-trust/README.md: the test modules stay dev modules). Its version is a pre-release (0.1.0-dev.N): a
# dev version must be strictly newer than the active one.
#
# The module SDK is PINNED to SDK_REF (a tag sdk-vX.Y.Z of this repo), exported read-only with `git archive` into .sdk/
# and installed into ~/.m2 from there, as modules/kf-aas-r3/build.sh does.
#   KF_SDK_REF   overrides SDK_REF;  KF_SDK_REPO  the path of kf-platform-modules (default: ../../..)
#   SKIP_TESTS=1 packages without running the module's tests
set -euo pipefail

VERSION="${1:-}"
CRASH_VERSION="${2:-}"
SEMVER='^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'
if [[ ! "$VERSION" =~ $SEMVER || $# -lt 1 || $# -gt 2 || ( -n "$CRASH_VERSION" && ! "$CRASH_VERSION" =~ $SEMVER ) ]]; then
  echo "usage: ./build.sh <semver version> [<crash semver version>], e.g. ./build.sh 0.1.0-dev.1 0.1.0-dev.2" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK_REF="${KF_SDK_REF:-sdk-v0.7.2}"
SDK_REPO="${KF_SDK_REPO:-$HERE/../../..}"
KF_MODULE="$SDK_REPO/sdk/tools/kf-module.mjs"
VENDOR="$HERE/.sdk"
JAVA_SDK="$VENDOR/sdk/java/kf-module-sdk"
DIST="$HERE/dist"
SLUG="api-ui-test"

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
[[ -n "$PY" ]] || { echo "python is needed to write the zips" >&2; exit 1; }

# pack <variant> <zip name>: stage the manifest (version set; the variant applied), the jar, Dockerfile, compose,
# kf-build.json, then zip the stage.
pack() {
  local variant="$1" zip_name="$2" version="${3:-$VERSION}" stage="$DIST/stage-$1"
  rm -rf "$stage" "$DIST/$zip_name"
  mkdir -p "$stage"
  node --input-type=module - "$HERE/kf-module-conf.json" "$stage/kf-module-conf.json" "$version" "$SLUG" "$variant" <<'JSEOF'
import { readFileSync, writeFileSync } from 'node:fs';
const [src, out, version, slug, variant] = process.argv.slice(2);
const conf = JSON.parse(readFileSync(src, 'utf8'));
conf.version = version;
if (conf.slug !== slug || conf.schemaVersion !== 2) { console.error('kf-module-conf.json: slug/schemaVersion'); process.exit(1); }
if (variant === 'page-pe-tab') {
  // first zip: "page" on a surface of type tab
  conf.capabilities.surfaces.push({ slug: 'models.detail.tabs', kind: 'react', label: 'page.demo', page: 'demo' });
} else if (variant === 'pagini-fara-page') {
  // second zip: a module.pages surface without "page"
  conf.capabilities.surfaces.push({ slug: 'module.pages', kind: 'react', label: 'page.demo' });
} else if (variant !== 'bun' && variant !== 'crapa') {
  console.error(`unknown variant ${variant}`); process.exit(1);
}
writeFileSync(out, JSON.stringify(conf, null, 2) + '\n');
console.log(`   manifest ${variant}: ${conf.slug} ${conf.version}`);
JSEOF
  cp "$HERE/docker-compose.yml" "$HERE/Dockerfile" "$stage/"
  if [[ "$variant" == "crapa" ]]; then
    # the order contributions on the exports that throw (ApiUiTestModule.CRASH_ENV)
    node -e 'const f=process.argv[1],fs=require("fs");const s=fs.readFileSync(f,"utf8");fs.writeFileSync(f,s.replace(/^    environment:$/m,"    environment:\n      API_UI_TEST_CRASH: \"1\""))' "$stage/docker-compose.yml"
    grep -q 'API_UI_TEST_CRASH: "1"' "$stage/docker-compose.yml" || { echo "crapa: compose not patched" >&2; exit 1; }
  fi
  cp "$HERE/service/target/api-ui-test.jar" "$stage/app.jar"
  node "$KF_MODULE" build-info --module-dir "$HERE" --version "$version" --sdk-ref "$SDK_REF" --sdk-repo "$SDK_REPO" \
    --out "$stage/kf-build.json"
  (cd "$stage" && "$PY" - "$zip_name" <<'PYEOF'
import os, sys, zipfile
out = os.path.join("..", sys.argv[1])
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("."):
        for f in sorted(files):
            path = os.path.join(root, f)
            z.write(path, os.path.relpath(path, ".").replace(os.sep, "/"))
PYEOF
  )
  rm -rf "$stage"
  echo "==> $DIST/$zip_name"
}

echo "==> staging and zipping"
pack bun "$SLUG-$VERSION.zip"
pack page-pe-tab "$SLUG-$VERSION-page-pe-tab.zip"
pack pagini-fara-page "$SLUG-$VERSION-pagini-fara-page.zip"
if [[ -n "$CRASH_VERSION" ]]; then
  pack crapa "$SLUG-$CRASH_VERSION.zip" "$CRASH_VERSION"
fi
node "$KF_MODULE" inspect "$DIST/$SLUG-$VERSION.zip" | sed 's/^/   /'
echo "==> SDK $SDK_REF = ${SDK_SHA:0:12}, ro.kf:kf-module-sdk:$SDK_VERSION. A dev build: upload it on the development"
echo "    organization (/modules/dev), unsigned."
