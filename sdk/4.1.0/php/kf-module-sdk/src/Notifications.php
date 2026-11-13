<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Notifications.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module sends a notification it declared in `capabilities.notifications` of `kf-module-conf.json` (
 * sdk-protocol.md §11.1). Obtained from `KfModule::notifications()`.
 *
 *   $kf->notifications()->send($tenantId, 'author-changed', [$userSubject], ['modelName' => 'Rochie']);
 *   $kf->notifications()->send($tenantId, 'author-changed', [$userSubject], ['modelName' => 'Rochie'],
 *       new NotificationOptions(deepLink: '/models/42', channels: ['in_app']));
 *
 * `POST /api/v1/platform/modules/{slug}/notifications` with the module token. The module names its own key
 * (`author-changed`); the platform stores it as `m.<slug>.<key>`, renders the module's texts in each recipient's
 * language and writes the rows the recipients' bell and mail show. The recipients are OIDC subjects (the `user` of a
 * RequestContext, `kfactor` of a callback), members of the tenant.
 *
 * Checked locally first, with the platform's rules (each a KfException of class PERMANENT, nothing sent): a tenant,
 * the key's grammar `[a-z][a-z0-9_-]{0,40}`, at most MAX_RECIPIENTS non-blank recipients, at least one recipient or
 * one role, 1 to MAX_ROLES role names of the grammar `[A-Z][A-Z0-9_]{0,63}` (a repeated one counts once), variables
 * whose values are strings, numbers or booleans, a relative `deepLink`, channels among `in_app`/`email`. Whether the
 * key, its variables and its channels are DECLARED, and whether a role is one of the grantable system roles, is the
 * platform's answer (`422`, `unknown-role` for a role). From protocol 1.3 (SDK 0.9.0) the module may name roles
 * instead of people (`NotificationOptions::$recipientRoles`): the platform adds the role's active members of the
 * tenant, each person once, and the module never sees who they are.
 *
 * **Retries.** Only a `transient` answer (or a network error) is retried, on DEFAULT_RETRY_DELAYS_MS (a `Retry-After`
 * is honoured up to the longest step), and every retry sends the SAME request -- the same `idempotencyKey`, generated
 * once per call when the options name none -- so the platform writes the notification once. A `permanent` refusal of
 * the platform is a NotificationRejected with the protocol's `reason` (`unknown-key`, `undeclared-variable`,
 * `not-a-member`, `not-live`, ...); a `transient` one that outlives the schedule is a KfException of class TRANSIENT.
 */
final class Notifications
{
    /** The most recipients one request may name. */
    public const MAX_RECIPIENTS = 100;
    /** The most role names of `recipientRoles` (protocol 1.3). */
    public const MAX_ROLES = 10;
    /** The shape of a role name the SDK sends (the grantable system roles are upper case); the list is the platform's. */
    public const ROLE = '/^[A-Z][A-Z0-9_]{0,63}$/';
    /** The manifest grammar of a notification key (without `m.<slug>.`). */
    public const KEY = '/^[a-z][a-z0-9_\-]{0,40}$/';
    /** The channels of the protocol. */
    public const CHANNELS = ['in_app', 'email'];
    /** The retry schedule of a `transient` answer, in milliseconds; its length is the number of retries. */
    public const DEFAULT_RETRY_DELAYS_MS = [500, 1000, 2000];

    private const MAX_DEEP_LINK = 2000;

    /** @var list<int> */
    private array $retryDelaysMs = self::DEFAULT_RETRY_DELAYS_MS;

    /** @internal */
    public function __construct(private readonly KfModule $kf)
    {
    }

    /**
     * Replaces the retry schedule of a `transient` answer (tests, a module with other needs), in milliseconds.
     *
     * @param list<int> $delaysMs
     */
    public function withRetryDelays(array $delaysMs): self
    {
        foreach ($delaysMs as $d) {
            if (!is_int($d) || $d < 0) throw new \InvalidArgumentException('retry delays must be a list of non-negative durations');
        }
        $this->retryDelaysMs = array_values($delaysMs);
        return $this;
    }

    /**
     * Sends `$key` to `$recipients` of `$tenantId`, answered `202`. Without `$options`: every declared channel, no link,
     * a generated idempotency key.
     *
     * @param list<string> $recipients
     * @param array<string, mixed> $variables
     * @throws KfException a local refusal, a network error or a `transient` answer that outlived the schedule
     * @throws NotificationRejected a `permanent` refusal of the platform
     */
    public function send(?string $tenantId, ?string $key, ?array $recipients, array $variables,
        ?NotificationOptions $options = null): NotificationResult
    {
        $opts = $options ?? NotificationOptions::none();
        $errors = [];
        if ($tenantId === null || trim($tenantId) === '') {
            $errors[] = 'tenantId is required';
        } elseif (preg_match('/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/', $tenantId) !== 1) {
            $errors[] = 'tenantId must be a UUID';
        }
        if ($key === null || preg_match(self::KEY, $key) !== 1) {
            $errors[] = 'key must match [a-z][a-z0-9_-]{0,40} (the key as the manifest declares it, without m.<slug>.)';
        }
        $roles = $opts->recipientRoles;
        if ($roles !== null) {
            if ($roles === []) $errors[] = 'recipientRoles names no role; omit it to send to recipients only';
            foreach ($roles as $role) {
                if (!is_string($role) || preg_match(self::ROLE, $role) !== 1) {
                    $errors[] = "recipientRoles: '" . (is_string($role) ? $role : 'null') . "' is not a role name (upper case, e.g. TENANT_ADMIN)";
                }
            }
            if (count(array_unique(array_map('strval', $roles))) > self::MAX_ROLES) {
                $errors[] = 'at most ' . self::MAX_ROLES . ' recipientRoles per request';
            }
        }
        $byRole = $roles !== null && $roles !== [];
        if ($recipients === null || $recipients === []) {
            if (!$byRole) $errors[] = 'recipients names no one (and no recipientRoles)';
        } elseif (count($recipients) > self::MAX_RECIPIENTS) {
            $errors[] = 'at most ' . self::MAX_RECIPIENTS . ' recipients per request';
        } else {
            foreach ($recipients as $r) {
                if (!is_string($r) || trim($r) === '') {
                    $errors[] = 'a recipient subject is blank';
                    break;
                }
            }
        }
        $vars = [];
        foreach ($variables as $name => $value) {
            if (!is_string($value) && !is_int($value) && !is_float($value) && !is_bool($value)) {
                $errors[] = "the variable '$name' must be a string, a number or a boolean";
            }
            $vars[(string) $name] = $value;
        }
        $deepLink = $opts->deepLink;
        if ($deepLink !== null && $deepLink !== '' && !self::relative($deepLink)) {
            $errors[] = "deepLink must be a relative path starting with a single '/'";
        }
        $channels = $opts->channels;
        if ($channels !== null) {
            if ($channels === []) $errors[] = 'channels names no channel; omit it for every declared channel';
            foreach ($channels as $channel) {
                if (!in_array($channel, self::CHANNELS, true)) $errors[] = "channel '$channel' is not one of in_app, email";
            }
        }
        if ($this->kf->platformUrlValue() === null || $this->kf->slug() === null || $this->kf->tokenValue() === null) {
            $errors[] = 'notifications need KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN';
        }
        if ($errors !== []) {
            throw new KfException(PlatformProblem::local('invalid notification: ' . implode('; ', $errors)));
        }

        $body = [
            'tenantId' => $tenantId,
            'key' => $key,
            'recipients' => $recipients === null ? [] : array_values(array_unique($recipients)),
        ];
        if ($byRole) $body['recipientRoles'] = array_values(array_unique($roles));
        $body['variables'] = Json::obj($vars);
        if ($deepLink !== null && $deepLink !== '') $body['deepLink'] = $deepLink;
        if ($channels !== null) $body['channels'] = array_values($channels);
        $body['idempotencyKey'] = $opts->idempotencyKey ?? self::uuid();
        $json = Json::encode($body);

        $delays = $this->retryDelaysMs;
        $longest = $delays === [] ? 0 : max($delays);
        $url = $this->kf->platformUrlValue() . '/api/v1/platform/modules/' . $this->kf->slug() . '/notifications';
        $headers = [
            'Authorization' => 'Bearer ' . $this->kf->tokenValue(),
            'Accept' => 'application/json',
            'Content-Type' => 'application/json',
        ];
        for ($attempt = 0; ; $attempt++) {
            try {
                [$status, $text, $responseHeaders] = Http::request('POST', $url, $headers, $json);
                if ($status === 202) return self::result($text);
                $problem = PlatformProblem::parse($status, $text, $responseHeaders['retry-after'] ?? null);
                if (!$problem->retryable()) throw new NotificationRejected($problem, self::reason($text));
            } catch (\RuntimeException $e) {
                if ($e instanceof KfException) throw $e;
                $problem = PlatformProblem::network(KfModule::describe($e));
            }
            if ($attempt >= count($delays)) throw new KfException($problem);
            $delay = $delays[$attempt];
            if ($problem->retryAfterSeconds !== null) {
                $asked = $problem->retryAfterSeconds * 1000;
                $delay = $asked > $longest ? $longest : $asked;
            }
            usleep($delay * 1000);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static function result(string $body): NotificationResult
    {
        try {
            $parsed = Json::decode($body);
            if ($parsed instanceof \stdClass && is_int($parsed->accepted ?? null) && is_int($parsed->duplicates ?? null)) {
                return new NotificationResult($parsed->accepted, $parsed->duplicates);
            }
        } catch (\JsonException) {
            // falls through: a 202 the SDK cannot read
        }
        throw new KfException(new PlatformProblem(202, null, null, 'the notification answer has no accepted/duplicates',
            ErrorClass::PERMANENT, null));
    }

    private static function reason(string $body): ?string
    {
        try {
            $parsed = Json::decode($body);
            if ($parsed instanceof \stdClass && is_string($parsed->reason ?? null)) return $parsed->reason;
        } catch (\JsonException) {
            // no reason
        }
        return null;
    }

    private static function relative(string $link): bool
    {
        return str_starts_with($link, '/') && !str_starts_with($link, '//') && strlen($link) <= self::MAX_DEEP_LINK
            && !str_contains($link, '\\') && preg_match('/[\x{0000}-\x{001F}\x{007F}-\x{009F}]/u', $link) !== 1;
    }

    /** A random (version 4) UUID, as `UUID.randomUUID()` gives in Java. */
    private static function uuid(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($b), 4));
    }
}
