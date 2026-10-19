#!/usr/bin/env python3
"""Check the shrunk release on the dedicated emulator; replaces only this project's test installs."""
import os,subprocess,pathlib,sys
root=pathlib.Path(__file__).resolve().parents[1]
adb=os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
serial=os.environ.get('ANDROID_SERIAL','emulator-5554')
assert serial.startswith('emulator-'),'This script only resets the dedicated emulator install'
base=[adb,'-s',serial]
for package in ['dev.localledger.test','dev.localledger']:
 subprocess.run(base+['uninstall',package],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
for apk in ['app/build/outputs/apk/release/app-release.apk','app/build/outputs/apk/androidTest/release/app-release-androidTest.apk']:
 subprocess.run(base+['install','-r',str(root/apk)],check=True)
methods=['piExecutesAndRestoresEncryptedTranscript','timeoutCancelsHostAndPreservesRecoverableRun','explicitCancellationReleasesEngine','interruptedToolCommitDoesNotDuplicateWrite']
targets=','.join('dev.localledger.PiRuntimeTest#'+m for m in methods)
result=subprocess.run(base+['shell','am','instrument','-w','-e','class',targets,'dev.localledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
print(result.stdout+result.stderr)
if result.returncode or 'OK (4 tests)' not in result.stdout: sys.exit(1)
result=subprocess.run(base+['shell','am','instrument','-w','-e','class','dev.localledger.LedgerIntegrationTest#completeItemAllocationChangesCategoryTotalsWithoutInventingDiscounts','dev.localledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
(root/'.local/release-product-test.log').write_text(result.stdout+result.stderr)
print('Release product query: '+('PASS' if 'OK (1 test)' in result.stdout else 'FAIL'))
if result.returncode or 'OK (1 test)' not in result.stdout: sys.exit(1)
