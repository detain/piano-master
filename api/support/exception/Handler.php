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
                'code' => $exception->getCode() ?: $status,
                'message' => $this->debug ? $exception->getMessage() : 'Internal server error',
            ],
        ];

        return new Response($status, ['Content-Type' => 'application/json'], json_encode($payload, JSON_UNESCAPED_SLASHES));
    }

    /**
     * Use the exception code when it is a plausible HTTP status (4xx/5xx),
     * otherwise fall back to 500. Fail loud: never mask a 5xx as 200.
     */
    private function resolveStatus(Throwable $exception): int
    {
        $code = $exception->getCode();

        if ($code >= 400 && $code <= 599) {
            return (int) $code;
        }

        return 500;
    }
}