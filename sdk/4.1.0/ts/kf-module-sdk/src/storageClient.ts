// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/StorageClient.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import type { KfModule } from './kfModule.js';
import { local, PlatformChannel } from './platformChannel.js';
import { PlatformProblem } from './platformProblem.js';
import type { RequestContext } from './requestContext.js';

/**
 * A presigned upload: send the bytes with `method` to `url`, with `headers`, before `expiresAt` (ISO-8601). The body
 * must be exactly `size` bytes. A browser drops `Content-Length` (a forbidden header) and sets it from the body itself.
 */
export interface UploadUrl {
  readonly key: string;
  readonly url: string;
  readonly method: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly expiresAt: string | null;
}

/** A presigned download: `GET url` before `expiresAt` (ISO-8601). */
export interface DownloadUrl {
  readonly url: string;
  readonly expiresAt: string | null;
}

/**
 * The module's files through the platform: capability `storage` in `kf-module-conf.json`, protocol 1.2 §3.5
 *. Obtained from `KfModule.storage()`.
 *
 * ```ts
 * const up = await kf.storage().uploadUrl(ctx, 'image/png', bytes.length);
 * // PUT the bytes to up.url with up.headers (Content-Type, Content-Length) before up.expiresAt, keep up.key
 * const url = (await kf.storage().downloadUrl(ctx, up.key)).url;
 * ```
 *
 * - `uploadUrl`: `POST …/storage/upload-url` `{contentType, size}` → a presigned `PUT` (10 minutes) under a key the
 *   platform chooses, `kf-modules/{slug}/{tenant}/{uuid}.{ext}`;
 * - `downloadUrl`: `POST …/storage/download-url` `{key}` → a presigned `GET` (60 minutes).
 *
 * Both calls carry the module token, the forwarded context and its proof (§15, like `SecretsClient`). Refused locally,
 * `permanent`, nothing sent: a missing content type, a `size` ≤ 0, an empty key, the context rules of `ReadsClient`,
 * and, when the manifest is known, a content type its `capabilities.storage` does not declare (or no `storage` at
 * all) and a `size` above its `maxBytes`. The platform's refusals are `PlatformRefusal`s (`400` validation, `401`,
 * `403 capability-not-declared | module-not-live | forbidden`, `404`); a `5xx` is a `KfException` with its class
 * (`transient` without one). This client logs nothing: neither the token, nor the context, nor the proof. The module never lists or deletes objects: when a tenant is deleted, the platform purges
 * `kf-modules/{slug}/{tenant}/`.
 */
export class StorageClient {
  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'storage');
  }

  /**
   * A presigned `PUT` for one object of `contentType` and exactly `size` bytes, in the tenant of `context`.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
   */
  async uploadUrl(context: RequestContext, contentType: string, size: number): Promise<UploadUrl> {
    if (contentType == null || contentType.trim() === '') throw local('invalid upload: the contentType is required');
    if (!(size > 0)) throw local(`invalid upload: size must be greater than 0 (got ${size})`);
    const manifest = this.channel.kf().manifest();
    if (manifest != null) {
      const storage = manifest.capabilities.storage[0] ?? null;
      if (storage == null) {
        throw local('the manifest does not declare capabilities.storage');
      }
      if (!storage.contentTypes.includes(contentType)) {
        throw local(`content type ${contentType} is not declared in capabilities.storage of the manifest `
          + `[${storage.contentTypes.join(', ')}]`);
      }
      if (storage.maxBytes != null && size > storage.maxBytes) {
        throw local(`invalid upload: size ${size} is above capabilities.storage maxBytes ${storage.maxBytes}`);
      }
    }
    const forwarded = this.channel.forwarded(context);

    const answer = await this.channel.send('POST', forwarded, '/upload-url', '', { contentType, size });
    if (answer.status !== 200) throw this.channel.failure(answer);
    const body = this.channel.answerObject(answer, 'an object {key, url, method, headers, expiresAt}');
    if (typeof body.key !== 'string' || typeof body.url !== 'string') {
      throw unreadable('the storage answer has no key or no url');
    }
    const headers: Record<string, string> = {};
    if (body.headers != null && typeof body.headers === 'object' && !Array.isArray(body.headers)) {
      for (const [k, v] of Object.entries(body.headers as Record<string, unknown>)) headers[k] = String(v);
    }
    const method = typeof body.method === 'string' ? body.method : 'PUT';
    return { key: body.key, url: body.url, method, headers, expiresAt: text(body.expiresAt) };
  }

  /**
   * A presigned `GET` for the object under `key`, a key the platform gave this module.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
   */
  async downloadUrl(context: RequestContext, key: string): Promise<DownloadUrl> {
    if (key == null || key.trim() === '') throw local('invalid download: the key is required');
    const forwarded = this.channel.forwarded(context);

    const answer = await this.channel.send('POST', forwarded, '/download-url', '', { key });
    if (answer.status !== 200) throw this.channel.failure(answer);
    const body = this.channel.answerObject(answer, 'an object {url, expiresAt}');
    if (typeof body.url !== 'string') throw unreadable('the storage answer has no url');
    return { url: body.url, expiresAt: text(body.expiresAt) };
  }
}

function unreadable(detail: string): KfException {
  return new KfException(new PlatformProblem(200, null, null, detail, ErrorClass.PERMANENT, null));
}

function text(value: unknown): string | null {
  return value == null ? null : String(value);
}
