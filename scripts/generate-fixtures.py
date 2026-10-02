#!/usr/bin/env python3
"""Small synthetic interoperability corpus. Requires python-docx, openpyxl, reportlab."""
from pathlib import Path
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from openpyxl import Workbook
from reportlab.pdfgen import canvas
root=Path(__file__).resolve().parents[1]/'fixtures';root.mkdir(exist_ok=True)
d=Document();sec=d.sections[0];sec.header.paragraphs[0].text='ALLDOC / FIELD NOTES'
d.add_heading('A little room to think.',0)
p=d.add_paragraph('Project notebook  /  October 2026');p.runs[0].font.color.rgb=RGBColor.from_string('235A49')
d.add_paragraph('A document should feel as clear as the idea inside it. This synthetic file tests paragraphs, emphasis, tables, headers and page breaks.')
p=d.add_paragraph();p.add_run('Keep the original. ').bold=True;p.add_run('Make a copy. ').italic=True;p.add_run('Review the result.')
d.add_heading('What matters',1)
for x in ['Quiet, readable tools','Predictable saving','Honest format boundaries']:d.add_paragraph(x,style='List Bullet')
t=d.add_table(rows=1, cols=3);t.style='Light Shading Accent 1'
for c,v in zip(t.rows[0].cells,['Workstream','Owner','Status']):c.text=v
for values in [['Canvas','Design','In review'],['Roundtrip','Engine','Testing'],['Offline','Platform','Ready']]:
 for c,v in zip(t.add_row().cells,values):c.text=v
d.add_heading('Across languages',1);d.add_paragraph('English · العربية · 日本語 · हिन्दी · Español')
d.add_page_break();d.add_heading('The next page',1);d.add_paragraph('Explicit page breaks should remain visible. Layout varies with available fonts; the original file remains unchanged.')
sec.footer.paragraphs[0].text='Synthetic test document • No private information'
d.save(root/'field-notes.docx')
w=Workbook();s=w.active;s.title='Project budget';s.append(['Item','Quantity','Cost','Total']);s.append(['Paper',3,4.50,'=B2*C2']);s.append(['Pens',12,1.75,'=B3*C3']);s['A8']='مرحبا';s['B8']='世界';w.create_sheet('Archive').append(['Old entry','Preserved']);w.save(root/'budget.xlsx')
c=canvas.Canvas(str(root/'reading.pdf'));c.setFont('Helvetica-Bold',28);c.drawString(64,760,'A little room to think.');c.setFont('Helvetica',12);c.drawString(64,725,'Synthetic PDF for page navigation and zoom tests.');c.showPage();c.drawString(64,760,'Second page');c.save()
(root/'notes.txt').write_text('A little room to think.\n\nEnglish · العربية · 日本語\n',encoding='utf-8')
