<?php
/**
 * Middleware configuration.
 *
 * Middleware is registered ONCE at bootstrap (support/bootstrap.php →
 * Middleware::load). Anything registered per request leaks across requests in
 * a long-lived worker (§13.4.2).
 *
 * '@' applies to every route. The P0.6 placeholder auth middleware is
 * registered per-route in config/route.php (POST /auth/echo only) — P2.A2
 * replaces it with real JWT bearer auth.
 */

return [
    '@' => [
        \App\Middleware\RequestIdMiddleware::class,
    ],
];