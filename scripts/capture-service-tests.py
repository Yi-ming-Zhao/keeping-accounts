#!/usr/bin/env python3
"""Exercise Android services via a separate fixture APK; emulator only."""
import os, subprocess, pathlib
root=pathlib.Path(__file__).resolve().parent.parent
adb=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')
def run(*args,check=True):
 return subprocess.run([adb,'-s','emulator-5554',*args],check=check,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout
assert 'sdk_gphone' in run('shell','getprop','ro.product.model')
result=run('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk'),check=False)
if 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' in result:
 run('uninstall','dev.localledger',check=False);run('uninstall','dev.localledger.test',check=False)
 print(run('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')))
elif 'Success' not in result: raise RuntimeError(result)
print(run('install','-r',str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')))
print(run('install','-r',str(root/'fixtures/build/outputs/apk/debug/fixtures-debug.apk')))
run('shell','wm','size','1260x2800');run('shell','wm','density','440')
result=run('shell','am','instrument','-w','-e','class','dev.localledger.CaptureServiceTest','dev.localledger.test/androidx.test.runner.AndroidJUnitRunner')
print(result)
assert 'OK (11 tests)' in result
