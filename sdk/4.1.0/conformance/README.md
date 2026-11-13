# The conformance suite of the module SDKs

Language-neutral JSON fixtures that fix the behaviour of the module SDK. Each SDK (Java, the reference, written by
hand; TS and PHP, derived from it) has a runner that reads **every** fixture here and passes it:

- Java: `java/kf-module-sdk/src/test/java/ro/kf/sdk/conformance/ConformanceSuiteTest.java` (`./mvnw -B verify` in
  `java/kf-module-sdk`);
- TS: `ts/kf-module-sdk/test/conformance.test.mjs`;
- PHP: `php/kf-module-sdk/tests/conformance.php`.

## The format

One JSON file per case, in the directory of its area: `conformance/<area>/<name>.json`. The shape is
`fixture.schema.json`; a runner checks it key by key, without a JSON Schema library.

| key | what it is |
|---|---|
| `id` | `<area>/<file name without .json>`, unique |
| `area` | the area; equal to the directory name |
| `description` | what the case proves |
| `source` | where the wire shape comes from: a section of `sdk-protocol.md`, the platform class it follows, or a rule of the SDK itself |
| `input` | the input, specific to the area |
| `expect` | the expected behaviour, specific to the area |

No other key. JSON values compare as JSON (the order of the keys of an object does not matter), except where the
area says otherwise (the signed bytes).

## The rules

1. **Nothing skipped.** A runner fails the suite when it finds an area it does not know, a failing fixture, a fixture
   it cannot run, a fixture without `source`, or no fixture at all. There is no skip mechanism.
2. **One change, its fixture.** A fixture comes with the change of the Java SDK that needs it; the TS and PHP SDKs
   receive it when they are derived again.

## The areas

- `errors`: the platform's answer at registration and the SDK's decision (retry or not, the error class, the message);
- `registration`: the module's declarative description and the exact registration body the SDK sends;
- `manifest`: `kf-module-conf.json`, checked locally before the first request;
- `signature`: the HMAC signature of the callbacks;
- `envelope` and `before`: a signed callback, the handler it reaches and the answer;
- `flags`: the feature flags of a callback;
- `context`: the signed context on the module's own routes;
- `audit`, `notifications`: the audit and notification clients;
- `reads`, `settings`, `secrets`, `extensions`, `storage`: the platform services of protocol 1.1 and 1.2;
- `peers`, `events`: calls and events between modules (protocol 2.0);
- `slots`: the slots of a module and the contributions to them (protocol 2.0);
- `flow`, `services`: the module's steps in the order flow (protocol 2.0).

The input and the expected result of each area follow `fixture.schema.json` and the section of `sdk-protocol.md` that
each fixture's `source` names.
