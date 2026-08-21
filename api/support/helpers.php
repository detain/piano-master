<?php
/**
 * Webman support helpers (placeholder).
 *
 * This file defines nothing. The real helpers (request(), response(), config(),
 * logger(), path()/base_path()/runtime_path()/config_path() accessors) are
 * autoloaded by the framework itself: `vendor/workerman/webman-framework/composer.json`
 * registers `autoload.files` -> `./src/support/helpers.php`, which Composer loads
 * with vendor/autoload.php.
 *
 * start.php guards its require with file_exists() so the scaffold parses
 * before vendor/ exists; once vendor/ is installed, the framework's helpers
 * are already loaded and this placeholder is inert.
 */