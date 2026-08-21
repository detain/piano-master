<?php
/**
 * Application configuration.
 *
 * This is the FIRST config file the framework loads (support\App::run()
 * requires it directly before the recursive Config::load), so it must exist
 * and must not depend on other config files.
 *
 * Env overrides use getenv() ONLY — never the superglobal env/server arrays,
 * which are meaningless in long-lived Workerman workers (plan §13.4.2). Config is
 * parsed once at bootstrap, so an env change requires `php start.php reload`.
 */

return [
    // Service identity (plan §3.3).
    'name' => 'keyquest-api',

    // Spike mode: debug on. Production toggles this off (P2 hardening).
    'debug' => filter_var(getenv('APP_DEBUG') ?: 'true', FILTER_VALIDATE_BOOL),

    // Workers run forever; timezone is process-global, set once at bootstrap.
    'default_timezone' => getenv('APP_TIMEZONE') ?: 'UTC',

    // PHP error_reporting level, applied once at bootstrap (process-global).
    // E_DEPRECATED noise is muted for the spike; revisit at P2.
    'error_reporting' => E_ALL & ~E_DEPRECATED & ~E_USER_DEPRECATED,

    'controller_suffix' => 'Controller',
    'controller_reuse' => true,

    // P0.6.1 placeholder token for the authenticated-write spike route
    // (POST /auth/echo). NOT production auth — P2.A2 replaces it with
    // RS256 JWT + rotating refresh tokens (§3.3).
    'dev_api_token' => getenv('DEV_API_TOKEN') ?: 'dev-token',

    'request_class' => support\Request::class,
    'runtime_path' => base_path(false) . '/runtime',
    'public_path' => base_path(false) . '/public',
];