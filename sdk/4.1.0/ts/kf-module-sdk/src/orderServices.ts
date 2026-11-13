// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/OrderServices.java. Do not edit by hand: fix Java, then re-derive.
import { DeliveryContext } from './hookEvents.js';
import type { ForwardedContext, KfModule } from './kfModule.js';
import { Manifest } from './manifest.js';
import { type Answer, local, PlatformChannel, queryValue } from './platformChannel.js';
import type { RequestContext } from './requestContext.js';

/**
 * One service line: `orderId` and `entryId` the platform's ids (as text), the line's `name`, its cost (`amount` ≥ 0 in
 * `currency`) and the module's own key `externalRef`. `amount` is a decimal, as a number or a decimal text (Java:
 * `BigDecimal`). Java: `OrderServices.Line`.
 */
export class OrderServiceLine {
  constructor(
    readonly orderId: string | null,
    readonly entryId: string | null,
    readonly name: string | null,
    readonly amount: number | string | null,
    readonly currency: string | null,
    readonly externalRef: string | null,
  ) {}

  /** The JSON body of the upsert. */
  toJson(): Record<string, unknown> {
    return {
      orderId: this.orderId,
      entryId: this.entryId,
      name: this.name,
      cost: { amount: this.amount == null ? null : Number(this.amount), currency: this.currency },
      externalRef: this.externalRef,
    };
  }
}

/**
 * The module's service lines on an order (protocol 2.0, `order.services.v1`, sdk-protocol.md §16.6): the module adds,
 * updates and removes its OWN lines with a cost; the platform keys them on `(orderId, slug, externalRef)` (a second
 * upsert with the same key updates the line), takes `source` from the token, never from the body, and keeps the lines
 * when the module stops or the order is cancelled. The user of the context needs `api.v1.orders.put`; a `system`
 * context writes no line. Delivered by the platform with `ORDER_SERVICES` (together with the provider
 * `OrderServiceLinesAdapter`); a module relies on it only when the platform announces the feature. A platform before
 * it answers `422 not-delivered`, and one that delivers the feature without a provider `501 no-provider`.
 *
 * ```ts
 * await kf.orderServices().upsert(ctx, new OrderServiceLine('812', '4401', 'Embroidery', '12.50', 'EUR', 'embroidery:4401'));
 * await kf.orderServices().delete(ctx, '812', 'embroidery:4401');
 * ```
 *
 * The local refusals (`KfException`, `class: permanent`, nothing sent): the capability not declared in
 * `capabilities.services` of the manifest, a name outside 1..200 characters, a negative amount, a currency that is not
 * ISO 4217, an `externalRef` outside `^[A-Za-z0-9._:-]{1,128}$`. The platform's refusals are `PlatformRefusal`s:
 * `invalid` (400, its `errors` by path), `forbidden` (403), `not-found` (404, an unknown port), `conflict`
 * (409 `order-service-line-owned-by-module`: a delete of a line another module wrote), `refused`
 * (422 `not-delivered`), `unavailable` (501 `no-provider`).
 */
export class OrderServices {
  /**
   * The reason of `409` on a delete (§16.6): the line was written by another module, and a module deletes only its
   * own (`conflict`, permanent).
   */
  static readonly LINE_OWNED_BY_MODULE = 'order-service-line-owned-by-module';

  /** The port of protocol 2.0. */
  static readonly PORT = Manifest.SERVICES_PORT;
  /** The longest name of a line. */
  static readonly MAX_NAME = 200;
  /** The module's own key of a line on an order. */
  static readonly EXTERNAL_REF = /^[A-Za-z0-9._:-]{1,128}$/;
  /** An ISO 4217 code. */
  static readonly CURRENCY = /^[A-Z]{3}$/;

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'services');
  }

  /**
   * Inserts or updates the line of key `(line.orderId, this module, line.externalRef)`:
   * `POST …/services/order.services.v1` → `204`. With a request's context or, for a background job, an after
   * delivery's.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the line
   */
  async upsert(context: RequestContext | DeliveryContext, line: OrderServiceLine): Promise<void> {
    const body = this.check(line);
    this.done(await this.channel.send('POST', this.forwarded(context), `/${OrderServices.PORT}`, '', body));
  }

  /**
   * Removes this module's line `externalRef` of the order `orderId`:
   * `DELETE …/services/order.services.v1/{externalRef}?orderId=` → `204` (an absent line is not an error).
   */
  async delete(context: RequestContext | DeliveryContext, orderId: string, externalRef: string): Promise<void> {
    const [path, query] = this.checkDelete(orderId, externalRef);
    this.done(await this.channel.send('DELETE', this.forwarded(context), path, query, null));
  }

  // ---------------------------------------------------------------------------------------------------------

  private forwarded(context: RequestContext | DeliveryContext): ForwardedContext {
    return context instanceof DeliveryContext ? this.channel.forwardedDelivery(context) : this.channel.forwarded(context);
  }

  private check(line: OrderServiceLine | null | undefined): Record<string, unknown> {
    if (line == null) throw local('invalid service line: the line is required');
    const errors: string[] = [];
    if (blank(line.orderId)) errors.push('orderId is required');
    if (blank(line.entryId)) errors.push('entryId is required');
    if (blank(line.name) || (line.name as string).length > OrderServices.MAX_NAME) {
      errors.push(`name must be 1 to ${OrderServices.MAX_NAME} characters`);
    }
    const amount = line.amount == null || (typeof line.amount === 'string' && line.amount.trim() === '')
      ? Number.NaN : Number(line.amount);
    if (Number.isNaN(amount) || amount < 0) errors.push('cost.amount must be a number >= 0');
    if (line.currency == null || !OrderServices.CURRENCY.test(line.currency)) {
      errors.push('cost.currency must be an ISO 4217 code (three upper-case letters)');
    }
    if (line.externalRef == null || !OrderServices.EXTERNAL_REF.test(line.externalRef)) {
      errors.push(`externalRef must match ${OrderServices.EXTERNAL_REF.source}`);
    }
    if (errors.length > 0) throw local(`invalid service line: ${errors.join('; ')}`);
    this.declared();
    return line.toJson();
  }

  private checkDelete(orderId: string, externalRef: string): [string, string] {
    const errors: string[] = [];
    if (blank(orderId)) errors.push('orderId is required');
    if (externalRef == null || !OrderServices.EXTERNAL_REF.test(externalRef)) {
      errors.push(`externalRef must match ${OrderServices.EXTERNAL_REF.source}`);
    }
    if (errors.length > 0) throw local(`invalid service line: ${errors.join('; ')}`);
    this.declared();
    // the grammar of externalRef is safe in a path segment: sent as it is (the platform matches it as given)
    return [`/${OrderServices.PORT}/${externalRef}`, `?orderId=${queryValue(orderId)}`];
  }

  /** The capability, when the module gave its manifest (`KF_MODULE_MANIFEST`). */
  private declared(): void {
    const manifest = this.channel.kf().manifest();
    if (manifest != null && !manifest.capabilities.services.some((s) => s.port === OrderServices.PORT)) {
      throw local(`the service ${OrderServices.PORT} is not declared in capabilities.services of the manifest`);
    }
  }

  private done(answer: Answer): void {
    if (answer.status >= 200 && answer.status <= 299) return;
    throw this.channel.servicesFailure(answer);
  }
}

function blank(value: string | null | undefined): boolean {
  return value == null || value.trim() === '';
}
