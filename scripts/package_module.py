#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Validate locally supplied payloads and assemble a module without private tooling."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "build/pudding-eu-localization"
COMPAT = "system/product/app/PuddingEUCompatibility/PuddingEUCompatibility.apk"


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_payload(root, name):
    relative = PurePosixPath(name)
    require(name and str(relative) == name and not relative.is_absolute() and ".." not in relative.parts
            and "\\" not in name and relative.parts[0] == "system", "Invalid payload path: " + name)
    path = root / name
    require(path.is_file() and not path.is_symlink(), "Missing or linked payload: " + name)
    require(path.resolve().is_relative_to(root.resolve()), "Payload escapes root: " + name)
    return path


def apk_identity(apk, apksigner, aapt):
    signature = subprocess.run([apksigner, "verify", "--print-certs", str(apk)],
                               capture_output=True, text=True, check=True).stdout
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)
    require(len(certs) == 1, "Compatibility APK must have one verified signer")
    badging = subprocess.run([aapt, "dump", "badging", str(apk)],
                             capture_output=True, text=True, check=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    require(package is not None, "Cannot read compatibility APK identity")
    return (*package.groups(), certs[0].lower())


def prepare(module, payload_root, compat, helper, apksigner, aapt):
    require(compat.is_file() and not compat.is_symlink(), "Missing compatibility APK")
    require(helper.is_file() and not helper.is_symlink(), "Missing helper DEX")
    with helper.open("rb") as stream:
        require(stream.read(8).startswith(b"dex\n"), "Invalid helper DEX magic")
    props = dict(line.split("=", 1) for line in (module / "module.prop").read_text().splitlines() if "=" in line)
    package, version_code, version_name, cert = apk_identity(compat, apksigner, aapt)
    require(package == "local.pudding.weathercompat" and version_code == props["versionCode"]
            and version_name == props["version"], "Compatibility APK package/version differs from module")
    with (module / "payload-manifest.tsv").open() as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        fields, rows = reader.fieldnames, list(reader)
    require(rows and fields and {"path", "bytes", "sha256", "package", "versionCode", "versionName", "signerSha256"} <= set(fields),
            "Invalid payload manifest columns")
    payloads, seen = {}, set()
    for row in rows:
        name = row["path"]
        require(name not in seen, "Duplicate payload: " + name)
        seen.add(name)
        if name == COMPAT:
            path = compat
            row.update(bytes=str(path.stat().st_size), sha256=digest(path), package=package,
                       versionCode=version_code, versionName=version_name, signerSha256=cert)
        else:
            path = safe_payload(payload_root, name)
            require(path.stat().st_size == int(row["bytes"]) and digest(path) == row["sha256"],
                    "Payload size/hash mismatch: " + name)
        payloads[name] = (path, row["sha256"])
    require(COMPAT in payloads, "Compatibility APK absent from manifest")
    output = io.StringIO()
    writer = csv.DictWriter(output, fields, delimiter="\t", lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    metadata = {"payload-manifest.tsv": output.getvalue().encode()}
    cache = []
    compat_count = 0
    for line in (module / "cache-targets.tsv").read_text().splitlines():
        columns = line.split("|")
        require(len(columns) == 6, "Invalid cache target")
        if columns[1] == COMPAT:
            columns[0], columns[2], columns[3] = package, payloads[COMPAT][1], version_code
            columns[5] = package.encode("utf-16le").hex()
            compat_count += 1
        cache.append("|".join(columns))
    require(compat_count == 1, "Compatibility cache target must be unique")
    metadata["cache-targets.tsv"] = ("\n".join(cache) + "\n").encode()
    plain = [*module.glob("*.sh"), *module.glob("*.prop"), module / "EU2CN_Config.ini",
             module / "README.md", module / "native-library-manifest.tsv"]
    for directory in ("META-INF", "overlay-src"):
        plain.extend(p for p in (module / directory).rglob("*") if p.is_file())
    for path in plain:
        require(not path.is_symlink() and path.resolve().is_relative_to(module.resolve()), "Linked module metadata")
        name = path.relative_to(module).as_posix()
        require(path.suffix not in {".apk", ".pk8", ".pem", ".jks", ".keystore", ".key"}, "Unexpected metadata binary/key")
        require(name not in metadata and name not in payloads, "Duplicate archive entry: " + name)
        metadata[name] = path.read_bytes()
    require((module / "META-INF/com/google/android/update-binary").is_file(), "Missing installer entry")
    require((module / "customize.sh").is_file(), "Missing installer script")
    payloads["ksu-mount-helper.dex"] = (helper, digest(helper))
    return metadata, payloads, props


def package(output, metadata, payloads):
    require(output.suffix == ".zip", "Output must end in .zip")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation leaves existing releases untouched, including symlinks.
    stream = output.open("xb")
    try:
        with stream, zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name in sorted(set(metadata) | set(payloads)):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                mode = 0o755 if name.endswith(".sh") or name.endswith("/update-binary") else 0o644
                info.external_attr = (0o100000 | mode) << 16
                with archive.open(info, "w", force_zip64=True) as target:
                    if name in metadata:
                        target.write(metadata[name])
                    else:
                        source, expected = payloads[name]
                        actual = hashlib.sha256()
                        with source.open("rb") as content:
                            while chunk := content.read(1024 * 1024):
                                actual.update(chunk)
                                target.write(chunk)
                        require(actual.hexdigest() == expected, "Input changed while packaging: " + name)
        with zipfile.ZipFile(output) as archive:
            require(archive.testzip() is None, "ZIP CRC verification failed")
    except BaseException:
        output.unlink(missing_ok=True)
        raise
    return digest(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload-root", required=True, type=Path)
    parser.add_argument("--compat-apk", required=True, type=Path)
    parser.add_argument("--helper-dex", required=True, type=Path)
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if not args.verify_only and args.output is None:
        parser.error("--output is required unless --verify-only is used")
    metadata, payloads, props = prepare(MODULE, args.payload_root, args.compat_apk, args.helper_dex, args.apksigner, args.aapt)
    report = dict(version=props["version"], entries=len(metadata) + len(payloads), verified=True)
    if not args.verify_only:
        report.update(output=str(args.output), sha256=package(args.output, metadata, payloads))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
