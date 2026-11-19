<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/AssetAnnotations.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\HookEvents\DeliveryContext;

/**
 * The platform's annotations on a model asset the module removed (protocol 2.3,
 * `collaboration.asset-annotations.v1`, sdk-protocol.md §15.10): when the module deletes an asset it owns, it
 * asks the platform to delete the annotations the platform keeps on it.
 * `DELETE …/services/collaboration.asset-annotations.v1/{assetId}` → `204`, idempotent (an asset without annotations is
 * not an error). The context is a user's (RequestContext) or, for a background job, an after delivery's
 * (DeliveryContext: `system via hook`); the platform audits the call (`MODULE_SERVICE_CALL`). Delivered by the
 * platform.
 *
 *     $kf->assetAnnotations()->delete($ctx, 'c0ffee00-0000-4000-8000-000000000001');
 *
 * The local refusals (KfException, `class: permanent`, nothing sent): the port not declared in
 * `capabilities.services` of the manifest, an `assetId` outside `^[A-Za-z0-9._:-]{1,128}$`. The platform's refusals are
 * PlatformRefusals as on every service of §15.
 */
final class AssetAnnotations
{
    /** The port of protocol 2.3. */
    public const PORT = Manifest::ASSET_ANNOTATIONS_PORT;
    /** The module's own id of an asset: safe in a path segment, sent as it is. */
    public const ASSET_ID = '^[A-Za-z0-9._:-]{1,128}$';

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'services');
    }

    /**
     * Deletes the annotations of the asset `assetId`: `DELETE …/services/…/{assetId}` → `204`. With a request's context
     * or, for a background job, an after delivery's.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call
     */
    public function delete(RequestContext|DeliveryContext $context, string $assetId): void
    {
        $path = $this->check($assetId);
        $this->done($this->channel->send('DELETE', $this->forwarded($context), $path, '', null));
    }

    /** @return array{contextHeader: string, proof: ?string, ts: int} */
    private function forwarded(RequestContext|DeliveryContext $context): array
    {
        return $context instanceof DeliveryContext ? $this->channel->forwardedDelivery($context) : $this->channel->forwarded($context);
    }

    private function check(string $assetId): string
    {
        if (preg_match('/' . self::ASSET_ID . '/D', $assetId) !== 1) {
            throw PlatformChannel::local('invalid asset annotations call: assetId must match ' . self::ASSET_ID);
        }
        $manifest = $this->channel->kf()->manifest();
        if ($manifest !== null) {
            $declared = false;
            foreach ($manifest->capabilities->services as $s) {
                if ($s->port === self::PORT) {
                    $declared = true;
                    break;
                }
            }
            if (!$declared) {
                throw PlatformChannel::local('the service ' . self::PORT . ' is not declared in capabilities.services of the manifest');
            }
        }
        return '/' . self::PORT . '/' . $assetId;
    }

    /** @param array{status: int, body: string, retryAfter: ?string} $answer */
    private function done(array $answer): void
    {
        if ($answer['status'] >= 200 && $answer['status'] <= 299) return;
        throw $this->channel->servicesFailure($answer);
    }
}
