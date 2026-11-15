<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/HookResult.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * What to answer to a platform callback or to a refused request: HTTP status, body and its content type
 * (`application/json` for a decision or an ack, `application/problem+json` for an RFC 9457 refusal).
 */
final class HookResult
{
    public const CONTENT_TYPE = 'application/json';
    public const PROBLEM_CONTENT_TYPE = 'application/problem+json';

    public function __construct(
        public readonly int $status,
        public readonly string $body,
        public readonly string $contentType = self::CONTENT_TYPE,
    ) {
    }

    /** Writes status, Content-Type and body to the current SAPI response. */
    public function emit(): void
    {
        if (!headers_sent()) {
            http_response_code($this->status);
            header('Content-Type: ' . $this->contentType);
        }
        echo $this->body;
    }
}
