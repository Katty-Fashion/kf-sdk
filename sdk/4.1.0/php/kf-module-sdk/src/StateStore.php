<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfModule.java (the state a JVM keeps in memory; the idiomatic PHP adapter). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Small JSON files shared by the processes of one container (PHP keeps nothing between requests): the registration
 * state written by `bin/kf-register` and the feature flags cache. The Java SDK keeps the same in the memory of its one
 * process. Default directory: `{sys_get_temp_dir()}/kf-sdk-{slug}` (override with KF_SDK_STATE_DIR).
 *
 * @internal
 */
final class StateStore
{
    public function __construct(private readonly string $dir)
    {
    }

    public function dir(): string
    {
        return $this->dir;
    }

    /** The stored value as PHP arrays, or null. */
    public function read(string $name): mixed
    {
        $raw = @file_get_contents($this->path($name));
        if ($raw === false || $raw === '') return null;
        $value = json_decode($raw, true);
        return json_last_error() === JSON_ERROR_NONE ? $value : null;
    }

    /** Atomic replace (write + rename): readers never see half a file. */
    public function write(string $name, mixed $value): void
    {
        if (!$this->ensureDir()) return;
        $tmp = $this->path($name) . '.' . getmypid() . '.' . bin2hex(random_bytes(3)) . '.tmp';
        if (@file_put_contents($tmp, Json::encode($value)) !== false) {
            if (!@rename($tmp, $this->path($name))) @unlink($tmp);
        }
    }

    private function path(string $name): string
    {
        return $this->dir . DIRECTORY_SEPARATOR . $name . '.json';
    }

    private function ensureDir(): bool
    {
        return is_dir($this->dir) || @mkdir($this->dir, 0770, true) || is_dir($this->dir);
    }
}
