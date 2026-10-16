#!/usr/bin/env python3
"""Build the bounded semantic JNI core; no dependencies on a ZFM checkout."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from verify_elf import verify_elf

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('abis', nargs='*', choices=['arm64-v8a', 'x86_64', 'host'])
parser.add_argument('--ndk', default='../../.android/sdk/ndk/29.0.14206865')
parser.add_argument('--jobs', type=int, default=2)
args = parser.parse_args()
if not 1 <= args.jobs <= 8:
    parser.error('jobs must be 1..8')
cache = Path('.tmp/native/documents')
cache.mkdir(parents=True, exist_ok=True)
ndk = Path(args.ndk)
tools = ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi in args.abis or ['arm64-v8a', 'x86_64']:
    env = os.environ.copy()
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    env['CARGO_TARGET_DIR'] = str(cache.resolve()/'target')
    env['RUSTFLAGS'] = '-C link-arg=-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384 -C link-arg=-Wl,-soname,liballdoc.so'
    command = ['cargo','build','--locked','--release','--manifest-path','native/documents/Cargo.toml','-j',str(args.jobs)]
    if abi == 'host':
        javac = Path(shutil.which('javac')).resolve()
        env['ALLDOC_DOC_JNI_INCLUDE'] = str(javac.parent.parent/'include')
        env['ALLDOC_DOC_CC'] = 'cc'; env['ALLDOC_DOC_AR'] = 'ar'
        artifact = cache/'target/release/liballdoc.so'
        destination = cache/'liballdoc.so'
    else:
        target = 'aarch64-linux-android' if abi == 'arm64-v8a' else 'x86_64-linux-android'
        env['ALLDOC_DOC_CC'] = str((tools/(target+'28-clang')).resolve())
        env['ALLDOC_DOC_AR'] = str((tools/'llvm-ar').resolve())
        env.pop('ALLDOC_DOC_JNI_INCLUDE',None)
        env['CARGO_TARGET_'+target.upper().replace('-','_')+'_LINKER'] = env['ALLDOC_DOC_CC']
        command += ['--target',target]
        artifact = cache/'target'/target/'release/liballdoc.so'
        destination = Path('app/src/main/jniLibs')/abi/'liballdoc.so'
    subprocess.run(command, env=env, check=True)
    destination.parent.mkdir(parents=True,exist_ok=True)
    if abi == 'host': shutil.copy2(artifact,destination)
    else: subprocess.run([str(tools/'llvm-strip'),'--strip-unneeded','-o',str(destination),str(artifact)],check=True)
    if abi != 'host': verify_elf(destination, abi, executable=False)
    print(f'{destination}: {destination.stat().st_size} bytes',flush=True)
