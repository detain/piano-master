<?php
/**
 * Bootstrap classes — run once per worker at start (support/bootstrap.php).
 *
 * Each entry must implement Webman\Bootstrap::start($worker). The
 * webman/database plugin initializes lazily on first use (its Initializer is
 * require_once'd by support\Db / support\Model), so no entry is needed here
 * for the spike. Register long-lived listeners/daemons here ONLY — never in
 * request handling (§13.4.2).
 *
 * Test seam: support\RedisRecordBootstrap is inert unless the integration
 * suite sets KQ_TEST_RECORD_REDIS=1 (RedisCommandSurfaceGuardTest); in
 * production this entry is a no-op.
 */

return [
    \support\RedisRecordBootstrap::class,
];