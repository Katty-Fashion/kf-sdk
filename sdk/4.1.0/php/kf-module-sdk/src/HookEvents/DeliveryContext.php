<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/HookEvents.java (HookEvents.DeliveryContext). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\HookEvents;

use Kf\Sdk\RequestContext;
use Kf\Sdk\Signatures;

/**
 * The context of one after delivery (protocol 2.0, sdk-protocol.md §5.5): the platform's `X-KF-Context` --
 * `actor {"type": "system", "via": "hook"}`, no user, no rights, the event's tenant and locale -- and its proof
 * `X-KF-Context-Proof`, signed by the platform for THIS module. A background job started by the delivery passes it to
 * PeersClient or EventsClient in place of a RequestContext, within WINDOW_SECONDS s of ts(); the platform then calls the
 * owner as `system via module:{caller}`. The tenant always comes from the platform's context, never from the module.
 *
 * `contextHeader` and `proof` are opaque; a job queued in another process (a PHP request does not outlive its delivery)
 * can carry both and rebuild the context with of(). The proof is never printed (__toString, jsonSerialize).
 */
final class DeliveryContext implements \JsonSerializable
{
    /** How long around its `ts` the platform accepts the context (±300 s, as every context). */
    public const WINDOW_SECONDS = Signatures::MAX_SKEW_SECONDS;

    public function __construct(
        public readonly RequestContext $context,
        public readonly string $contextHeader,
        public readonly string $proof,
    ) {
        if (trim($contextHeader) === '' || trim($proof) === '') {
            throw new \InvalidArgumentException('a delivery context needs the context, ' . RequestContext::CONTEXT_HEADER
                . ' and ' . RequestContext::PROOF_HEADER);
        }
    }

    /**
     * The context of `X-KF-Context` and `X-KF-Context-Proof` as delivered (e.g. carried by a job queue).
     *
     * @throws \Kf\Sdk\Rejected          when the header is not a coherent context
     * @throws \InvalidArgumentException when the proof is missing
     */
    public static function of(?string $contextHeader, ?string $proof): self
    {
        if ($proof === null || trim($proof) === '') {
            throw new \InvalidArgumentException('a delivery context needs ' . RequestContext::PROOF_HEADER);
        }
        return new self(RequestContext::decodeDelivered($contextHeader), trim((string) $contextHeader), trim($proof));
    }

    /** The tenant of the event, as the platform signed it. */
    public function tenant(): string
    {
        return $this->context->tenant;
    }

    /** The context's `ts`, epoch seconds: the middle of the ±WINDOW_SECONDS s window. */
    public function ts(): int
    {
        return $this->context->ts;
    }

    /** Whether the platform would still accept the context at `$nowSeconds`. */
    public function usableAt(int $nowSeconds): bool
    {
        return abs($nowSeconds - $this->context->ts) <= self::WINDOW_SECONDS;
    }

    /** Whether the platform would still accept the context now. */
    public function usable(): bool
    {
        return $this->usableAt(time());
    }

    public function __toString(): string
    {
        return 'DeliveryContext[tenant=' . $this->context->tenant . ', requestId=' . $this->context->requestId . ', ts='
            . $this->context->ts . ', proof=***]';
    }

    /** Never the proof in a JSON log line. */
    public function jsonSerialize(): array
    {
        return ['tenant' => $this->context->tenant, 'requestId' => $this->context->requestId, 'ts' => $this->context->ts,
            'proof' => '***'];
    }

    /** Never the proof in a var_dump / print_r. */
    public function __debugInfo(): array
    {
        return $this->jsonSerialize();
    }
}
