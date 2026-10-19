#!/usr/bin/env python3
"""Verify the exact release APK and signed 0.1.0 → 0.2.0 upgrade. Emulator only."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
base=[adb,'-s','emulator-5554']
def run(*args,check=True):
 r=subprocess.run(base+list(args),text=True,capture_output=True,check=check)
 return r.stdout+r.stderr
assert 'sdk_gphone' in run('shell','getprop','ro.product.model')
for pkg in ['dev.localledger.test','dev.localledger']:run('uninstall',pkg,check=False)
print(run('install',str(root/'artifacts/keeping-accounts-0.1.0-arm64.apk')))
print(run('install',str(root/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')))
def test(target,count):
 output=run('shell','am','instrument','-w','-e','class',target,'dev.localledger.test/androidx.test.runner.AndroidJUnitRunner')
 print(output)
 assert f'OK ({count} test' in output
# Seed with the OLD application's database code before replacing the APK.
test('dev.localledger.UpgradeTest#seedOldRelease',1)
print(run('install','-r',str(root/'app/build/outputs/apk/release/app-release.apk')))
test('dev.localledger.UpgradeTest#newReleasePreservesOldBook',1)
methods=['piExecutesAndRestoresEncryptedTranscript','timeoutCancelsHostAndPreservesRecoverableRun','explicitCancellationReleasesEngine','interruptedToolCommitDoesNotDuplicateWrite']
test(','.join('dev.localledger.PiRuntimeTest#'+m for m in methods),4)
test('dev.localledger.UiFlowTest',1)
test('dev.localledger.LedgerIntegrationTest#incomeTotalsAndDeferredAccountsUseTheirOwnSemantics,dev.localledger.LedgerIntegrationTest#completeItemAllocationChangesCategoryTotalsWithoutInventingDiscounts',2)
