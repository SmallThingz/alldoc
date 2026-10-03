#!/usr/bin/env python3
"""Synthetic, redistributable presentation fidelity fixtures. Requires Pillow only to regenerate JPEG."""
from pathlib import Path
import base64, io, struct, zipfile, zlib
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]/'fixtures'
A='http://schemas.openxmlformats.org/drawingml/2006/main'
P='http://schemas.openxmlformats.org/presentationml/2006/main'
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
def png():
    def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b))
    w,h=320,180
    rows=b''.join(b'\0'+bytes(v for x in range(w) for v in (28+x//2,90+y//2,130+(x+y)//8,255)) for y in range(h))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')
image=png(); jpg=io.BytesIO(); Image.open(io.BytesIO(image)).convert('RGB').save(jpg,format='JPEG',quality=80)
(ROOT/'presentation-raster.jpg').write_bytes(jpg.getvalue())
def bounds(x,y,w,h):return f'<a:xfrm><a:off x="{x*12700}" y="{y*12700}"/><a:ext cx="{w*12700}" cy="{h*12700}"/></a:xfrm>'
def shape(i,x,y,w,h,text='',size=26,color='FFFFFF',fill=None,bold=False):
    colorfill=f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>' if fill else '<a:noFill/>'
    txt=f'<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="{size*100}" b="{int(bold)}"><a:solidFill><a:srgbClr val="{color}"/></a:solidFill><a:latin typeface="sans-serif"/></a:rPr><a:t>{text}</a:t></a:r></a:p></p:txBody>' if text else ''
    return f'<p:sp><p:nvSpPr><p:cNvPr id="{i}" name="Shape {i}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>{bounds(x,y,w,h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>{colorfill}</p:spPr>{txt}</p:sp>'
def picture(i,crop=''):
    return f'<p:pic><p:nvPicPr><p:cNvPr id="{i}" name="Embedded gradient"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="image"/>{crop}<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>{bounds(530,150,360,202)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>'
def slide(body):return f'<p:sld xmlns:p="{P}" xmlns:a="{A}" xmlns:r="{R}"><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="102332"/></a:solidFill></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>{body}</p:spTree></p:cSld></p:sld>'
def rels(body):return '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+body+'</Relationships>'
def rel(i,t,target):return f'<Relationship Id="{i}" Type="{R}/{t}" Target="{target}"/>'
parts={
 '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'+''.join(f'<Override PartName="/ppt/slides/slide{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>' for i in (1,2))+'</Types>',
 '_rels/.rels':rels(rel('office','officeDocument','ppt/presentation.xml')),
 'ppt/presentation.xml':f'<p:presentation xmlns:p="{P}" xmlns:r="{R}"><p:sldIdLst><p:sldId id="256" r:id="slide1"/><p:sldId id="257" r:id="slide2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>',
 'ppt/_rels/presentation.xml.rels':rels(rel('slide1','slide','slides/slide1.xml')+rel('slide2','slide','slides/slide2.xml')),
 'ppt/media/gradient.png':image,
 'ppt/slides/slide1.xml':slide(shape(2,44,42,420,48,'A closer look',36,bold=True)+shape(3,44,124,432,252,fill='1D4859')+shape(4,64,154,385,110,'Real styles. Embedded images.',30,bold=True)+shape(5,64,282,360,58,'Editable text stays in its source runs.',18,color='B7DCE4')+picture(6)+shape(7,44,445,850,32,'Synthetic fixture: solid fills, run styles, PNG, bounds and layer order.',16,color='9EC2D3')),
 'ppt/slides/slide2.xml':slide(shape(2,44,42,850,70,'Truthful limits',36,bold=True)+shape(3,44,154,430,160,'This cropped picture must show an omission marker, never an uncropped substitute.',24)+picture(4,'<a:srcRect l="25000"/>')),
}
for i in (1,2):parts[f'ppt/slides/_rels/slide{i}.xml.rels']=rels(rel('image','image','../media/gradient.png'))
with zipfile.ZipFile(ROOT/'presentation-fidelity.pptx','w',zipfile.ZIP_DEFLATED) as z:
 for k,v in parts.items():z.writestr(k,v)
ns='xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink"'
styles='<office:automatic-styles><style:style style:name="title" style:family="paragraph"><style:text-properties fo:font-size="32pt" fo:font-weight="bold" fo:color="#FFFFFF"/></style:style><style:style style:name="bg" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#102332"/></style:style><style:page-layout style:name="page"><style:page-layout-properties fo:page-width="960pt" fo:page-height="540pt"/></style:page-layout></office:automatic-styles><office:master-styles><style:master-page style:name="master" style:page-layout-name="page"/></office:master-styles>'
def odf(raster):return f'<office:document {ns} office:version="1.3">{styles}<office:body><office:presentation><draw:page draw:name="Styled ODF slide" draw:style-name="bg" draw:master-page-name="master"><draw:frame svg:x="44pt" svg:y="42pt" svg:width="850pt" svg:height="60pt"><draw:text-box><text:p text:style-name="title">Styles and embedded raster images</text:p></draw:text-box></draw:frame><draw:frame svg:x="44pt" svg:y="150pt" svg:width="640pt" svg:height="360pt">{raster}</draw:frame></draw:page></office:presentation></office:body></office:document>'
(ROOT/'presentation-fidelity.fodp').write_text(odf('<draw:image><office:binary-data>'+base64.b64encode(image).decode()+'</office:binary-data></draw:image>'))
with zipfile.ZipFile(ROOT/'presentation-fidelity.odp','w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('mimetype','application/vnd.oasis.opendocument.presentation',compress_type=zipfile.ZIP_STORED)
 z.writestr('content.xml',odf('<draw:image xlink:href="Pictures/gradient.png"/>'))
 z.writestr('Pictures/gradient.png',image)
 z.writestr('META-INF/manifest.xml','<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.presentation"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="Pictures/gradient.png" manifest:media-type="image/png"/></manifest:manifest>')
print('Wrote synthetic presentation fixtures')
