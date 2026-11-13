// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ReadPage.java. Do not edit by hand: fix Java, then re-derive.

/**
 * One page of a list read (`ReadsClient.list`, protocol 1.1 §15.1): the objects of the port in the platform's fixed
 * order (`id` ascending), as JSON objects (`null` kept).
 */
export class ReadPage {
  readonly items: readonly Record<string, unknown>[];

  /**
   * @param items the objects of this page, never null
   * @param page  the page number, from 0
   * @param size  the page size the platform applied
   * @param total how many objects the whole list has
   */
  constructor(items: readonly Record<string, unknown>[] | null, readonly page: number, readonly size: number,
    readonly total: number) {
    this.items = Object.freeze([...(items ?? [])]);
  }

  /** Whether a later page has objects: `(page + 1) * size < total`. */
  hasNext(): boolean {
    return (this.page + 1) * this.size < this.total;
  }
}
