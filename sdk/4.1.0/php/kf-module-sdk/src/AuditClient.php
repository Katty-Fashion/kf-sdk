<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/AuditClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's access to the platform's audit trail: capability `audit` in `kf-module-conf.json`
 * (`[{"access": "write"}, {"access": "read-own"}]`). Obtained from `KfModule::audit()`.
 *
 * **Implemented by the platform.** Both calls go to `/api/v1/platform/modules/{slug}/audit` with the module
 * token and the context the module received for the request -- `X-KF-Context` and the platform's own proof of it,
 * `X-KF-Context-Proof`, both exactly as they arrived (kept by `KfModule::verifyContext(..., proofHeader: ...)`, which
 * the adapters call). The platform takes the tenant and the actor from that context and from nowhere else.
 * - `record()`: access `write` -- one row in the tenant of the request, attributed to the module's slug, its type
 *   stored as `m.<slug>.<type>` (from a hook context, `actor.type = system`, the row's actor is `system:hook:<hookId>`);
 * - `list()`: access `read-own` -- only the module's own rows of that tenant, newest first.
 *
 * The record is checked locally with the platform's rules before any request (AuditRecord); a context this instance
 * did not verify (or verified more than 300 s ago), or one that came without the platform's proof, is refused locally
 * too. Every local refusal is a KfException of class PERMANENT and sends nothing. The platform's refusals: `401` →
 * AuditUnauthorized, `403` → AuditForbidden (the capability is not declared, or the module is not live for the
 * tenant), anything else → KfException.
 */
final class AuditClient
{
    /** The action: an upper-case code, as the platform validates it. */
    public const ACTION = '/^[A-Z][A-Z0-9_]{1,63}$/';
    /** The module's own type (the platform stores it as `m.<slug>.<type>`). */
    public const AGGREGATE_TYPE = '/^[A-Za-z][A-Za-z0-9_.\-]{0,49}$/';
    /** The longest aggregate reference. */
    public const MAX_AGGREGATE_REF = 255;

    /** @internal */
    public function __construct(private readonly KfModule $kf)
    {
    }

    /**
     * Writes one audit row in the tenant of `$context` (`POST …/audit`, answered `201`).
     *
     * @throws KfException a local refusal (invalid record, unknown context, no proof) or a platform error
     * @throws AuditUnauthorized the platform refused the token or the context (`401`)
     * @throws AuditForbidden the capability is not declared or the module is not live for the tenant (`403`)
     */
    public function record(RequestContext $context, AuditRecord $record): void
    {
        $errors = [];
        self::checkCode($errors, 'action', $record->action, self::ACTION, 'must match [A-Z][A-Z0-9_]{1,63}');
        self::checkCode($errors, 'aggregateType', $record->aggregateType, self::AGGREGATE_TYPE,
            'must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)');
        self::checkRef($errors, $record->aggregateRef, true);
        if ($errors !== []) throw self::local('invalid audit record: ' . implode('; ', $errors));
        $forwarded = $this->forwarded($context);

        $body = Json::encode(['action' => $record->action, 'aggregateType' => $record->aggregateType,
            'aggregateRef' => $record->aggregateRef]);
        $this->send('POST', $forwarded, '', $body, 201);
    }

    /**
     * The module's own audit rows of one aggregate type (and, when `$aggregateRef` is not null, of one aggregate) of the
     * tenant of `$context`, newest first (`GET …/audit`). Each row: `id`, `aggregateType` (the module's own type),
     * `aggregateRef`, `action`, `actor`, `performedAt`, `requestId`.
     *
     * @param ?string $aggregateRef one aggregate, or null for every aggregate of the type
     * @return list<array<string, mixed>>
     * @throws KfException a local refusal or a platform error
     * @throws AuditUnauthorized the platform refused the token or the context (`401`)
     * @throws AuditForbidden the capability is not declared or the module is not live for the tenant (`403`)
     */
    public function list(RequestContext $context, string $aggregateType, ?string $aggregateRef): array
    {
        $errors = [];
        self::checkCode($errors, 'aggregateType', $aggregateType, self::AGGREGATE_TYPE,
            'must match [A-Za-z][A-Za-z0-9_.-]{0,49} (at most 50 characters)');
        self::checkRef($errors, $aggregateRef, false);
        if ($errors !== []) throw self::local('invalid audit query: ' . implode('; ', $errors));
        $forwarded = $this->forwarded($context);

        $query = '?aggregateType=' . urlencode($aggregateType)
            . ($aggregateRef === null || trim($aggregateRef) === '' ? '' : '&aggregateRef=' . urlencode($aggregateRef));
        $answer = $this->send('GET', $forwarded, $query, null, 200);
        $parsed = json_decode($answer, true);
        if (!is_array($parsed) || array_is_list($parsed) || !isset($parsed['rows']) || !is_array($parsed['rows'])
            || !array_is_list($parsed['rows'])) {
            throw new KfException(new PlatformProblem(200, null, null, 'the audit answer has no rows', ErrorClass::PERMANENT, null));
        }
        $rows = [];
        foreach ($parsed['rows'] as $row) {
            if (is_array($row) && ($row === [] || !array_is_list($row))) $rows[] = $row;
        }
        return $rows;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** @return array{contextHeader: string, proof: ?string, ts: int} */
    private function forwarded(RequestContext $context): array
    {
        if ($this->kf->platformUrlValue() === null || $this->kf->slug() === null || $this->kf->tokenValue() === null) {
            throw self::local('audit needs KF_PLATFORM_URL, KF_MODULE_SLUG and KF_MODULE_TOKEN');
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

    /** @param array{contextHeader: string, proof: ?string, ts: int} $forwarded */
    private function send(string $method, array $forwarded, string $query, ?string $body, int $expected): string
    {
        $headers = [
            'Authorization' => 'Bearer ' . $this->kf->tokenValue(),
            'Accept' => 'application/json',
            RequestContext::CONTEXT_HEADER => $forwarded['contextHeader'],
            RequestContext::PROOF_HEADER => (string) $forwarded['proof'],
        ];
        if ($body !== null) $headers['Content-Type'] = 'application/json';
        $url = $this->kf->platformUrlValue() . '/api/v1/platform/modules/' . $this->kf->slug() . '/audit' . $query;
        try {
            [$status, $text, $responseHeaders] = Http::request($method, $url, $headers, $body);
        } catch (\RuntimeException $e) {
            throw new KfException(PlatformProblem::network(KfModule::describe($e)));
        }
        if ($status === $expected) return $text;
        $problem = PlatformProblem::parse($status, $text, $responseHeaders['retry-after'] ?? null);
        if ($status === 401) throw new AuditUnauthorized($problem);
        if ($status === 403) throw new AuditForbidden($problem);
        throw new KfException($problem);
    }

    /** @param list<string> $errors */
    private static function checkCode(array &$errors, string $name, ?string $value, string $rule, string $text): void
    {
        if ($value === null || trim($value) === '') $errors[] = "$name is required";
        elseif (preg_match($rule, $value) !== 1) $errors[] = "$name $text";
    }

    /** @param list<string> $errors */
    private static function checkRef(array &$errors, ?string $value, bool $required): void
    {
        if ($value === null || trim($value) === '') {
            if ($required) $errors[] = 'aggregateRef is required';
            return;
        }
        if (preg_match_all('/./su', trim($value)) > self::MAX_AGGREGATE_REF) {
            $errors[] = 'aggregateRef must be at most ' . self::MAX_AGGREGATE_REF . ' characters';
        } elseif (preg_match('/[\x{0000}-\x{001F}\x{007F}-\x{009F}]/u', $value) === 1) {
            $errors[] = 'aggregateRef must not contain control characters';
        }
    }

    private static function local(string $detail): KfException
    {
        return new KfException(PlatformProblem::local($detail));
    }
}
