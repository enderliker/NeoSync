# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only

import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile


SPEC = importlib.util.spec_from_file_location("prepare_release", Path(__file__).resolve().parents[2] / "scripts/prepare_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
KEY = b"neosync-fixture-not-a-real-key"


def archive(entries):
    stream = io.BytesIO()
    with ZipFile(stream, "w", ZIP_DEFLATED) as jar:
        for name, data in entries.items():
            jar.writestr(name, data)
    return stream.getvalue()


def masked(key=KEY):
    return bytes(value for byte in key for value in (0xa5, byte ^ 0xa5))


class ReleaseCredentialTests(unittest.TestCase):
    def test_export_requires_committed_untracked_sources(self):
        for status in ("?? src/main/java/NewRuntime.java", " M tracked.java", "A  new.java"):
            with self.subTest(status=status), patch.object(release, "git", return_value=status) as git:
                with self.assertRaisesRegex(ValueError, "Commit all source changes"):
                    release.require_clean_tree()
                git.assert_called_once_with("status", "--porcelain")
        with patch.object(release, "git", return_value=""):
            release.require_clean_tree()

    def test_requires_explicit_authorization_and_exact_credential(self):
        jar = archive({release.PROVIDER_ACCESS: masked()})
        with self.assertRaisesRegex(ValueError, "outside the authorized runtime"):
            release.check_archive_access(jar, None)
        release.check_archive_access(jar, KEY, ((release.PROVIDER_ACCESS,),))
        with self.assertRaisesRegex(ValueError, "differs from the supplied file"):
            release.check_archive_access(jar, b"another-fixture-key", ((release.PROVIDER_ACCESS,),))

    def test_rejects_malformed_masked_resources(self):
        for contents in (b"", b"x", b"x" * 8194):
            with self.subTest(size=len(contents)), self.assertRaisesRegex(ValueError, "Invalid embedded"):
                release.check_archive_access(archive({release.PROVIDER_ACCESS: contents}), KEY, ((release.PROVIDER_ACCESS,),))

    def test_checks_nested_installer_runtime_and_rejects_extra_resources(self):
        runtime_path = "maven/runtime-universal.jar"
        runtime = archive({release.PROVIDER_ACCESS: masked()})
        installer = archive({runtime_path: runtime})
        release.check_archive_access(installer, KEY, ((runtime_path, release.PROVIDER_ACCESS),))
        for forbidden in (archive({release.PROVIDER_ACCESS: masked()}), archive({"extra.jar": runtime})):
            with self.assertRaisesRegex(ValueError, "outside the authorized runtime"):
                release.check_archive_access(forbidden, KEY, ((runtime_path, release.PROVIDER_ACCESS),))

    def test_finds_literal_credentials_in_compressed_nested_files_and_names(self):
        for jar in (
            archive({"runtime.jar": archive({"private.txt": KEY})}),
            archive({"disguised.bin": archive({"private.txt": KEY})}),
            archive({KEY.decode(): b"ordinary content"}),
            archive({"private.txt": KEY.decode().encode("utf-16-le")}),
        ):
            with self.assertRaises(ValueError) as failure:
                release.check_archive_access(jar, KEY)
            self.assertNotIn(KEY.decode(), str(failure.exception))

    def test_bounds_nested_archives_and_sanitizes_corruption(self):
        nested = archive({"ordinary.txt": b"content"})
        for _ in range(10):
            nested = archive({"nested.jar": nested})
        with self.assertRaisesRegex(ValueError, "inspection limit"):
            release.check_archive_access(nested, KEY)
        with self.assertRaisesRegex(ValueError, "Could not inspect"):
            release.check_archive_access(archive({"broken.jar": KEY[:5]}), KEY)

    def test_reads_trimmed_key_and_rejects_invalid_file_without_displaying_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "credential"
            path.write_bytes(b"\r\n" + KEY + b"\n")
            self.assertEqual(KEY, release.credential_bytes(path))
            for contents in (b"", b"spaces inside", b"x" * 4097, b"\xff", b"\xc2\xa0" + KEY):
                path.write_bytes(contents)
                with self.assertRaisesRegex(ValueError, "Invalid CurseForge credential file"):
                    release.credential_bytes(path)

    def test_scans_logs_across_chunk_boundaries_and_utf16(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "validation.log"
            path.write_text("PASS: ordinary validation output")
            release.check_log(path, KEY)
            for contents in (b"x" * (65536 - 4) + KEY, KEY.decode().encode("utf-16-le")):
                path.write_bytes(contents)
                with self.assertRaises(ValueError) as failure:
                    release.check_log(path, KEY)
                self.assertNotIn(KEY.decode(), str(failure.exception))


if __name__ == "__main__":
    unittest.main()
