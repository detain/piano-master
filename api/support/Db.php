<?php
/**
 * App-level `support\Db` — shadows the vendored
 * `vendor/webman/database/src/support/Db.php` (Composer PSR-4 resolves the
 * `support\` prefix to `api/support/` FIRST), so the whole app routes every
 * DB access through the reconnect-safe manager (soak Finding A fix).
 *
 * The vendored Initializer is still required: it populates the container's
 * database config AND flips its own `initialized` guard, so the vendored
 * `support\Model` never re-creates a second global capsule. We then install
 * THIS class (whose `setupManager()` builds the fixed manager) as the global
 * capsule, overwriting the vendor capsule the Initializer created.
 *
 * @see docs/runbooks/soak-results-2026-08-22.md §1f
 */

namespace support;

use App\Support\ReconnectingDatabaseManager;
use Illuminate\Container\Container as IlluminateContainer;
use Illuminate\Database\Connectors\ConnectionFactory;
use Webman\Database\Manager;

require_once __DIR__ . '/../vendor/webman/database/src/Initializer.php';

/**
 * Class Db
 * @package support
 * @method static array select(string $query, $bindings = [], $useReadPdo = true)
 * @method static int insert(string $query, $bindings = [])
 * @method static int update(string $query, $bindings = [])
 * @method static int delete(string $query, $bindings = [])
 * @method static bool statement(string $query, $bindings = [])
 * @method static mixed transaction(Closure $callback, $attempts = 1)
 * @method static void beginTransaction()
 * @method static void rollBack($toLevel = null)
 * @method static void commit()
 */
class Db extends Manager
{
    protected function setupManager()
    {
        $factory = new ConnectionFactory($this->container);
        $this->manager = new ReconnectingDatabaseManager($this->container, $factory);
    }
}

// Install THIS capsule (fixed DatabaseManager) as the global instance. Runs
// once when `support\Db` is first loaded — before any query, since a query
// needs the class loaded. Both `setAsGlobal()` and `bootEloquent()` are
// required: bootEloquent() re-points Eloquent's connection resolver at our
// manager (the vendor Initializer's bootEloquent pointed it at ITS capsule).
//
// Capsule's constructor resets the container's `database.default` to
// 'default' (setupDefaultConfiguration), so re-apply the configured default
// connection — the vendor Initializer's setDefaultConnection() ran before
// this constructor and would otherwise be lost.
$db = new Db(IlluminateContainer::getInstance());
if ($defaultConnection = config('database.default', false)) {
    $db->getDatabaseManager()->setDefaultConnection($defaultConnection);
}
$db->setAsGlobal();
$db->bootEloquent();
unset($db);