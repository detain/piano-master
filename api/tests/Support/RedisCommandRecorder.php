<?php

declare(strict_types=1);

namespace Tests\Support;

use Redis;

/**
 * RedisCommandRecorder — plan §13.5 enforcement primitive.
 *
 * A decorator around phpredis that records every command name + argument
 * vector before delegating to the wrapped client. The recorded trace is the
 * source of truth for the pinned Dragonfly command surface: any command the
 * app issues that is NOT already verified against Dragonfly shows up here
 * instead of silently growing the surface.
 *
 * Usage (integration suite, per plan §13.5):
 *
 *   $recorder = new RedisCommandRecorder(new Redis());
 *   $recorder->connect('127.0.0.1', 6379, 2);
 *   $recorder->get('kq:key');                       // recorded: GET kq:key
 *   $recorder->rawCommand('SET', 'kq:key', 'v');    // recorded: SET kq:key v
 *   $commands = $recorder->commands();              // ['GET', 'SET']
 *   $recorder->assertOnlyVerifiedCommands($verifiedList, 'Dragonfly surface grew');
 *
 * Command names are upper-cased at the boundary so callers compare against
 * the pinned list without case games. No per-command state lives here beyond
 * the recorded trace; the wrapped connection is the caller's.
 */
final class RedisCommandRecorder
{
    /** @var list<array{command: string, args: list<mixed>}> */
    private array $trace = [];

    public function __construct(private readonly Redis $redis)
    {
    }

    /**
     * Rehydrate a recorder from a previously captured trace (e.g. one flushed
     * to disk by the child-process seam; see RedisCommandSurfaceGuardTest).
     * The wrapped client is a disconnected dummy — assertions only read the
     * recorded trace.
     *
     * @param list<array{command: string, args: list<mixed>}> $trace
     */
    public static function fromTrace(array $trace): self
    {
        $recorder = new self(new Redis());
        $recorder->trace = $trace;

        return $recorder;
    }

    /**
     * @return list<array{command: string, args: list<mixed>}>
     */
    public function trace(): array
    {
        return $this->trace;
    }

    /**
     * Distinct command names seen so far, upper-cased, in first-seen order.
     *
     * @return list<string>
     */
    public function commands(): array
    {
        $names = [];
        foreach ($this->trace as $entry) {
            if (!in_array($entry['command'], $names, true)) {
                $names[] = $entry['command'];
            }
        }

        return $names;
    }

    public function reset(): void
    {
        $this->trace = [];
    }

    /**
     * Fail loudly when a recorded command is missing from the verified list
     * — the §13.5 "can't silently grow" guard.
     *
     * @param list<string> $verifiedCommandNames
     */
    public function assertOnlyVerifiedCommands(array $verifiedCommandNames, string $message = ''): void
    {
        $missing = array_values(array_diff($this->commands(), $verifiedCommandNames));

        if ($missing !== []) {
            $detail = $message !== '' ? $message : 'Redis command outside the verified Dragonfly surface';
            throw new \RuntimeException(
                $detail . ': ' . implode(', ', $missing)
                . ' (see api/tests/Support/dragonfly_command_surface.php)'
            );
        }
    }

    /**
     * phpredis passthrough — the one place a raw wire command can be issued,
     * recorded as the first argument (the command name) + remaining args so
     * the trace matches the surface file's [command, args] shape.
     *
     * @param mixed $command
     * @param mixed ...$args
     */
    public function rawCommand($command, ...$args): mixed
    {
        $this->record((string) $command, $args);

        return $this->redis->rawCommand($command, ...$args);
    }

    /**
     * @param list<mixed> $args
     */
    private function record(string $command, array $args): void
    {
        $this->trace[] = [
            'command' => strtoupper($command),
            'args' => $args,
        ];
    }

    /**
     * Generic phpredis passthrough: record, then delegate. Typed methods like
     * connect() keep their real signatures below; everything else funnels
     * through __call so no command can bypass recording.
     *
     * @param list<mixed> $args
     */
    public function __call(string $name, array $args): mixed
    {
        $this->record($name, $args);

        return $this->redis->{$name}(...$args);
    }

    // --- connection lifecycle passthroughs (also recorded) ---

    public function connect(string $host, int $port = 6379, float $timeout = 0.0): bool
    {
        $this->record('CONNECT', [$host, $port, $timeout]);

        return $this->redis->connect($host, $port, $timeout);
    }

    public function getLastError(): ?string
    {
        return $this->redis->getLastError();
    }

    public function clearLastError(): bool
    {
        return $this->redis->clearLastError();
    }
}