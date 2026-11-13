<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/OrderServices.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\HookEvents\DeliveryContext;
use Kf\Sdk\OrderServices\Line;

/**
 * The module's service lines on an order (protocol 2.0, `order.services.v1`, sdk-protocol.md §16.6): the module adds,
 * updates and removes its OWN lines with a cost; the platform keys them on `(orderId, slug, externalRef)` (a second
 * upsert with the same key updates the line), takes `source` from the token, never from the body, and keeps the lines
 * when the module stops or the order is cancelled. The user of the context needs `api.v1.orders.put`; a `system`
 * context writes no line. Delivered by the platform with `ORDER_SERVICES` (together with the provider
 * `OrderServiceLinesAdapter`); a module relies on it only when the platform announces the feature. A platform before
 * it answers `422 not-delivered`, and one that delivers the feature without a provider `501 no-provider`.
 *
 *     $kf->orderServices()->upsert($ctx, new OrderServices\Line('812', '4401', 'Embroidery', '12.50', 'EUR', 'embroidery:4401'));
 *     $kf->orderServices()->delete($ctx, '812', 'embroidery:4401');
 *
 * The local refusals (KfException, `class: permanent`, nothing sent): the capability not declared in
 * `capabilities.services` of the manifest, a name outside 1..200 characters, a negative amount, a currency that is not
 * ISO 4217, an `externalRef` outside `^[A-Za-z0-9._:-]{1,128}$`. The platform's refusals are PlatformRefusals: INVALID
 * (400, its `errors` by path), FORBIDDEN (403), NOT_FOUND (404, an unknown port), CONFLICT
 * (409 `order-service-line-owned-by-module`: a delete of a line another module wrote), REFUSED (422 `not-delivered`),
 * UNAVAILABLE (501 `no-provider`).
 */
final class OrderServices
{
    /**
     * The reason of `409` on a delete (§16.6): the line was written by another module, and a module deletes only
     * its own (CONFLICT, permanent).
     */
    public const LINE_OWNED_BY_MODULE = 'order-service-line-owned-by-module';

    /** The port of protocol 2.0. */
    public const PORT = Manifest::SERVICES_PORT;
    /** The longest name of a line. */
    public const MAX_NAME = 200;
    /** The module's own key of a line on an order. */
    public const EXTERNAL_REF = '^[A-Za-z0-9._:-]{1,128}$';
    /** An ISO 4217 code. */
    public const CURRENCY = '^[A-Z]{3}$';

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'services');
    }

    /**
     * Inserts or updates the line of key `(line.orderId, this module, line.externalRef)`:
     * `POST …/services/order.services.v1` → `204`. With a request's context or, for a background job, an after
     * delivery's.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the line
     */
    public function upsert(RequestContext|DeliveryContext $context, Line $line): void
    {
        $body = $this->check($line);
        $this->done($this->channel->send('POST', $this->forwarded($context), '/' . self::PORT, '', $body));
    }

    /**
     * Removes this module's line `externalRef` of the order `orderId`:
     * `DELETE …/services/order.services.v1/{externalRef}?orderId=` → `204` (an absent line is not an error).
     */
    public function delete(RequestContext|DeliveryContext $context, string $orderId, string $externalRef): void
    {
        [$path, $query] = $this->checkDelete($orderId, $externalRef);
        $this->done($this->channel->send('DELETE', $this->forwarded($context), $path, $query, null));
    }

    // ---------------------------------------------------------------------------------------------------------

    /** @return array{contextHeader: string, proof: ?string, ts: int} */
    private function forwarded(RequestContext|DeliveryContext $context): array
    {
        return $context instanceof DeliveryContext ? $this->channel->forwardedDelivery($context) : $this->channel->forwarded($context);
    }

    /** @return array<string, mixed> */
    private function check(Line $line): array
    {
        $errors = [];
        if (self::blank($line->orderId)) $errors[] = 'orderId is required';
        if (self::blank($line->entryId)) $errors[] = 'entryId is required';
        if (self::blank($line->name) || mb_strlen((string) $line->name) > self::MAX_NAME) {
            $errors[] = 'name must be 1 to ' . self::MAX_NAME . ' characters';
        }
        $amount = $line->amount;
        if ($amount === null || (is_string($amount) && !is_numeric($amount)) || $amount + 0 < 0) {
            $errors[] = 'cost.amount must be a number >= 0';
        }
        if ($line->currency === null || preg_match('/' . self::CURRENCY . '/D', $line->currency) !== 1) {
            $errors[] = 'cost.currency must be an ISO 4217 code (three upper-case letters)';
        }
        if ($line->externalRef === null || preg_match('/' . self::EXTERNAL_REF . '/D', $line->externalRef) !== 1) {
            $errors[] = 'externalRef must match ' . self::EXTERNAL_REF;
        }
        if ($errors !== []) throw PlatformChannel::local('invalid service line: ' . implode('; ', $errors));
        $this->declared();
        return $line->toJson();
    }

    /** @return array{0: string, 1: string} */
    private function checkDelete(string $orderId, string $externalRef): array
    {
        $errors = [];
        if (self::blank($orderId)) $errors[] = 'orderId is required';
        if (preg_match('/' . self::EXTERNAL_REF . '/D', $externalRef) !== 1) {
            $errors[] = 'externalRef must match ' . self::EXTERNAL_REF;
        }
        if ($errors !== []) throw PlatformChannel::local('invalid service line: ' . implode('; ', $errors));
        $this->declared();
        // the grammar of externalRef is safe in a path segment: sent as it is (the platform matches it as given)
        return ['/' . self::PORT . '/' . $externalRef, '?orderId=' . PlatformChannel::queryValue($orderId)];
    }

    /** The capability, when the module gave its manifest (`KF_MODULE_MANIFEST`). */
    private function declared(): void
    {
        $manifest = $this->channel->kf()->manifest();
        if ($manifest === null) return;
        foreach ($manifest->capabilities->services as $s) {
            if ($s->port === self::PORT) return;
        }
        throw PlatformChannel::local('the service ' . self::PORT . ' is not declared in capabilities.services of the manifest');
    }

    /** @param array{status: int, body: string, retryAfter: ?string} $answer */
    private function done(array $answer): void
    {
        if ($answer['status'] >= 200 && $answer['status'] <= 299) return;
        throw $this->channel->servicesFailure($answer);
    }

    private static function blank(?string $value): bool
    {
        return $value === null || trim($value) === '';
    }
}
