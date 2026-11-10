# api-peer-owner-test - the owner side of a module-to-module call

A small conformance module of protocol 2.0. It offers other modules a peer read
(`m.api-peer-owner-test.items.v1`), an idempotent command (`register-item`), an event
(`m.api-peer-owner-test.after.item-registered`) and a UI slot (`panel.sections.v1`, kind A) that other modules
contribute to. Items live in memory, per tenant. Its pair is `api-peer-caller-test`.

Java 21 without Spring (`KfHttpServer` of the SDK); tests on the SDK's `FakePlatform`.

Protocol 2.0, manifest `schemaVersion` 3 (`kf-module-conf.json`).

## Building

`build.sh <version>` builds the pack (`dist/<slug>-<version>.zip`) after running the tests. Today it takes the SDK
with `git archive` from a git ref (`KF_SDK_REF`) of a repository with the SDK under `sdk/java/...`, `sdk/ts/...`
(`KF_SDK_REPO`), the layout of the platform's module repository; building from `sdk/<version>/` of this repository
is not wired yet (see `examples/README.md`). Requirements: Git Bash or a POSIX shell, Java 21, Node >= 22. Dev packs
are installed unsigned on a development organization of the platform (Modules -> Dev); see the wiki books linked
from the top README.

This directory is exported from the platform's module repository; changes are made there and exported again.
