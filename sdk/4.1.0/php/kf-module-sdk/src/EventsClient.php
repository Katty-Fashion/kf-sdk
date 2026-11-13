<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/EventsClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\HookEvents\DeliveryContext;

/**
 * The module events `m.{owner}.after.{name}` (protocol 2.0, sdk-protocol.md §6.3): emitting the module's
 * own, declared in `capabilities.events[]`, and subscribing to another module's, declared in
 * `capabilities.hooks[{point: "m.{owner}.after.{name}"}]` with the owner in `requires`. Obtained from
 * `KfModule::events()`.
 *
 *     $kf->events()->emit($ctx, 'level-changed', ['sku' => 'KF-42', 'level' => 7]);   // the owner, kf-stock
 *     $kf->events()->on('kf-stock', 'level-changed', fn ($ev) => $jobs->submit($ev));    // a subscriber
 *
 * - `emit()`: `POST …/modules/{self}/events` `{"name", "data"}` with the module token and a proved context (the
 *   RequestContext of a request the module serves, or the DeliveryContext of an after delivery for a background job) → `202 {"eventId", "type"}`; the platform records it in its outbox, in the context's tenant. Refused
 *   locally, PERMANENT, nothing sent: a name outside `[a-z][a-z0-9-]{1,40}`, `data` null or over MAX_DATA_BYTES bytes as
 *   JSON, an event the manifest does not declare in `capabilities.events` (when the manifest is known), a context not
 *   verified or without proof. The platform's refusals are PlatformRefusals: `401 invalid-context`, `403
 *   module-not-live`, `400 validation`, `422 event-not-declared`.
 * - `on()`: the handler of one event of another module; registered as the hook `m.{owner}.after.{name}` (no
 *   `onFailure`). Delivered at least once: the handler is idempotent on ModuleEvent::eventId(). A subscription the owner
 *   does not offer now is stored inactive by the platform, with a warning.
 */
final class EventsClient
{
    /** The largest `data`, serialized as JSON (64 KiB, §6.3). */
    public const MAX_DATA_BYTES = 64 * 1024;
    /** The grammar of an event's name (and of an owner's slug, on()). */
    public const NAME = ManifestCheck::PEER_NAME;
    private const SLUG = '^[a-z0-9][a-z0-9-]{1,40}$';

    private readonly PlatformChannel $channel;
    /** @var array<string, list<callable>> */
    private array $handlers = [];

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'events');
    }

    /**
     * Emits the module's event `$name` with `$data`, in the tenant of `$context` (a request's context, or an after
     * delivery's for a background job).
     *
     * @param ?array<string, mixed> $data
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the event
     */
    public function emit(RequestContext|DeliveryContext $context, string $name, ?array $data): EmittedEvent
    {
        $body = $this->check($name, $data);
        $forwarded = $context instanceof DeliveryContext ? $this->channel->forwardedDelivery($context) : $this->channel->forwarded($context);
        return $this->emitted($this->channel->send('POST', $forwarded, '', '', $body));
    }

    /**
     * Subscribes `$handler` to the event `$name` of the module `$owner`: the point `m.{owner}.after.{name}`, sent with the
     * registration. `$handler` receives a HookEvents\ModuleEvent.
     *
     * @throws \InvalidArgumentException an owner or a name outside the grammar
     */
    public function on(string $owner, string $name, callable $handler): self
    {
        if (preg_match('/' . self::SLUG . '/D', $owner) !== 1) {
            throw new \InvalidArgumentException('owner must be a module slug (' . self::SLUG . ')');
        }
        if (preg_match('/' . self::NAME . '/D', $name) !== 1) {
            throw new \InvalidArgumentException('event name must match ' . self::NAME);
        }
        $this->handlers[HookEvents::moduleEventPoint($owner, $name)][] = $handler;
        return $this;
    }

    /**
     * @internal The handlers of a point `m.{owner}.after.{name}`; empty when none.
     *
     * @return list<callable>
     */
    public function handlers(string $point): array
    {
        return $this->handlers[$point] ?? [];
    }

    /**
     * @internal The `hooks` part of the registration body: one `{"event": point}` per subscribed point, sorted.
     *
     * @return list<array{event: string}>
     */
    public function registration(): array
    {
        $points = array_map('strval', array_keys($this->handlers));
        sort($points, SORT_STRING);
        return array_map(static fn (string $p): array => ['event' => $p], $points);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * @param ?array<string, mixed> $data
     * @return array<string, mixed>
     */
    private function check(string $name, ?array $data): array
    {
        $errors = [];
        if (preg_match('/' . self::NAME . '/D', $name) !== 1) $errors[] = 'name must match ' . self::NAME;
        if ($data === null) {
            $errors[] = 'data must be an object';
        } elseif (strlen(Json::encode(Json::obj($data))) > self::MAX_DATA_BYTES) {
            $errors[] = 'data is over ' . self::MAX_DATA_BYTES . ' bytes as JSON';
        }
        if ($errors !== []) throw PlatformChannel::local('invalid event: ' . implode('; ', $errors));
        $manifest = $this->channel->kf()->manifest();
        if ($manifest !== null) {
            $declared = false;
            foreach ($manifest->capabilities->events as $e) if ($e->name === $name) $declared = true;
            if (!$declared) throw PlatformChannel::local("the event $name is not declared in capabilities.events of the manifest");
        }
        return ['name' => $name, 'data' => Json::obj($data)];
    }

    /** @param array{status: int, body: string, retryAfter: ?string} $answer */
    private function emitted(array $answer): EmittedEvent
    {
        if ($answer['status'] < 200 || $answer['status'] > 299) throw $this->channel->betweenModulesFailure($answer);
        $body = $this->channel->answerObject($answer, '{eventId, type}');
        if (!is_string($body['eventId'] ?? null) || !is_string($body['type'] ?? null)) {
            throw new KfException(new PlatformProblem($answer['status'], null, null, 'the events answer is not {eventId, type}',
                ErrorClass::PERMANENT, null));
        }
        return new EmittedEvent($body['eventId'], $body['type']);
    }
}
