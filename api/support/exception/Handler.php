<?php

namespace support\exception;

use Throwable;
use Webman\Exception\ExceptionHandler;
use Webman\Http\Request;
use Webman\Http\Response;

/**
 * JSON exception handler — the API never returns HTML error pages or leaks
 * stack traces to clients (plan §13.6). Full context is logged server-side
 * by the parent's report() (with the request-id when the RequestIdMiddleware
 * already ran).
 */
class Handler extends ExceptionHandler
{
    public function render(Request $request, Throwable $exception): Response
    {
        $status = $this->resolveStatus($exception);

        $payload = [
            'error' => [
                // Always reflect the resolved HTTP status: an exception code
                // can be a non-HTTP value (PDOException SQLSTATE, domain code)
                // and must not leak into the body or HTTP layer.
                'code' => $status,
                'message' => $this->debug ? $exception->getMessage() : 'Internal server error',
            ],
        ];

        return new Response($status, ['Content-Type' => 'application/json'], json_encode($payload, JSON_UNESCAPED_SLASHES));
    }

    /**
     * Use the exception code when it is a real int in the 4xx/5xx range,
     * otherwise fall back to 500. The code is type-checked first: PDO/
     * Illuminate exceptions can surface a string SQLSTATE (e.g. "42S02"),
     * which PHP 8 would otherwise compare true against 400..599 and then
     * cast to 42 — an invalid HTTP status that Webman would write verbatim.
     * Fail loud: never mask a 5xx as 200.
     */
    private function resolveStatus(Throwable $exception): int
    {
        $code = $exception->getCode();

        if (is_int($code) && $code >= 400 && $code <= 599) {
            return $code;
        }

        return 500;
    }
}