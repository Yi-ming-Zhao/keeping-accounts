#!/usr/bin/env python3
"""Freeze the tested 0.2.1 release once; never overwrite prior artifacts."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / "artifacts"
version = "0.2.1"
metadata = json.loads((root / "app/build/outputs/apk/release/output-metadata.json").read_text())
assert {(e["versionName"], e["versionCode"]) for e in metadata["elements"]} == {(version, 5)}
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
upgrade_log = (root / ".local/stable-v021-upgrade.log").read_text()
assert upgrade_log.count("OK (1 test)") == 2 and "FAILURES!!!" not in upgrade_log
assert "INSTRUMENTATION_FAILED" not in upgrade_log
reused = (root / ".local/notification-rc2-validation.log").read_text()
assert "OK (7 tests)" in reused and "FAILURES!!!" not in reused
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
    "version": version, "versionCode": 5, "date": "2026-10-21",
    "channel": "stable", "device": "ARM64 Android 16 emulator, 1260x2800",
    "physicalDeviceVerified": False, "actualWechatNotificationVerified": False,
    "artifacts": artifacts, "signatureVerified": True, "zipAlignment16KB": True,
    "nativeLibraries": native, "credentialScanPassed": True,
    "upgrade": {"from": "0.2.0", "to": version, "passed": True,
                "evidence": ".local/stable-v021-upgrade.log"},
    "reusedFunctionalEvidence": {
        "reason": "Capture implementation unchanged from tested rc2; stable build changes version only.",
        "notificationAndroidChecks": 7, "notificationAndParserUnitChecks": 19,
        "documents": ["docs/CAPTURE_FIX_0.2.1.md", "docs/NOTIFICATION_FIX_0.2.1_RC2.md"]
    },
    "releaseDocument": "docs/RELEASE_0.2.1.md"
}
report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
sums_file.write_text("".join(f"{a['sha256']}  {a['file']}\n" for a in artifacts))
print(json.dumps({"artifacts": artifacts, "signatureVerified": True}, ensure_ascii=False))
