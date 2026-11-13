<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/NotificationOptions.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The optional part of `Notifications::send()`. Java: the record `NotificationOptions` and its builder; PHP:
 * named arguments.
 * - `deepLink` -- where the bell takes the recipient: a path of the platform starting with a single `/` (never an
 *   absolute URL);
 * - `channels` -- `in_app` and/or `email`, each declared by the notification; null = every declared channel;
 * - `idempotencyKey` -- the key that makes a repeated request write nothing new; null = the SDK generates one per call
 *   (the retries of that call reuse it);
 * - `recipientRoles` (protocol 1.3, SDK 0.9.0) -- names of grantable system roles (`TENANT_ADMIN`, `QC`,
 *   ...): the platform adds the active members of each role in the notification's tenant to `recipients`, each person
 *   once, and the module sees no subject; null = only `recipients`. With roles, `recipients` may be empty. Which roles
 *   exist is the platform's answer (`422 unknown-role`, `SUPER_ADMIN` included).
 *
 * ```php
 * $kf->notifications()->send($tenantId, 'm2f-impact-received', [], $variables, NotificationOptions::roles('TENANT_ADMIN'));
 * ```
 */
final class NotificationOptions
{
    /**
     * @param list<string>|null $channels
     * @param list<string|null>|null $recipientRoles
     */
    public function __construct(
        public readonly ?string $deepLink = null,
        public readonly ?array $channels = null,
        public readonly ?string $idempotencyKey = null,
        public readonly ?array $recipientRoles = null,
    ) {
    }

    /** No option: every declared channel, no link, a generated idempotency key, no role. */
    public static function none(): self
    {
        return new self();
    }

    /** Only `recipientRoles` (protocol 1.3): every declared channel, no link, a generated idempotency key. */
    public static function roles(?string ...$roles): self
    {
        return new self(recipientRoles: array_values($roles));
    }
}
