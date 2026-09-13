#!/usr/bin/env python3
"""Reject missing, placeholder, or wrong-architecture native libraries before installation."""
import json
import pathlib
import struct
import sys
import zipfile

directory = pathlib.Path(sys.argv[1])
metadata = json.loads((directory / "output-metadata.json").read_text())
outputs = [item for item in metadata["elements"] if any(
    f["filterType"] == "ABI" and f["value"] == "arm64-v8a" for f in item["filters"]
)]
if len(outputs) != 1:
    raise SystemExit("Expected exactly one arm64 APK in Gradle output metadata")
apk = directory / outputs[0]["outputFile"]
with zipfile.ZipFile(apk) as archive:
    for name in ("libMuPDF.so", "liblame.so"):
        data = archive.read("lib/arm64-v8a/" + name)
        if len(data) < 1024 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack("<H", data[18:20])[0] != 183:
            raise SystemExit(f"Invalid AArch64 ELF library: {name}; build the native arm64 libraries first")
print(apk)
