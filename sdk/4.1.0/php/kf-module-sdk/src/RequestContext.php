<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RequestContext.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What the platform tells the module about one request it forwards to the module's own routes:
 * sent INSTEAD of the user's token, in `X-KF-Context`, signed with the module's own key.
 *
 * From protocol 1.2 `person` comes last, only on the routes `audience: member`; `v` stays 1.
 * The JSON keys, in this order: `v, tenant, user, actor, locale, rights, method, path, query, ts, requestId` (`actor`
 * right after `user`). `v` stays 1.
 * - `tenant`: the caller's active tenant (UUID text); the module filters its data on it
 * - `user`: the caller's OIDC subject (opaque, the same identifier as `kfactor` on events); null when the platform calls
 *   as the system
 * - `actor`: who calls, a user or the system through a hook
 * - `locale`: the caller's language, never null
 * - `rights`: the caller's codes under `api.v1.m.{slug}.` in the active tenant, sorted
 * - `method`: the HTTP method, upper case; `path`: the decoded path the module receives (without `/m/{slug}`)
 * - `query`: the raw query string, or null; `ts`: epoch seconds, equal to `X-KF-Timestamp`
 * - `requestId`: the platform's id of this forward, for correlating the two logs
 */
final class RequestContext
{
    /** The context version this SDK reads. */
    public const CURRENT_VERSION = 1;
    /** The request headers the platform sends with the context. */
    public const CONTEXT_HEADER = 'X-KF-Context';
    public const TIMESTAMP_HEADER = 'X-KF-Timestamp';
    public const SIGNATURE_HEADER = 'X-KF-Signature';
    /**
     * The platform's own proof over `X-KF-Context` for this module: opaque to the module, which keeps it with the
     * context and sends both back when it calls the platform on behalf of the request (AuditClient). The module's key
     * cannot produce it, so it is what proves to the PLATFORM that the context (and its tenant) is one the platform
     * issued. Absent from an older platform.
     */
    public const PROOF_HEADER = 'X-KF-Context-Proof';
    /** The request attribute the PSR-15 adapter stores the verified context under. */
    public const ATTRIBUTE = 'kf.context';

    /** @param list<string> $rights */
    public function __construct(
        public readonly int $v,
        public readonly string $tenant,
        public readonly ?string $user,
        public readonly Actor $actor,
        public readonly string $locale,
        public readonly array $rights,
        public readonly string $method,
        public readonly string $path,
        public readonly ?string $query,
        public readonly int $ts,
        public readonly string $requestId,
        /**
         * protocol 1.2: the caller's e-mail and display name at the time of the request, sent
         * only on the routes `audience: member`; null when the context carries none. Never a ground for authorization:
         * the identity stays `user`
         */
        public readonly ?Person $person = null,
    ) {
    }

    /** Whether the caller holds `$right` in the active tenant. */
    public function hasRight(string $right): bool
    {
        return in_array($right, $this->rights, true);
    }

    /**
     * Verifies one forwarded request and returns its context:
     * 1. the three headers are present;
     * 2. `X-KF-Signature` = lowercase hex `HMAC-SHA256(secret, ts + "." + X-KF-Context + "." + body)`, compared in constant
     *    time, with `ts` within ±300 s of `now` (Signatures);
     * 3. `X-KF-Context` is base64url (no padding) of a JSON object of version 1, whose `ts` equals `X-KF-Timestamp`, with
     *    `tenant`, `locale`, `method`, `path`, `requestId` as text and `rights` as a list of text;
     * 4. `actor` is coherent: `user` = a user (`via`, `hookId` null, `user` set); `system` = through a hook
     *    (`user` null, `via` = `hook`, `hookId` set) or, from protocol 1.1, the platform itself calling a
     *    route the module declares in `provides` (`user` null, `via` = `platform`, `hookId` null, `rights` empty); any
     *    other type or combination is refused. A context WITHOUT `actor`
     *    (an older platform) reads as `Actor::user()`.
     *
     * @throws Rejected 401, PERMANENT, naming the reason (never the secret)
     */
    public static function verify(?string $contextHeader, ?string $timestampHeader, ?string $signatureHeader, ?string $body,
        ?string $secret, int $nowSeconds): self
    {
        if ($contextHeader === null || trim($contextHeader) === '') throw new Rejected('missing ' . self::CONTEXT_HEADER);
        $invalid = Signatures::verify($secret, $timestampHeader, $signatureHeader, $contextHeader . '.' . ($body ?? ''), $nowSeconds);
        if ($invalid !== null) throw new Rejected($invalid);
        return self::decode($contextHeader, trim((string) $timestampHeader));
    }

    /**
     * @internal The context of an after delivery (protocol 2.0, sdk-protocol.md §5.5): `X-KF-Context` as the
     * platform put it on a delivery whose signature (`ts + "." + body`) the module already verified. The header itself is
     * not covered by that signature -- the platform's proof binds it, and the platform checks the proof when the module
     * sends both back -- so it is read with the same rules as verify(), without a signature and without the match on
     * `X-KF-Timestamp`. HookEvents\DeliveryContext is the API.
     *
     * @throws Rejected when the header is not a coherent context of version 1
     */
    public static function decodeDelivered(?string $contextHeader): self
    {
        if ($contextHeader === null || trim($contextHeader) === '') throw new Rejected('missing ' . self::CONTEXT_HEADER);
        return self::decode(trim($contextHeader), null);
    }

    /** The checks of verify() after the signature; `$timestamp` null = not matched against a header. */
    private static function decode(string $contextHeader, ?string $timestamp): self
    {
        $json = null;
        if (preg_match('/^[A-Za-z0-9_-]*={0,2}$/', $contextHeader) === 1) {
            $decoded = base64_decode(strtr(rtrim($contextHeader, '='), '-_', '+/'), true);
            if ($decoded !== false) {
                try {
                    $json = Json::decode($decoded);
                } catch (\JsonException) {
                    $json = null;
                }
            }
        }
        if (!Json::isObject($json)) throw new Rejected(self::CONTEXT_HEADER . ' is not base64url of a JSON object');

        $v = Json::get($json, 'v');
        if (!(is_int($v) || is_float($v)) || (int) $v !== self::CURRENT_VERSION) {
            throw new Rejected('context version ' . (Json::text($v) ?? 'null') . ' is not supported (supported: ' . self::CURRENT_VERSION . ')');
        }
        $rawTs = Json::get($json, 'ts');
        $ts = is_int($rawTs) || is_float($rawTs) ? (int) $rawTs : PHP_INT_MIN;
        if ($timestamp === null && $ts === PHP_INT_MIN) throw new Rejected('context ts is required');
        if ($timestamp !== null && (string) $ts !== $timestamp) {
            throw new Rejected('the context ts does not match ' . self::TIMESTAMP_HEADER);
        }
        $tenant = self::requiredText($json, 'tenant');
        $locale = self::requiredText($json, 'locale');
        $method = self::requiredText($json, 'method');
        $path = self::requiredText($json, 'path');
        $requestId = self::requiredText($json, 'requestId');
        $user = Json::get($json, 'user');
        if ($user !== null && !is_string($user)) throw new Rejected('context user must be text');
        $query = Json::get($json, 'query');
        if ($query !== null && !is_string($query)) throw new Rejected('context query must be text');
        $rights = Json::get($json, 'rights');
        if (!is_array($rights) || array_filter($rights, static fn ($r) => !is_string($r)) !== []) {
            throw new Rejected('context rights must be a list of text');
        }
        $actor = self::actor($json, $user, array_values($rights));
        $person = self::person($json, $actor);
        return new self(self::CURRENT_VERSION, $tenant, $user, $actor, $locale, array_values($rights), $method, $path, $query, $ts,
            $requestId, $person);
    }

    /**
     * Protocol 1.2: `person`, the last key, only on the routes `audience: member`; absent or null = none. A
     * system context never carries one.
     */
    private static function person(\stdClass $json, Actor $actor): ?Person
    {
        $raw = Json::get($json, 'person');
        if ($raw === null) return null;
        if ($actor->type === Actor::SYSTEM) throw new Rejected('a system context carries no person');
        if (!Json::isObject($raw)) throw new Rejected('context person must be an object');
        $email = Json::get($raw, 'email');
        $displayName = Json::get($raw, 'displayName');
        if ($email !== null && !is_string($email)) throw new Rejected('context person.email must be text or null');
        if ($displayName !== null && !is_string($displayName)) throw new Rejected('context person.displayName must be text or null');
        return new Person($email, $displayName);
    }

    /**
     * the actor, coherent with `user`; absent = a user (an older platform). Protocol 1.1: `system` via `platform` has no user, no hookId and no rights.
     */
    private static function actor(\stdClass $json, ?string $user, array $rights): Actor
    {
        if (!Json::has($json, 'actor')) return Actor::user();
        $raw = Json::get($json, 'actor');
        if (!Json::isObject($raw)) throw new Rejected('context actor must be an object');
        $type = Json::get($raw, 'type');
        $via = Json::get($raw, 'via');
        $hookId = Json::get($raw, 'hookId');
        if ($type === 'user') {
            if ($user === null || trim($user) === '') throw new Rejected('actor user requires a user');
            // Protocol 2.0: a user via a module -- a call between modules, through the platform (§15.9)
            if (is_string($via) && str_starts_with($via, Actor::VIA_MODULE_PREFIX)) {
                if ($hookId !== null) throw new Rejected('actor user via a module has no hookId');
                return new Actor(Actor::USER, self::viaModule($via), null);
            }
            if ($via !== null || $hookId !== null) throw new Rejected('actor user has no via and no hookId');
            return Actor::user();
        }
        if ($type === 'system') {
            if ($user !== null) throw new Rejected('actor system has no user');
            // Protocol 2.0: a background job's call between modules, as the system via the caller
            if (is_string($via) && str_starts_with($via, Actor::VIA_MODULE_PREFIX)) {
                if ($hookId !== null) throw new Rejected('actor system via a module has no hookId');
                if ($rights !== []) throw new Rejected('actor system via a module has no rights');
                return new Actor(Actor::SYSTEM, self::viaModule($via), null);
            }
            if ($via === 'platform') {
                if ($hookId !== null) throw new Rejected('actor system via platform has no hookId');
                if ($rights !== []) throw new Rejected('actor system via platform has no rights');
                return Actor::platform();
            }
            if ($via !== 'hook') throw new Rejected('actor system requires via = hook or platform');
            if (!is_string($hookId) || trim($hookId) === '') throw new Rejected('actor system requires a hookId');
            return new Actor(Actor::SYSTEM, 'hook', $hookId);
        }
        throw new Rejected('actor type ' . ($type === null ? 'null' : '"' . Json::text($type) . '"') . ' is not user or system');
    }

    /** `module:{slug}` with the slug's grammar, or refused. */
    private static function viaModule(string $via): string
    {
        $slug = substr($via, strlen(Actor::VIA_MODULE_PREFIX));
        if (preg_match('/' . Actor::MODULE_SLUG . '/D', $slug) !== 1) {
            throw new Rejected("actor via $via is not module:{slug} (the slug must match " . Actor::MODULE_SLUG . ')');
        }
        return $via;
    }

    private static function requiredText(\stdClass $json, string $key): string
    {
        $s = Json::get($json, $key);
        if (!is_string($s) || trim($s) === '') throw new Rejected("context $key is required");
        return $s;
    }
}
