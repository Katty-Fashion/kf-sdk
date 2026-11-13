<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfModule.java (the java.net.http client of the registration). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * Minimal HTTP client on PHP streams (`file_get_contents` + stream context): no ext-curl, no library. The http://
 * wrapper is built into every PHP and `allow_url_fopen` is on by default.
 *
 * @internal
 */
final class Http
{
    private function __construct()
    {
    }

    /**
     * @param array<string, string> $headers
     * @return array{0: int, 1: string, 2: array<string, string>} status, body, response headers (lower-case names)
     * @throws \RuntimeException on connection errors and timeouts (the message names the cause, never the request headers)
     */
    public static function request(string $method, string $url, array $headers = [], ?string $body = null, float $timeout = 10.0): array
    {
        $lines = [];
        foreach ($headers as $name => $value) $lines[] = $name . ': ' . $value;
        $context = stream_context_create(['http' => [
            'method' => $method,
            'header' => implode("\r\n", $lines),
            'content' => $body ?? '',
            'timeout' => $timeout,
            'ignore_errors' => true,     // return the body of 4xx / 5xx answers too
            'follow_location' => 0,
            'protocol_version' => 1.1,
        ]]);
        $error = null;
        set_error_handler(static function (int $no, string $msg) use (&$error): bool {
            $error = $msg;
            return true;
        });
        try {
            $response = file_get_contents($url, false, $context);
        } finally {
            restore_error_handler();
        }
        $responseHeaders = function_exists('http_get_last_response_headers')
            ? (http_get_last_response_headers() ?? [])
            : ($http_response_header ?? []);
        if ($response === false || $responseHeaders === []) {
            throw new \RuntimeException($error ?? "request to $url failed");
        }
        $status = 0;
        $out = [];
        foreach ($responseHeaders as $line) {   // the last status line wins (100-continue, ...)
            if (preg_match('#^HTTP/\S+\s+(\d{3})#', $line, $m) === 1) {
                $status = (int) $m[1];
                $out = [];
            } elseif (str_contains($line, ':')) {
                [$name, $value] = explode(':', $line, 2);
                $out[strtolower(trim($name))] = trim($value);
            }
        }
        return [$status, $response, $out];
    }
}
