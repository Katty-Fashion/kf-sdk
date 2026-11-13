// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Surface.java. Do not edit by hand: fix Java, then re-derive.

/** One entry of the `surfaces` part of the registration body. */
export interface SurfaceRegistration {
  slug: string;
  kind: 'react' | 'wc' | 'layer' | 'data';
  entry?: string;
  layer?: string;
  source?: string;
  data?: Record<string, unknown>;
  export?: string;
  tag?: string;
  css?: string;
  order?: number;
  label?: string;
  flag?: string;
  key?: string;
  peer?: Record<string, string>;
}

/**
 * A UI contribution to a frontend contact surface of the host (sdk-protocol.md §7).
 *
 *   kf.ui().contribute('models.form.edit.fields.after',
 *     Surface.react('/m/api-test/ui/surfaces.mjs', 'AuthorSeasonFields')
 *       .css('/m/api-test/ui/surfaces.css').order(10).labelKey('surface.author.label').peer('react', '^19.2'));
 *   kf.ui().contribute('models.form.edit.fields.after', Surface.wc('/m/my-mod/ui/surfaces.mjs', 'kf-my-card').flag('my-card'));
 *   kf.ui().contribute('models.detail.tabs',
 *     Surface.react('/m/api-test/ui/surfaces.mjs', 'AuthorSeasonTab').key('author').labelKey('surface.author.label'));
 *
 * `entry` and `css` must be served by the module itself, under `/m/{slug}/`; with the Node adapter, files of the
 * module's UI directory are served at `/ui/**` with the right MIME types (`serveUi`).
 */
export class Surface {
  private cssUrl: string | null = null;
  private orderValue: number | null = null;
  private label: string | null = null;
  private flagKey: string | null = null;
  private keyValue: string | null = null;
  private readonly peers = new Map<string, string>();

  /** The largest `data` of a kind R contribution, serialized as JSON (sdk-protocol.md §7.12). */
  static readonly MAX_DATA_BYTES = 64 * 1024;

  /** The layer kind of a B slot (§7.12). */
  static readonly LAYER_KIND = /^[a-z][a-z0-9-]{1,30}$/;

  private constructor(
    private readonly kind: 'react' | 'wc' | 'layer' | 'data',
    private readonly entry: string | null,
    private readonly exportName: string | null,
    private readonly tag: string | null,
    private readonly layerKind: string | null = null,
    private readonly source: string | null = null,
    private readonly data: Record<string, unknown> | null = null,
  ) {
    if (entry == null && (kind === 'react' || kind === 'wc')) throw new TypeError('entry');
  }

  /**
   * A data layer for a kind B slot of another module (`m.{owner}.{key}`, protocol 2.0, §7.12): no code — the layer kind
   * (one of the slot's `layerKinds`) and the path of a `GET` route this module declares; the host fetches it and hands
   * the data to the owner.
   *
   * @throws Error when the layer kind does not match `^[a-z][a-z0-9-]{1,30}$` or the source is not a path `/m/...`
   */
  static layer(layerKind: string, sourcePath: string): Surface {
    if (layerKind == null || !Surface.LAYER_KIND.test(layerKind)) {
      throw new Error(`layer kind must match ${Surface.LAYER_KIND.source} but was ${layerKind}`);
    }
    if (sourcePath == null || !sourcePath.startsWith('/m/') || sourcePath.includes('..') || sourcePath.includes('//')) {
      throw new Error(`source must be the path /m/{slug}/... of a GET route of this module but was ${sourcePath}`);
    }
    return new Surface('layer', null, null, null, layerKind, sourcePath, null);
  }

  /**
   * A catalog resource for a kind R slot of another module (§7.12): a JSON object of at most `MAX_DATA_BYTES`
   * bytes, no code; the owner validates it against the slot's `dataSchema`.
   *
   * @throws Error when `json` is not an object or is larger than `MAX_DATA_BYTES` bytes
   */
  static data(json: Record<string, unknown>): Surface {
    if (json == null || typeof json !== 'object' || Array.isArray(json)) throw new Error('data must be a JSON object');
    const size = Buffer.byteLength(JSON.stringify(json), 'utf8');
    if (size > Surface.MAX_DATA_BYTES) {
      throw new Error(`data must be at most ${Surface.MAX_DATA_BYTES} bytes but was ${size}`);
    }
    return new Surface('data', null, null, null, null, null, { ...json });
  }

  /** A React component: the named export `exportName` of the ES module `entry`, rendered in the host tree. */
  static react(entry: string, exportName: string): Surface {
    if (exportName == null) throw new TypeError('exportName');
    return new Surface('react', entry, exportName, null);
  }

  /** A Web Component: `entry` defines the custom element `tag` (prefix it, e.g. `kf-...`). */
  static wc(entry: string, tag: string): Surface {
    if (tag == null) throw new TypeError('tag');
    return new Surface('wc', entry, null, tag);
  }

  /** Stylesheet injected once in the host page (React contributions style the light DOM). */
  css(css: string): this {
    this.cssUrl = css;
    return this;
  }

  /** Lower renders first (default 100). */
  order(order: number): this {
    this.orderValue = order;
    return this;
  }

  /**
   * Translation KEY of the text shown discreetly next to the contribution (defaults to the module name). Under
   * manifest `schemaVersion` 2 it must be a key of `i18n.en` (checked locally by
   * `ManifestCheck`); the platform serves the text on the user's language.
   */
  labelKey(labelKey: string): this {
    this.label = labelKey;
    return this;
  }

  /**
   * Gates the contribution with one of the module's feature flags (declared in `kf-module-conf.json`): the host only
   * renders it while the flag is effectively on. An undeclared key is accepted with a warning and counts as off.
   */
  flag(flagKey: string): this {
    if (flagKey == null) throw new TypeError('flagKey');
    this.flagKey = flagKey;
    return this;
  }

  /**
   * Stable id of the contribution within its surface (`^[a-z][a-z0-9-]{0,40}$`); tabs use it in the URL
   * (`?tab={module}.{key}`). Defaults to the tag without `kf-` or the export in kebab case.
   */
  key(key: string): this {
    if (key == null) throw new TypeError('key');
    this.keyValue = key;
    return this;
  }

  /** Peer range checked by the host before rendering, e.g. `peer('react', '^19.2')`. */
  peer(name: string, range: string): this {
    if (name == null || range == null) throw new TypeError('peer');
    this.peers.set(name, range);
    return this;
  }

  /** @internal One entry of the `surfaces` part of the registration body. */
  toJson(slug: string): SurfaceRegistration {
    const s: SurfaceRegistration = { slug, kind: this.kind };
    if (this.entry != null) s.entry = this.entry;
    if (this.layerKind != null) s.layer = this.layerKind;
    if (this.source != null) s.source = this.source;
    if (this.data != null) s.data = { ...this.data };
    if (this.exportName != null) s.export = this.exportName;
    if (this.tag != null) s.tag = this.tag;
    if (this.cssUrl != null) s.css = this.cssUrl;
    if (this.orderValue != null) s.order = this.orderValue;
    if (this.label != null) s.label = this.label;
    if (this.flagKey != null) s.flag = this.flagKey;
    if (this.keyValue != null) s.key = this.keyValue;
    if (this.peers.size > 0) s.peer = Object.fromEntries(this.peers);
    return s;
  }
}
