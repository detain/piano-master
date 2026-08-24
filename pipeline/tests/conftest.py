"""Shared pytest setup for the pipeline tests.

Every test runs against an isolated content store/builds/catalog under the
session tmp dir — the real ``content/store`` and ``content/builds`` are never
touched (plan §8.2 discipline). Individual tests may monkeypatch the env vars
to build the same song twice into separate dirs (determinism double-build).
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest


@pytest.fixture(scope="session", autouse=True)
def _isolated_content(tmp_path_factory: pytest.TempPathFactory):
    base = tmp_path_factory.mktemp("keyquest-content")
    os.environ["KEYQUEST_STORE_DIR"] = str(base / "store")
    os.environ["KEYQUEST_BUILDS_DIR"] = str(base / "builds")
    os.environ["KEYQUEST_CATALOG_DIR"] = str(base / "catalog")
    yield base


@pytest.fixture()
def fixture_root() -> Path:
    from pathlib import Path

    return Path(__file__).resolve().parent / "fixtures"


@pytest.fixture()
def bad_root() -> Path:
    from pathlib import Path

    return Path(__file__).resolve().parent / "bad"