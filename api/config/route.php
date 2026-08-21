<?php
/**
 * HTTP routes — P0.6.1 skeleton surface (plan §20 P0.6.1, §13.2).
 *
 * Loaded once at bootstrap by Webman\Route::load (config/route.php is
 * excluded from the recursive Config::load). Register routes here only —
 * never inside request handling (§13.4.2).
 */

use App\Controller\AuthEchoController;
use App\Controller\CacheController;
use App\Controller\DbController;
use App\Controller\HealthController;
use App\Controller\IndexController;
use App\Middleware\DevAuthMiddleware;
use Webman\Route;

// Static JSON, no DB.
Route::get('/', [IndexController::class, 'index']);

// Liveness: process is up.
Route::get('/healthz', [HealthController::class, 'healthz']);

// Readiness: MySQL + Dragonfly ping.
Route::get('/readyz', [HealthController::class, 'readyz']);

// MySQL read.
Route::get('/db/version', [DbController::class, 'version']);

// Dragonfly read-through cache.
Route::get('/cache/now', [CacheController::class, 'now']);

// Authenticated write (P0.6.1 placeholder auth — P2.A2 replaces it).
Route::post('/auth/echo', [AuthEchoController::class, 'echo'])
    ->middleware([DevAuthMiddleware::class]);