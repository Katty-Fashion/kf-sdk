<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/SettingsClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\HookEvents\DeliveryContext;
use Kf\Sdk\Manifest\SettingCapability;

/**
 * The module's settings in a tenant: capability `settings` in `kf-module-conf.json`, protocol 1.1 §15.2 (
 * "settings carry no data": a setting is a closed scalar). Obtained from `KfModule::settings()`.
 *
 *     $s = $kf->settings()->effective($ctx);
 *     $live = $s['live'];             // BOOLEAN -> bool
 *     $batch = $s['batch-size'];      // NUMBER  -> int|float (Java: BigDecimal)
 *     $mode = $s['push-mode'];        // ENUM    -> string, one of the declared values
 *
 * `effective()` sends `GET …/settings` with the module token, the context and its proof, unchanged (PlatformChannel);
 * the platform answers every declared key of the context's tenant, defaults included. The values are typed by the
 * module's manifest (SettingCapability): `BOOLEAN` → bool, `NUMBER` → int|float (within `min..max` when declared),
 * `ENUM` → string (one of `values`). A declared key missing from the answer, or a value that does not match its
 * declaration, takes the declared default (with a WARN); a key the manifest does not declare is ignored (with a WARN).
 * Without the manifest the call is refused locally (nothing to type the values with). The same local refusals as
 * ReadsClient for the context; the platform's refusals are PlatformRefusals.
 */
final class SettingsClient
{
    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'settings');
    }

    /**
     * The effective settings of the module in the tenant of `$context`, typed by the manifest, in the order the manifest
     * declares them.
     *
     * With a `DeliveryContext` (Java's overload `effective(DeliveryContext)`), for a background job on the
     * context of an after delivery: the same answer, the same typing; a context outside its window is refused locally,
     * nothing sent.
     *
     * @return array<string, mixed>
     * @throws KfException a local refusal (no manifest, unknown context, no proof), a transient error or an unreadable
     *                     answer
     * @throws PlatformRefusal the platform refused the call (`401`, `403`)
     */
    public function effective(RequestContext|DeliveryContext $context): array
    {
        $manifest = $this->channel->kf()->manifest();
        if ($manifest === null) {
            throw PlatformChannel::local('settings need the module manifest (KF_MODULE_MANIFEST) to type the values');
        }
        $forwarded = $context instanceof DeliveryContext ? $this->channel->forwardedDelivery($context) : $this->channel->forwarded($context);
        $answer = $this->channel->send('GET', $forwarded, '', '', null);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        $values = $this->channel->answerObject($answer, 'an object of values');

        $result = [];
        foreach ($manifest->capabilities->settings as $setting) {
            $key = (string) $setting->key;
            $fallback = self::typed($setting, $setting->defaultValue);
            if (!array_key_exists($key, $values)) {
                Log::warn("KF SDK: setting $key is missing from the platform's answer; the declared default applies");
                $result[$key] = $fallback;
                continue;
            }
            $value = self::typed($setting, $values[$key]);
            if ($value === null) {
                Log::warn("KF SDK: setting $key does not match its declaration ({$setting->type}); the declared default applies");
                $value = $fallback;
            }
            $result[$key] = $value;
        }
        foreach (array_keys($values) as $key) {
            if (!array_key_exists((string) $key, $result)) Log::warn("KF SDK: setting $key is not declared in the manifest; ignored");
        }
        return $result;
    }

    /** @internal The value typed by its declaration, or null when it does not match it. */
    public static function typed(SettingCapability $setting, mixed $value): mixed
    {
        if ($value === null || $setting->type === null) return null;
        return match ($setting->type) {
            'BOOLEAN' => is_bool($value) ? $value : null,
            'NUMBER' => (is_int($value) || is_float($value))
                && ($setting->min === null || $value >= $setting->min)
                && ($setting->max === null || $value <= $setting->max) ? $value : null,
            'ENUM' => is_string($value) && in_array($value, $setting->values, true) ? $value : null,
            default => null,
        };
    }
}
