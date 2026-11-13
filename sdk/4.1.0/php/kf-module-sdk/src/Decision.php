<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Decision.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\Decision\Reject;

/**
 * Answer of a before-hook: `Decision\Allow`, `Decision\Reject` (vetoes the operation, 422 on the platform, translated
 * on the user's language) or `Decision\Patch` (implies allow; own fields and the module's own extensions; not on
 * delete). Get them from `$ctx->allow()`, `$ctx->reject($key, $params)`, `$ctx->patch()`.
 */
abstract class Decision
{
    /** The key grammar of the manifest's `i18n` block (the platform's `HookDecision.KEY`). */
    public const KEY = '/^[a-z][a-z0-9_.-]{0,119}$/';

    /** The JSON body of the answer. */
    abstract public function toJson(): array;

    /**
     * A veto with a translation key and its parameters. A key or parameters the platform considers malformed are
     * dropped there, but the veto stands: the SDK logs a warning and sends them unchanged.
     */
    public static function reject(string $key, ?array $params = null): self
    {
        if (preg_match(self::KEY, $key) !== 1) {
            Log::warn('KF SDK: veto key "' . $key . '" does not match ' . trim(self::KEY, '/') . '; the platform keeps the veto but drops the key');
        }
        return new Reject($key, $params ?? []);
    }
}
