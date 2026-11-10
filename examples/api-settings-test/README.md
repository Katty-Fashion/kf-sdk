# api-settings-test - settings, an own field and a provided read

Tenant settings (NUMBER, ENUM and BOOLEAN with defaults and bounds), an extension field the module owns on
`model_version`, and a provided read (`factory.stations.v1`) the platform forwards to the module. Every route
returns what the platform answered, including refusals (status, kind, reason, errors), so the protocol's
error shapes can be seen end to end.

Java 21 without Spring (`KfHttpServer`); tests on the SDK's `FakePlatform`.

Protocol 1.1, manifest `schemaVersion` 2 (`kf-module-conf.json`).

## Building

`build.sh <version>` builds the pack (`dist/<slug>-<version>.zip`) after running the tests. Today it takes the SDK
with `git archive` from a git ref (`KF_SDK_REF`) of a repository with the SDK under `sdk/java/...`, `sdk/ts/...`
(`KF_SDK_REPO`), the layout of the platform's module repository; building from `sdk/<version>/` of this repository
is not wired yet (see `examples/README.md`). Requirements: Git Bash or a POSIX shell, Java 21, Node >= 22. Dev packs
are installed unsigned on a development organization of the platform (Modules -> Dev); see the wiki books linked
from the top README.

This directory is exported from the platform's module repository; changes are made there and exported again.
