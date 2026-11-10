# The public module SDK

**Only the external (public) SDK variant is published here.** The internal SDK variant of the platform is never
published in this repository, not even in part. Every version directory carries `kf-sdk-build.json` with
`"variant": "external"`; anything else is not a version of this repository.

No version is published yet: **the first public version will be 4.1.0**, when the platform, the SDK and the release
milestone align on the same major.minor. Until then this directory holds only this README. Each published version
appears as `sdk/<version>/` and is never rewritten: a correction is a new version.

## A version directory

```text
sdk/<version>/
├── sdk-protocol.md          the module protocol (normative): versions, manifest, callbacks, hooks, UI surfaces,
│                            routes and signed context, errors, signing; its header table gives the protocol version,
│                            the manifest schemaVersion, the envelope and context versions
├── catalog/                 the platform catalogs a module may use (public UI surfaces, hook-points schema)
├── conformance/             the language-neutral conformance suite (JSON fixtures); every SDK passes all of it
├── java/kf-module-sdk/      the Java reference SDK (ro.kf:kf-module-sdk, JDK 21)
├── ts/kf-module-sdk/        the TypeScript / Node SDK (Node >= 22), derived from the Java one
├── php/kf-module-sdk/       the PHP SDK (kf/module-sdk), derived from the Java one
├── ui/                      kf-host.d.ts (the host page API for UI contributions) and kf-theme.css
├── tools/                   the kf-module tool: inspect, verify, dev builds, keygen and signing with your own key
└── kf-sdk-build.json        version, variant (external), source commit, every file with its SHA-256
```

All three SDKs of a version carry the same semver. Java is the reference implementation; the TypeScript and PHP SDKs
are derived from it and pass the same conformance suite.

## Using a version

Pick one version and pin it in your module's build. With `V` the version and `SDK=<path to kf-sdk>/sdk/$V`:

| language | install | dependency in your module |
|---|---|---|
| Java | `cd $SDK/java/kf-module-sdk && ./mvnw -B -q install -DskipTests` (into your local `~/.m2`) | `<dependency><groupId>ro.kf</groupId><artifactId>kf-module-sdk</artifactId><version>V</version></dependency>` |
| TypeScript | `cd $SDK/ts/kf-module-sdk && npm ci && npm run build` | `"kf-module-sdk": "file:<path>/ts/kf-module-sdk"` (or bundle it into your module with esbuild) |
| PHP | none (path repository) | composer repository `{"type": "path", "url": "<path>/php/kf-module-sdk", "options": {"symlink": false}}` and `"kf/module-sdk": "V"` |
| UI | none | the types of `ui/kf-host.d.ts`; `react`, `@kf/ui` and `@kf/host-sdk` come from the host page at runtime |

The tool: `node $SDK/tools/kf-module.mjs inspect <pack.zip>` shows what a pack declares; `verify <pack.zip> --trust
<trust-store.json>` checks a signed pack the way the platform does. The Module Specifications book (top README) explains
packs, dev builds and signing.

## Checking a version

`kf-sdk-build.json` lists every file of the version with its SHA-256. For example, in Node:

```bash
node -e "const fs=require('fs'),c=require('crypto'),d='sdk/'+process.argv[1],b=require('./'+d+'/kf-sdk-build.json');let bad=0;for(const f of b.files){const h=c.createHash('sha256').update(fs.readFileSync(d+'/'+f.path)).digest('hex');if(h!==f.sha256){bad++;console.log('DIFF',f.path)}}console.log(bad?bad+' file(s) differ':'OK '+b.files.length+' files, variant '+b.variant)" <version>
```

Files marked `"executable": true` (e.g. `mvnw`) need `chmod +x` on systems that do not keep the mode.

## Compatibility

The protocol version, the manifest `schemaVersion`, the callback envelope version and the signed-context version of a
version are in the header table of its `sdk-protocol.md`, and the rules in the top README ("Versions and
compatibility") and in the Module Specifications book (chapter "SDK versioning").
