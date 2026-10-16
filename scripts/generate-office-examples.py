#!/usr/bin/env python3
"""Regenerate AllDoc's authored Office examples (stdlib + development LibreOffice).

Run from the repository root. No document data is downloaded. The iWork examples
exercise saved PDF previews only, not Apple's editable document representation.
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import zipfile
from xml.etree import ElementTree as ET

OUT = pathlib.Path('app/src/main/assets/examples/office')
WORK = pathlib.Path('.tmp/examples-office')
OUT.mkdir(parents=True, exist_ok=True)
WORK.mkdir(parents=True, exist_ok=True)
EXAMPLES = []
RECEIPTS = []


def package(path, parts, stored=()):
    with zipfile.ZipFile(path, 'w') as archive:
        for name, data in parts.items():
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if name in stored else zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)


def record(ext, title, mime, category='Documents', description=''):
    path = OUT / ('office-guide.' + ext)
    EXAMPLES.append(dict(id='office-' + ext, title=title, asset='examples/office/' + path.name,
                         mime=mime, extension=ext, category=category, description=description))
    return path


def pdf(title, pages=3):
    objects = [b'', b'<< /Type /Pages /Kids [' + b' '.join(f'{4 + n*2} 0 R'.encode() for n in range(pages)) + b'] /Count ' + str(pages).encode() + b' >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    colors = [(0.12, 0.36, 0.46), (0.56, 0.23, 0.16), (0.26, 0.34, 0.58)]
    for i in range(pages):
        color = ' '.join(map(str, colors[i % len(colors)]))
        lines = [title, f'AllDoc / Page {i+1} of {pages}', 'Scroll vertically in Normal view.',
                 'Switch to Present for one page at a time.', 'Your reading position stays with this document.']
        commands = [f'{color} rg 0 580 600 220 re f', '1 1 1 rg', 'BT /F1 28 Tf 42 720 Td (' + lines[0] + ') Tj ET',
                    '0.12 0.14 0.17 rg']
        for row, text in enumerate(lines[1:]):
            commands.append(f'BT /F1 16 Tf 42 {530-row*42} Td ({text}) Tj ET')
        stream = '\n'.join(commands).encode('ascii')
        objects.extend([f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 3 0 R >> >> /Contents {5+i*2} 0 R >>'.encode(),
                        b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream'])
    objects[0] = b'<< /Type /Catalog /Pages 2 0 R >>'
    result = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n')
    start = len(result)
    result.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    result.extend(b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:]))
    result.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    return bytes(result)


NS = ('xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
      'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
      'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
      'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
      'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
      'xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" '
      'xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" '
      'xmlns:dc="http://purl.org/dc/elements/1.1/" '
      'xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"')
COMMENT = '<office:annotation><dc:creator>AllDoc</dc:creator><dc:date>2026-01-01T12:00:00</dc:date><text:p>Example review comment.</text:p></office:annotation>'
STYLES = ('<office:styles><style:default-style style:family="paragraph"><style:text-properties fo:font-size="18pt"/></style:default-style></office:styles>'
          '<office:automatic-styles><style:style style:name="Heading" style:family="paragraph"><style:text-properties fo:font-size="28pt" fo:font-weight="bold" fo:color="#235d75"/></style:style>'
          '<style:style style:name="PageBreak" style:family="paragraph"><style:paragraph-properties fo:break-before="page"/></style:style>'
          '<style:page-layout style:name="Landscape"><style:page-layout-properties fo:page-width="28cm" fo:page-height="15.75cm" style:print-orientation="landscape"/></style:page-layout>'
          '</office:automatic-styles><office:master-styles><style:master-page style:name="Default" style:page-layout-name="Landscape"/></office:master-styles>')
WORD = ('<text:h text:style-name="Heading" text:outline-level="1">AllDoc field guide</text:h>'
        '<text:p>Five minutes outside. A notebook, a map, and a good place to read.' + COMMENT + '</text:p>'
        '<text:p>Normal view scrolls from top to bottom. Present view shows one page at a time.</text:p>'
        '<text:p text:style-name="PageBreak">Second page</text:p><text:p>Keep a short reading list. Mark the ideas worth revisiting.</text:p>'
        '<text:p>Unicode: café, Ω, 日本, क्षि, 😀.</text:p>')
SHEET = '<table:table table:name="Reading plan">' + ''.join('<table:table-row>' + ''.join(
    f'<table:table-cell office:value-type="string"><text:p>{cell}</text:p></table:table-cell>' for cell in row) + '</table:table-row>'
    for row in [('AllDoc reading plan', 'Minutes', 'Status'), ('Field guide', '15', 'Read'), ('Research notes', '30', 'Next'), ('Short story', '20', 'Saved')]) + '</table:table>'
SLIDE_TITLES = ['AllDoc field guide', 'Read without interruption', 'One page at a time', 'Keep the useful details', 'Make room for questions']
SLIDE_BODIES = ['Five pages. Two ways to read.', 'Normal view scrolls from top to bottom.', 'Present view snaps to a single page.', 'Notes and comments stay beside the document.', 'Return to any page and carry on reading.']


def slide(i, drawing=False):
    notes = '' if drawing else ('<presentation:notes><draw:frame presentation:class="notes" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="5cm"><draw:text-box><text:p>'
                                f'Speaker note {i+1}: {SLIDE_BODIES[i]}</text:p></draw:text-box></draw:frame></presentation:notes>')
    return (f'<draw:page draw:name="Page {i+1}" draw:master-page-name="Default">'
            '<draw:frame svg:x="1.5cm" svg:y="2cm" svg:width="24cm" svg:height="3cm"><draw:text-box>'
            f'<text:p text:style-name="Heading">{SLIDE_TITLES[i]}</text:p></draw:text-box></draw:frame>'
            '<draw:frame svg:x="1.5cm" svg:y="6cm" svg:width="24cm" svg:height="5cm"><draw:text-box>'
            f'<text:p>{SLIDE_BODIES[i]}</text:p><text:p>AllDoc / {i+1} of 5</text:p></draw:text-box></draw:frame>' + notes + '</draw:page>')


BODIES = {'text': WORD, 'spreadsheet': SHEET, 'presentation': ''.join(slide(i) for i in range(5)),
          'drawing': ''.join(slide(i, True) for i in range(2))}
ODF_BASE = {'text': 'text', 'spreadsheet': 'spreadsheet', 'presentation': 'presentation', 'drawing': 'graphics'}


def odf_xml(kind, flat=False):
    tag = 'document' if flat else 'document-content'
    mime = f' office:mimetype="application/vnd.oasis.opendocument.{ODF_BASE[kind]}"' if flat else ''
    styles = STYLES if flat else STYLES[STYLES.index('<office:automatic-styles>'):STYLES.index('</office:automatic-styles>')+len('</office:automatic-styles>')]
    return f'<?xml version="1.0" encoding="UTF-8"?><office:{tag} {NS} office:version="1.2"{mime}>{styles}<office:body><office:{kind}>{BODIES[kind]}</office:{kind}></office:body></office:{tag}>'


def odf(ext, kind, suffix):
    mime = 'application/vnd.oasis.opendocument.' + suffix
    path = record(ext, 'Field guide' if kind == 'text' else 'Reading plan' if kind == 'spreadsheet' else 'Reading slides' if kind == 'presentation' else 'Reading cards', mime,
                  'Spreadsheets' if kind == 'spreadsheet' else 'Presentations' if kind in ('presentation', 'drawing') else 'Documents')
    manifest = ('<?xml version="1.0"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">'
                f'<manifest:file-entry manifest:full-path="/" manifest:media-type="{mime}"/>'
                '<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/></manifest:manifest>')
    package(path, {'mimetype': mime, 'content.xml': odf_xml(kind), 'styles.xml': f'<?xml version="1.0"?><office:document-styles {NS} office:version="1.2">{STYLES}</office:document-styles>', 'META-INF/manifest.xml': manifest}, ('mimetype',))
    return path


def convert(source, ext, filter_name):
    destination = WORK / ('office-guide.' + ext)
    destination.unlink(missing_ok=True)
    command = ['libreoffice', '-env:UserInstallation=' + (WORK / 'lo-profile').resolve().as_uri(), '--headless', '--convert-to',
               ext + ':' + filter_name, '--outdir', str(WORK), str(source)]
    run = subprocess.run(command, capture_output=True, text=True, timeout=300, env={**os.environ, 'SAL_USE_VCLPLUGIN':'svp', 'SAL_DISABLESKIA':'1', 'SAL_DISABLE_OPENCL':'1'})
    RECEIPTS.append(dict(source=str(source), target=ext, filter=filter_name, exit=run.returncode, output=run.stdout + run.stderr))
    if run.returncode or not destination.is_file():
        raise RuntimeError(RECEIPTS[-1])
    return destination


def ooxml(source, ext, title, family, content_type, mime):
    with zipfile.ZipFile(source) as archive:
        parts = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    types = ET.fromstring(parts['[Content_Types].xml'])
    primary = {'word': '/word/document.xml', 'sheet': '/xl/workbook.xml', 'slides': '/ppt/presentation.xml'}[family]
    for element in types:
        if element.attrib.get('PartName') == primary:
            element.set('ContentType', content_type)
    parts['[Content_Types].xml'] = ET.tostring(types, encoding='utf-8', xml_declaration=True)
    # Remove build-time identity/timestamps without changing authored document content.
    if 'docProps/core.xml' in parts:
        core = ET.fromstring(parts['docProps/core.xml'])
        for element in core:
            tag = element.tag.rsplit('}', 1)[-1]
            if tag in ('creator', 'lastModifiedBy'):
                element.text = 'AllDoc'
            elif tag in ('created', 'modified'):
                element.text = '2026-01-01T12:00:00Z'
        parts['docProps/core.xml'] = ET.tostring(core, encoding='utf-8', xml_declaration=True)
    package(record(ext, title, mime, 'Spreadsheets' if family == 'sheet' else 'Presentations' if family == 'slides' else 'Documents'), parts)


def main():
    record('pdf', 'Field guide', 'application/pdf', description='Three pages to scroll or present.').write_bytes(pdf('AllDoc field guide'))
    families = [('text', [('odt','text'),('ott','text-template'),('odm','text-master'),('oth','text-web')]),
                ('spreadsheet', [('ods','spreadsheet'),('ots','spreadsheet-template')]),
                ('presentation', [('odp','presentation'),('otp','presentation-template')]),
                ('drawing', [('odg','graphics'),('otg','graphics-template')])]
    for kind, extensions in families:
        for ext, suffix in extensions:
            odf(ext, kind, suffix)
    for ext, kind in [('fodt','text'),('fods','spreadsheet'),('fodp','presentation'),('fodg','drawing')]:
        record(ext, 'Reading plan' if kind == 'spreadsheet' else 'Reading slides' if kind == 'presentation' else 'Field guide',
               'application/vnd.oasis.opendocument.' + ODF_BASE[kind] + '-flat-xml',
               'Spreadsheets' if kind == 'spreadsheet' else 'Presentations' if kind in ('presentation','drawing') else 'Documents').write_text(odf_xml(kind, True))
    sources = {}
    for ext, original, filter_name in [('docx','fodt','Office Open XML Text'),('xlsx','fods','Calc MS Excel 2007 XML'),('pptx','fodp','Impress MS PowerPoint 2007 XML')]:
        sources[ext] = convert(OUT / ('office-guide.' + original), ext, filter_name)
    variants = {
        'docx': ('word', 'Field guide', [('docx','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'),
            ('docm','application/vnd.ms-word.document.macroEnabled.12','application/vnd.ms-word.document.macroEnabled.main+xml'),
            ('dotx','application/vnd.openxmlformats-officedocument.wordprocessingml.template','application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml'),
            ('dotm','application/vnd.ms-word.template.macroEnabled.12','application/vnd.ms-word.template.macroEnabledTemplate.main+xml')]),
        'xlsx': ('sheet', 'Reading plan', [('xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'),
            ('xlsm','application/vnd.ms-excel.sheet.macroEnabled.12','application/vnd.ms-excel.sheet.macroEnabled.main+xml'),
            ('xltx','application/vnd.openxmlformats-officedocument.spreadsheetml.template','application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml'),
            ('xltm','application/vnd.ms-excel.template.macroEnabled.12','application/vnd.ms-excel.template.macroEnabled.main+xml')]),
        'pptx': ('slides', 'Reading slides', [(ext, mime, mime + '.main+xml') for ext, mime in [
            ('pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation'),
            ('ppsx','application/vnd.openxmlformats-officedocument.presentationml.slideshow'),
            ('potx','application/vnd.openxmlformats-officedocument.presentationml.template')]] + [
            ('pptm','application/vnd.ms-powerpoint.presentation.macroEnabled.12','application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml'),
            ('ppsm','application/vnd.ms-powerpoint.slideshow.macroEnabled.12','application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml'),
            ('potm','application/vnd.ms-powerpoint.template.macroEnabled.12','application/vnd.ms-powerpoint.template.macroEnabled.main+xml')])}
    for base, (family, title, entries) in variants.items():
        for ext, mime, content_type in entries:
            ooxml(sources[base], ext, title, family, content_type, mime)
    for ext, original, filter_name, mime, category in [
        ('doc','fodt','MS Word 97','application/msword','Documents'),
        ('dot','fodt','MS Word 97 Vorlage','application/msword','Documents'),
        ('xls','fods','MS Excel 97','application/vnd.ms-excel','Spreadsheets'),
        ('ppt','fodp','MS PowerPoint 97','application/vnd.ms-powerpoint','Presentations'),
        ('pps','fodp','MS PowerPoint 97 AutoPlay','application/vnd.ms-powerpoint','Presentations'),
        ('pot','fodp','MS PowerPoint 97 Vorlage','application/vnd.ms-powerpoint','Presentations')]:
        source = convert(OUT / ('office-guide.' + original), ext, filter_name)
        shutil.copyfile(source, record(ext, 'Reading slides' if category == 'Presentations' else 'Reading plan' if category == 'Spreadsheets' else 'Field guide', mime, category))
    record('rtf', 'Field guide', 'application/rtf').write_bytes(b'{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Helvetica;}}\\f0\\fs36\\b AllDoc field guide\\b0\\par\\fs24 Normal view scrolls vertically.\\par Present view shows one page at a time.\\page Second page\\par Keep a notebook close and a reading list short.}')
    epub = {'mimetype': 'application/epub+zip', 'META-INF/container.xml': '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
            'EPUB/book.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:4a4d1fd0-2c92-4ae1-9980-45d00cdd0120</dc:identifier><dc:title>AllDoc field guide</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T12:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>',
            'EPUB/nav.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol><li><a href="chapter.xhtml">AllDoc field guide</a></li></ol></nav></body></html>',
            'EPUB/chapter.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>AllDoc field guide</title></head><body><h1>AllDoc field guide</h1><p>A short book for a quiet afternoon.</p><h2>Find a page</h2><p>Normal view scrolls vertically. Present view shows one page at a time.</p><h2>Keep reading</h2><p>Your position follows the document.</p></body></html>'}
    package(record('epub', 'Field guide', 'application/epub+zip', 'Books'), epub, ('mimetype',))
    for ext, name, mime in [('pages','Pages','application/vnd.apple.pages'),('numbers','Numbers','application/vnd.apple.numbers'),('key','Keynote','application/vnd.apple.keynote')]:
        package(record(ext, name + ' saved preview', mime, 'Documents', 'Saved PDF preview only; no editable iWork source.'),
                {'QuickLook/Preview.pdf': pdf('AllDoc ' + name + ' preview'),
                 'Metadata/Properties.plist': '<?xml version="1.0"?><plist version="1.0"><dict><key>AllDocExample</key><string>Saved preview demonstration. No editable iWork source.</string></dict></plist>'})
    pathlib.Path('scripts/fixtures/examples-office.json').write_text(json.dumps({'version':1, 'examples':EXAMPLES}, ensure_ascii=False, indent=2) + '\n')
    checks = []
    for example in EXAMPLES:
        path = pathlib.Path('app/src/main/assets') / example['asset']
        data = path.read_bytes()
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                assert archive.testzip() is None
                for name in archive.namelist():
                    if name.endswith(('.xml','.xhtml','.opf','.plist')):
                        ET.fromstring(archive.read(name))
        elif example['extension'].startswith('fod'):
            ET.fromstring(data)
        checks.append({'extension':example['extension'], 'bytes':len(data), 'sha256':hashlib.sha256(data).hexdigest()})
    WORK.joinpath('generation.json').write_text(json.dumps({'conversions':RECEIPTS,'files':checks}, indent=2) + '\n')
    print(json.dumps({'examples':len(checks),'bytes':sum(e['bytes'] for e in checks),'largest':max(checks,key=lambda e:e['bytes']), 'files':checks}, indent=2))


if __name__ == '__main__':
    main()
