# Example modules

Modules built on the public SDK, each in its own directory with a README of its own. They are exported from the
platform's module repository, where they are built and tested; changes are made there and exported again.

| example | protocol | manifest `schemaVersion` | what it shows |
|---|---|---|---|
| [`kf-demo-model-card`](kf-demo-model-card/README.md) | 1.3.1 | 2 | the tutorial module: a UI card on a platform page, a module page, its own database, platform reads, hooks, the tenant-data export |
| [`api-peer-owner-test`](api-peer-owner-test/README.md) | 2.0 | 3 | the owner side of a module-to-module call: a peer read, a command, an event, a UI slot |
| [`api-peer-caller-test`](api-peer-caller-test/README.md) | 2.0 | 3 | the caller side: an optional dependency, peer reads and commands, a subscription, a contribution into another module's slot |
| [`api-ui-test`](api-ui-test/README.md) | 1.1 | 2 | one contribution on each UI surface, `nav[]`, a hand-written UI module with no build step |
| [`api-settings-test`](api-settings-test/README.md) | 1.1 | 2 | tenant settings, an extension field the module owns, a provided read, the platform's error shapes |

`api-peer-owner-test` and `api-peer-caller-test` are a pair: install the owner first. Modules on manifest
`schemaVersion` 3 (protocol 2.0) need a platform that speaks protocol 2.0.

## Building an example

Each example has a `build.sh <version>` that builds its pack, `dist/<slug>-<version>.zip`, after running its tests.
Today the scripts take the SDK with `git archive` from a git ref (`KF_SDK_REF`) of a repository that holds it under
`sdk/java/...`, `sdk/ts/...` (`KF_SDK_REPO`) — the layout of the platform's module repository. Building them from an
`sdk/<version>/` directory of this repository is not wired yet; it comes with the first public SDK version, 4.1.0. Until then,
the code, the manifests and the tests are the part to read. Requirements: Git Bash or another POSIX shell, Java 21,
Node >= 22. The UI tests of `kf-demo-model-card` run on a Vitest/React install given by `KF_FE_NODE_MODULES`.

A pack built this way is a **dev pack**: it is installed unsigned on a development organization of the platform
(Modules -> Dev). A release pack is signed with the publisher's key; the Module Specifications book explains signing and
the publisher identity.

## Reusing an example for your own module

- **Change the slug.** The `kf-` prefix is reserved for the platform's publisher (Katty Fashion): a pack under `kf-*`
  signed by anyone else is refused. Pick your own slug, and change `author` in `kf-module-conf.json`.
- **Keep the manifest honest.** Declare only what the module uses (reads, hooks, surfaces, routes); the platform shows
  the declarations at approval and refuses calls outside them.
- **No secrets in the repository.** Secrets reach a module through the platform at runtime, never through files in the
  pack or the repository.
