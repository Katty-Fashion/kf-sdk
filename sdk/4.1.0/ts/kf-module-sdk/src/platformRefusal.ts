// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformRefusal.java. Do not edit by hand: fix Java, then re-derive.
import { KfException } from './kfException.js';
import type { PlatformProblem } from './platformProblem.js';

/**
 * What the platform refused, by status: `invalid` (400, the request is invalid, {@link PlatformRefusal.errors}),
 * `unauthorized` (401, the token or the context, `invalid-context`), `forbidden` (403, the capability, the module's
 * state in the tenant, or the user's rights), `not-found` (404, nothing visible in the context's tenant); between
 * modules only (protocol 2.0, §15.9, §6.3): `conflict` (409, `idempotency-conflict` permanent or
 * `idempotency-in-progress` transient), `refused` (422, `module-refused` or `event-not-declared`), `invalid-answer`
 * (502, the owner answered a `2xx` that is not JSON), `unavailable` (503, `module-unavailable`: permanent when the owner
 * is not installed or outside the required range, transient when it is stopped or not activated for the tenant).
 * Java: `PlatformRefusal.Kind` (its `wire()` names).
 */
export type PlatformRefusalKind = 'invalid' | 'unauthorized' | 'forbidden' | 'not-found' | 'conflict' | 'refused'
  | 'invalid-answer' | 'unavailable';

/** @internal The kind of a status, or null when the status is not a refusal of this kind. */
export function refusalKindOf(status: number): PlatformRefusalKind | null {
  switch (status) {
    case 400: return 'invalid';
    case 401: return 'unauthorized';
    case 403: return 'forbidden';
    case 404: return 'not-found';
    default: return null;
  }
}

/**
 * @internal The kind of a status on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3), where the
 * platform answers `409`, `422`, `502` and `503` with a reason too; null otherwise.
 */
export function refusalKindBetweenModules(status: number): PlatformRefusalKind | null {
  switch (status) {
    case 409: return 'conflict';
    case 422: return 'refused';
    case 502: return 'invalid-answer';
    case 503: return 'unavailable';
    default: return refusalKindOf(status);
  }
}

/**
 * @internal The kind of a status on a platform service (protocol 2.0, `order.services.v1`, §16.6): `422 not-delivered`
 * is `refused`, `501 no-provider` `unavailable`, and `409 order-service-line-owned-by-module` (a delete of a line another
 * module wrote) is `conflict`; null otherwise. Java: `PlatformRefusal.Kind.services`.
 */
export function refusalKindServices(status: number): PlatformRefusalKind | null {
  switch (status) {
    case 409: return 'conflict'; // order-service-line-owned-by-module
    case 422: return 'refused';
    case 501: return 'unavailable';
    default: return refusalKindOf(status);
  }
}

/**
 * A refusal of the platform to one of the module's calls of protocol 1.1 (§15: `ReadsClient`, `SettingsClient`,
 * `SecretsClient`, `ExtensionsClient`): `400`, `401`, `403` or `404`, always `permanent` unless the body says
 * otherwise. Any other failure (a `5xx`, a network error, an answer the SDK cannot read) is a plain
 * {@link KfException}; a local refusal (before any request) is a plain {@link KfException} of status `0`.
 *
 * {@link reason} is the protocol's reason: the `reason` member of the RFC 9457 body when present, otherwise the last
 * segment of its `type` (`https://kf.ro/problems/insufficient-rights` → `insufficient-rights`); null when the body has
 * neither. The reasons of §15: `capability-not-declared`, `module-not-live`, `insufficient-rights` (`403`),
 * `invalid-context` (`401`), `validation` (`400`), `not-found` (`404`). Between modules (protocol 2.0, §15.9, §6.3,
 * `PeersClient`, `EventsClient`) also `409`, `422`, `502` and `503`, with the reasons `peer-not-required`,
 * `peer-port-unknown`, `peer-command-unknown`, `peer-forbidden`, `idempotency-key-required`, `idempotency-conflict`,
 * `idempotency-in-progress`, `not-configured`, `module-refused`, `module-unavailable`, `invalid-answer` and
 * `event-not-declared`; their class is the body's (`idempotency-in-progress` and a stopped owner's
 * `module-unavailable` are transient).
 *
 * {@link errors} are the field errors of a `400` (`errors` of the body, field → message; e.g.
 * `extensions.<slug>.<field>` on an extension write), empty otherwise; the message of the exception names them.
 */
export class PlatformRefusal extends KfException {
  readonly errors: Readonly<Record<string, string>>;

  /** @internal */
  constructor(problem: PlatformProblem, readonly kind: PlatformRefusalKind, readonly reason: string | null,
    errors: Record<string, string> | null) {
    super(problem);
    this.name = 'PlatformRefusal';
    this.errors = Object.freeze({ ...(errors ?? {}) });
  }
}
