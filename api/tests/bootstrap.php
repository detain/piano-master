<?php

declare(strict_types=1);

/**
 * PHPUnit bootstrap for the api/ workspace.
 *
 * Loads Composer (framework + phpredis + Guzzle + PHPUnit) and registers a
 * tiny PSR-4-style autoloader for the Tests\ namespace (tests/), so the
 * shared helpers under tests/Support and tests/Integration are available
 * without require_once chains. This is deliberately NOT a framework test
 * harness — the integration suites boot real Webman child processes
 * themselves (WebmanTestHarness, plan §13.7).
 */

require __DIR__ . '/../vendor/autoload.php';

spl_autoload_register(static function (string $class): void {
    if (!str_starts_with($class, 'Tests\\')) {
        return;
    }

    $relative = str_replace('\\', '/', substr($class, strlen('Tests\\')));
    $path = __DIR__ . '/' . $relative . '.php';
    if (is_file($path)) {
        require $path;
    }
});