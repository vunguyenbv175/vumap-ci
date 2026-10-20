#!/usr/bin/env python3
"""Verify a generated vumap public CI snapshot against PUBLIC_SNAPSHOT.json.

Stdlib only. This file is copied into the public snapshot by the private
exporter; it contains no private data and requires no credentials.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

EXPECTED_PUBLIC_REPO_URL = "https://github.com/vunguyenbv175/vumap-ci"
EXPECTED_SOURCE_REPO_URL = "https://github.com/vunguyenbv175/vumap"
SNAPSHOT_NAME = "PUBLIC_SNAPSHOT.json"
FORMAT = 1


class VerificationError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(root: Path, expect_source_sha: str | None, check_git: bool) -> dict:
    root = root.resolve()
    manifest_path = root / SNAPSHOT_NAME
    if not manifest_path.is_file():
        raise VerificationError(f"missing {SNAPSHOT_NAME}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerificationError(f"invalid {SNAPSHOT_NAME}: {exc}") from exc

    if manifest.get("format") != FORMAT or manifest.get("snapshotKind") != "public-ci":
        raise VerificationError("unexpected snapshot format or kind")
    if manifest.get("publicRepositoryUrl") != EXPECTED_PUBLIC_REPO_URL:
        raise VerificationError("manifest publicRepositoryUrl does not match this repository")
    if manifest.get("sourceRepositoryUrl") != EXPECTED_SOURCE_REPO_URL:
        raise VerificationError("manifest sourceRepositoryUrl does not match the expected private source")

    source_ref = manifest.get("sourceRef")
    if not isinstance(source_ref, str) or not re.fullmatch(r"[0-9a-f]{40}", source_ref):
        raise VerificationError("manifest sourceRef is not a full 40-character commit id")
    if expect_source_sha and source_ref != expect_source_sha:
        raise VerificationError(f"sourceRef {source_ref} does not match expected {expect_source_sha}")

    entries = manifest.get("files")
    if not isinstance(entries, list):
        raise VerificationError("manifest files must be a list")
    if manifest.get("fileCount") != len(entries):
        raise VerificationError("manifest fileCount does not match files")
    if manifest.get("totalBytes") != sum(entry.get("size", -1) for entry in entries):
        raise VerificationError("manifest totalBytes does not match files")

    listed: set[str] = set()
    for entry in entries:
        path = entry.get("path")
        if not isinstance(path, str) or not path:
            raise VerificationError("manifest contains an invalid path")
        if path in listed:
            raise VerificationError(f"duplicate manifest path: {path}")
        listed.add(path)
        target = (root / path).resolve()
        if root not in target.parents and target != root:
            raise VerificationError(f"manifest path escapes snapshot root: {path}")
        if target.is_symlink() or not target.is_file():
            raise VerificationError(f"manifest file is missing or not regular: {path}")
        if target.stat().st_size != entry.get("size"):
            raise VerificationError(f"size mismatch: {path}")
        if sha256_file(target) != entry.get("sha256"):
            raise VerificationError(f"sha256 mismatch: {path}")

    actual: set[str] = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise VerificationError(f"symlink is not allowed: {path.relative_to(root).as_posix()}")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative == ".git" or relative.startswith(".git/"):
            continue
        if relative == SNAPSHOT_NAME:
            continue
        actual.add(relative)
    if actual != listed:
        missing = sorted(listed - actual)
        extra = sorted(actual - listed)
        detail = []
        if missing:
            detail.append("missing: " + ", ".join(missing[:10]))
        if extra:
            detail.append("extra: " + ", ".join(extra[:10]))
        raise VerificationError("snapshot file set does not match manifest (" + "; ".join(detail) + ")")

    commit = None
    if check_git and (root / ".git").exists():
        try:
            status = subprocess.run(
                ["git", "status", "--porcelain"],
                cwd=str(root),
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            ).stdout.decode("utf-8", "replace").strip()
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=str(root),
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            ).stdout.decode("ascii").strip()
        except (OSError, subprocess.CalledProcessError) as exc:
            raise VerificationError(f"cannot inspect snapshot git state: {exc}") from exc
        if status:
            raise VerificationError("snapshot git worktree is not clean:\n" + status)

    return {
        "sourceRef": source_ref,
        "sourceCommitDate": manifest.get("sourceCommitDate"),
        "fileCount": manifest.get("fileCount"),
        "totalBytes": manifest.get("totalBytes"),
        "commit": commit,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify a vumap public CI snapshot.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--expect-source-sha", default=None)
    parser.add_argument("--no-git-check", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = verify(args.root, args.expect_source_sha, not args.no_git_check)
    except VerificationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print("snapshot OK")
    print(f"sourceRef: {result['sourceRef']}")
    print(f"sourceCommitDate: {result['sourceCommitDate']}")
    print(f"fileCount: {result['fileCount']}")
    print(f"totalBytes: {result['totalBytes']}")
    if result["commit"]:
        print(f"exportCommit: {result['commit']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
