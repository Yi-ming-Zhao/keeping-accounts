#!/usr/bin/env python3
"""Create a local release signing identity once. Credentials never enter command arguments."""
from pathlib import Path
import os, secrets, subprocess, shutil
root=Path(__file__).resolve().parent.parent
local=root/'.local';local.mkdir(exist_ok=True)
config=local/'signing.properties'
if not config.exists():
    password=secrets.token_urlsafe(32)
    config.write_text('storeFile=.local/ledger-release.jks\nstorePassword='+password+'\nkeyPassword='+password+'\nkeyAlias=ledger\n')
    config.chmod(0o600)
    env=dict(os.environ,LEDGER_SIGN_PASSWORD=password)
    keytool=str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else (shutil.which('keytool') or '/opt/homebrew/opt/openjdk@17/bin/keytool')
    subprocess.run([keytool,'-genkeypair','-keystore',str(local/'ledger-release.jks'),'-storepass:env','LEDGER_SIGN_PASSWORD','-keypass:env','LEDGER_SIGN_PASSWORD','-alias','ledger','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Local Ledger, O=Local Ledger, C=CN'],env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    (local/'ledger-release.jks').chmod(0o600)
print('Local release signing identity ready')
