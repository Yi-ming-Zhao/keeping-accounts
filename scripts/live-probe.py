#!/usr/bin/env python3
"""Inject credentials through stdin into the debug app's private storage; never log them."""
import json, os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parents[1]
adb=os.environ.get('ADB', str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
serial=os.environ.get('ANDROID_SERIAL','emulator-5554')
base=[adb,'-s',serial]
config=json.loads((root/'model-config.local.json').read_text())
config['apiKey']=(root/'model-key.txt').read_text().strip()
os.chmod(root/'model-key.txt',0o600)
subprocess.run(base+['shell','run-as','dev.localledger','mkdir','-p','files'],check=True,stdout=subprocess.DEVNULL)
try:
    subprocess.run(base+['exec-in','run-as','dev.localledger','tee','files/probe-config.json'],input=json.dumps(config).encode(),check=True,stdout=subprocess.DEVNULL)
    subprocess.run(base+['shell','run-as','dev.localledger','chmod','600','files/probe-config.json'],check=True,stdout=subprocess.DEVNULL)
    result=subprocess.run(base+['shell','am','instrument','-w','-e','class',os.environ.get('TEST_METHOD','dev.localledger.PiRuntimeTest#liveDeepSeekToolAndRecovery'),'dev.localledger.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True)
    # Never print a provider exception body or credential material.
    output=result.stdout+result.stderr
    output=output.replace(config['apiKey'],'[REDACTED]')
    print(output)
    if result.returncode or 'OK (1 test)' not in output: sys.exit(1)
finally:
    subprocess.run(base+['shell','run-as','dev.localledger','rm','-f','files/probe-config.json'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
