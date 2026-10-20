#!/usr/bin/env python3
"""Check the signed capture candidate and 0.2.0 upgrade on an emulator only.

Build with :app:assembleRelease :app:assembleReleaseAndroidTest -PtestRelease
and :fixtures:assembleDebug first. This erases only the emulator's app data.
No third-party payment applications or real transactions are involved.
"""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parents[1]
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
base = [adb, '-s', 'emulator-5554']
metadata = json.loads((root / 'app/build/outputs/apk/release/output-metadata.json').read_text())
new_version = metadata['elements'][0]['versionName']


def run(*args, check=True):
    result = subprocess.run(base + list(args), text=True, capture_output=True, check=check)
    return result.stdout + result.stderr


assert 'sdk_gphone' in run('shell', 'getprop', 'ro.product.model')
for package in ['dev.localledger.test', 'dev.localledger']:
    run('uninstall', package, check=False)
print(run('install', str(root / 'artifacts/keeping-accounts-0.2.0-arm64.apk')), flush=True)
print(run('install', str(root / 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')), flush=True)
print(run('install', '-r', str(root / 'fixtures/build/outputs/apk/debug/fixtures-debug.apk')), flush=True)


def test(target, count, *extras):
    output = run('shell', 'am', 'instrument', '-w', *extras, '-e', 'class', target,
                 'dev.localledger.test/androidx.test.runner.AndroidJUnitRunner')
    print(output, flush=True)
    assert f'OK ({count} test' in output


test('dev.localledger.UpgradeTest#seedOldRelease', 1, '-e', 'oldVersion', '0.2.0')
print(run('install', '-r', str(root / 'app/build/outputs/apk/release/app-release.apk')), flush=True)
test('dev.localledger.UpgradeTest#newReleasePreservesOldBook', 1, '-e', 'newVersion', new_version)
# Exercise the optimized APK through Android's actual services.
test('dev.localledger.CaptureServiceTest', 11)
test('dev.localledger.NoticeReaderTest', 2)
test('dev.localledger.CaptureStatusUiTest', 1)
