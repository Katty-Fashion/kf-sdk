<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
