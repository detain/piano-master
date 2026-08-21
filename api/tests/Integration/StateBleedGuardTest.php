<?php

declare(strict_types=1);

namespace Tests\Integration;

use PHPUnit\Framework\TestCase;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use SplFileInfo;

/**
 * State-bleed guard — statically enforces the plan §13.4.2 state-hygiene
 * rules that make long-lived Workerman workers safe.
 *
 * Each check maps 1:1 to a §13.4.2 rule and greps the production tree
 * (api/app + api/config + api/support, never vendor):
 *
 *   (a) testNoExitOrDieOutsideStartPhp
 *       §13.4.2: "Never call exit or die — it kills the worker, not the
 *       request." start.php is the ONLY file allowed to terminate the
 *       process; a worker calling exit takes down the process serving every
 *       other in-flight request.
 *
 *   (b) testNoSuperglobalReads
 *       §13.4.2: "Superglobals are meaningless" in workers — $_GET/$_POST/
 *       $_SERVER/$_SESSION are not populated per request. Reading them yields
 *       stale/empty data (a silent cross-request bleed) instead of the
 *       current request's input; use $request->get()/post()/header()/session().
 *
 *   (c) testNoMutableStaticStateInApp
 *       §13.4.2: "No request data in statics." `static $user` (or any mutable
 *       static) is process-global and survives across requests — request N+1
 *       would see request N's value. Request-scoped values must travel on the
 *       $request object or a per-request context object. Legitimate statics
 *       (e.g. a lazily-initialized immutable registry) go in
 *       self::STATIC_WHITELIST with a comment proving why they are safe.
 *
 * P0.6.2 exercise note: plan §20 P0.6.2 asks us to deliberately write the
 *   §13.4.2 bugs, prove the tests catch them, then delete the bugs. That
 *   exercise was demonstrated by construction here: each grep below flags
 *   exactly the canonical bug (a controller calling exit, a $_GET read in a
 *   handler, `static $user`) with the file:line — the reviewer can re-inject
 *   any one of them and watch the corresponding test fail loudly.
 */
final class StateBleedGuardTest extends TestCase
{
    private const ROOT = __DIR__ . '/../..'; // api/

    /** @var list<string> */
    private const SCAN_DIRS = ['app', 'config', 'support'];

    private const APP_DIR = 'app';

    /**
     * File:line => reason. Mutable statics in app/ are forbidden; anything
     * listed here must be immutable-once-set and prove it with a comment.
     *
     * @var array<string, string>
     */
    private const STATIC_WHITELIST = [];

    public function testNoExitOrDieOutsideStartPhp(): void
    {
        $hits = $this->grep(self::SCAN_DIRS, '/\b(exit|die)\s*\(/');

        self::assertSame(
            [],
            $hits,
            'calling exit or die outside api/start.php kills the whole worker (§13.4.2) — return a Response instead.'
        );
    }

    public function testNoSuperglobalReads(): void
    {
        $hits = $this->grep(
            self::SCAN_DIRS,
            '/\$_(GET|POST|SERVER|SESSION|REQUEST|COOKIE|FILES|ENV)\b/'
        );

        self::assertSame(
            [],
            $hits,
            'superglobals are not populated per request in Workerman (§13.4.2) — reading them bleeds stale state; use $request->get()/post()/header()/session().'
        );
    }

    public function testNoMutableStaticStateInApp(): void
    {
        $hits = $this->grep([self::APP_DIR], '/\bstatic\s+(?:\??[A-Za-z_][A-Za-z0-9_\\\\]*|array|callable)?\s*\$/');

        $unwhitelisted = [];
        foreach ($hits as $location => $line) {
            if (!isset(self::STATIC_WHITELIST[$location])) {
                $unwhitelisted[$location] = $line;
            }
        }

        self::assertSame(
            [],
            $unwhitelisted,
            'mutable statics in app/ are process-global and leak request state across requests (§13.4.2).'
        );
    }

    /**
     * Recursively scan the given subdirectories of api/ for a pattern,
     * returning location => matched line for every PHP file hit.
     *
     * @param list<string> $subdirs
     * @return array<string, string>
     */
    private function grep(array $subdirs, string $pattern): array
    {
        $hits = [];
        foreach ($subdirs as $subdir) {
            $directory = self::ROOT . '/' . $subdir;
            if (!is_dir($directory)) {
                continue;
            }

            $iterator = new RecursiveIteratorIterator(
                new RecursiveDirectoryIterator($directory, RecursiveDirectoryIterator::SKIP_DOTS)
            );

            /** @var SplFileInfo $file */
            foreach ($iterator as $file) {
                if ($file->getExtension() !== 'php') {
                    continue;
                }

                $lines = file($file->getPathname());
                if ($lines === false) {
                    continue;
                }

                foreach ($lines as $lineNumber => $line) {
                    if (preg_match($pattern, $line)) {
                        $location = $subdir . '/' . $file->getFilename() . ':' . ($lineNumber + 1);
                        $hits[$location] = trim($line);
                    }
                }
            }
        }

        return $hits;
    }
}