<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfModule.java (the JSON mapper the Java SDK gets from Jackson). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * JSON for the SDK. Parsed JSON keeps objects and lists apart, like Jackson: an object is a `\stdClass`, a list a PHP
 * list (`json_decode($json, false)`), so `{}` and `[]` never read the same. What the SDK writes is built as PHP arrays;
 * `Json::obj()` marks a map that must stay an object when empty.
 *
 * @internal
 */
final class Json
{
    private const FLAGS = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION | JSON_THROW_ON_ERROR;

    private function __construct()
    {
    }

    public static function encode(mixed $value, bool $pretty = false): string
    {
        return json_encode($value, self::FLAGS | ($pretty ? JSON_PRETTY_PRINT : 0));
    }

    /** A map that encodes as `{}` when empty. */
    public static function obj(array $map): array|\stdClass
    {
        return $map === [] ? new \stdClass() : $map;
    }

    /**
     * Objects as `\stdClass`, lists as lists.
     *
     * @throws \JsonException
     */
    public static function decode(string $json): mixed
    {
        return json_decode($json, false, 512, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
    }

    public static function isObject(mixed $value): bool
    {
        return $value instanceof \stdClass;
    }

    /** The members of an object (`[]` for anything else), in document order. */
    public static function entries(mixed $object): array
    {
        return $object instanceof \stdClass ? get_object_vars($object) : [];
    }

    public static function has(mixed $object, string $key): bool
    {
        return $object instanceof \stdClass && property_exists($object, $key);
    }

    public static function get(mixed $object, string $key): mixed
    {
        return $object instanceof \stdClass && property_exists($object, $key) ? $object->{$key} : null;
    }

    /** A parsed tree as PHP arrays (objects become associative arrays): for handlers that read `data`. */
    public static function toArray(mixed $value): mixed
    {
        if ($value instanceof \stdClass) {
            $out = [];
            foreach (get_object_vars($value) as $k => $v) $out[$k] = self::toArray($v);
            return $out;
        }
        if (is_array($value)) return array_map(self::toArray(...), $value);
        return $value;
    }

    /** A value as text the way Java's `String.valueOf` writes a scalar (`null` stays null). */
    public static function text(mixed $value): ?string
    {
        if ($value === null) return null;
        if (is_string($value)) return $value;
        if (is_bool($value)) return $value ? 'true' : 'false';
        if (is_int($value) || is_float($value)) return (string) $value;
        return self::encode($value);
    }
}
