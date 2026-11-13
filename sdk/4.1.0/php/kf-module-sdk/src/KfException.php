<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
