<?php

declare(strict_types=1);

use PHPUnit\Framework\TestCase;

/**
 * Smoke test proving the PHPUnit suite is wired.
 *
 * The real P0.6 Webman + Dragonfly suite replaces this placeholder.
 */
final class SmokeTest extends TestCase
{
    public function testThatTestSuiteIsWired(): void
    {
        self::assertTrue(true);
    }
}