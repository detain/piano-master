<?php
/**
 * Middleware configuration — placeholder.
 *
 * Planned global middleware (register at bootstrap only — anything registered
 * in a request loop leaks, §13.4.2):
 *  - RequestIdMiddleware  — sets the request-id, echoes X-Request-Id, seeds
 *                           structured log context (§13.6).
 *  - CorsMiddleware       — admin CMS and mobile clients.
 *  - ValidateRequest      — respect/validation (or illuminate/validation
 *                           standalone) invoked per-route (§13.4.4).
 *  - AuthMiddleware       — JWT bearer verify + entitlement token.
 *  - RateLimitMiddleware  — Dragonfly EVAL token bucket (§13.5).
 *
 * Per-route / per-controller middleware are keyed by path here; '@' applies
 * to every route.
 */

return [
    // '@' => [
    //     \App\Middleware\RequestIdMiddleware::class,
    // ],
    // 'auth' => [
    //     \App\Middleware\AuthMiddleware::class,
    // ],
];