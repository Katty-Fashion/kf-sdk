<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/PlatformChannel.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * @internal The module's channel to one platform service of protocol 1.1 (§15: `reads`, `settings`, `secrets`,
 * `extensions`), shared by ReadsClient, SettingsClient, SecretsClient and ExtensionsClient. Not public: the clients are
 * the API.
 *
 * Every call goes to `/api/v1/platform/modules/{slug}/{service}…` with the module token and the context the module
 * received for the request -- `X-KF-Context` and the platform's proof of it, `X-KF-Context-Proof`, both exactly as they
 * arrived (kept by `KfModule::verifyContext(..., proofHeader: ...)`), as the audit does (§11.2). A context this
 * instance did not verify (or verified more than 300 s ago), or one that came without the proof, is refused locally,
 * PERMANENT, and nothing is sent.
 *
 * The platform's `400`/`401`/`403`/`404` are PlatformRefusals; any other non-2xx answer is a KfException with the class
 * the body (or the status table) gives; a network error is TRANSIENT. JSON objects are read as associative arrays
 * (order kept), numbers as int / float (Java reads the ones with a fraction as `BigDecimal`).
 */
final class PlatformChannel
{
    public function __construct(private readonly KfModule $kf, private readonly string $service)
    {
    }

    public function kf(): KfModule
    {
        return $this->kf;
    }

    /**
     * Protocol 2.0: the context and proof of an after delivery, for a background job, or a local refusal
     * (nothing is sent) when the configuration is missing or the context is outside its ±300 s window. Java:
     * `forwarded(HookEvents.DeliveryContext)`.
     *
     * @return array{contextHeader: string, proof: ?string, ts: int}
     */
    public function forwardedDelivery(?HookEvents\DeliveryContext $delivery): array
    {
        if ($this->kf->platformUrlValue() === null || $this->kf->slug() === null || $this->kf->tokenValue() === null) {
            throw self::local($this->service . ' needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN');
        }
        if ($delivery === null) throw self::local('a delivery context is required');
        if (!$delivery->usable()) {
            throw self::local('the delivery context is outside its ' . HookEvents\DeliveryContext::WINDOW_SECONDS
                . ' s window (ts ' . $delivery->ts() . '); the platform would refuse it');
        }
        return ['contextHeader' => $delivery->contextHeader, 'proof' => $delivery->proof, 'ts' => $delivery->ts()];
    }

    /**
     * The raw context and proof of `$context`, or a local refusal (nothing is sent).
     *
     * @return array{contextHeader: string, proof: ?string, ts: int}
     */
    public function forwarded(RequestContext $context): array
    {
        if ($this->kf->platformUrlValue() === null || $this->kf->slug() === null || $this->kf->tokenValue() === null) {
            throw self::local($this->service . ' needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN');
        }
        $forwarded = $this->kf->forwardedContext($context);
        if ($forwarded === null) {
            throw self::local('the context of this request was not verified by this module in the last 300 s '
                . '(use the RequestContext the adapter gives the route handler)');
        }
        if ($forwarded['proof'] === null) {
            throw self::local('the context of this request came without ' . RequestContext::PROOF_HEADER
                . ' (an older platform)');
        }
        return $forwarded;
    }

    /**
     * Sends one call: `$method` on `…/{service}{path}{query}` (`$path` and `$query` already encoded; `$query` empty or
     * starting with `?`), `$body` as JSON when not null.
     *
     * @param array{contextHeader: string, proof: ?string, ts: int} $forwarded
     * @param array<string, string> $extraHeaders more headers (e.g. `Idempotency-Key`)
     * @return array{status: int, body: string, retryAfter: ?string}
     */
    public function send(string $method, array $forwarded, string $path, string $query, mixed $body,
        array $extraHeaders = []): array
    {
        $headers = [
            'Authorization' => 'Bearer ' . $this->kf->tokenValue(),
            'Accept' => 'application/json',
            RequestContext::CONTEXT_HEADER => $forwarded['contextHeader'],
            RequestContext::PROOF_HEADER => (string) $forwarded['proof'],
        ] + $extraHeaders;
        $text = null;
        if ($body !== null) {
            $headers['Content-Type'] = 'application/json';
            $text = Json::encode(is_array($body) ? Json::obj($body) : $body);
        }
        $url = $this->kf->platformUrlValue() . '/api/v1/platform/modules/' . $this->kf->slug() . '/' . $this->service
            . $path . $query;
        try {
            [$status, $answer, $responseHeaders] = Http::request($method, $url, $headers, $text);
        } catch (\RuntimeException $e) {
            throw new KfException(PlatformProblem::network(KfModule::describe($e)));
        }
        return ['status' => $status, 'body' => (string) $answer, 'retryAfter' => $responseHeaders['retry-after'] ?? null];
    }

    /**
     * The error of a non-2xx answer: a PlatformRefusal for 400/401/403/404, a KfException otherwise; every text of the
     * answer passed through `$redact` first (the secrets client removes a value the platform might echo).
     *
     * @param array{status: int, body: string, retryAfter: ?string} $answer
     */
    public function failure(array $answer, ?\Closure $redact = null): KfException
    {
        return $this->failureOf($answer, $redact, PlatformRefusalKind::of($answer['status']));
    }

    /**
     * The error of a non-2xx answer on a call between modules or an emitted event (protocol 2.0, §15.9, §6.3): also
     * `409`, `422`, `502` and `503` are PlatformRefusals, with the reason of the body.
     *
     * @param array{status: int, body: string, retryAfter: ?string} $answer
     */
    public function betweenModulesFailure(array $answer): KfException
    {
        return $this->failureOf($answer, null, PlatformRefusalKind::betweenModules($answer['status']));
    }

    /**
     * The error of a non-2xx answer of a platform service (protocol 2.0, `order.services.v1`, §16.6): also
     * `422 not-delivered` (REFUSED) and `501 no-provider` (UNAVAILABLE) are PlatformRefusals, with the reason of the body.
     *
     * @param array{status: int, body: string, retryAfter: ?string} $answer
     */
    public function servicesFailure(array $answer): KfException
    {
        return $this->failureOf($answer, null, PlatformRefusalKind::services($answer['status']));
    }

    /** @param array{status: int, body: string, retryAfter: ?string} $answer */
    private function failureOf(array $answer, ?\Closure $redact, ?PlatformRefusalKind $kind): KfException
    {
        $redact ??= static fn (string $text): string => $text;
        $parsed = PlatformProblem::parse($answer['status'], $answer['body'], $answer['retryAfter']);
        $body = self::object($answer['body']);
        $errors = [];
        if ($body !== null) {
            $raw = $body['errors'] ?? null;
            if (is_array($raw) && !array_is_list($raw)) {
                foreach ($raw as $k => $v) $errors[$redact((string) $k)] = $redact(self::valueOf($v));
            } elseif (is_array($raw)) {
                foreach ($raw as $o) {
                    if (is_array($o) && !array_is_list($o) && isset($o['field'])) {
                        $message = $o['message'] ?? ($o['code'] ?? null);
                        $errors[$redact(self::valueOf($o['field']))] = $redact(self::valueOf($message));
                    }
                }
            }
        }
        $detail = $parsed->detail === null ? null : $redact($parsed->detail);
        if ($errors !== []) {
            $named = [];
            foreach ($errors as $field => $message) $named[] = $field . ': ' . $message;
            $detail = ($detail === null ? '' : $detail . ' ') . '(errors: ' . implode('; ', $named) . ')';
        }
        $problem = new PlatformProblem($parsed->status, $parsed->type, $parsed->title === null ? null : $redact($parsed->title),
            $detail, $parsed->errorClass, $parsed->retryAfterSeconds);
        if ($kind === null) return new KfException($problem);
        return new PlatformRefusal($problem, $kind, self::reason($body, $parsed->type), $errors);
    }

    /**
     * The JSON value of a 2xx answer (objects as associative arrays, lists, scalars), or a permanent error when it is not
     * JSON.
     *
     * @param array{status: int, body: string, retryAfter: ?string} $answer
     */
    public function answerJson(array $answer, string $what): mixed
    {
        try {
            if (trim($answer['body']) === '') throw new \JsonException('empty');
            $parsed = json_decode($answer['body'], false, 512, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
        } catch (\JsonException) {
            throw new KfException(new PlatformProblem($answer['status'], null, null,
                'the ' . $this->service . ' answer is not ' . $what, ErrorClass::PERMANENT, null));
        }
        return self::assoc($parsed);
    }

    /**
     * The JSON object of a 2xx answer, or a permanent error naming what was expected.
     *
     * @param array{status: int, body: string, retryAfter: ?string} $answer
     * @return array<string, mixed>
     */
    public function answerObject(array $answer, string $what): array
    {
        $parsed = self::object($answer['body']);
        if ($parsed === null) {
            throw new KfException(new PlatformProblem($answer['status'], null, null,
                'the ' . $this->service . ' answer is not ' . $what, ErrorClass::PERMANENT, null));
        }
        return $parsed;
    }

    /** A refusal of the SDK itself, before any request: permanent, status `0`. */
    public static function local(string $detail): KfException
    {
        return new KfException(PlatformProblem::local($detail));
    }

    /** One path segment, percent-encoded as Java's `URLEncoder` + `%20` (`9 9` → `9%209`). */
    public static function segment(string $value): string
    {
        return str_replace('+', '%20', self::queryValue($value));
    }

    /** One query value, form-encoded as Java's `URLEncoder` (`4 2` → `4+2`). */
    public static function queryValue(string $value): string
    {
        return str_replace('%2A', '*', urlencode($value));
    }

    /**
     * A JSON text as an associative array (order kept), or null when it is not an object.
     *
     * @return ?array<string, mixed>
     */
    public static function object(?string $text): ?array
    {
        if ($text === null || trim($text) === '') return null;
        try {
            $parsed = json_decode($text, false, 512, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
        } catch (\JsonException) {
            return null;
        }
        if (!($parsed instanceof \stdClass)) return null;
        return self::assoc($parsed);
    }

    /** A parsed object as an associative array; nested objects too, lists as lists. */
    private static function assoc(mixed $value): mixed
    {
        if ($value instanceof \stdClass) {
            $out = [];
            foreach (get_object_vars($value) as $k => $v) $out[(string) $k] = self::assoc($v);
            return $out;
        }
        if (is_array($value)) return array_map(self::assoc(...), $value);
        return $value;
    }

    /** Java's `String.valueOf` of a parsed JSON value. */
    private static function valueOf(mixed $v): string
    {
        if ($v === null) return 'null';
        if (is_string($v)) return $v;
        if (is_bool($v)) return $v ? 'true' : 'false';
        if (is_array($v)) return Json::encode($v);
        return (string) $v;
    }

    /** @param ?array<string, mixed> $body */
    private static function reason(?array $body, ?string $type): ?string
    {
        if ($body !== null && is_string($body['reason'] ?? null) && trim($body['reason']) !== '') return $body['reason'];
        if ($type === null || trim($type) === '') return null;
        $last = substr($type, strrpos($type, '/') === false ? 0 : strrpos($type, '/') + 1);
        return trim($last) === '' ? null : $last;
    }
}
