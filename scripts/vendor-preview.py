#!/usr/bin/env python3
"""Reproduce pinned preview assets from known npm tarballs, with SRI verification."""
import base64, hashlib, io, json, pathlib, tarfile, urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
for name,filename in [('docx-preview','docx-preview.min.js'),('jszip','jszip.min.js')]:
    record=json.loads((root/'third-party'/f'{name}-provenance.json').read_text())
    with urllib.request.urlopen(record['url'],timeout=60) as response: archive=response.read()
    integrity='sha512-'+base64.b64encode(hashlib.sha512(archive).digest()).decode()
    if integrity!=record['integrity']: raise SystemExit(f'{name}: archive integrity mismatch')
    with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as package:
        payload=package.extractfile('package/dist/'+filename).read()
    if hashlib.sha256(payload).hexdigest()!=record['asset_sha256']: raise SystemExit(f'{name}: asset hash mismatch')
    (root/'app/src/main/assets/preview'/filename).write_bytes(payload)
    print(name,record['version'],len(payload),'bytes, verified')
