<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformProblem.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A platform error as the SDK reads it: an RFC 9457 body (`type`, `title`, `status`, `detail`, `class`) or, without a
 * readable body, just the status; or a network error.
 *
 * The `errorClass` decides whether the SDK retries:
 * - `class` present and known: that class;
 * - `class` present but unknown: PERMANENT -- an answer the SDK does not understand must never turn into an endless
 *   retry;
 * - `class` absent (an older platform, a filter that writes its own body, no body at all): the status table
 *   `ErrorClass::forStatus`;
 * - a network error (refused, unresolvable host, timeout): TRANSIENT.
 *
 * `describe()` is the one line the SDK logs and keeps as `lastRegistrationError()`: the status, the class and the
 * `detail`. It is built from the answer only, never from the request, so the module token cannot reach it.
 */
final class PlatformProblem
{
    private const MAX_DETAIL = 500;

    /**
     * @param int $status the HTTP status, 0 for a network error or a local refusal
     * @param ?int $retryAfterSeconds the `Retry-After` the platform asked for, or null
     */
    public function __construct(
        public readonly int $status,
        public readonly ?string $type,
        public readonly ?string $title,
        public readonly ?string $detail,
        public readonly ErrorClass $errorClass,
        public readonly ?int $retryAfterSeconds,
    ) {
    }

    /** Reads one non-2xx answer of the platform. Never throws: an unreadable body is a body without problem. */
    public static function parse(int $status, ?string $body, ?string $retryAfterHeader): self
    {
        $type = null;
        $title = null;
        $detail = null;
        $errorClass = ErrorClass::forStatus($status);
        if ($body !== null && trim($body) !== '') {
            try {
                $problem = Json::decode($body);
                if (Json::isObject($problem)) {
                    $type = Json::text(Json::get($problem, 'type'));
                    $title = Json::text(Json::get($problem, 'title'));
                    $detail = Json::text(Json::get($problem, 'detail'));
                    if (Json::has($problem, ErrorClass::PROPERTY)) {
                        $errorClass = ErrorClass::fromWire(Json::text(Json::get($problem, ErrorClass::PROPERTY))) ?? ErrorClass::PERMANENT;
                    }
                }
            } catch (\JsonException) {
                // a body that is not JSON carries no class: the status decides
            }
        }
        return new self($status, $type, $title, self::truncate($detail), $errorClass, self::retryAfter($retryAfterHeader));
    }

    /** A network error: no answer at all. Transient. `$description` is `KfModule::describe($error)`. */
    public static function network(string $description): self
    {
        return new self(0, null, null, $description, ErrorClass::TRANSIENT, null);
    }

    /**
     * A refusal of the SDK itself, before any request (the local manifest check, a capability the platform does not
     * implement yet): permanent, status 0.
     */
    public static function local(string $detail): self
    {
        return new self(0, null, null, $detail, ErrorClass::PERMANENT, null);
    }

    /** Whether the SDK retries the request. */
    public function retryable(): bool
    {
        return $this->errorClass === ErrorClass::TRANSIENT;
    }

    /**
     * One line for the log: `HTTP 409 (permanent): Module 'x' is FAILED; expected INSTALLED`, or the cause chain of a
     * network error. Never contains the token (only the answer is read).
     */
    public function describe(): string
    {
        if ($this->status === 0) return ($this->detail ?? 'null') . ' (' . $this->errorClass->wire() . ')'; // a network error or a local refusal
        $text = $this->detail ?? $this->title;
        return 'HTTP ' . $this->status . ' (' . $this->errorClass->wire() . ')' . ($text === null ? '' : ': ' . $text);
    }

    private static function truncate(?string $s): ?string
    {
        if ($s === null || strlen($s) <= self::MAX_DETAIL) return $s;
        // the first MAX_DETAIL characters (not bytes), without ext-mbstring
        if (preg_match('/^.{' . self::MAX_DETAIL . '}/us', $s, $m) !== 1) return $s;
        return $m[0] === $s ? $s : $m[0] . '…';
    }

    private static function retryAfter(?string $header): ?int
    {
        if ($header === null) return null;
        $trimmed = trim($header);
        if (preg_match('/^[+-]?\d+$/', $trimmed) !== 1) return null; // an HTTP-date is not used by the platform
        $seconds = (int) $trimmed;
        return $seconds < 0 ? null : min($seconds, 300);
    }
}
