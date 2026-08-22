<?php

declare(strict_types=1);

namespace Tests\Integration;

use App\Support\ReconnectingDatabaseManager;
use GuzzleHttp\Client;
use ArrayAccess;
use Illuminate\Container\Container;
use Illuminate\Database\Connectors\ConnectionFactory;
use Illuminate\Database\DatabaseManager as IlluminateDatabaseManager;
use PDO;
use PHPUnit\Framework\TestCase;
use Throwable;

/**
 * MySQL lost-connection regression (soak Finding A, docs/runbooks/soak-results-2026-08-22.md).
 *
 * Plan §13.4.2 / P0.6.3 expectation: after a MySQL connection drop the FIRST
 * request must reconnect cleanly — no "MySQL server has gone away" on the
 * request that hits the dead connection.
 *
 * Test A boots a real Webman child (WebmanTestHarness), kills the worker's
 * live MySQL connection server-side (information_schema.processlist + KILL),
 * then immediately GETs /db/version: it must be 200 with a parseable body,
 * and the FOLLOWING request must also be 200 (the fresh connection sticks).
 *
 * Test B is a unit-ish probe of the retry path: it builds the app's database
 * manager (the reconnect-safe subclass when the fix is present, else the
 * vendored webman manager) against a real MySQL, kills the connection PDO
 * server-side from a second PDO, and asserts the retry evicts the dead
 * connection and reconnects. On pre-fix code the vendored manager returns the
 * Context-cached dead connection to Illuminate's lost-connection retry and
 * this test FAILS with a QueryException.
 */
final class DbReconnectTest extends TestCase
{
    private const SKIP_MESSAGE = 'MySQL/Dragonfly not reachable — start services with `docker compose up -d` in api/ (see api/docker/README.md).';

    public static function setUpBeforeClass(): void
    {
        if (!WebmanTestHarness::dependenciesReachable()) {
            self::markTestSkipped(self::SKIP_MESSAGE);
        }

        WebmanTestHarness::boot();
    }

    public static function tearDownAfterClass(): void
    {
        WebmanTestHarness::shutdown();
    }

    /**
     * Test A — the request that hits the dead connection must reconnect.
     */
    public function testFirstRequestAfterServerSideKillReconnectsCleanly(): void
    {
        // Warm the worker's connection so it exists in processlist, and prove
        // the endpoint was healthy a moment before the kill.
        $before = $this->client()->get('/db/version');
        self::assertSame(200, $before->getStatusCode());

        $killed = $this->killWorkerMysqlConnections();
        self::assertGreaterThan(0, $killed, 'expected the worker MySQL connection in processlist');

        // The FIRST request after the kill must reconnect cleanly (200), not
        // surface "MySQL server has gone away" (500).
        $first = $this->client()->get('/db/version');
        self::assertSame(200, $first->getStatusCode(), 'first request after MySQL kill must reconnect, not 500');
        $data = json_decode((string) $first->getBody(), true, 512, JSON_THROW_ON_ERROR);
        self::assertNotEmpty($data['db_version']);

        // The SECOND request must also be 200 — the fresh connection persists.
        $second = $this->client()->get('/db/version');
        self::assertSame(200, $second->getStatusCode(), 'second request after MySQL kill must stay healthy');
    }

    /**
     * Test B — unit-ish probe of the evict + reconnect retry path.
     */
    public function testRetryPathEvictsDeadConnectionAndReconnects(): void
    {
        $config = require dirname(__DIR__, 2) . '/config/database.php';

        $container = new Container();
        // Illuminate's DatabaseManager reads $app['config']['database.connections']
        // (ArrayAccess) AND $app['config']->get('database.dbal.types', []) in
        // registerConfiguredDoctrineTypes(), so bind a minimal repository.
        $container['config'] = new class([
            'database.connections' => $config['connections'],
            'database.default' => $config['default'],
        ]) implements ArrayAccess {
            public function __construct(private array $data)
            {
            }

            public function get(string $key, mixed $default = null): mixed
            {
                return $this->data[$key] ?? $default;
            }

            public function offsetExists(mixed $offset): bool
            {
                return isset($this->data[$offset]);
            }

            public function offsetGet(mixed $offset): mixed
            {
                return $this->data[$offset] ?? null;
            }

            public function offsetSet(mixed $offset, mixed $value): void
            {
                $this->data[$offset] = $value;
            }

            public function offsetUnset(mixed $offset): void
            {
                unset($this->data[$offset]);
            }
        };

        $factory = new ConnectionFactory($container);

        // Use the reconnect-safe manager when present (post-fix); on pre-fix
        // code this falls back to the vendored webman manager, which is
        // exactly the broken path the soak isolated.
        $managerClass = class_exists(ReconnectingDatabaseManager::class)
            ? ReconnectingDatabaseManager::class
            : \Webman\Database\DatabaseManager::class;

        /** @var IlluminateDatabaseManager $manager */
        $manager = new $managerClass($container, $factory);
        $manager->setDefaultConnection('mysql');

        $connection = $manager->connection('mysql');
        $connection->select('SELECT 1');

        $pdo = $connection->getPdo();
        self::assertInstanceOf(PDO::class, $pdo);
        $connectionId = $pdo->query('SELECT CONNECTION_ID()')->fetchColumn();

        $killer = $this->mysqlAdminConnection();
        $killer->exec('KILL ' . (int) $connectionId);

        // KILL sets the kill flag asynchronously from the victim's
        // perspective: poll the processlist until the row disappears (<= 2 s)
        // so the retry really hits a dead connection — a timing race would
        // let the first SELECT succeed and pass the test vacuously.
        $deadline = microtime(true) + 2.0;
        do {
            $stillAlive = $killer->query(
                'SELECT 1 FROM information_schema.processlist WHERE ID = ' . (int) $connectionId
            )->fetchColumn();
            if ($stillAlive === false) {
                break;
            }
            usleep(20_000);
        } while (microtime(true) < $deadline);
        self::assertFalse((bool) $stillAlive, 'killed connection ' . $connectionId . ' must leave the processlist');

        // The retry must evict the dead connection and run on a fresh one —
        // no QueryException may surface.
        try {
            $rows = $connection->select('SELECT 1');
        } catch (Throwable $throwable) {
            self::fail(
                'lost-connection retry must reconnect instead of throwing: ' . $throwable->getMessage()
            );
        }
        self::assertNotEmpty($rows);

        // Prove a reconnect actually happened: the retried PDO's connection
        // id must differ from the killed one (MySQL never reuses ids within
        // a server lifetime).
        $freshConnectionId = $connection->getPdo()->query('SELECT CONNECTION_ID()')->fetchColumn();
        self::assertNotSame($connectionId, $freshConnectionId, 'retry must reconnect to a fresh connection');
    }

    private function client(): Client
    {
        return new Client([
            'base_uri' => WebmanTestHarness::baseUrl(),
            'timeout' => 5,
            'connect_timeout' => 2,
            'http_errors' => false,
        ]);
    }

    /**
     * Kill every MySQL connection to the app database owned by the app user
     * (the Webman worker's pooled connection). Our own admin PDO connects
     * without a default database, so its processlist row (DB = NULL) is not
     * matched and we never kill ourselves.
     *
     * @return int number of connections killed
     */
    private function killWorkerMysqlConnections(): int
    {
        $dbName = getenv('DB_DATABASE') ?: 'keyquest';
        $dbUser = getenv('DB_USERNAME') ?: 'root';

        $pdo = $this->mysqlAdminConnection();
        $stmt = $pdo->query(
            'SELECT ID FROM information_schema.processlist'
            . ' WHERE DB = ' . $pdo->quote($dbName)
            . ' AND USER = ' . $pdo->quote($dbUser)
        );
        $ids = $stmt->fetchAll(PDO::FETCH_COLUMN);

        foreach ($ids as $id) {
            $pdo->exec('KILL ' . (int) $id);
        }

        return count($ids);
    }

    private function mysqlAdminConnection(): PDO
    {
        $dbHost = getenv('DB_HOST') ?: '127.0.0.1';
        $dbPort = getenv('DB_PORT') ?: '3306';
        $dbUser = getenv('DB_USERNAME') ?: 'root';
        $dbPass = getenv('DB_PASSWORD') ?: '';

        $pdo = new PDO(
            "mysql:host=$dbHost;port=$dbPort",
            $dbUser,
            $dbPass,
            [PDO::ATTR_TIMEOUT => 2]
        );
        $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

        return $pdo;
    }
}
