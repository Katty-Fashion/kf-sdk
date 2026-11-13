<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PeersClient.java (PeersClient.Answer). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The owner's answer, passed on unchanged by the platform: its `2xx` status and its JSON body (objects as associative
 * arrays, lists, scalars); `null` when the owner answered a `2xx` without a body, in which
 * case `object()` is a permanent error.
 */
final class PeerAnswer
{
    public function __construct(
        public readonly int $status,
        public readonly mixed $body,
    ) {
    }

    /**
     * The body as an object (an associative array); a permanent KfException when it is not one.
     *
     * @return array<string, mixed>
     */
    public function object(): array
    {
        if (is_array($this->body) && ($this->body === [] || !array_is_list($this->body))) return $this->body;
        throw new KfException(new PlatformProblem($this->status, null, null, "the owner's answer is not a JSON object",
            ErrorClass::PERMANENT, null));
    }
}
