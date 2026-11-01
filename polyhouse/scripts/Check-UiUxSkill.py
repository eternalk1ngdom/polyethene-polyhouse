"""Read-only verification of the repository's pinned UI/UX skill installation.

Use only the Python standard library; never import or execute vendored skill code.
"""

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


class IntegrityError(ValueError):
    """Report a failed integrity boundary without exposing file contents."""


def _manifest_entries(manifest: object) -> list[tuple[str, str]]:
    """Validate the entire manifest before inspecting any installed skill file."""
    if not isinstance(manifest, dict) or (
        type(manifest.get("schemaVersion")) is not int
        or manifest.get("schemaVersion") != 1
        or manifest.get("skillName") != "ui-ux-pro-max"
        or manifest.get("installedDirectory") != ".agents/skills/ui-ux-pro-max"
        or manifest.get("repository")
        != "https://github.com/nextlevelbuilder/ui-ux-pro-max-skill"
        or not isinstance(manifest.get("sourceCommit"), str)
        or not re.fullmatch(r"[a-fA-F0-9]{40}", manifest["sourceCommit"])
        or not isinstance(manifest.get("files"), list)
    ):
        raise IntegrityError("Invalid manifest format or pinned source")

    entries = []
    seen = set()
    for entry in manifest["files"]:
        if not isinstance(entry, dict):
            raise IntegrityError("Invalid manifest file entry")
        path, digest = entry.get("path"), entry.get("sha256")
        if (
            not isinstance(path, str)
            or not re.fullmatch(r"[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)*", path)
            or any(part in {".", ".."} for part in path.split("/"))
            or path.lower() in seen
        ):
            raise IntegrityError("Invalid or duplicate manifest path")
        if not isinstance(digest, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
            raise IntegrityError("Invalid manifest SHA-256")
        # Preserve the old Windows checker's case-insensitive duplicate detection.
        seen.add(path.lower())
        entries.append((path, digest.lower()))
    if not {"skill.md", "scripts/search.py"}.issubset(seen):
        raise IntegrityError("Manifest is missing SKILL.md or scripts/search.py")
    return entries


def _checked_stat(path: Path) -> os.stat_result:
    """lstat and the Windows attribute cover both symbolic links and junctions."""
    result = path.lstat()
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    if stat.S_ISLNK(result.st_mode) or (
        getattr(result, "st_file_attributes", 0) & reparse_flag
    ):
        raise IntegrityError(f"Symbolic link or reparse point is not allowed: {path.name}")
    return result


def _checked_directory(path: Path) -> None:
    if not stat.S_ISDIR(_checked_stat(path).st_mode):
        raise IntegrityError(f"Skill directory is missing or invalid: {path.name}")


def _checked_file(root: Path, relative: str) -> Path:
    """Check every parent, so a normal file cannot hide behind a directory junction."""
    parts = relative.split("/")
    parent = root
    for part in parts[:-1]:
        parent /= part
        _checked_directory(parent)
    file = parent / parts[-1]
    if not file.resolve(strict=True).is_relative_to(root):
        raise IntegrityError(f"Manifest path escapes the skill directory: {relative}")
    if not stat.S_ISREG(_checked_stat(file).st_mode):
        raise IntegrityError(f"Skill file is missing or invalid: {relative}")
    return file


def _hash_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _raise_walk_error(error: OSError) -> None:
    # os.walk normally ignores inaccessible directories; this checker must fail closed.
    raise error


def check_integrity(repository: Path) -> dict[str, object]:
    """Verify fixed metadata, confined regular files, hashes and unregistered files."""
    repository = repository.resolve(strict=True)
    manifest_path = repository / "docs/operations/ui-ux-skill-lock.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    entries = _manifest_entries(manifest)

    root = repository
    for part in manifest["installedDirectory"].split("/"):
        root /= part
        _checked_directory(root)
    root = root.resolve(strict=True)
    # Follow host filesystem case rules; Linux must not overlook an extra case-only file.
    expected = {os.path.normcase(relative) for relative, _ in entries}
    for relative, digest in entries:
        if _hash_file(_checked_file(root, relative)) != digest:
            raise IntegrityError(f"SHA-256 mismatch: {relative}")

    for directory, directories, files in os.walk(
        root, followlinks=False, onerror=_raise_walk_error
    ):
        parent = Path(directory)
        # Match the old exclusion, including case-insensitive Windows spellings.
        directories[:] = [name for name in directories if name.lower() != "__pycache__"]
        for name in directories:
            _checked_directory(parent / name)
        for name in files:
            if name.lower().endswith((".pyc", ".pyo", ".pyd")):
                continue
            file = parent / name
            _checked_stat(file)
            relative = file.relative_to(root).as_posix()
            if os.path.normcase(relative) not in expected:
                raise IntegrityError(f"Unregistered skill file: {relative}")

    return {
        "Skill": manifest["skillName"],
        "Release": manifest.get("release"),
        "SourceCommit": manifest["sourceCommit"],
        "FilesVerified": len(entries),
        "Scope": "repository",
        "ReadOnly": True,
    }


def main() -> None:
    """Anchor to this script instead of the caller's working directory, and fail CI."""
    try:
        report = check_integrity(Path(__file__).resolve().parents[2])
    except (OSError, ValueError) as error:
        print(f"Skill integrity check failed: {error}", file=sys.stderr)
        sys.exit(1)
    for name, value in report.items():
        print(f"{name} = {value}")


if __name__ == "__main__":
    main()
