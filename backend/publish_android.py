#!/usr/bin/env python3
"""Publish a verified Android package and switch the update manifest atomically."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def output(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--notes", default="")
    args = parser.parse_args()
    badge = output(args.build_tools / "aapt", "dump", "badging", args.apk).splitlines()[0]
    package = re.match(r"package: name='com\.tidalgui\.player' versionCode='(\d+)' versionName='([^']+)'", badge)
    if package is None:
        parser.error("The APK package must be com.tidalgui.player")
    version = int(package.group(1))
    certs = output(args.build_tools / "apksigner", "verify", "--print-certs", args.apk)
    signatures = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})", certs)
    if not signatures:
        parser.error("The APK has no verified signer")
    directory = args.directory
    directory.mkdir(parents=True, exist_ok=True)
    latest = directory / "latest.json"
    if latest.is_file():
        previous = json.loads(latest.read_text())
        if version <= previous["versionCode"]:
            parser.error("The versionCode must increase")
        if signatures != previous["signers"]:
            parser.error("The signer differs from the previous release")
    sha = hashlib.sha256()
    with args.apk.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            sha.update(chunk)
    digest = sha.hexdigest()
    manifest = {
        "versionCode": version, "versionName": package.group(2), "sha256": digest,
        "signers": signatures, "notes": args.notes,
    }

    def atomic(name, write):
        fd, temporary = tempfile.mkstemp(dir=directory)
        try:
            with os.fdopen(fd, "wb") as file:
                write(file)
                file.flush()
                os.fsync(file.fileno())
            os.chmod(temporary, 0o644)
            os.replace(temporary, directory / name)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    with args.apk.open("rb") as source:
        atomic(f"tidal-{version}-{digest[:16]}.apk", lambda target: shutil.copyfileobj(source, target))
    atomic("latest.json", lambda file: file.write(json.dumps(manifest, ensure_ascii=False).encode()))
    print(f"Published Android {manifest['versionName']} ({version})")


if __name__ == "__main__":
    main()
