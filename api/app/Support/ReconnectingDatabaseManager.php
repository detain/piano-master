<?php

declare(strict_types=1);

namespace App\Support;

use Webman\Context;
use Webman\Database\DatabaseManager;

/**
 * ReconnectingDatabaseManager — reconnect-safe webman database manager.
 *
 * Root cause (soak Finding A, docs/runbooks/soak-results-2026-08-22.md §1f):
 * Illuminate's lost-connection retry re-runs the failed query on the SAME
 * Connection object it already holds, and `Connection::reconnect()` discards
 * whatever the reconnector returns. The vendored
 * `Webman\Database\DatabaseManager::connection()` caches the pooled
 * connection in the request Context and never populates the parent's
 * `$this->connections` cache, so Illuminate's `reconnect()` takes its
 * "create a fresh Connection" branch — the fresh object is thrown away, the
 * retry re-runs on the Context-cached dead PDO, and the request 500s until
 * the pool heartbeat (~50 s) replaces the connection.
 *
 * Fix: during reconnect, put the Context-cached Connection into the parent's
 * cache so Illuminate takes its `refreshPdoConnections()` branch — the one
 * that swaps a FRESH PDO onto the SAME Connection object the retry re-runs
 * on. Because the connection stays in the pool (its PDO is replaced in
 * place), the pool keeps a healthy connection for the next request instead
 * of handing the dead PDO out again.
 *
 * No per-request overhead: this path runs only when a query already failed
 * with a lost-connection error.
 *
 * Coroutine-mode caveat: this fix heals only the Context-cached connection in
 * place — in coroutine mode the pool can hold up to `max_connections` live
 * channel members and the others are handed out dead until the ~50 s heartbeat
 * replaces them. Deployment is coroutine-OFF per ADR-0002, so the single
 * `nonCoroutineConnection` (always the Context connection) is healed; future
 * coroutine enablement must revisit this.
 */
class ReconnectingDatabaseManager extends DatabaseManager
{
    public function reconnect($name = null)
    {
        $name = $name ?: $this->getDefaultConnection();

        $connection = Context::get("database.connections.$name");
        if ($connection) {
            // Illuminate's retry re-runs on this exact object, so it must be
            // the one Illuminate refreshes. Alias it into the parent's cache
            // so parent::reconnect() takes the refreshPdoConnections() path
            // (fresh PDO on the same Connection, which stays pooled) instead
            // of the "new Connection" path whose return value is discarded.
            $this->connections[$name] = $connection;
        }

        try {
            return parent::reconnect($name);
        } finally {
            // Keep the parent cache empty (the vendored manager caches in
            // Context, never here) so a later reconnect only refreshes the
            // Context connection while that connection is still current.
            unset($this->connections[$name]);
        }
    }
}
