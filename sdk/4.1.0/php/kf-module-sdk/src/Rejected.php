<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/RequestContext.java (RequestContext.Rejected). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/** A refused context: 401, PERMANENT, the reason in `detail`. */
final class Rejected extends KfException
{
    public function __construct(string $reason)
    {
        parent::__construct(new PlatformProblem(401, 'https://kf.ro/problems/invalid-context', 'Invalid context',
            'the request context was refused: ' . $reason, ErrorClass::PERMANENT, null));
    }

    /** The RFC 9457 body to answer with (`application/problem+json`). */
    public function problemJson(): string
    {
        $p = $this->problem();
        return Json::encode(['type' => $p->type, 'title' => $p->title, 'status' => $p->status, 'detail' => $p->detail,
            ErrorClass::PROPERTY => $p->errorClass->wire()]);
    }

    /** The answer for an adapter. */
    public function toResult(): HookResult
    {
        return new HookResult(401, $this->problemJson(), HookResult::PROBLEM_CONTENT_TYPE);
    }
}
