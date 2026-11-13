<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Surface.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * A UI contribution to a frontend contact surface of the host (sdk-protocol.md §7).
 *
 *   $kf->ui()->contribute('models.form.edit.fields.after',
 *       Surface::react('/m/api-test/ui/surfaces.mjs', 'AuthorSeasonFields')
 *           ->css('/m/api-test/ui/surfaces.css')->order(10)->labelKey('surface.author.label')->peer('react', '^19.2'));
 *   $kf->ui()->contribute('models.form.edit.fields.after', Surface::wc('/m/my-mod/ui/surfaces.mjs', 'kf-my-card')->flag('my-card'));
 *   $kf->ui()->contribute('models.detail.tabs',
 *       Surface::react('/m/api-test/ui/surfaces.mjs', 'AuthorSeasonTab')->key('author')->labelKey('surface.author.label'));
 *
 * `entry` and `css` must be served by the module itself, under `/m/{slug}/`; `Sapi::serveUi()` serves the module's UI
 * directory at `/ui/**` with the right MIME types.
 */
final class Surface
{
    private ?string $css = null;
    private ?int $order = null;
    private ?string $label = null;
    private ?string $flag = null;
    private ?string $key = null;
    /** @var array<string, string> */
    private array $peer = [];

    /** The largest `data` of a kind R contribution, serialized as JSON (sdk-protocol.md §7.12). */
    public const MAX_DATA_BYTES = 64 * 1024;

    /** The layer kind of a B slot (§7.12). */
    public const LAYER_KIND = '/^[a-z][a-z0-9-]{1,30}$/';

    private function __construct(
        private readonly string $kind,
        private readonly ?string $entry,
        private readonly ?string $exportName,
        private readonly ?string $tag,
        private readonly ?string $layer = null,
        private readonly ?string $source = null,
        private readonly ?array $data = null,
    ) {
    }

    /**
     * A data layer for a kind B slot of another module (`m.{owner}.{key}`, protocol 2.0, §7.12): no code — the layer
     * kind (one of the slot's `layerKinds`) and the path of a `GET` route this module declares; the host fetches it and
     * hands the data to the owner.
     *
     * @throws \InvalidArgumentException when the layer kind does not match `^[a-z][a-z0-9-]{1,30}$` or the source is
     *                                   not a path `/m/...`
     */
    public static function layer(string $layerKind, string $sourcePath): self
    {
        if (preg_match(self::LAYER_KIND, $layerKind) !== 1) {
            throw new \InvalidArgumentException('layer kind must match ^[a-z][a-z0-9-]{1,30}$ but was ' . $layerKind);
        }
        if (!str_starts_with($sourcePath, '/m/') || str_contains($sourcePath, '..') || str_contains($sourcePath, '//')) {
            throw new \InvalidArgumentException('source must be the path /m/{slug}/... of a GET route of this module but was '
                . $sourcePath);
        }
        return new self('layer', null, null, null, $layerKind, $sourcePath, null);
    }

    /**
     * A catalog resource for a kind R slot of another module (§7.12): a JSON object of at most
     * `MAX_DATA_BYTES` bytes, no code; the owner validates it against the slot's `dataSchema`.
     *
     * @param array|null $json an associative array (a JSON object)
     * @throws \InvalidArgumentException when `$json` is not an object or is larger than `MAX_DATA_BYTES` bytes
     */
    public static function data(?array $json): self
    {
        if ($json === null || ($json !== [] && array_is_list($json))) {
            throw new \InvalidArgumentException('data must be a JSON object');
        }
        $size = strlen(json_encode((object) $json, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
        if ($size > self::MAX_DATA_BYTES) {
            throw new \InvalidArgumentException('data must be at most ' . self::MAX_DATA_BYTES . ' bytes but was ' . $size);
        }
        return new self('data', null, null, null, null, null, $json);
    }

    /** A React component: the named export `$exportName` of the ES module `$entry`, rendered in the host tree. */
    public static function react(string $entry, string $exportName): self
    {
        return new self('react', $entry, $exportName, null);
    }

    /** A Web Component: `$entry` defines the custom element `$tag` (prefix it, e.g. `kf-...`). */
    public static function wc(string $entry, string $tag): self
    {
        return new self('wc', $entry, null, $tag);
    }

    /** Stylesheet injected once in the host page (React contributions style the light DOM). */
    public function css(string $css): self
    {
        $this->css = $css;
        return $this;
    }

    /** Lower renders first (default 100). */
    public function order(int $order): self
    {
        $this->order = $order;
        return $this;
    }

    /**
     * Translation KEY of the text shown discreetly next to the contribution (defaults to the module name). Under
     * manifest `schemaVersion` 2 it must be a key of `i18n.en` (checked locally by
     * ManifestCheck); the platform serves the text on the user's language.
     */
    public function labelKey(string $labelKey): self
    {
        $this->label = $labelKey;
        return $this;
    }

    /**
     * Gates the contribution with one of the module's feature flags (declared in `kf-module-conf.json`): the host only
     * renders it while the flag is effectively on. An undeclared key is accepted with a warning and counts as off.
     */
    public function flag(string $flagKey): self
    {
        $this->flag = $flagKey;
        return $this;
    }

    /**
     * Stable id of the contribution within its surface (`^[a-z][a-z0-9-]{0,40}$`); tabs use it in the URL
     * (`?tab={module}.{key}`). Defaults to the tag without `kf-` or the export in kebab case.
     */
    public function key(string $key): self
    {
        $this->key = $key;
        return $this;
    }

    /** Peer range checked by the host before rendering, e.g. `peer('react', '^19.2')`. */
    public function peer(string $name, string $range): self
    {
        $this->peer[$name] = $range;
        return $this;
    }

    /** @internal One entry of the `surfaces` part of the registration body. */
    public function toJson(string $slug): array
    {
        $s = ['slug' => $slug, 'kind' => $this->kind];
        if ($this->entry !== null) $s['entry'] = $this->entry;
        if ($this->layer !== null) $s['layer'] = $this->layer;
        if ($this->source !== null) $s['source'] = $this->source;
        if ($this->data !== null) $s['data'] = (object) $this->data;
        if ($this->exportName !== null) $s['export'] = $this->exportName;
        if ($this->tag !== null) $s['tag'] = $this->tag;
        if ($this->css !== null) $s['css'] = $this->css;
        if ($this->order !== null) $s['order'] = $this->order;
        if ($this->label !== null) $s['label'] = $this->label;
        if ($this->flag !== null) $s['flag'] = $this->flag;
        if ($this->key !== null) $s['key'] = $this->key;
        if ($this->peer !== []) $s['peer'] = $this->peer;
        return $s;
    }
}
