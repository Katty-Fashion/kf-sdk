<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/KfException.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * An error of the SDK or of the platform as the SDK reports it to the module's code, with its ErrorClass:
 * PERMANENT means "do not retry, fix the cause".
 */
class KfException extends \RuntimeException
{
    public function __construct(private readonly PlatformProblem $problem, ?\Throwable $previous = null)
    {
        parent::__construct($problem->describe(), $problem->status, $previous);
    }

    /** The problem: status (0 for a local refusal or a network error), detail, class. */
    public function problem(): PlatformProblem
    {
        return $this->problem;
    }

    public function errorClass(): ErrorClass
    {
        return $this->problem->errorClass;
    }
}
