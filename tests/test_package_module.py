# SPDX-License-Identifier: GPL-3.0-only
import csv
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("package_module", ROOT / "scripts/package_module.py")
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


class PackageTests(unittest.TestCase):
    def setUp(self):
        cache = ROOT / ".cache/tmp"
        cache.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="package-check-", dir=cache)
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.module, self.raw = self.work / "module", self.work / "raw"
        self.module.mkdir()
        self.payload = self.raw / "system/app/Test/Test.apk"
        self.payload.parent.mkdir(parents=True)
        self.payload.write_bytes(b"third-party-fixture")
        self.apk, self.helper = self.work / "compat.apk", self.work / "helper.dex"
        self.apk.write_bytes(b"new-compat-fixture")
        self.helper.write_bytes(b"dex\n035\0" + b"\0" * 104)
        (self.module / "module.prop").write_text("id=HyperOS_EU2CN\nversion=OS318.0.1\nversionCode=31801\n")
        for name in ("README.md", "EU2CN_Config.ini", "native-library-manifest.tsv", "customize.sh"):
            (self.module / name).write_text("fixture\n")
        entry = self.module / "META-INF/com/google/android/update-binary"
        entry.parent.mkdir(parents=True)
        entry.write_text("#!/bin/sh\n")
        fields = ["path", "bytes", "sha256", "package", "versionCode", "versionName", "signerSha256"]
        with (self.module / "payload-manifest.tsv").open("w") as stream:
            writer = csv.DictWriter(stream, fields, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerow(dict(path="system/app/Test/Test.apk", bytes=self.payload.stat().st_size, sha256=pack.digest(self.payload)))
            writer.writerow(dict(path=pack.COMPAT, bytes=1, sha256="old", package="local.pudding.weathercompat", versionCode=31801))
        (self.module / "cache-targets.tsv").write_text("local.pudding.weathercompat|" + pack.COMPAT + "|old|31801|cache|marker\n")

    def prepared(self):
        with patch.object(pack, "apk_identity", return_value=("local.pudding.weathercompat", "31801", "OS318.0.1", "a" * 64)):
            return pack.prepare(self.module, self.raw, self.apk, self.helper, "apksigner", "aapt")

    def test_bundle_uses_only_verified_payloads_and_updates_compat_identity(self):
        (self.module / "do-not-publish.key").write_text("private fixture")
        metadata, payloads, _ = self.prepared()
        output = self.work / "module.zip"
        checksum = pack.package(output, metadata, payloads)
        self.assertEqual(checksum, pack.digest(output))
        with zipfile.ZipFile(output) as archive:
            self.assertNotIn("do-not-publish.key", archive.namelist())
            self.assertEqual(archive.read(pack.COMPAT), self.apk.read_bytes())
            rows = list(csv.DictReader(archive.read("payload-manifest.tsv").decode().splitlines(), delimiter="\t"))
            compat = next(r for r in rows if r["path"] == pack.COMPAT)
            self.assertEqual(compat["sha256"], pack.digest(self.apk))
            self.assertEqual(compat["signerSha256"], "a" * 64)
            self.assertIsNone(archive.testzip())

    def test_existing_output_is_never_overwritten(self):
        metadata, payloads, _ = self.prepared()
        output = self.work / "module.zip"
        output.write_bytes(b"existing release")
        with self.assertRaises(FileExistsError):
            pack.package(output, metadata, payloads)
        self.assertEqual(output.read_bytes(), b"existing release")

    def test_changed_input_removes_incomplete_output(self):
        metadata, payloads, _ = self.prepared()
        self.payload.write_bytes(b"changed after verification")
        output = self.work / "module.zip"
        with self.assertRaisesRegex(ValueError, "Input changed"):
            pack.package(output, metadata, payloads)
        self.assertFalse(output.exists())

    def test_payload_hash_mismatch_is_rejected(self):
        self.payload.write_bytes(b"wrong payload")
        with self.assertRaisesRegex(ValueError, "size/hash mismatch"):
            self.prepared()

    def test_traversal_and_symlink_are_rejected(self):
        for name in ("../secret", "/system/app/test.apk", "system/../../secret", "system\\bad.apk", "system//app/Test/Test.apk"):
            with self.assertRaises(ValueError):
                pack.safe_payload(self.raw, name)
        self.payload.unlink()
        self.payload.symlink_to(self.apk)
        with self.assertRaises(ValueError):
            self.prepared()

    def test_wrong_compatibility_identity_is_rejected(self):
        with patch.object(pack, "apk_identity", return_value=("another.package", "31801", "OS318.0.1", "a" * 64)):
            with self.assertRaisesRegex(ValueError, "package/version"):
                pack.prepare(self.module, self.raw, self.apk, self.helper, "a", "b")


if __name__ == "__main__":
    unittest.main()
