// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/KfException.java. Do not edit by hand: fix Java, then re-derive.
import type { ErrorClass } from './errorClass.js';
import type { PlatformProblem } from './platformProblem.js';

/**
 * An error of the SDK or of the platform as the SDK reports it to the module's code, with its {@link ErrorClass}: `permanent` means "do not retry, fix the cause".
 */
export class KfException extends Error {
  constructor(readonly problem: PlatformProblem) {
    super(problem.describe());
    this.name = 'KfException';
  }

  /** The class of the problem. */
  get errorClass(): ErrorClass {
    return this.problem.errorClass;
  }
}
