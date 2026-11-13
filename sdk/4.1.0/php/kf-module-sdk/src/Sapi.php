<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfHttpServer.java + ro/kf/sdk/spring/KfSpringConfiguration.java (the idiomatic PHP adapter). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Serves the module from the PHP globals (`php -S` router, any front controller): the PHP twin of the Java adapters.
 *
 *   // public/index.php
 *   $kf = require __DIR__ . '/../kf.php';
 *   $path = Sapi::path();
 *   if ($path === $kf->hookPath()) { Sapi::handleGlobals($kf); return; }      // POST /_kf/hooks
 *   if (Sapi::serveUi(__DIR__ . '/../ui')) return;                             // GET|HEAD /ui/**
 *   if ($kf->isPublicPath($path)) { ...health... return; }
 *   $ctx = Sapi::verifyGlobals($kf);                                            // the module's own routes
 *   if ($ctx === null) return;                                                  // 401 already answered
 *   ... $ctx->tenant, $ctx->hasRight('api.v1.m.my-mod.notes.post'), Sapi::body() ...
 *
 * Every route outside `KfModule::isPublicPath()` must carry a context signed by the platform for its method and path;
 * otherwise it is answered 401 with an RFC 9457 body (`class: permanent`).
 */
final class Sapi
{
    private static ?string $body = null;

    private function __construct()
    {
    }

    /** `POST {hookPath}`: verifies, dispatches and answers the current request (405 on another method). */
    public static function handleGlobals(KfModule $kf): void
    {
        if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
            http_response_code(405);
            return;
        }
        $h = self::headers();
        $kf->handle(self::body(), $h['x-kf-timestamp'] ?? null, $h['x-kf-signature'] ?? null,
            contextHeader: $h['x-kf-context'] ?? null, proofHeader: $h['x-kf-context-proof'] ?? null)->emit();
    }

    /**
     * The module's own route of the current request: the verified context, or null after answering 401
     * (`application/problem+json`, `class: permanent`).
     */
    public static function verifyGlobals(KfModule $kf): ?RequestContext
    {
        try {
            return self::verify($kf, $_SERVER['REQUEST_METHOD'] ?? 'GET', self::path(), self::headers(), self::body());
        } catch (Rejected $rejected) {
            Log::warn('KF SDK: ' . ($_SERVER['REQUEST_METHOD'] ?? 'GET') . ' ' . ($_SERVER['REQUEST_URI'] ?? '') . ' refused: '
                . $rejected->problem()->detail);
            $rejected->toResult()->emit();
            return null;
        }
    }

    /**
     * One module route without the globals (the twin of `KfHttpServer.route`): `$handler(RequestContext, string $body):
     * HookResult` runs only for a context signed by the platform for this method and path; otherwise 401. A handler that
     * throws answers 500.
     *
     * @param array<string, string|list<string>> $headers any case
     */
    public static function route(KfModule $kf, string $method, string $path, array $headers, string $body, callable $handler): HookResult
    {
        try {
            return $handler(self::verify($kf, $method, $path, $headers, $body), $body);
        } catch (Rejected $rejected) {
            return $rejected->toResult();
        } catch (\Throwable $e) {
            Log::error("KF SDK: route $path failed: " . KfModule::describe($e));
            return new HookResult(500, Json::encode(['type' => 'https://kf.ro/problems/handler-failed', 'title' => 'Handler failed',
                'status' => 500, 'class' => 'transient']), HookResult::PROBLEM_CONTENT_TYPE);
        }
    }

    /**
     * @param array<string, string|list<string>> $headers any case
     * @throws Rejected
     */
    public static function verify(KfModule $kf, string $method, string $path, array $headers, string $body): RequestContext
    {
        $h = [];
        foreach ($headers as $name => $value) $h[strtolower((string) $name)] = is_array($value) ? ($value[0] ?? null) : $value;
        $ctx = $kf->verifyContext($h['x-kf-context'] ?? null, $h['x-kf-timestamp'] ?? null, $h['x-kf-signature'] ?? null, $body,
            $h['x-kf-context-proof'] ?? null);
        if ($ctx->method !== $method || $ctx->path !== $path) {
            throw new Rejected("the context was signed for {$ctx->method} {$ctx->path}, not for $method $path");
        }
        return $ctx;
    }

    /** The decoded path of the current request (after the platform strips `/m/{slug}`). */
    public static function path(): string
    {
        return rawurldecode((string) parse_url((string) ($_SERVER['REQUEST_URI'] ?? '/'), PHP_URL_PATH));
    }

    /** The raw body of the current request (read once: the signature needs the exact bytes). */
    public static function body(): string
    {
        return self::$body ??= (string) file_get_contents('php://input');
    }

    /** Request headers of the current request, lower-cased (getallheaders() or $_SERVER['HTTP_*']). */
    public static function headers(): array
    {
        if (function_exists('getallheaders')) {
            $all = getallheaders();
            if (is_array($all)) return array_change_key_case($all, CASE_LOWER);
        }
        $out = [];
        foreach ($_SERVER as $k => $v) {
            if (str_starts_with((string) $k, 'HTTP_')) $out[strtolower(str_replace('_', '-', substr((string) $k, 5)))] = $v;
        }
        if (isset($_SERVER['CONTENT_TYPE'])) $out['content-type'] = $_SERVER['CONTENT_TYPE'];
        return $out;
    }

    /**
     * Explicit MIME types: `.mjs` MUST be `text/javascript`, browsers refuse module scripts served as
     * application/octet-stream.
     */
    private const UI_TYPES = [
        'mjs' => 'text/javascript;charset=UTF-8',
        'js' => 'text/javascript;charset=UTF-8',
        'css' => 'text/css;charset=UTF-8',
        'map' => 'application/json',
        'json' => 'application/json',
        'svg' => 'image/svg+xml',
        'png' => 'image/png',
        'woff2' => 'font/woff2',
    ];

    /**
     * Serves `GET|HEAD {prefix}**` from `$dir` with explicit MIME types, `Cache-Control: no-cache` (new module versions
     * show up at once) and `X-Content-Type-Options: nosniff`. Returns false when the request is not for it (method or
     * prefix), true when it answered (200 or 404). The UI paths are public (`KfModule::isPublicPath`): the platform's
     * asset proxy fetches them without a context.
     */
    public static function serveUi(string $dir, ?string $path = null, string $prefix = '/ui/'): bool
    {
        $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
        $path ??= self::path();
        if (!str_starts_with($path, $prefix) || ($method !== 'GET' && $method !== 'HEAD')) return false;
        $rel = substr($path, strlen($prefix));
        $type = self::UI_TYPES[strtolower(pathinfo($rel, PATHINFO_EXTENSION))] ?? null;
        $root = realpath($dir);
        $file = $root === false || $rel === '' || $type === null || str_contains($rel, '..') || str_contains($rel, '\\') || str_starts_with($rel, '/')
            ? false : realpath($root . DIRECTORY_SEPARATOR . $rel);
        if ($file === false || !is_file($file) || !str_starts_with($file, $root . DIRECTORY_SEPARATOR)) {
            (new HookResult(404, '{"error":"not found"}'))->emit();
            return true;
        }
        http_response_code(200);
        header('Content-Type: ' . $type);
        header('Content-Length: ' . filesize($file));
        header('Cache-Control: no-cache');
        header('X-Content-Type-Options: nosniff');
        if ($method !== 'HEAD') readfile($file);
        return true;
    }
}
