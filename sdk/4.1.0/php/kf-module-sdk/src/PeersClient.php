<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PeersClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\HookEvents\DeliveryContext;

/**
 * The module's reads and commands of ANOTHER module, through the platform (protocol 2.0, sdk-protocol.md §15.9): the owner offers `capabilities.peerReads[]` / `capabilities.commands[]`, this module declares
 * `capabilities.peers[] {owner, reads, commands}` with the owner in its `requires`. Obtained from `KfModule::peers()`.
 *
 *     $stock = $kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.level.v1', ['sku' => 'KF-42']);
 *     $reserved = $kf->peers()->command($ctx, 'kf-stock', 'reserve', PeersClient::newIdempotencyKey(), ['sku' => 'KF-42', 'qty' => 3]);
 *
 * - `read()`: `GET …/modules/{self}/peers/{owner}/reads/{port}[?query]` → the owner's status and JSON body, unchanged;
 * - `command()`: `POST …/modules/{self}/peers/{owner}/commands/{name}` with the JSON body and the mandatory
 *   `Idempotency-Key`: the same key with the same body replays the stored answer (the owner is not called again),
 *   another body is `409 idempotency-conflict`; a key is kept 7 days. Use one key per intended effect
 *   (newIdempotencyKey()) and the SAME key when retrying it.
 *
 * Both calls carry the module token and a proved context, unchanged (PlatformChannel): the RequestContext of the request
 * the module serves, or -- for a background job -- the DeliveryContext of an after delivery, within ±300 s of its `ts`; the platform then calls the owner as `system via module:{self}`. Refused locally, PERMANENT, nothing sent:
 * an owner outside the slug grammar, a port outside `m.{owner}.{name}.v{N}`, a command name outside
 * `[a-z][a-z0-9-]{1,40}`, a blank query name or a null query value, a missing or invalid idempotency key
 * (IDEMPOTENCY_KEY), a port or command the manifest's `capabilities.peers` does not name (when the manifest is known), a
 * context not verified or without proof, a delivery context outside its window.
 *
 * The platform's refusals are PlatformRefusals with the reason of §15.9: `peer-not-required`, `capability-not-declared`,
 * `peer-forbidden` (`403`); `peer-port-unknown`, `peer-command-unknown`, NOT_CONFIGURED (`404`);
 * `idempotency-key-required`, `validation` (`400`); IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS and IDEMPOTENCY_ANSWER_NOT_STORED
 * (`409`; the last is permanent: use a new key to run the command again);
 * MODULE_REFUSED (`422`); INVALID_ANSWER (`502`); MODULE_UNAVAILABLE (`503`, transient when the owner is stopped,
 * permanent when it is absent -- an absent optional dependency included). The class is the body's.
 *
 * Java's overloads on RequestContext / DeliveryContext are one method taking either.
 */
final class PeersClient
{
    /** The header of a command's idempotency key. */
    public const IDEMPOTENCY_HEADER = 'Idempotency-Key';
    /** The grammar of an idempotency key (§15.9). */
    public const IDEMPOTENCY_KEY = '^[A-Za-z0-9._:-]{8,128}$';

    /** The owner answered `404` (`NOT_CONFIGURED`). */
    public const NOT_CONFIGURED = 'not-configured';
    /** The owner answered another `4xx` (`MODULE_REFUSED`, `422`). */
    public const MODULE_REFUSED = 'module-refused';
    /** The owner is absent, stopped, failing or unreachable (`MODULE_UNAVAILABLE`, `503`). */
    public const MODULE_UNAVAILABLE = 'module-unavailable';
    /** The owner answered a `2xx` that is not JSON (`502`). */
    public const INVALID_ANSWER = 'invalid-answer';
    /** The owner is not in this module's `requires` (`403`). */
    public const PEER_NOT_REQUIRED = 'peer-not-required';
    /** The key is missing or outside IDEMPOTENCY_KEY (`400`). */
    public const IDEMPOTENCY_KEY_REQUIRED = 'idempotency-key-required';
    /** The same key with another body (`409`, permanent). */
    public const IDEMPOTENCY_CONFLICT = 'idempotency-conflict';
    /**
     * The same key again, the first `2xx` too large to store (`409`, permanent): use a new key to run the command again.
     */
    public const IDEMPOTENCY_ANSWER_NOT_STORED = 'idempotency-answer-not-stored';
    /** The same key, still running (`409`, transient). */
    public const IDEMPOTENCY_IN_PROGRESS = 'idempotency-in-progress';

    private const SLUG = '^[a-z0-9][a-z0-9-]{1,40}$';

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'peers');
    }

    /** A fresh idempotency key (a random UUID, 36 characters, inside IDEMPOTENCY_KEY). */
    public static function newIdempotencyKey(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
        $h = bin2hex($b);
        return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-' . substr($h, 12, 4) . '-' . substr($h, 16, 4) . '-' . substr($h, 20);
    }

    /**
     * Reads `$port` of `$owner` in the context's tenant, on behalf of the request the module serves -- or, for a
     * background job, with an after delivery's context.
     *
     * @param ?array<string, string> $query the port's query parameters (name → value, sent in this order); empty or null for none
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call
     */
    public function read(RequestContext|DeliveryContext $context, string $owner, string $port, ?array $query = null): PeerAnswer
    {
        $q = $this->checkRead($owner, $port, $query);
        return $this->answer($this->channel->send('GET', $this->forwarded($context), self::readPath($owner, $port), $q, null));
    }

    /**
     * Runs the command `$name` of `$owner` in the context's tenant, once per `$idempotencyKey`.
     *
     * @param ?string $idempotencyKey mandatory, IDEMPOTENCY_KEY; the same one when retrying the same effect
     * @param mixed $body the command's JSON body; null sends `{}`
     * @throws KfException a local refusal (a missing or invalid key included), a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call
     */
    public function command(RequestContext|DeliveryContext $context, string $owner, string $name, ?string $idempotencyKey,
        mixed $body = null): PeerAnswer
    {
        $this->checkCommand($owner, $name, $idempotencyKey);
        return $this->answer($this->channel->send('POST', $this->forwarded($context), self::commandPath($owner, $name), '',
            $body ?? new \stdClass(), [self::IDEMPOTENCY_HEADER => (string) $idempotencyKey]));
    }

    // ---------------------------------------------------------------------------------------------------------

    /** @return array{contextHeader: string, proof: ?string, ts: int} */
    private function forwarded(RequestContext|DeliveryContext $context): array
    {
        return $context instanceof DeliveryContext ? $this->channel->forwardedDelivery($context) : $this->channel->forwarded($context);
    }

    /** @param array{status: int, body: string, retryAfter: ?string} $answer */
    private function answer(array $answer): PeerAnswer
    {
        if ($answer['status'] < 200 || $answer['status'] > 299) throw $this->channel->betweenModulesFailure($answer);
        // an owner's 2xx without a body (204) is passed on as its status and no body
        if (trim($answer['body']) === '') return new PeerAnswer($answer['status'], null);
        return new PeerAnswer($answer['status'], $this->channel->answerJson($answer, 'JSON'));
    }

    /** @param ?array<string, ?string> $query */
    private function checkRead(string $owner, string $port, ?array $query): string
    {
        $errors = [];
        $ownerOk = self::checkOwner($errors, $owner);
        if ($ownerOk && !ManifestCheck::fullMatch(ManifestCheck::peerPort($owner), $port)) {
            $errors[] = "port must be m.$owner.{name}.v{N}";
        }
        $q = '';
        foreach ($query ?? [] as $name => $value) {
            $name = (string) $name;
            if (trim($name) === '') $errors[] = 'a query name is blank';
            elseif ($value === null) $errors[] = "query $name has no value";
            else $q .= ($q === '' ? '?' : '&') . PlatformChannel::queryValue($name) . '=' . PlatformChannel::queryValue((string) $value);
        }
        if ($errors !== []) throw PlatformChannel::local('invalid peer read: ' . implode('; ', $errors));
        $this->checkDeclared($owner, $port, true);
        return $q;
    }

    private function checkCommand(string $owner, string $name, ?string $idempotencyKey): void
    {
        $errors = [];
        self::checkOwner($errors, $owner);
        if (!ManifestCheck::fullMatch(ManifestCheck::PEER_NAME, $name)) $errors[] = 'command must match ' . ManifestCheck::PEER_NAME;
        if ($idempotencyKey === null || trim($idempotencyKey) === '') {
            $errors[] = self::IDEMPOTENCY_HEADER . ' is required (PeersClient.newIdempotencyKey(), the same one on a retry)';
        } elseif (preg_match('/' . self::IDEMPOTENCY_KEY . '/D', $idempotencyKey) !== 1) {
            $errors[] = self::IDEMPOTENCY_HEADER . ' must match ' . self::IDEMPOTENCY_KEY;
        }
        if ($errors !== []) throw PlatformChannel::local('invalid peer command: ' . implode('; ', $errors));
        $this->checkDeclared($owner, $name, false);
    }

    /** @param list<string> $errors */
    private static function checkOwner(array &$errors, string $owner): bool
    {
        if (preg_match('/' . self::SLUG . '/D', $owner) !== 1) {
            $errors[] = 'owner must be a module slug (' . self::SLUG . ')';
            return false;
        }
        return true;
    }

    /** With the manifest known, `capabilities.peers` must name the owner and the port or command (else 403). */
    private function checkDeclared(string $owner, string $what, bool $read): void
    {
        $manifest = $this->channel->kf()->manifest();
        if ($manifest === null) return;
        foreach ($manifest->capabilities->peers as $p) {
            if ($p->owner === $owner && in_array($what, $read ? $p->reads : $p->commands, true)) return;
        }
        throw PlatformChannel::local(($read ? 'the read ' : 'the command ') . "$what of $owner is not declared in "
            . 'capabilities.peers of the manifest');
    }

    private static function readPath(string $owner, string $port): string
    {
        return '/' . $owner . '/reads/' . PlatformChannel::segment($port);
    }

    private static function commandPath(string $owner, string $name): string
    {
        return '/' . $owner . '/commands/' . $name;
    }
}
