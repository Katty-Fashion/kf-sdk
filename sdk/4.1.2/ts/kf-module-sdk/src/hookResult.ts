// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/HookResult.java. Do not edit by hand: fix Java, then re-derive.

/**
 * What to answer to a platform callback or to a refused request: HTTP status, body and its content type
 * (`application/json` for a decision or an ack, `application/problem+json` for an RFC 9457 refusal).
 */
export class HookResult {
  static readonly CONTENT_TYPE = 'application/json';
  static readonly PROBLEM_CONTENT_TYPE = 'application/problem+json';

  constructor(
    readonly status: number,
    readonly body: string,
    readonly contentType: string = HookResult.CONTENT_TYPE,
  ) {}
}
