#!/usr/bin/env python3
"""Synthetic native-PDF QA corpus, generated with ReportLab + pypdf; never packaged in the APK."""
from io import BytesIO
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DictionaryObject, NameObject, NumberObject, TextStringObject

root = Path(__file__).resolve().parents[1] / 'fixtures'
root.mkdir(exist_ok=True)
stream = BytesIO()
c = canvas.Canvas(stream, pagesize=(612, 792), invariant=1)
c.setTitle('alldoc synthetic fillable form')
c.setAuthor('SmallThingz test corpus')

def heading(title, subtitle, page):
    c.setFillColor(colors.HexColor('#19332E'))
    c.setFont('Helvetica-Bold', 25)
    c.drawString(54, 728, title)
    c.setFillColor(colors.HexColor('#52645D'))
    c.setFont('Helvetica', 10)
    c.drawString(54, 702, subtitle)
    c.setFont('Helvetica', 9)
    c.drawString(54, 38, f'Synthetic data only. Android 15+ native form QA. Page {page}/2.')

heading('A small form, safely filled.', 'Editable text, a multiline field, a read-only field, and an unsupported checkbox.', 1)
f = c.acroForm
for y, label in [(655, 'Full name (maximum 40 characters)'), (572, 'Notes (multiline, maximum 300 characters)'), (381, 'Reference (read-only)')]:
    c.setFont('Helvetica-Bold', 11)
    c.drawString(54, y, label)
style = dict(x=54, width=504, fillColor=colors.HexColor('#F7F8F4'), borderColor=colors.HexColor('#A8B9AF'), textColor=colors.HexColor('#19332E'), fontName='Helvetica', fontSize=12, borderWidth=1)
f.textfield(name='full_name', tooltip='Full name', value='Alex Example', y=607, height=32, maxlen=40, **style)
f.textfield(name='notes', tooltip='Notes', value='First line\nSecond line', y=419, height=135, maxlen=300, fieldFlags='multiline', **style)
f.textfield(name='reference', tooltip='Read-only reference', value='SYNTHETIC-0042', y=335, height=30, maxlen=40, fieldFlags='readOnly', **style)
f.checkbox(name='consent', tooltip='Unsupported checkbox', x=54, y=280, checked=False, buttonStyle='check', size=18)
c.setFont('Helvetica', 11)
c.drawString(83, 284, 'This checkbox is intentionally not editable in alldoc.')
c.showPage()
heading('Another page, same draft.', 'Check that page navigation, saved copies, and draft recovery keep all text edits.', 2)
c.setFont('Helvetica-Bold', 11)
c.drawString(54, 655, 'City (no document-defined text limit)')
f.textfield(name='city', tooltip='City', value='Sample City', y=607, height=32, maxlen=100, **style)
c.showPage()
c.save()

writer = PdfWriter(clone_from=PdfReader(BytesIO(stream.getvalue())))
# Omission of /MaxLen means no PDF-defined limit. Android exposes that as -1; alldoc caps at 4096.
for page in writer.pages:
    for ref in page.get('/Annots', []):
        widget = ref.get_object()
        if widget.get('/T') == 'city':
            widget.pop(NameObject('/MaxLen'), None)
normal = root / 'fillable-form.pdf'
with normal.open('wb') as output:
    writer.write(output)

restricted = PdfWriter(clone_from=normal)
# Intentional synthetic empty-user-password encryption fixture, not a secret or account credential.
# API-level PdfRenderer has no public document permission query. Do not infer writable permission
# merely because an encrypted PDF can be opened without prompting for a password.
restricted.encrypt(user_password='', owner_password='synthetic-owner-password', permissions_flag=0, algorithm='AES-128')
with (root / 'restricted-empty-password-form.pdf').open('wb') as output:
    restricted.write(output)

signature = PdfWriter(clone_from=normal)
widget = DictionaryObject({
    NameObject('/Type'): NameObject('/Annot'),
    NameObject('/Subtype'): NameObject('/Widget'),
    NameObject('/FT'): NameObject('/Sig'),
    NameObject('/T'): TextStringObject('signature_placeholder'),
    NameObject('/Rect'): ArrayObject([NumberObject(v) for v in (54, 420, 330, 470)]),
    NameObject('/F'): NumberObject(4),
    NameObject('/P'): signature.pages[1].indirect_reference,
})
widget_ref = signature._add_object(widget)
signature.pages[1]['/Annots'].append(widget_ref)
signature.root_object['/AcroForm']['/Fields'].append(widget_ref)
# This is an UNSIGNED signature field, not a digital signature. Its presence should block filling.
with (root / 'signature-field-form.pdf').open('wb') as output:
    signature.write(output)

for name in ['fillable-form.pdf', 'restricted-empty-password-form.pdf', 'signature-field-form.pdf']:
    reader = PdfReader(root / name)
    if reader.is_encrypted:
        assert reader.decrypt('')
        assert int(reader.user_access_permissions) == 0
    fields = reader.get_fields()
    assert fields['full_name']['/V'] == 'Alex Example'
    assert fields['notes']['/V'] == 'First line\nSecond line'
    assert fields['reference']['/Ff'] & 1
    assert fields['city']['/V'] == 'Sample City'
    widgets = [item.get_object() for page in reader.pages for item in page.get('/Annots', [])]
    assert len(widgets) == len(fields)
    for item in widgets:
        if item.get('/FT') == '/Tx':
            assert item['/V'] == fields[item['/T']]['/V']
            assert item['/AP']['/N'].get_object().get_data()
    print(name, len(reader.pages), 'pages;', len(fields), 'fields;', (root / name).stat().st_size, 'bytes')
