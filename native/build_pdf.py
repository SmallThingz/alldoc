#!/usr/bin/env python3
"""Build a bounded PDF text and contents worker, separate from Android's page renderer."""
import argparse, json, os, shutil, subprocess
from pathlib import Path
from verify_elf import verify_elf
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('abis', nargs='*', choices=['arm64-v8a','x86_64','host'])
p.add_argument('--ndk', default='../../.android/sdk/ndk/29.0.14206865')
p.add_argument('--jobs',type=int,default=2)
a=p.parse_args()
if not 1 <= a.jobs <= 8: p.error('jobs must be 1..8')
cache=Path('.tmp/native/pdf');cache.mkdir(parents=True,exist_ok=True)
tools=Path(a.ndk)/'toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi in a.abis or ['arm64-v8a','x86_64']:
 env=os.environ.copy();env.pop('CARGO_ENCODED_RUSTFLAGS', None);env.pop('RUSTFLAGS', None)
 env['CARGO_TARGET_DIR']=str((cache/'target').resolve())
 command=['cargo','build','--locked','--release','--manifest-path','native/pdf/Cargo.toml','-j',str(a.jobs)]
 if abi=='host':
  artifact=cache/'target/release/allpdf';destination=cache/'allpdf'
 else:
  target='aarch64-linux-android' if abi=='arm64-v8a' else 'x86_64-linux-android'
  env['CARGO_TARGET_'+target.upper().replace('-','_')+'_LINKER']=str((tools/(target+'28-clang')).resolve())
  env['RUSTFLAGS']='-C link-arg=-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384'
  command+=['--target',target]
  artifact=cache/'target'/target/'release/allpdf'
  destination=Path('app/src/main/jniLibs')/abi/'liballpdf.so'
 subprocess.run(command,env=env,check=True)
 destination.parent.mkdir(parents=True,exist_ok=True)
 shutil.copy2(artifact,destination)
 if abi != 'host': verify_elf(destination, abi, executable=True)
 print(f'{destination}: {destination.stat().st_size} bytes',flush=True)
