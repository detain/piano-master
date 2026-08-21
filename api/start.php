#!/usr/bin/env php
<?php
/**
 * KeyQuest API — Webman bootstrap.
 *
 * This is the ONLY file where exit()/die() is allowed. It boots the long-lived
 * Workerman worker processes once; every request after that is served inside
 * the loop of an already-running worker. That is where the throughput comes
 * from, and it is also the entire source of new failure modes (plan §13.4.2).
 *
 * State-hygiene rules — violating these is the #1 Workerman bug class:
 *
 *  - Never exit()/die() inside request handling — return a Response object.
 *  - No request data in statics: no `static $user`, no container singleton
 *    holding $request, no $GLOBALS. Request-scoped values travel in the
 *    $request object or a per-request context object created by middleware
 *    and discarded at response time.
 *  - Superglobals are meaningless in workers ($_GET/$_POST/$_SERVER/$_SESSION
 *    are not populated per request). Use $request->get()/post()/header()/
 *    session().
 *  - set_error_handler, ini_set, date_default_timezone_set and locale changes
 *    are process-global and permanent — do them once at bootstrap, never
 *    inside a handler.
 *  - Anything registered in a loop leaks (Eloquent model event listeners,
 *    middleware arrays, spl_autoload_register) — register at bootstrap only;
 *    a listener re-registered per request fires N callbacks on the Nth
 *    request.
 *  - Unbounded in-process caches are leaks — per-process memoization must be
 *    an LRU with a hard entry cap.
 *  - Long-lived DB connections drop on idle: enable reconnect handling and
 *    keep MySQL wait_timeout above the idle window.
 *
 * @see plan_piano.md §13.4
 */

require_once __DIR__ . '/vendor/autoload.php';

// The real Webman helpers (request(), response(), config(), logger(), path()
// accessors) are autoloaded by the framework via vendor/autoload.php
// (vendor/workerman/webman-framework/composer.json `autoload.files`). The
// project support/helpers.php is a placeholder; loading it when present lets
// this scaffold parse before vendor/ exists and stays inert once it does.
if (file_exists(__DIR__ . '/support/helpers.php')) {
    require_once __DIR__ . '/support/helpers.php';
}

support\App::run();