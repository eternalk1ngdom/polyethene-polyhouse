"""Exercise the real checker on disposable copies, never mutate the installed skill."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


REPOSITORY = Path(__file__).resolve().parents[4]
CHECKER = REPOSITORY / "polyhouse/scripts/Check-UiUxSkill.py"
ARTIFACTS = REPOSITORY / "polyhouse/target/skill-check-tests"
SPEC = importlib.util.spec_from_file_location("skill_integrity_checker", CHECKER)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class SkillIntegrityTests(unittest.TestCase):
    """Verify successful output, negative exit codes and every confined-read boundary."""

    def setUp(self):
        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="case-", dir=ARTIFACTS)
        self.addCleanup(temporary.cleanup)
        self.repository = Path(temporary.name)
        self.skill = self.repository / ".agents/skills/ui-ux-pro-max"
        self.manifest = self.repository / "docs/operations/ui-ux-skill-lock.json"
        self.script = self.repository / "polyhouse/scripts/Check-UiUxSkill.py"
        self.manifest.parent.mkdir(parents=True)
        self.script.parent.mkdir(parents=True)
        shutil.copytree(REPOSITORY / ".agents/skills/ui-ux-pro-max", self.skill)
        shutil.copy2(REPOSITORY / "docs/operations/ui-ux-skill-lock.json", self.manifest)
        shutil.copy2(CHECKER, self.script)

    def _run(self):
        # Run from elsewhere as well: the public CLI must resolve its own repository.
        return subprocess.run(
            [sys.executable, "-B", str(self.script)],
            cwd=ARTIFACTS,
            capture_output=True,
            text=True,
        )

    def _manifest_value(self):
        return json.loads(self.manifest.read_text(encoding="utf-8"))

    def _set_manifest(self, value):
        # Only this disposable fixture changes; never regenerate the real lock file.
        self.manifest.write_text(json.dumps(value), encoding="utf-8")

    def _assert_cli_failure(self, text):
        run = self._run()
        self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
        self.assertEqual(run.stdout, "")
        self.assertIn(text, run.stderr)
        return run

    def _junction(self, link, target):
        self.assertTrue(link.absolute().is_relative_to(self.repository))
        self.assertTrue(target.resolve().is_relative_to(self.repository))
        if os.name == "nt":
            created = subprocess.run(
                ["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(target)],
                capture_output=True,
            )
            if created.returncode:
                self.fail("Cannot create the isolated junction fixture; do not claim coverage")
            self.addCleanup(os.rmdir, link)
        else:
            link.symlink_to(target, target_is_directory=True)
            self.addCleanup(link.unlink)

    def _alternate_repository(self, excluded=None):
        # Build link fixtures without renaming freshly copied directories: Windows
        # scanners may briefly hold those directories and make rename intermittent.
        repository = self.repository / "linked-install"
        manifest = repository / "docs/operations/ui-ux-skill-lock.json"
        self.script = repository / "polyhouse/scripts/Check-UiUxSkill.py"
        skill = repository / ".agents/skills/ui-ux-pro-max"
        manifest.parent.mkdir(parents=True)
        self.script.parent.mkdir(parents=True)
        skill.parent.mkdir(parents=True)
        shutil.copy2(self.manifest, manifest)
        shutil.copy2(CHECKER, self.script)
        if excluded:
            shutil.copytree(
                self.skill, skill,
                ignore=lambda directory, names: [excluded] if Path(directory) == self.skill else [],
            )
        return repository, skill

    def test_valid_installation_and_cli_output_are_read_only(self):
        before = {
            file.relative_to(self.repository): hashlib.sha256(file.read_bytes()).hexdigest()
            for file in self.repository.rglob("*")
            if file.is_file()
        }
        report = CHECK.check_integrity(self.repository)
        self.assertEqual(
            set(report),
            {"Skill", "Release", "SourceCommit", "FilesVerified", "Scope", "ReadOnly"},
        )
        self.assertEqual(report["FilesVerified"], 73)
        self.assertTrue(report["ReadOnly"])
        run = self._run()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("FilesVerified = 73", run.stdout)
        self.assertEqual(run.stderr, "")
        after = {
            file.relative_to(self.repository): hashlib.sha256(file.read_bytes()).hexdigest()
            for file in self.repository.rglob("*")
            if file.is_file()
        }
        self.assertEqual(before, after)

    def test_one_changed_byte_fails_with_exit_one(self):
        file = self.skill / "SKILL.md"
        content = bytearray(file.read_bytes())
        content[0] ^= 1
        file.write_bytes(content)
        self._assert_cli_failure("SHA-256 mismatch: SKILL.md")

    def test_unregistered_file_fails_with_exit_one(self):
        (self.skill / "unregistered-probe.txt").write_text("fixture", encoding="utf-8")
        self._assert_cli_failure("Unregistered skill file: unregistered-probe.txt")

    def test_missing_file_fails_with_exit_one(self):
        (self.skill / "SKILL.md").unlink()
        self._assert_cli_failure("Skill integrity check failed")

    def test_bytecode_caches_are_ignored(self):
        cache = self.skill / "scripts/__pycache__/nested"
        cache.mkdir(parents=True)
        (cache / "cached.txt").write_text("ignored", encoding="utf-8")
        for suffix in [".pyc", ".pyo", ".pyd", ".PYC"]:
            (self.skill / f"cache-probe{suffix}").write_bytes(b"ignored")
        self.assertEqual(self._run().returncode, 0)

    def test_invalid_source_fields_stop_before_skill_reads(self):
        original = self._manifest_value()
        for field, value in [
            ("schemaVersion", 2),
            ("schemaVersion", True),
            ("skillName", "other-skill"),
            ("installedDirectory", "../private"),
            ("repository", "https://example.invalid"),
            ("sourceCommit", "0" * 39),
        ]:
            with self.subTest(field=field, value=value):
                self._set_manifest({**original, field: value})
                with (
                    patch.object(CHECK, "_checked_directory", side_effect=AssertionError("Skill accessed")),
                    patch.object(CHECK, "_hash_file", side_effect=AssertionError("Skill read")),
                ):
                    with self.assertRaisesRegex(CHECK.IntegrityError, "Invalid manifest"):
                        CHECK.check_integrity(self.repository)
                self._assert_cli_failure("Invalid manifest")

    def test_paths_reject_absolute_traversal_separators_and_duplicates(self):
        original = self._manifest_value()
        for path in [
            "/private.txt", "C:/private.txt", "../private.txt", "scripts/./search.py",
            "scripts/../SKILL.md", "scripts\\search.py", "scripts//search.py", "",
        ]:
            with self.subTest(path=path):
                value = {
                    **original,
                    "files": [{**original["files"][0], "path": path}, *original["files"][1:]],
                }
                self._set_manifest(value)
                self._assert_cli_failure("Invalid or duplicate manifest path")
        for duplicate in ["SKILL.md", "skill.MD"]:
            value = {
                **original,
                "files": [*original["files"], {"path": duplicate, "sha256": "0" * 64}],
            }
            self._set_manifest(value)
            self._assert_cli_failure("Invalid or duplicate manifest path")

    def test_required_entries_cannot_be_omitted(self):
        original = self._manifest_value()
        for missing in ["SKILL.md", "scripts/search.py"]:
            self._set_manifest({
                **original,
                "files": [file for file in original["files"] if file["path"] != missing],
            })
            self._assert_cli_failure("Manifest is missing SKILL.md or scripts/search.py")

    def test_malformed_json_and_hash_fail(self):
        original = self._manifest_value()
        self.manifest.write_text("{", encoding="utf-8")
        self._assert_cli_failure("Skill integrity check failed")
        original["files"][0]["sha256"] = "not-a-sha256"
        self._set_manifest(original)
        self._assert_cli_failure("Invalid manifest SHA-256")

    def test_missing_skill_directory_fails(self):
        self._alternate_repository()
        self._assert_cli_failure("Skill integrity check failed")

    def test_root_junction_is_rejected(self):
        _, skill = self._alternate_repository()
        self._junction(skill, self.skill)
        self._assert_cli_failure("reparse point is not allowed")

    def test_intermediate_junction_cannot_escape_or_hide_extra_files(self):
        repository, skill = self._alternate_repository("scripts")
        outside = self.skill / "scripts"
        (outside / "unregistered-probe.txt").write_text("outside fixture", encoding="utf-8")
        self._junction(skill / "scripts", outside)
        with patch.object(CHECK, "_hash_file", wraps=CHECK._hash_file) as read:
            with self.assertRaisesRegex(CHECK.IntegrityError, "reparse point is not allowed"):
                CHECK.check_integrity(repository)
            self.assertTrue(all(
                not call.args[0].resolve().is_relative_to(outside)
                for call in read.call_args_list
            ))
        self._assert_cli_failure("reparse point is not allowed")

    def test_file_symlink_is_rejected_without_reading_target(self):
        _, skill = self._alternate_repository("SKILL.md")
        outside = self.skill / "SKILL.md"
        file = skill / "SKILL.md"
        try:
            file.symlink_to(outside)
        except OSError as error:
            code = getattr(error, "winerror", error.errno)
            self.skipTest(f"File symbolic-link privilege unavailable: {code}")
        self.addCleanup(file.unlink)
        self._assert_cli_failure("escapes the skill directory")

    def test_symlink_and_reparse_flags_are_rejected_without_opening_file(self):
        cases = [(stat.S_IFLNK, 0)]
        if hasattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT"):
            cases.append((stat.S_IFREG, stat.FILE_ATTRIBUTE_REPARSE_POINT))
        for mode, attributes in cases:
            with self.subTest(mode=mode, attributes=attributes):
                result = Mock(st_mode=mode, st_file_attributes=attributes)
                with patch.object(Path, "lstat", return_value=result):
                    with self.assertRaisesRegex(CHECK.IntegrityError, "reparse point"):
                        CHECK._checked_stat(self.skill / "SKILL.md")


if __name__ == "__main__":
    unittest.main()
