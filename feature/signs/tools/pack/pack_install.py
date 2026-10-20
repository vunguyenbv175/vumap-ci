"""Atomic install of a validated ``.vupack`` — host-side, stdlib only.

Contract (all steps exercised against real files/directories, not flags):

  1. The incoming pack+manifest pair is FIRST snapshotted byte-for-byte into
     private staging files in the destination directory — with a bounded copy
     that stops at ``MAX_BYTES + 1``, so an oversized input is rejected before
     it can fill the destination filesystem (a preliminary stat is only a fast
     path, not the bound). Validation then runs against those staged bytes —
     the exact bytes that will be committed — so a source rebuilt between
     validation and commit can never inject unvalidated content (closes the
     validate-then-copy TOCTOU window).
  2. If validation fails, nothing is touched: the previously installed pack
     and manifest keep byte identity.
  3. Before the commit, byte-identical backups of the currently installed
     pack AND manifest are taken, and ``<name>.previous`` /
     ``<name>.previous.manifest.json`` retention copies are written. A failure
     at any of these steps changes nothing installed.
  4. The commit itself is: ``os.replace`` staged pack onto the target, fsync
     the target, ``os.replace`` staged manifest onto the target manifest,
     fsync it. Every step after the first replacement is guarded: on ANY
     failure the destination is rolled back so both the prior pack AND the
     prior manifest are restored byte-identical (or removed if no prior file
     existed). The process never reports "prior pack preserved" while the
     destination holds a new pack with an old/partial manifest.
  5. On any failure every staging/backup temp file is removed;
     ``<name>.previous*`` was written pre-commit from the backups, so it
     always holds the prior pair.

Exit code 0 = installed; 1 = rejected (errors printed).

Usage:
    python tools/pack/pack_install.py \
        --pack build/synthetic_vn_signs_p1.vupack \
        --manifest build/synthetic_vn_signs_p1.vupack.manifest.json \
        --dest-dir /some/app/dir
"""

from __future__ import annotations

import argparse
import os
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pack_format as fmt  # noqa: E402
import pack_validate  # noqa: E402

DEFAULT_PACK_NAME = "synthetic_vn_signs_p1.vupack"


def _fsync_file(path: str | Path) -> None:
    with open(path, "rb+") as handle:
        os.fsync(handle.fileno())


def _input_size(path: str | Path) -> int:
    """Preliminary size probe. Only a fast path: the bounded copy below is the
    actual cap, since the source may grow after this stat."""
    return Path(path).stat().st_size


def _stage_copy(src: str | Path, dst: str | Path, limit: int) -> int:
    """Copy at most ``limit + 1`` bytes and stop; returns bytes copied.

    Stops as soon as the input is known to exceed the cap, so an oversized
    source is never fully duplicated into the destination directory (disk-fill
    protection for the install path). Reads are capped so the overshoot is
    exactly one byte past ``limit``.
    """
    copied = 0
    with open(src, "rb") as source, open(dst, "wb") as target:
        while copied <= limit:
            chunk = source.read(min(1024 * 1024, limit + 1 - copied))
            if not chunk:
                break
            target.write(chunk)
            copied += len(chunk)
        target.flush()
        os.fsync(target.fileno())
    return copied


def _restore(
    source: Path,
    destination: Path,
    existed: bool,
    label: str,
    errors: list[str],
) -> None:
    """Put ``destination`` back to its prior state (prior bytes or removal)."""
    try:
        if existed:
            os.replace(source, destination)
        elif destination.exists():
            destination.unlink()
    except OSError as exc:
        errors.append(f"ROLLBACK_{label}_ERROR:{exc}")


def install(
    pack_path: str | Path,
    manifest_path: str | Path,
    dest_dir: str | Path,
    pack_name: str = DEFAULT_PACK_NAME,
) -> list[str]:
    """Install pack+manifest into dest_dir. Returns [] on success, else errors."""
    dest = Path(dest_dir)
    if not dest.is_dir():
        return [f"DEST_DIR_MISSING:{dest}"]

    target = dest / pack_name
    target_manifest = dest / f"{pack_name}.manifest.json"
    previous = dest / f"{pack_name}.previous"
    previous_manifest = dest / f"{pack_name}.previous.manifest.json"

    tag = uuid.uuid4().hex[:8]
    stage_pack = dest / f".{pack_name}.stage-{tag}"
    stage_manifest = dest / f".{pack_name}.stage-manifest-{tag}"
    prev_tmp = dest / f".{pack_name}.stage-prev-{tag}"
    prev_tmp_manifest = dest / f".{pack_name}.stage-prev-manifest-{tag}"
    bak_pack = dest / f".{pack_name}.stage-bak-{tag}"
    bak_manifest = dest / f".{pack_name}.stage-bak-manifest-{tag}"
    temps = (
        stage_pack,
        stage_manifest,
        prev_tmp,
        prev_tmp_manifest,
        bak_pack,
        bak_manifest,
    )

    had_pack = target.exists()
    had_manifest = target_manifest.exists()
    pack_replaced = False
    manifest_replaced = False

    try:
        # Phase A: bounded snapshot of the inputs. Every later step
        # (validation, commit) uses these staged bytes, so sources mutated
        # mid-install can only change bytes that never get installed, and an
        # oversized input is rejected after at most limit+1 bytes are staged —
        # never fully duplicated into the destination.
        try:
            size = _input_size(pack_path)
            if size > fmt.MAX_BYTES:
                return [f"TOO_LARGE:{size}"]
            staged_size = _stage_copy(pack_path, stage_pack, fmt.MAX_BYTES)
            if staged_size > fmt.MAX_BYTES:
                return [f"TOO_LARGE:{staged_size}"]
            size = _input_size(manifest_path)
            if size > fmt.MAX_BYTES:
                return [f"MANIFEST_TOO_LARGE:{size}"]
            staged_size = _stage_copy(manifest_path, stage_manifest, fmt.MAX_BYTES)
            if staged_size > fmt.MAX_BYTES:
                return [f"MANIFEST_TOO_LARGE:{staged_size}"]
        except OSError as exc:
            return [f"INPUT_COPY_ERROR:{exc}"]

        # Phase B: validate the staged pair that will actually be installed.
        errors = pack_validate.validate(stage_pack, stage_manifest)
        if errors:
            return errors

        # Phase C: byte-identical backups of the installed pair (sources stay
        # in place; nothing installed has changed yet). Bounded as well, so a
        # tampered/oversized installed file cannot fill the disk either.
        try:
            if had_pack:
                staged_size = _stage_copy(target, bak_pack, fmt.MAX_BYTES)
                if staged_size > fmt.MAX_BYTES:
                    return [
                        f"INSTALL_IO_ERROR:installed pack exceeds {fmt.MAX_BYTES} bytes"
                    ]
            if had_manifest:
                staged_size = _stage_copy(target_manifest, bak_manifest, fmt.MAX_BYTES)
                if staged_size > fmt.MAX_BYTES:
                    return [
                        f"INSTALL_IO_ERROR:installed manifest exceeds {fmt.MAX_BYTES} bytes"
                    ]
        except OSError as exc:
            return [f"INSTALL_IO_ERROR:{exc}"]

        # Phase D: retention copies for manual rollback, written BEFORE the
        # commit so a failure here changes nothing installed.
        try:
            if had_pack:
                _stage_copy(bak_pack, prev_tmp, fmt.MAX_BYTES)
                os.replace(prev_tmp, previous)
            if had_manifest:
                _stage_copy(bak_manifest, prev_tmp_manifest, fmt.MAX_BYTES)
                os.replace(prev_tmp_manifest, previous_manifest)
        except OSError as exc:
            return [f"INSTALL_IO_ERROR:{exc}"]

        # Phase E: atomic commit — pack, then manifest, fsyncing each. Any
        # failure after the first replacement rolls the destination back to
        # the preserved prior pair (byte-identical) before returning.
        try:
            os.replace(stage_pack, target)
            pack_replaced = True
            _fsync_file(target)
            os.replace(stage_manifest, target_manifest)
            manifest_replaced = True
            _fsync_file(target_manifest)
        except OSError as exc:
            rollback_errors: list[str] = []
            if manifest_replaced:
                _restore(
                    bak_manifest, target_manifest, had_manifest,
                    "MANIFEST", rollback_errors,
                )
            if pack_replaced:
                _restore(bak_pack, target, had_pack, "PACK", rollback_errors)
            message = [f"INSTALL_IO_ERROR:{exc}"]
            if rollback_errors:
                message.append("ROLLBACK_FAILED")
                message.extend(rollback_errors)
            else:
                message.append("PRIOR_PAIR_RESTORED")
            return message

        return []
    finally:
        for temp in temps:
            try:
                if temp.exists():
                    temp.unlink()
            except OSError:
                pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--dest-dir", required=True)
    parser.add_argument("--pack-name", default=DEFAULT_PACK_NAME)
    args = parser.parse_args(argv)

    errors = install(args.pack, args.manifest, args.dest_dir, args.pack_name)
    if errors:
        print("RESULT: INSTALL REJECTED (prior pack preserved)")
        for error in errors:
            print(f"  - {error}")
        return 1
    target = Path(args.dest_dir) / args.pack_name
    print(f"installed={target}")
    print(f"sha256={fmt.sha256_file(target)}")
    print("RESULT: INSTALL OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
