<?php

namespace App\Middleware;

use Webman\Http\Request;
use Webman\Http\Response;
use Webman\MiddlewareInterface;

/**
 * RequestIdMiddleware — plan §13.6 observability baseline.
 *
 *  (a) assigns a per-request id,
 *  (b) echoes it in the X-Request-Id response header,
 *  (c) logs a structured JSON line {ts, request_id, method, uri, status,
 *      duration_ms} to stdout → journald.
 *
 * State-hygiene (§13.4.2): the request id travels on the $request object
 * itself (Workerman's magic __set/__get), never in a static. This class holds
 * no per-request state.
 */
class RequestIdMiddleware implements MiddlewareInterface
{
    public function process(Request $request, callable $handler): Response
    {
        $requestId = $this->resolveRequestId($request);
        $request->requestId = $requestId;

        $startedAt = microtime(true);
        $response = $handler($request);
        $durationMs = (microtime(true) - $startedAt) * 1000;

        $response->withHeader('X-Request-Id', $requestId);
        $this->writeLogLine($requestId, $request, $response, $durationMs);

        return $response;
    }

    /**
     * Honor a well-formed inbound X-Request-Id for end-to-end tracing, else
     * mint a fresh one. Parsed at the boundary so the value is always a
     * safe [A-Za-z0-9._-] string of bounded length.
     */
    private function resolveRequestId(Request $request): string
    {
        $inbound = $request->header('x-request-id', '');

        if (is_string($inbound) && preg_match('/^[A-Za-z0-9._-]{1,64}$/', $inbound)) {
            return $inbound;
        }

        return bin2hex(random_bytes(8));
    }

    private function writeLogLine(string $requestId, Request $request, Response $response, float $durationMs): void
    {
        $line = json_encode(
            [
                'ts' => date('c'),
                'request_id' => $requestId,
                'method' => $request->method(),
                'uri' => $request->uri(),
                'status' => $response->getStatusCode(),
                'duration_ms' => round($durationMs, 2),
            ],
            JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
        );

        fwrite(STDOUT, $line . "\n");
    }
}