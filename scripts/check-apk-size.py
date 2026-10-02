#!/usr/bin/env python3
"""Fail when a shipping APK exceeds the compact-app budget. Installed size is separate."""
import argparse,hashlib,json,pathlib,zipfile
p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('--max-bytes',type=int,default=5*1024*1024);a=p.parse_args()
f=pathlib.Path(a.apk)
with zipfile.ZipFile(f) as z:
    entries=z.infolist()
    report={'file':f.name,'apk_bytes':f.stat().st_size,'zip_expanded_bytes':sum(i.file_size for i in entries),'budget_bytes':a.max_bytes,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest(),'installed_bytes':None,'installed_note':'Not measured; ZIP expansion is not Android installed size.'}
    forbidden=[i.filename for i in entries if 'liblo-native-code' in i.filename or 'libandroidapp.so' in i.filename]
print(json.dumps(report,indent=2))
if forbidden:raise SystemExit('Heavy office engine is not permitted in the compact app: '+','.join(forbidden))
if report['apk_bytes']>a.max_bytes:raise SystemExit('APK exceeds compact-app budget')
