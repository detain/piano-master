<?php
/**
 * Static file serving configuration.
 *
 * Webman serves files from public/ only when 'static.enable' is true
 * (Webman\App::findFile). The API is JSON-only for the spike; nginx will
 * serve any static assets in production (§13.6).
 */

return [
    'enable' => false,
    'middleware' => [],
];