<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/Decision.java (Decision.Reject). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Decision;

use Kf\Sdk\Decision;
use Kf\Sdk\Json;

/** `{"decision":"reject","key":…,"params":{…}}`; `params` is always sent (`{}`). */
final class Reject extends Decision
{
    public function __construct(public readonly string $key, public readonly array $params = [])
    {
    }

    public function toJson(): array
    {
        return ['decision' => 'reject', 'key' => $this->key, 'params' => Json::obj($this->params)];
    }
}
