<?php
/**
 * Web entry point — nginx → public/index.php (plan §13.6).
 *
 * Webman runs the application once and serves requests in a loop inside
 * long-lived workers; this file is the web-handler entry (start.php is the
 * CLI/daemon bootstrap that starts those workers). The state-hygiene rules
 * from plan §13.4.2 apply to every request handler below this bootstrap:
 * never exit/die, no request data in statics, no superglobals, register
 * listeners at bootstrap only.
 *
 * TODO(favicon.ico): ship a brand icon (KeyQuest piano glyph) here before
 * launch — browsers 404 on it until then.
 */

require_once __DIR__ . '/../vendor/autoload.php';

support\App::run();