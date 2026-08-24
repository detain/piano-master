"""Determinism helpers (plan §8.2.10).

Byte-identical output for identical input requires all of: sorted JSON keys,
fixed float formatting, zip entries in sorted order with timestamps zeroed,
pinned rendering tools, and ``buildInfo`` (which carries a timestamp) excluded
from the content hash.

Python's float repr is deterministic across platforms (shortest round-trip,
IEEE 754), but two runs can still differ in the last ulp after unrelated math;
rounding every float to 12 decimal places at the JSON boundary kills that
noise while keeping rational-ish values (1/3 → 0.333333333333) exact enough
for beat math. ``-0.0`` is normalized so json.dumps never writes ``-0.0``.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
import os
import struct
import zipfile
from collections.abc import Iterable
from pathlib import Path
from typing import Any

# The documented deterministic build timestamp sentinel (§8.2.10): a real
# pipeline never produces wall-clock bytes unless --timestamp now is passed.
DEFAULT_BUILD_TIMESTAMP = "1970-01-01T00:00:00Z"

# ZipInfo dates before 1980 are not representable in the DOS format; use the
# earliest representable instant. ``external_attr`` is pinned so the entry
# mode (0o644) cannot leak host umask differences into the bytes.
_ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)
_ZIP_EXTERNAL_ATTR = 0o644 << 16
_ZIP_CREATE_SYSTEM = 3  # unix

# Fixed Ogg serial number + poly for canonicalize_ogg (see there).
_OGG_SERIAL = 0x4B455931  # "KEY1"
_OGG_POLY = 0x04C11DB7
_OGG_TABLE: list[int] | None = None


def _crc_table() -> list[int]:
    """Non-reflected CRC-32 table used by the Ogg page checksum (Xiph spec:
    polynomial 0x04c11db7, no reflection, running crc starts at 0)."""
    global _OGG_TABLE
    if _OGG_TABLE is None:
        table = []
        for i in range(256):
            r = i << 24
            for _ in range(8):
                r = ((r << 1) ^ _OGG_POLY) if (r & 0x80000000) else (r << 1)
            table.append(r & 0xFFFFFFFF)
        _OGG_TABLE = table
    return _OGG_TABLE


def _ogg_crc32(data: bytes) -> int:
    table = _crc_table()
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ table[((crc >> 24) & 0xFF) ^ byte]
    return crc


def canonicalize_ogg(data: bytes) -> bytes:
    """Make an Ogg stream byte-deterministic without changing its audio.

    ffmpeg's Ogg muxer embeds a random per-process serial number in every
    page, so two encodes of the same PCM differ in exactly the serial field
    and the page CRCs that cover it. This rewrites every page to a fixed
    serial and recomputes the CRC-32 (Ogg's non-reflected polynomial) — the
    only bytes that change, verified in the audio tests (decoded PCM is
    identical before/after)."""
    out = bytearray(data)
    pos = 0
    n = len(out)
    while pos + 27 <= n and out[pos : pos + 4] == b"OggS":
        struct.pack_into("<I", out, pos + 14, _OGG_SERIAL)
        out[pos + 22 : pos + 26] = b"\x00\x00\x00\x00"
        nsegs = out[pos + 26]
        body_len = sum(out[pos + 27 : pos + 27 + nsegs])
        total = 27 + nsegs + body_len
        crc = _ogg_crc32(bytes(out[pos : pos + total]))
        struct.pack_into("<I", out, pos + 22, crc)
        pos += total
    return bytes(out)


def _canonical_value(value: Any) -> Any:
    """Recursively prepare a value for deterministic JSON: sorted keys,
    floats rounded to 12 decimals, ``-0.0`` → ``0.0``, numpy scalars → Python."""
    if isinstance(value, dict):
        return {str(k): _canonical_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_canonical_value(v) for v in value]
    # numpy scalars arrive from audio math; convert before float handling.
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):  # noqa: PLR0124 — NaN is not JSON
            raise ValueError(f"non-finite float {value!r} cannot be packed deterministically")
        if value == 0:
            return 0.0
        return round(float(value), 12)
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return int(value)
    return value


def canonical_json_bytes(value: Any) -> bytes:
    """Serialize a value as deterministic UTF-8 JSON (sorted keys, fixed
    floats, compact separators, trailing newline)."""
    canonical = _canonical_value(value)
    text = json.dumps(
        canonical,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    )
    return (text + "\n").encode("utf-8")


def write_json_deterministic(path: Path, value: Any) -> None:
    """Write ``value`` as deterministic JSON (see canonical_json_bytes)."""
    path.write_bytes(canonical_json_bytes(value))


def write_zip_deterministic(path: Path, files: Iterable[tuple[str, bytes]]) -> None:
    """Write a zip with sorted entries, zeroed timestamps, fixed mode.

    ``files`` is ``(archive_name, content_bytes)``. Entry order, timestamps,
    permissions, compression level, and deflate streams are all pinned, so the
    byte output depends only on the file contents."""
    with zipfile.ZipFile(
        path,
        mode="w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as zf:
        for name, content in sorted(files, key=lambda pair: pair[0]):
            info = zipfile.ZipInfo(filename=name, date_time=_ZIP_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = _ZIP_EXTERNAL_ATTR
            info.create_system = _ZIP_CREATE_SYSTEM
            info.flag_bits |= 0x800  # UTF-8 filenames (zipfile default is cp437)
            zf.writestr(info, content)


def sha256_bytes(content: bytes) -> str:
    return f"sha256:{hashlib.sha256(content).hexdigest()}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def source_date_epoch() -> int | None:
    """The SOURCE_DATE_EPOCH value if set and valid, else None."""
    raw = os.environ.get("SOURCE_DATE_EPOCH")
    if raw is None or raw == "":
        return None
    try:
        value = int(raw)
    except ValueError:
        raise ValueError(f"SOURCE_DATE_EPOCH must be an integer, got {raw!r}")
    if value < 0:
        raise ValueError(f"SOURCE_DATE_EPOCH must be >= 0, got {value}")
    return value


def build_timestamp(timestamp_mode: str) -> str:
    """RFC 3339 UTC timestamp for buildInfo.buildTimestamp.

    Deterministic (plan §8.2.10): SOURCE_DATE_EPOCH wins, else the fixed
    sentinel. ``--timestamp now`` is the opt-in wall-clock escape hatch for
    publishing, and even then the timestamp is excluded from the content hash
    so the cache key stays stable."""
    if timestamp_mode == "now":
        return _dt.datetime.now(_dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    epoch = source_date_epoch()
    if epoch is not None:
        return _dt.datetime.fromtimestamp(epoch, tz=_dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    return DEFAULT_BUILD_TIMESTAMP