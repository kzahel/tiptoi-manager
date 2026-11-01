#!/usr/bin/env python3
"""Clone and verify immutable source references declared in repos.yaml."""

from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

import yaml


ROOT = Path(__file__).resolve().parent.parent
REFERENCES = ROOT / "references"


class ReferenceError(Exception):
    pass


def git(*args: str, cwd: Path = ROOT, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args], cwd=cwd, text=True, capture_output=True, check=False
    )
    if check and result.returncode:
        raise ReferenceError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip() if not result.returncode else ""


def load_manifest() -> list[dict]:
    manifest = yaml.safe_load((ROOT / "repos.yaml").read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise ReferenceError("repos.yaml must declare schema_version: 1")
    records = manifest.get("checkouts")
    if not isinstance(records, list) or not records:
        raise ReferenceError("checkouts must be a nonempty list")
    if REFERENCES.is_symlink():
        raise ReferenceError("references/ must not be a symlink")
    names: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            raise ReferenceError("each checkout must be a mapping")
        for field in ("name", "repository", "revision", "path", "purpose", "license_summary"):
            if not isinstance(record.get(field), str) or not record[field].strip():
                raise ReferenceError(f"checkout requires nonempty {field}")
        name = record["name"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name) or name in names:
            raise ReferenceError(f"invalid or duplicate reference name: {name}")
        names.add(name)
        if not re.fullmatch(r"[0-9a-f]{40}", record["revision"]):
            raise ReferenceError(f"{name}: revision must be a full Git commit")
        if not re.fullmatch(
            r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.git",
            record["repository"],
        ):
            raise ReferenceError(f"{name}: expected a public GitHub HTTPS repository")
        if record["path"] != f"references/{name}":
            raise ReferenceError(f"{name}: path must be references/{name}")
        path = ROOT / record["path"]
        if path.is_symlink() or not path.resolve().is_relative_to(REFERENCES.resolve()):
            raise ReferenceError(f"{name}: checkout escapes references/")
        sparse = record.get("sparse_paths", [])
        if not isinstance(sparse, list):
            raise ReferenceError(f"{name}: sparse_paths must be a list")
        for item in sparse:
            if (
                not isinstance(item, str)
                or not item
                or PurePosixPath(item).is_absolute()
                or any(part in (".", "..") for part in item.split("/"))
                or "\\" in item
                or item.startswith("-")
            ):
                raise ReferenceError(f"{name}: invalid sparse path")
        ignored = subprocess.run(
            ["git", "check-ignore", "--quiet", "--no-index", record["path"]],
            cwd=ROOT,
            check=False,
        )
        if ignored.returncode:
            raise ReferenceError(f"{name}: checkout path must be gitignored")
    return records


def inspect_checkout(record: dict) -> Path:
    path = ROOT / record["path"]
    if not path.is_dir():
        raise ReferenceError(f"{record['name']}: missing; run sync")
    top = git("rev-parse", "--show-toplevel", cwd=path)
    if Path(top).resolve() != path.resolve():
        raise ReferenceError(f"{record['name']}: not its own repository root")
    if git("remote", "get-url", "origin", cwd=path) != record["repository"]:
        raise ReferenceError(f"{record['name']}: unexpected origin; leaving untouched")
    if git("status", "--porcelain", "--untracked-files=all", cwd=path):
        raise ReferenceError(f"{record['name']}: dirty checkout; leaving untouched")
    return path


def sync(record: dict) -> None:
    path = ROOT / record["path"]
    if path.exists():
        inspect_checkout(record)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        git("init", "--quiet", str(path))
        git("remote", "add", "origin", record["repository"], cwd=path)
    if git("rev-parse", "--verify", "HEAD", cwd=path, check=False) != record["revision"]:
        print(f"[fetch] {record['name']} {record['revision']}", flush=True)
        git("fetch", "--depth=1", "--filter=blob:none", "origin", record["revision"], cwd=path)
        if record.get("sparse_paths"):
            git("sparse-checkout", "init", "--cone", cwd=path)
            git("sparse-checkout", "set", "--", *record["sparse_paths"], cwd=path)
        git("checkout", "--quiet", "--detach", record["revision"], cwd=path)
    status(record)


def status(record: dict) -> None:
    path = inspect_checkout(record)
    head = git("rev-parse", "HEAD", cwd=path)
    if head != record["revision"]:
        raise ReferenceError(f"{record['name']}: HEAD differs from repos.yaml; run sync")
    print(f"[ok] {record['name']} {head}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "sync", "status"))
    parser.add_argument("--only", action="append", default=[], metavar="NAME")
    args = parser.parse_args()
    try:
        records = load_manifest()
        unknown = set(args.only) - {r["name"] for r in records}
        if unknown:
            raise ReferenceError("unknown references: " + ", ".join(sorted(unknown)))
        if args.only:
            records = [r for r in records if r["name"] in args.only]
        if args.command == "validate":
            print(f"Validated {len(records)} pinned references and their ignore rules.")
        else:
            for record in records:
                (sync if args.command == "sync" else status)(record)
        return 0
    except (ReferenceError, OSError, yaml.YAMLError) as error:
        print(f"reference error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
