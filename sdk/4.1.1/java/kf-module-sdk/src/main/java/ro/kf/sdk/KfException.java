package ro.kf.sdk;

import java.util.Objects;

/**
 * An error of the SDK or of the platform as the SDK reports it to the module's code, with its {@link ErrorClass}: {@link ErrorClass#PERMANENT} means "do not retry, fix the cause".
 */
public class KfException extends RuntimeException {

    private final PlatformProblem problem;

    public KfException(PlatformProblem problem) {
        super(Objects.requireNonNull(problem, "problem").describe());
        this.problem = problem;
    }

    /** The problem: status ({@code 0} for a local refusal or a network error), detail, class. */
    public PlatformProblem problem() {
        return problem;
    }

    public ErrorClass errorClass() {
        return problem.errorClass();
    }
}
