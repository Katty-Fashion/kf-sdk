// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/AssetAnnotations.java. Do not edit by hand: fix Java, then re-derive.
import { DeliveryContext } from './hookEvents.js';
import type { ForwardedContext, KfModule } from './kfModule.js';
import { Manifest } from './manifest.js';
import { type Answer, local, PlatformChannel } from './platformChannel.js';
import type { RequestContext } from './requestContext.js';

/**
 * The platform's annotations on a model asset the module removed (protocol 2.3,
 * `collaboration.asset-annotations.v1`, sdk-protocol.md §15.10): when the module deletes an asset it owns, it
 * asks the platform to delete the annotations the platform keeps on it.
 * `DELETE …/services/collaboration.asset-annotations.v1/{assetId}` → `204`, idempotent (an asset without annotations is
 * not an error). The context is a user's (`RequestContext`) or, for a background job, an after delivery's
 * (`DeliveryContext`: `system via hook`); the platform audits the call (`MODULE_SERVICE_CALL`). Delivered by the
 * platform.
 *
 * ```ts
 * await kf.assetAnnotations().delete(ctx, 'c0ffee00-0000-4000-8000-000000000001');
 * ```
 *
 * The local refusals (`KfException`, `class: permanent`, nothing sent): the port not declared in
 * `capabilities.services` of the manifest, an `assetId` outside `^[A-Za-z0-9._:-]{1,128}$`. The platform's refusals are
 * `PlatformRefusal`s as on every service of §15.
 */
export class AssetAnnotations {
  /** The port of protocol 2.3. */
  static readonly PORT = Manifest.ASSET_ANNOTATIONS_PORT;
  /** The module's own id of an asset: safe in a path segment, sent as it is. */
  static readonly ASSET_ID = /^[A-Za-z0-9._:-]{1,128}$/;

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'services');
  }

  /**
   * Deletes the annotations of the asset `assetId`: `DELETE …/services/…/{assetId}` → `204`. With a request's context
   * or, for a background job, an after delivery's.
   */
  async delete(context: RequestContext | DeliveryContext, assetId: string): Promise<void> {
    const path = this.check(assetId);
    this.done(await this.channel.send('DELETE', this.forwarded(context), path, '', null));
  }

  private forwarded(context: RequestContext | DeliveryContext): ForwardedContext {
    return context instanceof DeliveryContext ? this.channel.forwardedDelivery(context) : this.channel.forwarded(context);
  }

  private check(assetId: string): string {
    if (assetId == null || !AssetAnnotations.ASSET_ID.test(assetId)) {
      throw local(`invalid asset annotations call: assetId must match ${AssetAnnotations.ASSET_ID.source}`);
    }
    const manifest = this.channel.kf().manifest();
    if (manifest != null && !manifest.capabilities.services.some((s) => s.port === AssetAnnotations.PORT)) {
      throw local(`the service ${AssetAnnotations.PORT} is not declared in capabilities.services of the manifest`);
    }
    return `/${AssetAnnotations.PORT}/${assetId}`;
  }

  private done(answer: Answer): void {
    if (answer.status >= 200 && answer.status <= 299) return;
    throw this.channel.servicesFailure(answer);
  }
}
