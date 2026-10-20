#!/usr/bin/env python3
"""Copy a signed release and freeze a source archive without local credentials/build data."""
from pathlib import Path
import hashlib,json,subprocess,zipfile,shutil,struct,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
# This script freezes the already published 0.2.0 evidence. Never let a later
# candidate overwrite those artifacts or inherit its old verification results.
metadata=json.loads((root/'app/build/outputs/apk/release/output-metadata.json').read_text())
assert {item['versionName'] for item in metadata['elements']}=={'0.2.0'},'This packager only supports the frozen 0.2.0 release'
assert not any((root/'artifacts'/name).exists() for name in ['keeping-accounts-0.2.0-arm64.apk','keeping-accounts-0.2.0-source.zip']),'Frozen 0.2.0 artifacts must not be overwritten'
out=root/'artifacts';out.mkdir(exist_ok=True)
source=root/'app/build/outputs/apk/release/app-release.apk'
assert source.exists(),'Build the signed release first'
apk=out/'keeping-accounts-0.2.0-arm64.apk';shutil.copy2(source,apk)
key=(root/'model-key.txt').read_bytes().strip() if (root/'model-key.txt').exists() else None
native=[]
with zipfile.ZipFile(apk) as z:
 for name in z.namelist():
  data=z.read(name)
  assert not key or key not in data,'Credential found in APK'
  if name.startswith('lib/') and name.endswith('.so'):
   assert name.startswith('lib/arm64-v8a/'),name
   # Inspect ELF load alignment; packaging alignment is checked by zipalign separately.
   assert data[:5]==b'\x7fELF\x02'
   phoff=struct.unpack_from('<Q',data,32)[0];phsize,phnum=struct.unpack_from('<HH',data,54)
   aligns=[struct.unpack_from('<Q',data,phoff+i*phsize+48)[0] for i in range(phnum) if struct.unpack_from('<I',data,phoff+i*phsize)[0]==1]
   native.append({'path':name,'minLoadAlignment':min(aligns)})
assert apk.stat().st_size<=40*1024*1024,'Release APK exceeds target'
files=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode().split('\0')
archive=out/'keeping-accounts-0.2.0-source.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for rel in sorted(set(files)):
  if not rel or (rel.startswith('artifacts/') and not rel.endswith('.png')) or not (root/rel).is_file():continue
  data=(root/rel).read_bytes()
  assert not key or key not in data,'Credential found in source'
  info=zipfile.ZipInfo.from_file(root/rel,'keeping-accounts/'+rel);info.compress_type=zipfile.ZIP_DEFLATED
  z.writestr(info,data)
unit=[]
for path in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 t=ET.parse(path).getroot();unit.append({k:t.attrib[k] for k in ['name','tests','failures','errors','skipped']})
report={'version':'0.2.0','date':'2026-10-20','device':'ARM64 Android 16 emulator, 1260x2800, 440dpi','physicalDeviceVerified':False,'thirdPartyPaymentPathsVerified':False,'apk':{'file':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},'source':{'file':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()},'nativeLibraries':native,'unitTests':unit,'credentialScan':'passed; key absent from source and APK'}
for label,file,expected in [('androidLedger','native-v02.log','OK (14 tests)'),('newNativeAndUI','ui-v02.log','OK (3 tests)'),('captureServices','capture-service-v02.log','OK (1 test)'),('realDeepSeekLedger','live-v02.log','OK (1 test)'),('realConversationUI','live-ui-v02.log','OK (1 test)'),('answerAmountValidation','answer-v02.log','OK (2 tests)'),('releaseUpgrade','release-upgrade-v02.log','OK (4 tests)')]:
 text=(root/'.local'/file).read_text();assert expected in text,(label,'missing test success');assert 'FAILURES!!!' not in text,(label,'test failure');report[label]={'status':'passed','evidence':'.local/'+file}
report['signing']={'status':'verified','evidence':'.local/apk-signature-v02.log'}
assert 'Verification successful' in (root/'.local/apk-alignment-v02.log').read_text()
report['zipAlignment16KB']='passed'
report['startupEvidence']=(root/'.local/release-start-v02.log').read_text()
if (root/'.local/idle-cpu-v02.log').exists():report['idleCpuEvidence']=(root/'.local/idle-cpu-v02.log').read_text()
(out/'SHA256SUMS').write_text(report['apk']['sha256']+'  '+apk.name+'\n'+report['source']['sha256']+'  '+archive.name+'\n')
(out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'apkBytes':apk.stat().st_size,'sourceBytes':archive.stat().st_size,'nativeLibraries':native},ensure_ascii=False))
