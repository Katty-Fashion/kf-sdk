<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ModelSnapshot.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A model as the platform sends it: `{id, name, materialList, extensions: {slug: {field: value}}}`.
 * `id` is null in `model.before.create`.
 *
 * `versionExtensions`: a version's values in the model's envelope, `{versionId: {slug: {field: value}}}` read from
 * `versions.{versionId}.extensions` (protocol 1.1, §5.1: a module's write on `model_version` is an update of the
 * version's model and carries only the fields written); empty when the snapshot has no `versions`. A PHP array turns
 * a numeric version id into an integer key; `versionExtension()` takes it as text.
 */
final class ModelSnapshot
{
    /**
     * @param list<Material> $materialList
     * @param array<string, array<string, mixed>> $extensions
     * @param array<string, array<string, array<string, mixed>>> $versionExtensions absent = none (the form before
     *        SDK 0.7.4)
     */
    public function __construct(
        public readonly ?string $id,
        public readonly ?string $name,
        public readonly array $materialList,
        public readonly array $extensions,
        public readonly array $versionExtensions = [],
    ) {
    }

    /** Value of `extensions.{slug}.{field}`, or null. */
    public function extension(string $slug, string $field): mixed
    {
        return $this->extensions[$slug][$field] ?? null;
    }

    /**
     * Value of `versions.{versionId}.extensions.{slug}.{field}`, or null when the snapshot does not carry that version,
     * module or field (§5.1; SDK 0.7.4).
     */
    public function versionExtension(string $versionId, string $slug, string $field): mixed
    {
        return $this->versionExtensions[$versionId][$slug][$field] ?? null;
    }

    /** @internal */
    public static function fromMap(mixed $raw): ?self
    {
        if (!Json::isObject($raw)) return null;
        $materials = [];
        $list = Json::get($raw, 'materialList');
        if (is_array($list)) {
            foreach ($list as $o) if (Json::isObject($o)) $materials[] = Material::fromMap($o);
        }
        $versions = [];
        foreach (Json::entries(Json::get($raw, 'versions')) as $versionId => $version) {
            if (Json::isObject($version)) {
                $ext = self::extensionsOf(Json::get($version, 'extensions'));
                if ($ext !== []) $versions[(string) $versionId] = $ext;
            }
        }
        return new self(Json::text(Json::get($raw, 'id')), Json::text(Json::get($raw, 'name')), $materials,
            self::extensionsOf(Json::get($raw, 'extensions')), $versions);
    }

    /** `{slug: {field: value}}`; an entry that is not an object is skipped. */
    private static function extensionsOf(mixed $raw): array
    {
        $ext = [];
        foreach (Json::entries($raw) as $slug => $fields) {
            if (Json::isObject($fields)) $ext[(string) $slug] = Json::toArray($fields);
        }
        return $ext;
    }
}
