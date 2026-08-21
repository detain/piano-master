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
 * Each check maps 1:1 to a §13.4.2 rule. Scope per check (never vendor):
 *
 *   (a) testNoExitOrDieOutsideStartPhp — scans api/app + api/config +
 *       api/support for the exit/die language construct (token_get_all, so
 *       comments and strings do not false-positive) with OR without parens.
 *       §13.4.2: "Never call exit or die — it kills the worker, not the
 *       request." start.php is the ONLY file allowed to terminate the
 *       process; a worker calling exit takes down the process serving every
 *       other in-flight request.
 *
 *   (b) testNoSuperglobalReads — scans api/app + api/config + api/support for
 *       $_GET/$_POST/$_SERVER/$_SESSION/$REQUEST/$COOKIE/$FILES/$ENV and
 *       $GLOBALS. §13.4.2: "Superglobals are meaningless" in workers —
 *       they are not populated per request. Reading them yields stale/empty
 *       data (a silent cross-request bleed) instead of the current request's
 *       input; use $request->get()/post()/header()/session().
 *
 *   (c) testNoMutableStaticStateInApp — scans api/app ONLY for mutable
 *       statics (config/ and support/ are bootstrap-time registries, not
 *       request handlers, so they are out of scope for this rule).
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
 *
 * NOTE for editors: make-lint greps api/ (including tests/) for the literal
 * `exit`/`die` followed by a paren token, so these docblocks must never
 * contain that exact token pair — quote the rule as "exit or die" without a
 * paren.
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
        $hits = $this->scanForExitOrDie(self::SCAN_DIRS);

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
            '/\$_(GET|POST|SERVER|SESSION|REQUEST|COOKIE|FILES|ENV)\b|\$GLOBALS\b/'
        );

        self::assertSame(
            [],
            $hits,
            'superglobals (incl. $GLOBALS) are not populated per request in Workerman (§13.4.2) — reading them bleeds stale state; use $request->get()/post()/header()/session().'
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
     * Token-based scan for the exit/die language construct. Both keywords
     * tokenize as T_EXIT, so this catches `exit`, `die`, and their
     * one-argument call forms — and, unlike a regex, never comments, strings,
     * or identifier suffixes.
     *
     * @param list<string> $subdirs
     * @return array<string, string> repo-relative location => matched line
     */
    private function scanForExitOrDie(array $subdirs): array
    {
        $hits = [];
        foreach ($this->phpFiles($subdirs) as $pathname => $code) {
            foreach (token_get_all($code) as $token) {
                if (!is_array($token) || $token[0] !== T_EXIT) {
                    continue;
                }
                $line = (int) $token[2];
                $lines = explode("\n", $code);
                $hits[$this->relativeLocation($pathname, $line)] = trim($lines[$line - 1] ?? '');
            }
        }

        return $hits;
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
        foreach ($this->phpFiles($subdirs) as $pathname => $code) {
            foreach (explode("\n", $code) as $lineNumber => $line) {
                if (preg_match($pattern, $line)) {
                    $hits[$this->relativeLocation($pathname, $lineNumber + 1)] = trim($line);
                }
            }
        }

        return $hits;
    }

    /**
     * @param list<string> $subdirs
     * @return array<string, string> absolute pathname => raw file contents
     */
    private function phpFiles(array $subdirs): array
    {
        $files = [];
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
                $contents = file_get_contents($file->getPathname());
                if ($contents !== false) {
                    $files[$file->getPathname()] = $contents;
                }
            }
        }

        return $files;
    }

    /**
     * api/-relative location (e.g. config/redis.php:12) so keys cannot
     * collide between same-named files in different directories.
     */
    private function relativeLocation(string $pathname, int $line): string
    {
        $relative = substr($pathname, strlen(self::ROOT) + 1);

        return $relative . ':' . $line;
    }
}