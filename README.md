# KF Platform — module SDK and examples

This repository holds the **public module SDK** of the KF Platform and **example modules** built with it. A module is a
separate service (its own container, its own database if it needs one) that plugs into the platform through the module
protocol: it declares what it adds in a manifest (`kf-module-conf.json`), receives signed callbacks and hooks, reads
platform data through published ports, contributes UI to the platform's pages and is installed as a pack (a zip).

## Layout

```text
kf-sdk/
├── sdk/                 the public SDK, one directory per version: sdk/<version>/
│   └── README.md        how to use a version, what each directory holds, how to check it
├── examples/            example modules, one directory per module
│   └── README.md        what each example shows and how to build it
├── LICENSE              proprietary, all rights reserved (use limited to building and testing KF Platform modules)
└── README.md            this file
```

Each `sdk/<version>/` is a plain copy of that SDK version (no links, no submodules): the Java reference SDK, the
TypeScript and PHP SDKs derived from it, the UI type definitions and theme, the normative protocol document, the
platform catalogs a module may use, the language-neutral conformance suite and the `kf-module` tool (packing, dev
builds, signing with your own key, verification). `sdk/<version>/kf-sdk-build.json` lists every file with its SHA-256.

No version is published yet. The first public version will be **4.1.0**: from then on the platform, the SDK and the
release milestone share the same major.minor.

## Versions and compatibility

- **One version for all languages.** Java, TypeScript and PHP carry the same semver for a given SDK version.
- **The protocol and the manifest.** Every SDK version speaks one protocol version and writes/reads one manifest
  `schemaVersion`; `sdk/<version>/sdk-protocol.md` states both in its header table, together with the callback
  envelope version (`kfenvelopeversion`) and the signed-context version (`v`).
- **What the platform judges.** The platform judges your manifest (`schemaVersion`, the `platform` semver range you
  declare), the callback envelope and the signed context, not the SDK version. An SDK refuses locally a manifest newer
  than the one it speaks; an envelope with an unsupported major version is refused with `422
  unsupported-envelope-version`. So an older SDK keeps working on a newer platform while those majors stay the same,
  and a module that wants a newer capability moves to an SDK version that knows it.
- **Pin a version.** Build your module against one `sdk/<version>/` and move to a newer one deliberately, with your
  module's tests green.

## Documentation

The documentation lives in two books of the KF wiki (shelf «2605. Aladin»), in English:

- **[KF Platform — Module Specifications](https://wiki.katty-fashion.ro/books/kf-platform-module-specifications)** —
  the module structure, the manifest, back-end and UI integration, build and packaging, lifecycle and dependencies,
  errors and conformance, SDK versioning, signing, platform reads, glossary;
- **[KF Platform — Tutorial: your first module](https://wiki.katty-fashion.ro/books/kf-platform-tutorial-your-first-module)** —
  a module built step by step, from the manifest to the signed pack (the example `examples/kf-demo-model-card/`).

The books are **protected**: you need an account on the wiki to read them. If you are building a module for the KF
Platform and do not have one yet, ask your contact at Katty Fashion for access.

## Reporting a problem

Open an issue in this repository with the SDK version (`sdk/<version>/`), the language, what you did, what you
expected and what happened (the platform's error body, `class` and `reason` included, when there is one). Do not paste
secrets, tokens, keys or personal data into an issue. For a security problem, do not open a public issue: use the
repository's **Security** tab to report it privately.

## License

Proprietary, all rights reserved: see [LICENSE](LICENSE). Partners building modules for the KF Platform may use the
contents only to build and test such modules; redistribution is not permitted.
