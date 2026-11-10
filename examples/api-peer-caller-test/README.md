# api-peer-caller-test - the caller side of a module-to-module call

The caller of `api-peer-owner-test` (protocol 2.0): an optional dependency (`requires[].optional`), `peers`
with a read and a command (with and without an idempotency key), a subscription to the owner's event that
re-reads the owner's port as a background job with the delivery context, and a panel contributed into the
owner's slot. When the owner is missing or stopped, calls get a typed `MODULE_UNAVAILABLE`.

Java 21 without Spring; tests on the SDK's `FakePlatform`.

Protocol 2.0, manifest `schemaVersion` 3 (`kf-module-conf.json`).

## Building

`build.sh <version>` builds the pack (`dist/<slug>-<version>.zip`) after running the tests. Today it takes the SDK
with `git archive` from a git ref (`KF_SDK_REF`) of a repository with the SDK under `sdk/java/...`, `sdk/ts/...`
(`KF_SDK_REPO`), the layout of the platform's module repository; building from `sdk/<version>/` of this repository
is not wired yet (see `examples/README.md`). Requirements: Git Bash or a POSIX shell, Java 21, Node >= 22. Dev packs
are installed unsigned on a development organization of the platform (Modules -> Dev); see the wiki books linked
from the top README.

This directory is exported from the platform's module repository; changes are made there and exported again.
