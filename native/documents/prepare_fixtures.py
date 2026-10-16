#!/usr/bin/env python3
"""Owned deterministic document fixtures, plus independent LibreOffice legacy exports."""
import argparse, hashlib, json, pathlib, subprocess, zipfile
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--libreoffice",action="store_true",help="also generate independent legacy/OOXML exports with LibreOffice")
args=parser.parse_args()

ROOT=pathlib.Path('.tmp/native/documents/fixtures')
ROOT.mkdir(parents=True,exist_ok=True)
NS='xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" office:version="1.2"'
def flat(kind,body):
 return '<?xml version="1.0" encoding="UTF-8"?><office:document '+NS+' office:mimetype="application/vnd.oasis.opendocument.'+{'text':'text','spreadsheet':'spreadsheet','presentation':'presentation'}[kind]+'"><office:body><office:'+kind+'>'+body+'</office:'+kind+'></office:body></office:document>'
word=flat('text','<text:h text:outline-level="1">Native heading</text:h><text:p>Hello Ω 日本 😀</text:p><text:p>Second paragraph</text:p>')
sheet=flat('spreadsheet','<table:table table:name="Numbers"><table:table-row><table:table-cell office:value-type="string"><text:p>Cell Ω 日本 😀</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="42"><text:p>42</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell office:value-type="boolean" office:boolean-value="true"><text:p>TRUE</text:p></table:table-cell></table:table-row></table:table>')
slides=flat('presentation',''.join('<draw:page draw:name="Slide '+str(i)+'"><draw:frame svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="10cm"><draw:text-box><text:p>'+t+'</text:p></draw:text-box></draw:frame></draw:page>' for i,t in enumerate(['First slide Ω 日本 😀','Second slide'],1)))
for ext,data in [('fodt',word),('fods',sheet),('fodp',slides)]: (ROOT/('sample.'+ext)).write_text(data)
def package(name,parts):
 with zipfile.ZipFile(ROOT/name,'w',compression=zipfile.ZIP_DEFLATED) as z:
  for path,data in parts.items():z.writestr(path,data)
package('sample.odt',{'mimetype':'application/vnd.oasis.opendocument.text','content.xml':word})
package('sample.ods',{'mimetype':'application/vnd.oasis.opendocument.spreadsheet','content.xml':sheet})
package('sample.odp',{'mimetype':'application/vnd.oasis.opendocument.presentation','content.xml':slides})
w='<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Native heading</w:t></w:r></w:p><w:p><w:r><w:t>Hello Ω 日本 😀</w:t></w:r></w:p></w:body></w:document>'
package('small.docx',{'word/document.xml':w})
rels='<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{}</Relationships>'
package('ordered.pptx',{'ppt/presentation.xml':'<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst><p:sldId id="256" r:id="r2"/><p:sldId id="257" r:id="r1"/></p:sldIdLst></p:presentation>', 'ppt/_rels/presentation.xml.rels':rels.format('<Relationship Id="r1" Target="slides/slide1.xml"/><Relationship Id="r2" Target="slides/slide2.xml"/>'),**{'ppt/slides/slide'+str(i)+'.xml':'<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>'+t+'</a:t></a:r></a:p></p:sld>'for i,t in [(1,'First'),(2,'Second')]}})
package('sparse.xlsx',{'xl/workbook.xml':'<workbook xmlns:r="r"><sheets><sheet name="Sparse" r:id="r1"/></sheets></workbook>','xl/_rels/workbook.xml.rels':rels.format('<Relationship Id="r1" Target="worksheets/sheet1.xml"/>'),'xl/worksheets/sheet1.xml':'<worksheet><dimension ref="A1:XFD1048576"/><sheetData><row r="1048576"><c r="XFD1048576" t="inlineStr"><is><t>Far corner Ω 😀</t></is></c></row></sheetData></worksheet>'})
(ROOT/'unicode.rtf').write_bytes(b'{\\rtf1\\ansi\\ansicpg1252\\uc1 Hello \\u937? \\u-10179?\\u-8704?\\par Caf\\\'e9}')
package('traversal.docx',{'../evil':'x','word/document.xml':w})
package('dtd.docx',{'word/document.xml':'<!DOCTYPE document [<!ENTITY x "boom">]>'+w})
package('entity.docx',{'word/document.xml':w.replace('Hello','&unknown;')})
package('depth.docx',{'word/document.xml':'<document>'+'<a>'*65+'x'+'</a>'*65+'</document>'})
(ROOT/'depth.rtf').write_bytes(b'{\\rtf1 '+b'{'*65+b'x'+b'}'*66)
package('crc.docx',{'word/document.xml':w})
data=bytearray((ROOT/'crc.docx').read_bytes()); p=data.index(b'PK\x01\x02'); data[p+16]^=1; data[14]^=1;(ROOT/'crc.docx').write_bytes(data)
package('oversize.docx',{'word/document.xml':'<document>'+('x'*(8*1024*1024))+'</document>'})
repeat=flat('spreadsheet','<table:table table:name="Repeated"><table:table-row table:number-rows-repeated="2"><table:table-cell table:number-columns-repeated="2" office:value-type="string"><text:p>R</text:p></table:table-cell></table:table-row></table:table>')
(ROOT/'repeat.fods').write_text(repeat)
(ROOT/'repeat-limit.fods').write_text(repeat.replace('number-rows-repeated="2"','number-rows-repeated="4000000000"'))
(ROOT/'malformed.doc').write_bytes(bytes.fromhex('d0cf11e0a1b11ae1')+bytes(504))
# An isolated LibreOffice profile touches no existing user installation or device.
profile=ROOT/'lo-profile'; output=ROOT/'libreoffice';output.mkdir(exist_ok=True)
receipts=[]
for source,target in ([('fodt','doc:MS Word 97'),('fodt','docx:Office Open XML Text'),('fods','xls:MS Excel 97'),('fods','xlsx:Calc MS Excel 2007 XML'),('fodp','ppt:MS PowerPoint 97'),('fodp','pptx:Impress MS PowerPoint 2007 XML')] if args.libreoffice else []):
 dest=output/('sample.'+target.split(':')[0])
 if not dest.exists():
  command=['libreoffice','-env:UserInstallation='+profile.resolve().as_uri(),'--headless','--convert-to',target,'--outdir',str(output),str(ROOT/('sample.'+source))]
  result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=60)
  receipts.append({'command':command,'exit':result.returncode,'output':result.stdout})
  if result.returncode or not dest.exists():raise RuntimeError(receipts[-1])
(ROOT/'conversion.json').write_text(json.dumps(receipts,indent=2)+'\n')
pins={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in sorted(ROOT.rglob('*'))if p.is_file()and 'lo-profile'not in p.parts and p.name!='pins.json'}
(ROOT/'pins.json').write_text(json.dumps(pins,indent=2)+'\n')
print('FIXTURES_READY',len(pins),'files; LibreOffice exports requested:',args.libreoffice,flush=True)
