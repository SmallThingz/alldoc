"""Deterministic, small mutations of pinned real files. No amplification bombs."""
import hashlib, io, pathlib, random, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]

def create_mutations(manifest,out):
    out.mkdir(parents=True,exist_ok=True);fixtures={r['id']:r for r in manifest['fixtures']};rows=[]
    def add(seed_id,suffix,data,policy='reject',reason='Malformed/security-significant input must be refused'):
        seed=fixtures[seed_id];ext=seed['format'];name='mutation-'+suffix+'.'+ext;p=out/name;p.write_bytes(data)
        rows.append({'id':name,'local_path':str(p),'source':seed['source'],'format':ext,'sha256':hashlib.sha256(data).hexdigest(),'seed_id':seed_id,'seed_sha256':seed['sha256'],'is_mutation':True,'expected':{'open':policy,'reason':reason}})
    def raw(id):return (ROOT/fixtures[id]['local_path']).read_bytes()
    def package(id,mutator):
        b=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(raw(id))) as before,zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as after:
            members=[(x.filename,before.read(x)) for x in before.infolist()]
            for path,data in mutator(members):after.writestr(path,data)
        return b.getvalue()
    docx='poi-SimpleHeadThreeColFoot.docx';xlsx='poi-shared_formulas.xlsx';rtf='libreoffice-hello.rtf';ole='poi-HeaderFooterUnicode.doc'
    add(docx,'duplicate-member',package(docx,lambda m:m+[next(x for x in m if x[0]=='word/document.xml')]))
    add(docx,'path-traversal',package(docx,lambda m:m+[('../outside.xml',b'<root/>')]))
    add(docx,'external-entity',package(docx,lambda m:[(p,(b'<!DOCTYPE x [<!ENTITY leak SYSTEM "http://127.0.0.1:9/alldoc-no-network">]><x>&leak;</x>') if p=='word/document.xml' else b) for p,b in m]))
    add(docx,'half-truncated-zip',raw(docx)[:len(raw(docx))//2])
    # All actual part bytes are otherwise retained; neither data source nor model
    # is replaced with a fully synthetic package.
    import re
    add(xlsx,'out-of-range-cell',package(xlsx,lambda m:[(p,re.sub(rb'<c\b[^>]*\br="[A-Z]+[0-9]+"',b'<c r="XFE1048577"',b,count=1) if p.startswith('xl/worksheets/sheet') and p.endswith('.xml') else b) for p,b in m]))
    add(rtf,'rtf-unbalanced',raw(rtf)[:-1])
    add(rtf,'rtf-deep-groups',raw(rtf)[:6]+b'{'*200+raw(rtf)[6:-1]+b'}'*201)
    add(ole,'ole-half-truncated',raw(ole)[:len(raw(ole))//2])
    add(ole,'ole-invalid-magic',b'BADMAGIC'+raw(ole)[8:])
    # Fixed-seed byte flips are bounded robustness probes, not semantic-correctness
    # proofs. Acceptance and ordinary checked refusal are both valid outcomes.
    rng=random.Random(0xA11D0C)
    for seed_id in [docx,xlsx,rtf,ole]:
        for n in range(2):
            data=bytearray(raw(seed_id));position=rng.randrange(len(data));data[position]^=1<<rng.randrange(8)
            add(seed_id,'flip-'+seed_id.rsplit('.',1)[0]+'-'+str(n),bytes(data),'bounded','Either safe parsing or an ordinary refusal is acceptable; crash/timeout/OOM/export corruption is not')
    return rows
