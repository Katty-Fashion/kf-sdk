<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformRefusal.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A refusal of the platform to one of the module's calls of protocol 1.1 (§15: ReadsClient, SettingsClient,
 * SecretsClient, ExtensionsClient): `400`, `401`, `403` or `404`, always PERMANENT unless the body says otherwise. Any
 * other failure (a `5xx`, a network error, an answer the SDK cannot read) is a plain KfException; a local refusal
 * (before any request) is a plain KfException of status `0`.
 *
 * `reason()` is the protocol's reason: the `reason` member of the RFC 9457 body when present, otherwise the last segment
 * of its `type` (`https://kf.ro/problems/insufficient-rights` → `insufficient-rights`); null when the body has neither.
 * The reasons of §15: `capability-not-declared`, `module-not-live`, `insufficient-rights` (`403`), `invalid-context`
 * (`401`), `validation` (`400`), `not-found` (`404`).
 *
 * `errors()` are the field errors of a `400` (`errors` of the body, field → message; e.g. `extensions.<slug>.<field>` on
 * an extension write), empty otherwise; the message of the exception names them.
 */
final class PlatformRefusal extends KfException
{
    /**
     * @internal
     * @param array<string, string> $errors
     */
    public function __construct(PlatformProblem $problem, private readonly PlatformRefusalKind $kind,
                                private readonly ?string $reason, private readonly array $errors)
    {
        parent::__construct($problem);
    }

    public function kind(): PlatformRefusalKind
    {
        return $this->kind;
    }

    /** The protocol's reason (`insufficient-rights`, `capability-not-declared`, …), or null. */
    public function reason(): ?string
    {
        return $this->reason;
    }

    /**
     * The field errors of a `400`, field → message; empty otherwise.
     *
     * @return array<string, string>
     */
    public function errors(): array
    {
        return $this->errors;
    }
}
