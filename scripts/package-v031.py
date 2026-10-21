#!/usr/bin/env python3
"""Freeze the tested 0.3.1 release once; never overwrite prior artifacts."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / "artifacts"
version = "0.3.1"
metadata = json.loads((root / "app/build/outputs/apk/release/output-metadata.json").read_text())
assert {(e["versionName"], e["versionCode"]) for e in metadata["elements"]} == {(version, 7)}
apk = out / f"keeping-accounts-{version}-arm64.apk"
archive = out / f"keeping-accounts-{version}-source.zip"
report_file = out / f"verification-{version}.json"
sums_file = out / f"SHA256SUMS-{version}"
assert not any(p.exists() for p in [apk, archive, report_file, sums_file]), "Release already frozen"
source = root / "app/build/outputs/apk/release/app-release.apk"
sdk = Path.home() / "Library/Android/sdk/build-tools/36.0.0"
signature = subprocess.check_output([str(sdk / "apksigner"), "verify", "--verbose", str(source)], text=True)
alignment = subprocess.check_output([str(sdk / "zipalign"), "-c", "-P", "16", "-v", "4", str(source)], text=True)
assert "Verifies" in signature and "Verification successful" in alignment
key = (root / "model-key.txt").read_bytes().strip()
assert key, "Expected local credential for exclusion scan"
native = []
with zipfile.ZipFile(source) as z:
    for name in z.namelist():
        data = z.read(name)
        assert key not in data, "Credential present in APK"
        if name.startswith("lib/") and name.endswith(".so"):
            assert name.startswith("lib/arm64-v8a/") and data[:5] == b"\x7fELF\x02"
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            aligns = [struct.unpack_from("<Q", data, offset + i * size + 48)[0]
                      for i in range(count) if struct.unpack_from("<I", data, offset + i * size)[0] == 1]
            assert min(aligns) >= 16384, "Native load alignment below 16 KB"
            native.append({"path": name, "minLoadAlignment": min(aligns)})
assert source.stat().st_size <= 40 * 1024 * 1024
for log in ["v031-upgrade.log", "v031-update-check.log"]:
    check = (root / ".local" / log).read_text()
    assert "OK (1 test)" in check and "FAILURES!!!" not in check and "INSTRUMENTATION_FAILED" not in check, log
import xml.etree.ElementTree as ET
suite = ET.parse(root / "app/build/test-results/testDebugUnitTest/TEST-dev.localledger.ReleaseUpdatesTest.xml").getroot()
assert suite.attrib["tests"] == "2" and suite.attrib["failures"] == "0" and suite.attrib["errors"] == "0"
previous = json.loads((out / "verification-0.3.0.json").read_text())
files = subprocess.check_output(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
).decode().split("\0")
public_files = []
for rel in sorted(set(files)):
    if not rel or (rel.startswith("artifacts/") and not rel.endswith(".png")) or not (root / rel).is_file():
        continue
    assert not any(part in {".local", "node_modules", "build", ".git"} for part in Path(rel).parts)
    assert Path(rel).name not in {"model-key.txt", "model-config.local.json", "local.properties"}
    assert not rel.endswith((".jks", ".keystore"))
    assert key not in (root / rel).read_bytes(), "Credential present in public source"
    public_files.append(rel)
shutil.copy2(source, apk)
with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as z:
    for rel in public_files:
        z.write(root / rel, "keeping-accounts/" + rel)
artifacts = [{"file": p.name, "bytes": p.stat().st_size,
              "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk, archive]]
report = {
    "version": version, "versionCode": 7, "date": "2026-10-22",
    "channel": "stable", "device": "ARM64 Android 16 emulator, 1260x2800",
    "physicalDeviceVerified": False, "actualWechatNotificationVerified": False,
    "artifacts": artifacts, "signatureVerified": True, "zipAlignment16KB": True,
    "nativeLibraries": native, "credentialScanPassed": True,
    "upgrade": {"from": "0.3.0", "to": version, "passed": True,
                "evidence": ".local/v031-upgrade.log"},
    "updateChecks": {"unitChecks": 2, "actualPublishedReleaseReadPassed": True,
                     "observedTagBeforePublication": "v0.3.0",
                     "evidence": ".local/v031-update-check.log",
                     "trigger": "GitHub anonymous API returned HTTP 403 with rate limit remaining 0; official latest release HEAD returned 302"},
    "functionalEvidence": previous["functionalEvidence"],
    "reusedEvidence": "Capture and Pi processing unchanged from v0.3.0 (a0c6e6d). See verification-0.3.0.json",
    "releaseDocument": "docs/RELEASE_0.3.0.md"
}
report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
sums_file.write_text("".join(f"{a['sha256']}  {a['file']}\n" for a in artifacts))
print(json.dumps({"artifacts": artifacts, "signatureVerified": True}, ensure_ascii=False))
