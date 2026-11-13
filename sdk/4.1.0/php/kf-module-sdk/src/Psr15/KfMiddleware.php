<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/spring/KfSpringConfiguration.java (the PSR-15 adapter). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Psr15;

use Kf\Sdk\HookResult;
use Kf\Sdk\KfModule;
use Kf\Sdk\Log;
use Kf\Sdk\Rejected;
use Kf\Sdk\RequestContext;
use Kf\Sdk\Sapi;
use Psr\Http\Message\ResponseFactoryInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Message\StreamFactoryInterface;
use Psr\Http\Server\MiddlewareInterface;
use Psr\Http\Server\RequestHandlerInterface;

/*
 * Optional PSR-15 adapter (Slim, Mezzio, ...), the twin of the Java Spring adapter. The SDK does not require the PSR
 * packages: this class only exists when the application already has psr/http-server-middleware and psr/http-factory
 * installed. Without them the file declares nothing, so the zero-dependency SDK never fails to autoload.
 *
 *   $app->add(new KfMiddleware($kf, $responseFactory, $streamFactory));
 *
 * - POST {hookPath}: the platform callbacks (KfModule::handle);
 * - any path outside KfModule::isPublicPath(): the context, signed for this method and path, is verified and set as
 *   the request attribute RequestContext::ATTRIBUTE (`kf.context`); otherwise 401 RFC 9457 (`class: permanent`);
 * - public paths pass through untouched.
 *
 * The signatures are checked on the raw body (`(string) $request->getBody()`): do not put a body-parsing middleware that
 * consumes the stream in front of it.
 */
if (!interface_exists(MiddlewareInterface::class) || !interface_exists(ResponseFactoryInterface::class)) {
    return;
}

final class KfMiddleware implements MiddlewareInterface
{
    public function __construct(
        private readonly KfModule $kf,
        private readonly ResponseFactoryInterface $responses,
        private readonly StreamFactoryInterface $streams,
    ) {
    }

    public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
    {
        $path = rawurldecode($request->getUri()->getPath());
        $headers = [];
        foreach ($request->getHeaders() as $name => $values) $headers[strtolower((string) $name)] = $values[0] ?? '';
        $body = $request->getBody();
        if ($body->isSeekable()) $body->rewind();
        $raw = (string) $body;
        if ($body->isSeekable()) $body->rewind();

        if ($path === $this->kf->hookPath()) {
            if ($request->getMethod() !== 'POST') return $this->responses->createResponse(405);
            return $this->respond($this->kf->handle($raw, $headers['x-kf-timestamp'] ?? null, $headers['x-kf-signature'] ?? null,
                contextHeader: $headers['x-kf-context'] ?? null, proofHeader: $headers['x-kf-context-proof'] ?? null));
        }
        if ($this->kf->isPublicPath($path)) return $handler->handle($request);
        try {
            $ctx = Sapi::verify($this->kf, $request->getMethod(), $path, $headers, $raw);
        } catch (Rejected $rejected) {
            Log::warn('KF SDK: ' . $request->getMethod() . ' ' . $path . ' refused: ' . $rejected->problem()->detail);
            return $this->respond($rejected->toResult());
        }
        return $handler->handle($request->withAttribute(RequestContext::ATTRIBUTE, $ctx));
    }

    private function respond(HookResult $result): ResponseInterface
    {
        return $this->responses->createResponse($result->status)
            ->withHeader('Content-Type', $result->contentType)
            ->withBody($this->streams->createStream($result->body));
    }
}
