# api-ui-test - one contribution on each UI surface

One contribution on each UI surface of protocol 1.1: a module page, a setup section, a tenant-management card,
the order rail and the order review, plus `nav[]` entries. The UI is a single hand-written ES module
(`service/src/main/resources/static/ui/surfaces.mjs`) with no build step, importing `react`, `@kf/ui` and
`@kf/host-sdk` through the page's import map. `build.sh` also produces packs that the platform refuses on
upload, to show the validation messages.

Java 21 without Spring (`KfHttpServer`).

Protocol 1.1, manifest `schemaVersion` 2 (`kf-module-conf.json`).

## Building

`build.sh <version>` builds the pack (`dist/<slug>-<version>.zip`) after running the tests. Today it takes the SDK
with `git archive` from a git ref (`KF_SDK_REF`) of a repository with the SDK under `sdk/java/...`, `sdk/ts/...`
(`KF_SDK_REPO`), the layout of the platform's module repository; building from `sdk/<version>/` of this repository
is not wired yet (see `examples/README.md`). Requirements: Git Bash or a POSIX shell, Java 21, Node >= 22. Dev packs
are installed unsigned on a development organization of the platform (Modules -> Dev); see the wiki books linked
from the top README.

This directory is exported from the platform's module repository; changes are made there and exported again.
