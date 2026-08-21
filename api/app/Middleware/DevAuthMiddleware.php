<?php

namespace App\Middleware;

use Webman\Http\Request;
use Webman\Http\Response;
use Webman\MiddlewareInterface;

/**
 * DevAuthMiddleware — P0.6.1 PLACEHOLDER auth, route-scoped to POST /auth/echo.
 *
 * Accepts only `Authorization: Bearer <token>` where <token> equals
 * config('app.dev_api_token') (env DEV_API_TOKEN, default 'dev-token').
 * Missing/wrong token → 401. Constant-time comparison via hash_equals.
 *
 * NOT production auth. P2.A2 replaces this with RS256 JWT access tokens
 * (lcobucci/jwt) + rotating refresh tokens (§3.3, §13.2).
 */
class DevAuthMiddleware implements MiddlewareInterface
{
    private const BEARER_PREFIX = 'Bearer ';

    public function process(Request $request, callable $handler): Response
    {
        $authorization = $request->header('authorization', '');

        if (!is_string($authorization) || !str_starts_with($authorization, self::BEARER_PREFIX)) {
            return $this->unauthorized();
        }

        $token = substr($authorization, strlen(self::BEARER_PREFIX));
        $expected = (string) config('app.dev_api_token');

        if ($expected === '' || !hash_equals($expected, $token)) {
            return $this->unauthorized();
        }

        return $handler($request);
    }

    private function unauthorized(): Response
    {
        return json(['error' => 'unauthorized'])->withStatus(401);
    }
}