<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/StorageClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's files through the platform: capability `storage` in `kf-module-conf.json`, protocol 1.2 §3.5
 *. Obtained from `KfModule::storage()`.
 *
 * ```php
 * $up = $kf->storage()->uploadUrl($ctx, 'image/png', strlen($bytes));
 * // PUT the bytes to $up->url with $up->headers (Content-Type, Content-Length) before $up->expiresAt, keep $up->key
 * $url = $kf->storage()->downloadUrl($ctx, $up->key)->url;
 * ```
 *
 * - `uploadUrl`: `POST …/storage/upload-url` `{contentType, size}` → a presigned `PUT` (10 minutes) under a key the
 *   platform chooses, `kf-modules/{slug}/{tenant}/{uuid}.{ext}`;
 * - `downloadUrl`: `POST …/storage/download-url` `{key}` → a presigned `GET` (60 minutes).
 *
 * Both calls carry the module token, the forwarded context and its proof (§15, like SecretsClient). Refused locally,
 * `permanent`, nothing sent: a missing content type, a `size` ≤ 0, an empty key, the context rules of ReadsClient,
 * and, when the manifest is known, a content type its `capabilities.storage` does not declare (or no `storage` at
 * all) and a `size` above its `maxBytes`. The platform's refusals are PlatformRefusals (`400` validation, `401`,
 * `403 capability-not-declared | module-not-live | forbidden`, `404`); a `5xx` is a KfException with its class
 * (`transient` without one). This client logs nothing: neither the token, nor the context, nor the proof. The module never lists or deletes objects: when a tenant is deleted, the platform purges
 * `kf-modules/{slug}/{tenant}/`.
 */
final class StorageClient
{
    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'storage');
    }

    /**
     * A presigned `PUT` for one object of `$contentType` and exactly `$size` bytes, in the tenant of `$context`.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
     */
    public function uploadUrl(RequestContext $context, string $contentType, int $size): UploadUrl
    {
        if (trim($contentType) === '') throw PlatformChannel::local('invalid upload: the contentType is required');
        if ($size <= 0) throw PlatformChannel::local("invalid upload: size must be greater than 0 (got $size)");
        $manifest = $this->channel->kf()->manifest();
        if ($manifest !== null) {
            $storage = $manifest->capabilities->storage[0] ?? null;
            if ($storage === null) {
                throw PlatformChannel::local('the manifest does not declare capabilities.storage');
            }
            if (!in_array($contentType, $storage->contentTypes, true)) {
                throw PlatformChannel::local("content type $contentType is not declared in capabilities.storage of the manifest ["
                    . implode(', ', $storage->contentTypes) . ']');
            }
            if ($storage->maxBytes !== null && $size > $storage->maxBytes) {
                throw PlatformChannel::local("invalid upload: size $size is above capabilities.storage maxBytes {$storage->maxBytes}");
            }
        }
        $forwarded = $this->channel->forwarded($context);

        $answer = $this->channel->send('POST', $forwarded, '/upload-url', '', ['contentType' => $contentType, 'size' => $size]);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        $body = $this->channel->answerObject($answer, 'an object {key, url, method, headers, expiresAt}');
        $key = $body['key'] ?? null;
        $url = $body['url'] ?? null;
        if (!is_string($key) || !is_string($url)) throw self::unreadable('the storage answer has no key or no url');
        $headers = [];
        $raw = $body['headers'] ?? null;
        if ($raw instanceof \stdClass) $raw = get_object_vars($raw);
        if (is_array($raw) && ($raw === [] || !array_is_list($raw))) {
            foreach ($raw as $k => $v) $headers[(string) $k] = is_scalar($v) ? (string) $v : (string) json_encode($v);
        }
        $method = is_string($body['method'] ?? null) ? $body['method'] : 'PUT';
        return new UploadUrl($key, $url, $method, $headers, self::text($body['expiresAt'] ?? null));
    }

    /**
     * A presigned `GET` for the object under `$key`, a key the platform gave this module.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
     */
    public function downloadUrl(RequestContext $context, string $key): DownloadUrl
    {
        if (trim($key) === '') throw PlatformChannel::local('invalid download: the key is required');
        $forwarded = $this->channel->forwarded($context);

        $answer = $this->channel->send('POST', $forwarded, '/download-url', '', ['key' => $key]);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        $body = $this->channel->answerObject($answer, 'an object {url, expiresAt}');
        $url = $body['url'] ?? null;
        if (!is_string($url)) throw self::unreadable('the storage answer has no url');
        return new DownloadUrl($url, self::text($body['expiresAt'] ?? null));
    }

    private static function unreadable(string $detail): KfException
    {
        return new KfException(new PlatformProblem(200, null, null, $detail, ErrorClass::PERMANENT, null));
    }

    private static function text(mixed $value): ?string
    {
        return $value === null ? null : (is_scalar($value) ? (string) $value : (string) json_encode($value));
    }
}
