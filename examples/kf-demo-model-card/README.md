# kf-demo-model-card - the tutorial module

The module built step by step in the wiki book "KF Platform - Tutorial: your first module": a card on the model's
Overview tab (surface `models.detail.overview.cards`), a module page with the pinned cards, its own database
(Flyway, tenant-scoped), platform reads (`model.v1`, the garment image port), hooks (`model.after.delete`,
`tenant.after.delete`), a notification and the tenant-data export (`admin/export`).

Back end: Java 21 + Spring Boot on `ro.kf:kf-module-sdk`. UI: React + esbuild, `@kf/ui` and `@kf/host-sdk` from
the host page.

Protocol 1.3.1, manifest `schemaVersion` 2 (`kf-module-conf.json`).

## Building

`build.sh <version>` builds the pack (`dist/<slug>-<version>.zip`) after running the tests. Today it takes the SDK
with `git archive` from a git ref (`KF_SDK_REF`) of a repository with the SDK under `sdk/java/...`, `sdk/ts/...`
(`KF_SDK_REPO`), the layout of the platform's module repository; building from `sdk/<version>/` of this repository
is not wired yet (see `examples/README.md`). Requirements: Git Bash or a POSIX shell, Java 21, Node >= 22. Dev packs
are installed unsigned on a development organization of the platform (Modules -> Dev); see the wiki books linked
from the top README.

This directory is exported from the platform's module repository; changes are made there and exported again.
