<?php

declare(strict_types=1);

namespace Tests\SongPack;

use Opis\JsonSchema\Errors\ErrorFormatter;
use Opis\JsonSchema\Schema;
use Opis\JsonSchema\ValidationResult;
use Opis\JsonSchema\Validator;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

use function basename;
use function file_get_contents;
use function glob;
use function is_dir;
use function json_decode;
use function json_encode;

use const JSON_THROW_ON_ERROR;
use const JSON_UNESCAPED_SLASHES;

/**
 * SongPackSchemaTest — the PHP consumer of the canonical SongPack v1 schema
 * (plan §8.1.10: one schema, three consumers — Python pipeline, PHP API,
 * Kotlin tests; drift is impossible by construction because this test reads
 * the canonical file from content/schema, never a committed copy).
 *
 * Pure unit test: no framework boot, no DB. Validates every golden fixture's
 * four JSON documents against the schema root (a oneOf dispatcher over
 * manifest / notes / chunks / skills), plus the negative `seconds` case that
 * every consumer must reject (plan §8.1.1).
 *
 * The validator and the schema are deliberately created together: opis
 * resolves `$ref`s through the validator's resolver at validation time, so
 * validating with a different Validator than the one that loaded the schema
 * would fail to resolve `#/$defs/...` references.
 */
final class SongPackSchemaTest extends TestCase
{
    /** Repo root — api/tests/SongPack/ -> ../../.. */
    private const REPO_ROOT = __DIR__ . '/../../..';

    private const SCHEMA_PATH = self::REPO_ROOT . '/content/schema/songpack-v1.json';

    private const FIXTURES_ROOT = self::REPO_ROOT . '/content/fixtures/songpack-v1';

    private const PACK_FILES = ['manifest.json', 'notes.json', 'chunks.json', 'skills.json'];

    /**
     * @return list<array{string}>
     */
    public static function fixtureProvider(): array
    {
        $fixtures = [];
        foreach (glob(self::FIXTURES_ROOT . '/*') ?: [] as $dir) {
            if (is_dir($dir)) {
                $fixtures[] = [basename($dir)];
            }
        }
        return $fixtures;
    }

    #[DataProvider('fixtureProvider')]
    public function testGoldenFixturesValidate(string $fixtureId): void
    {
        [$validator, $schema] = $this->validatorAndSchema();
        foreach (self::PACK_FILES as $file) {
            $data = $this->loadFixtureJson($fixtureId, $file);
            $result = $validator->validate($data, $schema);
            self::assertTrue(
                $result->isValid(),
                "{$fixtureId}/{$file} must validate against the canonical schema: "
                . $this->formatError($result)
            );
        }
    }

    public function testSecondsKeyInNoteIsRejected(): void
    {
        [$validator, $schema] = $this->validatorAndSchema();
        $notes = $this->loadFixtureJson('pickup_anacrusis', 'notes.json');
        $notes->levels->{'1'}[0]->seconds = 1.0;
        $result = $validator->validate($notes, $schema);
        self::assertFalse(
            $result->isValid(),
            'a note containing a seconds key must be rejected (plan §8.1.1 — beats are '
            . 'the unit of musical time; seconds appear nowhere in note data)'
        );
    }

    public function testNonSongpackFormatIsRejected(): void
    {
        [$validator, $schema] = $this->validatorAndSchema();
        $manifest = $this->loadFixtureJson('pickup_anacrusis', 'manifest.json');
        $manifest->format = 'songpack/v2';
        $result = $validator->validate($manifest, $schema);
        self::assertFalse(
            $result->isValid(),
            'a manifest whose format is not "songpack/v1" must be refused (§8.1.3)'
        );
    }

    /**
     * @return array{Validator, Schema}
     */
    private function validatorAndSchema(): array
    {
        self::assertFileExists(self::SCHEMA_PATH, 'canonical schema missing — content/schema/songpack-v1.json');
        $validator = new Validator();
        $validator->resolver()->registerFile(
            'https://keyquest.dev/schema/songpack-v1.json',
            self::SCHEMA_PATH
        );
        $schema = $validator->loader()->loadObjectSchema(
            json_decode((string) file_get_contents(self::SCHEMA_PATH), false, 512, JSON_THROW_ON_ERROR)
        );
        return [$validator, $schema];
    }

    private function loadFixtureJson(string $fixtureId, string $file): object|array
    {
        $path = self::FIXTURES_ROOT . '/' . $fixtureId . '/' . $file;
        self::assertFileExists($path, "missing fixture document {$fixtureId}/{$file}");
        return json_decode((string) file_get_contents($path), false, 512, JSON_THROW_ON_ERROR);
    }

    private function formatError(ValidationResult $result): string
    {
        if ($result->isValid()) {
            return 'valid';
        }
        $formatter = new ErrorFormatter();
        return (string) json_encode($formatter->format($result->error()), JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    }
}