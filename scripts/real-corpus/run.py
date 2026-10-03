#!/usr/bin/env python3
"""Run pinned real fixtures, one low-memory, no-network JVM per import.
Never opens office applications, executes source, or claims native renderer coverage.
"""
from __future__ import annotations
import argparse, collections, ctypes, errno, hashlib, json, os, pathlib, resource, shutil, signal, subprocess, sys, tempfile, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]
MANIFEST=ROOT/'corpus/manifest.json'
HEAP_MIB=160; RSS_MIB=320; WALL_SECONDS=15; CPU_SECONDS=12

def sha(b): return hashlib.sha256(b).hexdigest()
def local_path(name):
    p=(ROOT/name).resolve()
    if not p.is_relative_to(ROOT/'corpus'): raise ValueError('Manifest path escapes corpus')
    return p

def verify(manifest, fetch=False):
    records=list(manifest['fixtures'])
    for source in manifest['sources'].values():
        for item in source['license_files']: records.append({'local_path':item['path'],'source_url':item['url'],'sha256':item['sha256']})
    for row in records:
        p=local_path(row['local_path'])
        if fetch and not p.exists():
            url=row['source_url']
            if not url.startswith('https://raw.githubusercontent.com/'): raise ValueError('Unexpected source host')
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'alldoc-read-only-regression-corpus'}),timeout=40) as response: data=response.read(2*1024*1024+1)
            if len(data)>2*1024*1024 or sha(data)!=row['sha256']: raise ValueError('Pinned download size/hash mismatch: '+url)
            p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
        if sha(p.read_bytes())!=row['sha256']: raise ValueError('Fixture checksum mismatch: '+str(p))
    return len(records)

# The dynamic library is loaded before forking. The child filter is installed before
# execve(java); all inherited sockets are closed by Popen(close_fds=True).
def seccomp_setup():
    if sys.platform!='linux': raise RuntimeError('Linux libseccomp is required; refusing an unsandboxed run')
    lib=ctypes.CDLL('libseccomp.so.2',use_errno=True)
    lib.seccomp_init.argtypes=[ctypes.c_uint32];lib.seccomp_init.restype=ctypes.c_void_p
    lib.seccomp_rule_add.argtypes=[ctypes.c_void_p,ctypes.c_uint32,ctypes.c_int,ctypes.c_uint]
    lib.seccomp_syscall_resolve_name.argtypes=[ctypes.c_char_p];lib.seccomp_syscall_resolve_name.restype=ctypes.c_int
    lib.seccomp_load.argtypes=[ctypes.c_void_p];lib.seccomp_release.argtypes=[ctypes.c_void_p]
    calls=[]
    for name in ['socket','socketpair','socketcall','connect','bind','listen','accept','accept4','sendto','sendmsg','sendmmsg']:
        number=lib.seccomp_syscall_resolve_name(name.encode())
        if number>=0:calls.append(number)
    def child():
        os.setsid();resource.setrlimit(resource.RLIMIT_CPU,(CPU_SECONDS,CPU_SECONDS+1))
        resource.setrlimit(resource.RLIMIT_AS,(1536*1024*1024,1536*1024*1024))
        resource.setrlimit(resource.RLIMIT_FSIZE,(8*1024*1024,8*1024*1024))
        resource.setrlimit(resource.RLIMIT_CORE,(0,0));resource.setrlimit(resource.RLIMIT_NOFILE,(128,128))
        ctx=lib.seccomp_init(0x7fff0000)
        if not ctx:os._exit(124)
        for number in calls:
            if lib.seccomp_rule_add(ctx,0x00050000|errno.EPERM,number,0)!=0:os._exit(124)
        if lib.seccomp_load(ctx)!=0:os._exit(124)
        lib.seccomp_release(ctx)
    return child

def classpath():
    if os.environ.get('POI_CP'):return os.environ['POI_CP']
    bases=[pathlib.Path(x) for x in [os.environ.get('GRADLE_USER_HOME','/nonexistent'),str(pathlib.Path.home()/'.gradle'),'/workspace/shared/alldoc-gradle']]
    artifacts=[('org.apache.poi','poi','5.5.1'),('org.apache.poi','poi-scratchpad','5.5.1'),('commons-codec','commons-codec','1.20.0'),('org.apache.commons','commons-collections4','4.5.0'),('org.apache.commons','commons-math3','3.6.1'),('commons-io','commons-io','2.21.0'),('org.apache.logging.log4j','log4j-api','2.24.3'),('com.zaxxer','SparseBitSet','1.3')]
    jars=[]
    for group,artifact,version in artifacts:
        matches=[]
        for base in bases:matches+=list((base/'caches/modules-2/files-2.1'/group/artifact/version).glob('*/*.jar'))
        if not matches:
            # commons-io minor is pinned by the app's dependency resolution; allow
            # cached runtime version when a caller did not provide explicit POI_CP.
            if artifact=='commons-io':
                for base in bases:matches+=list((base/'caches/modules-2/files-2.1'/group/artifact).glob('*/*/*.jar'))
            if not matches:raise RuntimeError('Set POI_CP; missing cached dependency '+group+':'+artifact+':'+version)
        jars.append(str(sorted(matches)[-1]))
    return os.pathsep.join(jars)

def probe(cp,args,sandbox,outdir):
    command=['java',f'-Xmx{HEAP_MIB}m','-Xms16m','-Xss512k','-XX:MaxMetaspaceSize=80m','-XX:CompressedClassSpaceSize=16m','-XX:ReservedCodeCacheSize=32m','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:+DisableAttachMechanism',f'-Djava.io.tmpdir={outdir}','-cp',cp,'org.smallthingz.alldoc.core.RealCorpusProbe',*args]
    env={k:os.environ[k] for k in ['PATH','JAVA_HOME','LANG'] if k in os.environ}
    started=time.monotonic();peak=0;limit=None
    with tempfile.TemporaryFile() as stdout,tempfile.TemporaryFile() as stderr:
        process=subprocess.Popen(command,stdout=stdout,stderr=stderr,preexec_fn=sandbox,close_fds=True,env=env,cwd=outdir)
        while process.poll() is None:
            try:
                status=pathlib.Path(f'/proc/{process.pid}/status').read_text()
                rss=int(next(s for s in status.splitlines() if s.startswith('VmRSS:')).split()[1]);peak=max(peak,rss)
            except (FileNotFoundError,StopIteration,ProcessLookupError):pass
            if peak>RSS_MIB*1024:limit='RSS_LIMIT'
            if time.monotonic()-started>WALL_SECONDS:limit='TIMEOUT'
            if limit:os.killpg(process.pid,signal.SIGKILL);break
            time.sleep(.025)
        code=process.wait();stdout.seek(0);stderr.seek(0);text=stdout.read(1024*1024).decode('utf-8','replace');errors=stderr.read(12000).decode('utf-8','replace')
    try:result=json.loads(text.strip().splitlines()[-1])
    except (ValueError,IndexError):result={'harness':{'status':'FAIL','reason':'No valid JSON result','stdout':text[:3000]}}
    result['process']={'exit_code':code,'peak_rss_kib':peak,'wall_seconds':round(time.monotonic()-started,3),'limit':limit,'stderr':errors}
    return result

def verdict(fixture,result):
    p=result['process']
    if p['exit_code']!=0 or p['limit'] or result.get('harness',{}).get('status')=='FAIL':return 'FAIL'
    expectation=fixture['expected']['open'];opened=result['open']['status']
    if expectation=='android_only':return 'NOT_RUN'
    if expectation=='known_gap':return 'KNOWN_GAP' if opened=='REFUSED' else 'REVIEW_KNOWN_GAP_CHANGED'
    if expectation=='reject':return 'EXPECTED_REFUSAL' if opened=='REFUSED' else 'FAIL_UNEXPECTED_ACCEPT'
    if expectation=='bounded':return 'BOUNDED_REFUSAL' if opened=='REFUSED' else ('FAIL' if any(v.get('status')=='FAIL' for v in result.values() if isinstance(v,dict)) else 'BOUNDED_ACCEPT')
    if opened!='PASS':return 'FAIL_OPEN'
    if any(v.get('status')=='FAIL' for v in result.values() if isinstance(v,dict)):return 'FAIL'
    if fixture['expected'].get('all_blocks_read_only') and result['preview_model'].get('editable_blocks',0)!=0:return 'FAIL_PROTECTION'
    return 'PASS'

def main():
    p=argparse.ArgumentParser();p.add_argument('--fetch',action='store_true',help='Fetch only absent pinned files, verifying SHA-256 before writing');p.add_argument('--verify-only',action='store_true');p.add_argument('--only',help='Fixture ID substring');p.add_argument('--output',default='corpus/results/latest.json');p.add_argument('--mutations',action='store_true');args=p.parse_args()
    manifest=json.loads(MANIFEST.read_text());count=verify(manifest,args.fetch)
    if args.verify_only:print(f'Verified {len(manifest["fixtures"])} real fixtures and {count-len(manifest["fixtures"])} license files');return
    sandbox=seccomp_setup();deps=classpath()
    with tempfile.TemporaryDirectory(prefix='alldoc-real-corpus-') as tmp:
        build=pathlib.Path(tmp);source_dir=build/'snapshot';source_dir.mkdir();sources=[];source_hashes={}
        for src in sorted((ROOT/'app/src/main/java/org/smallthingz/alldoc/core').glob('*.java')):
            data=src.read_bytes();target=source_dir/src.name;target.write_bytes(data);sources.append(str(target));source_hashes[str(src.relative_to(ROOT))]=sha(data)
        harness=ROOT/'scripts/real-corpus/RealCorpusProbe.java';source_hashes[str(harness.relative_to(ROOT))]=sha(harness.read_bytes())
        subprocess.run(['javac','-J-Xmx256m','-cp',deps,'-d',str(build),*sources,str(harness)],check=True,timeout=90)
        cp=str(build)+os.pathsep+deps
        network=probe(cp,['--network-probe'],sandbox,tmp)
        if network.get('network_probe',{}).get('status')!='PASS':raise RuntimeError('Network-denial self-test failed: '+json.dumps(network))
        rows=list(manifest['fixtures']);ids={r['id']:r for r in rows}
        for alias in manifest.get('alias_probes',[]):
            base=ids[alias['fixture_id']];rows.append({**base,**alias,'is_alias':True})
        if args.mutations:
            from mutations import create_mutations
            rows+=create_mutations(manifest,build/'mutations')
        if args.only:rows=[r for r in rows if args.only in r['id']]
        results=[]
        for fixture in rows:
            path=pathlib.Path(fixture['local_path']);path=path if path.is_absolute() else ROOT/path
            outcome=probe(cp,[str(path),fixture.get('import_as',path.name)],sandbox,tmp);judgment=verdict(fixture,outcome)
            item={'id':fixture['id'],'format':fixture['format'],'source':fixture['source'],'sha256':fixture['sha256'],'expected':fixture['expected'],'verdict':judgment,'is_alias':fixture.get('is_alias',False),'is_mutation':fixture.get('is_mutation',False),**outcome};results.append(item)
            print(f'{judgment:24} {fixture["id"]} ({outcome["process"]["peak_rss_kib"]//1024} MiB)',flush=True)
        report={'schema_version':1,'utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'core_source_sha256':source_hashes,'dependency_jars':[{'name':pathlib.Path(j).name,'sha256':sha(pathlib.Path(j).read_bytes())} for j in deps.split(os.pathsep) if pathlib.Path(j).is_file()],'limits':{'heap_mib':HEAP_MIB,'rss_mib':RSS_MIB,'wall_seconds':WALL_SECONDS,'cpu_seconds':CPU_SECONDS,'address_space_mib':1536,'network':'libseccomp denies socket/network syscalls before Java startup'},'network_self_test':network,'counts':dict(collections.Counter(r['verdict'] for r in results)),'distinct_real_fixtures':sum(not r['is_alias'] and not r['is_mutation'] for r in results),'alias_probes':sum(r['is_alias'] for r in results),'mutation_probes':sum(r['is_mutation'] for r in results),'results':results}
        out=ROOT/args.output;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report['counts'],sort_keys=True));print(out)
        if any(r['verdict'].startswith('FAIL') for r in results):sys.exit(1)
if __name__=='__main__':main()
