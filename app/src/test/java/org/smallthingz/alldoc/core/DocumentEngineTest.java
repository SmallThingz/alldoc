package org.smallthingz.alldoc.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
public class DocumentEngineTest {
    static byte[] zip(Map<String,String> parts)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(var e:parts.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue().getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return out.toByteArray();}
    static Map<String,byte[]> unzip(byte[] bytes)throws Exception{Map<String,byte[]> m=new HashMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=z.getNextEntry())!=null)m.put(e.getName(),z.readAllBytes());}return m;}
    static String doc(String text){return "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>"+text+"</w:t></w:r></w:p></w:body></w:document>";}
    @Test public void unchangedIsByteIdentical()throws Exception{byte[] b=zip(Map.of("word/document.xml",doc("Hello")));assertArrayEquals(b,DocumentEngine.open("x.docx",b).export(Map.of()));}
    @Test public void wordEditPreservesOtherPartsAndOriginal()throws Exception{byte[] b=zip(Map.of("word/document.xml",doc("Hello"),"word/styles.xml","<styles>keep these bytes</styles>"));var s=DocumentEngine.open("x.docx",b);byte[] edited=s.export(Map.of("b0"," مرحبا 世界 & < > "));assertArrayEquals("<styles>keep these bytes</styles>".getBytes(),unzip(edited).get("word/styles.xml"));assertEquals(" مرحبا 世界 & < > ",DocumentEngine.open("x.docx",edited).blocks.get(0).original);assertArrayEquals(b,s.export(Map.of()));assertEquals("Hello",s.blocks.get(0).original);}
    @Test public void repeatedExportsAreIndependent()throws Exception{var s=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("Original"))));assertEquals("A",DocumentEngine.open("x.docx",s.export(Map.of("b0","A"))).blocks.get(0).original);assertEquals("B",DocumentEngine.open("x.docx",s.export(Map.of("b0","B"))).blocks.get(0).original);}
    @Test public void sharedStringEditDoesNotChangeOtherCell()throws Exception{var s=DocumentEngine.open("x.xlsx",zip(Map.of("xl/sharedStrings.xml","<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><si><t>Shared</t></si></sst>","xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>0</v></c><c r='C1'><f>A1</f><v>3</v></c></row></sheetData></worksheet>")));var round=DocumentEngine.open("x.xlsx",s.export(Map.of("b0","Local")));assertEquals("Local",round.blocks.get(0).original);assertEquals("Shared",round.blocks.get(1).original);assertFalse(round.blocks.get(2).editable);}
    @Test public void formulaEditRejected()throws Exception{var s=DocumentEngine.open("x.xlsx",zip(Map.of("xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row><c r='A1'><f>1+1</f><v>2</v></c></row></sheetData></worksheet>")));assertThrows(IOException.class,()->s.export(Map.of("b0","7")));}
    @Test public void utf16AndLineEndingsPreserved()throws Exception{byte[] text="a\r\nb".getBytes(StandardCharsets.UTF_16LE);byte[] bytes=new byte[text.length+2];bytes[0]=(byte)255;bytes[1]=(byte)254;System.arraycopy(text,0,bytes,2,text.length);var s=DocumentEngine.open("notes.txt",bytes);assertEquals("a\r\nb",s.blocks.get(0).original);byte[] output=s.export(Map.of("text","a\r\nc"));assertEquals((byte)255,output[0]);assertEquals("a\r\nc",new String(output,2,output.length-2,StandardCharsets.UTF_16LE));}
    @Test public void invalidUtf8Rejected(){assertThrows(Exception.class,()->DocumentEngine.open("x.txt",new byte[]{(byte)0xC3,0x28}));}
    @Test public void binaryTextRejected(){assertThrows(IOException.class,()->DocumentEngine.open("x.txt",new byte[]{0,1,2}));}
    @Test public void traversalRejected(){assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("../escape","bad","word/document.xml",doc("Hello")))));}
    @Test public void doctypeRejected(){assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml","<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+doc("&x;")))));}
    @Test public void macroRejected(){assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("x"),"word/vbaProject.bin","macro"))));}
    @Test public void signatureRejected(){assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("x"),"_xmlsignatures/sig.xml","signed"))));}
    @Test public void unknownEditRejected()throws Exception{var s=DocumentEngine.open("x.txt","Hi".getBytes());assertThrows(IOException.class,()->s.export(Map.of("unknown","bad")));}
    @Test public void partExpansionBombRejected(){assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("a".repeat(DocumentEngine.MAX_PART))))));}
    @Test public void odfTextPreservesSpanAndAsset()throws Exception{var parts=new LinkedHashMap<String,String>();parts.put("mimetype","application/vnd.oasis.opendocument.text");parts.put("content.xml","<office:document-content xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0'><office:body><text:p>Before <text:span>styled</text:span> after</text:p></office:body></office:document-content>");parts.put("Pictures/image.bin","binary bytes");var s=DocumentEngine.open("x.odt",zip(parts));byte[] result=s.export(Map.of("b1","new style text"));var again=DocumentEngine.open("x.odt",result);assertEquals("Before ",again.blocks.get(0).original);assertEquals("new style text",again.blocks.get(1).original);assertArrayEquals("binary bytes".getBytes(),unzip(result).get("Pictures/image.bin"));try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(result))){assertEquals(ZipEntry.STORED,z.getNextEntry().getMethod());}}
    @Test public void slideOrderIsNumeric()throws Exception{String slide="<p:sld xmlns:p='urn:p' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main'><a:t>%s</a:t></p:sld>";var s=DocumentEngine.open("x.pptx",zip(Map.of("ppt/slides/slide10.xml",slide.formatted("ten"),"ppt/slides/slide2.xml",slide.formatted("two"))));assertEquals("two",s.blocks.get(0).original);}
    @Test public void malformedArchiveFailsClosed(){assertThrows(Exception.class,()->DocumentEngine.open("x.docx","not a zip".getBytes()));}
    @Test public void unsupportedLegacyNeverPretendsText(){assertThrows(IOException.class,()->DocumentEngine.open("x.doc","abc".getBytes()));}

    @Test public void inputArrayCannotMutateOriginal()throws Exception {
        byte[] bytes="Original".getBytes(StandardCharsets.UTF_8);var session=DocumentEngine.open("a.txt",bytes);
        Arrays.fill(bytes,(byte)'x');assertEquals("Original",new String(session.export(Map.of()),StandardCharsets.UTF_8));
    }
    @Test public void deepXmlRejectedBeforeDomAllocation() {
        String nested="<x>".repeat(DocumentEngine.MAX_XML_DEPTH)+"text"+"</x>".repeat(DocumentEngine.MAX_XML_DEPTH);
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc(nested)))));
    }
    @Test public void emptyNodeBombRejected() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("<x/>".repeat(DocumentEngine.MAX_XML_NODES))))));
    }
    @Test public void complexityBudgetIncludesHiddenParts() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("ok"),"word/styles.xml","<styles>"+"<x/>".repeat(DocumentEngine.MAX_XML_NODES)+"</styles>"))));
    }
    @Test public void nodeBudgetIsAggregateAcrossParts() {
        String part="<root>"+"<x/>".repeat(60000)+"</root>";
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("ok"),"word/styles.xml",part,"word/numbering.xml",part))));
    }
    @Test public void hiddenDoctypeRejected() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("ok"),"word/styles.xml","<!DOCTYPE a [<!ENTITY x 'boom'>]><a>&x;</a>"))));
    }
    @Test public void xmlWhitespaceAndCdataRemainUsable()throws Exception {
        var session=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc(" A &amp; B "))));
        assertEquals(" A & B ",session.blocks.get(0).original);
    }
    @Test public void namespaceAliasedTraversalRejected() {
        assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/./styles.xml","<x/>","word/document.xml",doc("ok")))));
        assertThrows(IOException.class,()->DocumentEngine.open("x.docx",zip(Map.of("C:/x","bad","word/document.xml",doc("ok")))));
    }
    @Test public void macroContentTypeRejectedEvenWithRenamedPart() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("ok"),"[Content_Types].xml","<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/word/other.bin' ContentType='application/vnd.ms-office.vbaProject'/></Types>"))));
    }
    @Test public void odfInlineScriptRejected() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.odt",zip(Map.of("content.xml","<o:document-content xmlns:o='urn:oasis:names:tc:opendocument:xmlns:office:1.0'><o:script>run()</o:script></o:document-content>"))));
    }
    @Test public void combinedEditsBoundedBeforeSerialization()throws Exception {
        var session=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("a</w:t><w:t>b</w:t><w:t>c"))));
        String value="a".repeat(DocumentEngine.MAX_TEXT);
        assertThrows(IOException.class,()->session.export(Map.of("b0",value,"b1",value,"b2",value)));
        assertEquals("a",session.blocks.get(0).original);
    }
    @Test public void serializedPartCannotGrowBeyondReimportLimit()throws Exception {
        var session=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("a</w:t><w:t>b"))));
        String value="&".repeat(DocumentEngine.MAX_TEXT);
        assertThrows(Exception.class,()->session.export(Map.of("b0",value,"b1",value)));
        assertEquals("a",DocumentEngine.open("x.docx",session.export(Map.of())).blocks.get(0).original);
    }
    @Test public void plainTextExportBoundedByEncodedBytes()throws Exception {
        var session=DocumentEngine.open("x.txt","a".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->session.export(Map.of("text","世".repeat(DocumentEngine.MAX_TEXT/2))));
    }
    @Test public void invalidUnicodeEditDoesNotPoisonSession()throws Exception {
        var session=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("a"))));
        assertThrows(IOException.class,()->session.export(Map.of("b0","\ud800")));
        assertEquals("valid",DocumentEngine.open("x.docx",session.export(Map.of("b0","valid"))).blocks.get(0).original);
    }
    @Test public void odfMimetypeMovesToFirstStoredEntryOnEditedExport()throws Exception {
        var parts=new LinkedHashMap<String,String>();
        parts.put("content.xml","<o:document-content xmlns:o='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:t='urn:oasis:names:tc:opendocument:xmlns:text:1.0'><t:p>old</t:p></o:document-content>");
        parts.put("mimetype","application/vnd.oasis.opendocument.text");
        byte[] bytes=DocumentEngine.open("x.odt",zip(parts)).export(Map.of("b0","new"));
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){var first=in.getNextEntry();assertEquals("mimetype",first.getName());assertEquals(ZipEntry.STORED,first.getMethod());}
    }
    @Test public void runsKeepStableIndependentTargetsWithinParagraphs()throws Exception {
        var session=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("first</w:t><w:t>second"))));
        assertEquals(session.blocks.get(0).paragraphId,session.blocks.get(1).paragraphId);
        assertNotEquals(session.blocks.get(0).id,session.blocks.get(1).id);
        var next=DocumentEngine.open("x.docx",session.export(Map.of("b1","changed")));
        assertEquals("first",next.blocks.get(0).original);assertEquals("changed",next.blocks.get(1).original);
    }
    @Test public void odsFormulaHeadingsAndRepeatedCellsAreReadOnly()throws Exception {
        String content="<o:document-content xmlns:o='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:t='urn:oasis:names:tc:opendocument:xmlns:text:1.0' xmlns:c='urn:oasis:names:tc:opendocument:xmlns:table:1.0'><c:table><c:table-row><c:table-cell o:value-type='string' c:formula='of:=1'><t:h>formula</t:h></c:table-cell><c:table-cell o:value-type='string' c:number-columns-repeated='4'><t:p>repeated</t:p></c:table-cell></c:table-row></c:table></o:document-content>";
        var session=DocumentEngine.open("x.ods",zip(Map.of("content.xml",content)));
        assertEquals(2,session.blocks.size());for(var block:session.blocks)assertFalse(block.editable);
    }

    @Test public void previewNormalizesCentralDirectoryAliasesButExportKeepsOriginal()throws Exception {
        byte[] bytes=zip(Map.of("word/document.xml",doc("safe")));
        byte[] replacement="evil/document.xml".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<bytes.length-46;i++)if(bytes[i]==0x50 && bytes[i+1]==0x4b && bytes[i+2]==0x01 && bytes[i+3]==0x02) {
            System.arraycopy(replacement,0,bytes,i+46,replacement.length);break;
        }
        var session=DocumentEngine.open("x.docx",bytes);
        assertArrayEquals(bytes,session.export(Map.of()));
        byte[] normalized=session.previewBytes(Map.of());
        assertFalse(new String(normalized,StandardCharsets.ISO_8859_1).contains("evil/document.xml"));
        assertEquals("safe",DocumentEngine.open("x.docx",normalized).blocks.get(0).original);
    }

    @Test public void alternateOdfSignatureMembersAreRejected() {
        for(String signature:List.of("META-INF/macrosignatures.xml","META-INF/packagesignatures.xml"))
            assertThrows(IOException.class,()->DocumentEngine.open("x.odt",zip(Map.of("content.xml","<document/>",signature,"<signatures/>"))));
    }
    @Test public void renamedXmlSignatureIsRejected() {
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("safe"),"word/custom.xml","<Signature xmlns='http://www.w3.org/2000/09/xmldsig#'><SignedInfo/></Signature>"))));
    }
    @Test public void relationshipSelectedXmlCannotHideBehindDatExtension()throws Exception {
        String rel="<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='s' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles' Target='styles.dat'/></Relationships>";
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("Hello"),"word/_rels/document.xml.rels",rel,"word/styles.dat","<!DOCTYPE x><x/>"))));
        assertEquals("Hello",DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("Hello"),"word/_rels/document.xml.rels",rel,"word/styles.dat","<styles/>"))).blocks.get(0).original);
    }
    @Test public void contentTypeSelectedXmlCannotHideBehindDatExtension()throws Exception {
        String types="<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/word/hidden.dat' ContentType='application/test+xml'/></Types>";
        assertThrows(Exception.class,()->DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("Hello"),"[Content_Types].xml",types,"word/hidden.dat","<!DOCTYPE x><x/>"))));
    }
    @Test public void syntheticCorpusReopensAfterPreviewNormalization()throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("fixtures");if(!java.nio.file.Files.isDirectory(root))root=java.nio.file.Path.of("../fixtures");
        for(String name:List.of("field-notes.docx","budget.xlsx","notes.txt")) {
            byte[] original=java.nio.file.Files.readAllBytes(root.resolve(name));
            var session=DocumentEngine.open(name,original);
            assertArrayEquals(original,session.export(Map.of()));
            assertEquals(session.blocks.size(),DocumentEngine.open(name,session.previewBytes(Map.of())).blocks.size());
        }
    }
    static final String P="http://schemas.openxmlformats.org/presentationml/2006/main", A="http://schemas.openxmlformats.org/drawingml/2006/main", R="http://schemas.openxmlformats.org/officeDocument/2006/relationships", S="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static String rels(String... values){StringBuilder xml=new StringBuilder("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>");for(int i=0;i<values.length;i+=3)xml.append("<Relationship Id='").append(values[i]).append("' Type='").append(R).append('/').append(values[i+1]).append("' Target='").append(values[i+2]).append("'/>");return xml+"</Relationships>";}
    static String slide(String text){return "<p:sld xmlns:p='"+P+"' xmlns:a='"+A+"'><p:cSld><p:spTree><p:sp><p:spPr><a:xfrm><a:off x='127000' y='254000'/><a:ext cx='1270000' cy='635000'/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>"+text+"</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";}
    static String odf(String body){return "<office:document-content xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0' xmlns:table='urn:oasis:names:tc:opendocument:xmlns:table:1.0' xmlns:draw='urn:oasis:names:tc:opendocument:xmlns:drawing:1.0' xmlns:svg='urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0'><office:body>"+body+"</office:body></office:document-content>";}
    @Test public void presentationRelationshipOrderAndCanvasAreAuthoritative()throws Exception {
        var parts=new HashMap<String,String>();parts.put("ppt/presentation.xml","<p:presentation xmlns:p='"+P+"' xmlns:r='"+R+"'><p:sldIdLst><p:sldId id='256' r:id='second'/><p:sldId id='257' r:id='first'/></p:sldIdLst><p:sldSz cx='12192000' cy='6858000'/></p:presentation>");
        parts.put("ppt/_rels/presentation.xml.rels",rels("first","slide","slides/slide1.xml","second","slide","slides/custom.xml"));parts.put("ppt/slides/slide1.xml",slide("Later"));parts.put("ppt/slides/custom.xml",slide("First"));parts.put("ppt/slides/slide99.xml",slide("Orphan"));
        byte[] bytes=zip(parts);var s=DocumentEngine.open("x.pptx",bytes);assertEquals(2,s.slides.size());assertEquals("First",s.slides.get(0).title);assertEquals(960,s.slides.get(0).width,.01);assertEquals(540,s.slides.get(0).height,.01);assertEquals(10,s.blocks.get(0).x,.01);assertEquals(100,s.blocks.get(0).width,.01);assertEquals("Slide 1",s.blocks.get(0).section);assertArrayEquals(bytes,s.export(Map.of()));
        var edited=DocumentEngine.open("x.pptx",s.export(Map.of(s.blocks.get(0).id,"Changed")));assertEquals("Changed",edited.slides.get(0).title);assertEquals("Later",edited.slides.get(1).title);
    }
    @Test public void missingSlideRelationshipDoesNotFallBackToPartNames()throws Exception {
        var parts=Map.of("ppt/presentation.xml","<p:presentation xmlns:p='"+P+"' xmlns:r='"+R+"'><p:sldIdLst><p:sldId r:id='missing'/></p:sldIdLst></p:presentation>","ppt/_rels/presentation.xml.rels",rels("other","slide","slides/slide1.xml"),"ppt/slides/slide1.xml",slide("Wrong"));
        assertThrows(IOException.class,()->DocumentEngine.open("x.pptx",zip(parts)));
    }
    @Test public void slideGroupTransformsRemainExplicitlyUnknown()throws Exception {
        String xml=slide("Grouped").replace("<p:sp>","<p:grpSp><p:sp>").replace("</p:sp>","</p:sp></p:grpSp>");
        var s=DocumentEngine.open("x.pptx",zip(Map.of("ppt/slides/slide1.xml",xml)));assertEquals(-1,s.blocks.get(0).width,.01);
    }
    @Test public void workbookRelationshipsPreserveNamesAndEmptySheets()throws Exception {
        var parts=Map.of("xl/workbook.xml","<workbook xmlns='"+S+"' xmlns:r='"+R+"'><sheets><sheet name='Budget' sheetId='2' r:id='b'/><sheet name='Empty' sheetId='1' r:id='a'/></sheets></workbook>","xl/_rels/workbook.xml.rels",rels("a","worksheet","worksheets/sheet1.xml","b","worksheet","worksheets/custom.xml"),"xl/worksheets/sheet1.xml","<worksheet xmlns='"+S+"'><sheetData/></worksheet>","xl/worksheets/custom.xml","<worksheet xmlns='"+S+"'><sheetData><row r='5'><c r='C5'><v>42</v></c></row></sheetData><mergeCells><mergeCell ref='C5:D6'/></mergeCells></worksheet>");
        var s=DocumentEngine.open("x.xlsx",zip(parts));assertEquals(2,s.sheets.size());assertEquals("Budget",s.sheets.get(0).name);assertEquals(0,s.sheets.get(1).blocks.size());var cell=s.blocks.get(0);assertEquals(5,cell.row);assertEquals(3,cell.column);assertEquals(2,cell.rowSpan);assertEquals(2,cell.columnSpan);
    }
    @Test public void workbookAddressSpoofingIsRejected()throws Exception {
        for(String cells:List.of("<c r='A1'/><c r='A1'/>","<c r='A2'/>","<c r='XFE1'/>","<c r='A1048577'/>"))assertThrows(IOException.class,()->DocumentEngine.open("x.xlsx",zip(Map.of("xl/worksheets/sheet1.xml","<worksheet xmlns='"+S+"'><sheetData><row r='1'>"+cells+"</row></sheetData></worksheet>"))));
    }
    @Test public void protectedWorksheetAndWordTextAreReadOnly()throws Exception {
        var sheet=DocumentEngine.open("x.xlsx",zip(Map.of("xl/worksheets/sheet1.xml","<worksheet xmlns='"+S+"'><sheetData><row><c r='A1'><v>3</v></c></row></sheetData><sheetProtection sheet='1'/></worksheet>")));assertFalse(sheet.blocks.get(0).editable);assertThrows(IOException.class,()->sheet.export(Map.of("b0","4")));
        var word=DocumentEngine.open("x.docx",zip(Map.of("word/document.xml",doc("Locked"),"word/settings.xml","<w:settings xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:documentProtection w:enforcement='1'/></w:settings>")));assertFalse(word.blocks.get(0).editable);
    }
    @Test public void odsCellsKeepAddressesTypesAndCompactRepeats()throws Exception {
        String body="<table:table table:name='Costs'><table:table-row><table:table-cell table:number-columns-repeated='2'/><table:table-cell office:value-type='float' office:value='17.5'/><table:table-cell office:value-type='string'><text:p>One<text:s text:c='3'/>two<text:tab/>three</text:p></table:table-cell></table:table-row><table:table-row table:number-rows-repeated='4'><table:table-cell office:value-type='string' table:number-columns-repeated='5'><text:p>Repeated</text:p></table:table-cell></table:table-row></table:table>";
        var s=DocumentEngine.open("x.ods",zip(Map.of("content.xml",odf(body))));assertEquals(1,s.sheets.size());assertEquals("Costs",s.sheets.get(0).name);assertEquals(3,s.blocks.size());assertEquals("17.5",s.blocks.get(0).original);assertEquals(3,s.blocks.get(0).column);assertFalse(s.blocks.get(0).editable);assertEquals("One   two\tthree",s.blocks.get(1).original);assertTrue(s.blocks.get(1).editable);assertEquals(4,s.blocks.get(2).rowRepeat);assertEquals(5,s.blocks.get(2).columnRepeat);assertFalse(s.blocks.get(2).editable);
    }
    @Test public void odsSafeCellRoundtripPreservesWhitespaceAndOtherMembers()throws Exception {
        var parts=Map.of("content.xml",odf("<table:table table:name='Notes'><table:table-row><table:table-cell office:value-type='string' office:string-value='before'/><table:table-cell office:value-type='float' office:value='5'/></table:table-row></table:table>"),"mimetype","application/vnd.oasis.opendocument.spreadsheet","Pictures/test.bin","untouched");
        var s=DocumentEngine.open("x.ods",zip(parts));String value="  Hello  世界\tend\nNext line ";byte[] result=s.export(Map.of("b0",value));var round=DocumentEngine.open("x.ods",result);assertEquals(value,round.blocks.get(0).original);assertEquals("5",round.blocks.get(1).original);assertArrayEquals("untouched".getBytes(),unzip(result).get("Pictures/test.bin"));assertEquals("before",s.blocks.get(0).original);
    }
    @Test public void richTextOdsCellsDoNotLoseFormatting()throws Exception {
        var s=DocumentEngine.open("x.ods",zip(Map.of("content.xml",odf("<table:table><table:table-row><table:table-cell office:value-type='string'><text:p>Some <text:span>bold</text:span></text:p></table:table-cell></table:table-row></table:table>"))));assertEquals("Some bold",s.blocks.get(0).original);assertFalse(s.blocks.get(0).editable);assertThrows(IOException.class,()->s.export(Map.of("b0","plain")));
    }
    @Test public void odsRepeatAndSpaceBombsFailClosed()throws Exception {
        for(String attributes:List.of("table:number-columns-repeated='2147483647'","table:number-columns-repeated='0'","table:number-rows-spanned='1048577'"))assertThrows(IOException.class,()->DocumentEngine.open("x.ods",zip(Map.of("content.xml",odf("<table:table><table:table-row><table:table-cell "+attributes+" office:value-type='string'><text:p>x</text:p></table:table-cell></table:table-row></table:table>")))));
        assertThrows(IOException.class,()->DocumentEngine.open("x.ods",zip(Map.of("content.xml",odf("<table:table><table:table-row><table:table-cell office:value-type='string'><text:p><text:s text:c='2147483647'/></text:p></table:table-cell></table:table-row></table:table>")))));
    }
    @Test public void odsExportComplexityFailsBeforeChangingSource()throws Exception {
        var s=DocumentEngine.open("x.ods",zip(Map.of("content.xml",odf("<table:table><table:table-row><table:table-cell office:value-type='string'><text:p>safe</text:p></table:table-cell></table:table-row></table:table>"))));assertThrows(IOException.class,()->s.export(Map.of("b0","a\t".repeat(DocumentEngine.MAX_XML_NODES))));assertEquals("safe",s.blocks.get(0).original);
    }
    @Test public void odpPagesRemainDistinctAndBoundsUsePoints()throws Exception {
        String body="<draw:page draw:name='Opening'><draw:frame svg:x='1in' svg:y='2cm' svg:width='5in' svg:height='20mm'><draw:text-box><text:p>First</text:p></draw:text-box></draw:frame></draw:page><draw:page draw:name='Empty'/><draw:page draw:name='Closing'><text:p>Last</text:p></draw:page>";
        var s=DocumentEngine.open("x.odp",zip(Map.of("content.xml",odf(body))));assertEquals(3,s.slides.size());assertEquals("Opening",s.slides.get(0).title);assertEquals(0,s.slides.get(1).blocks.size());assertEquals("Slide 3",s.blocks.get(1).section);assertEquals(72,s.blocks.get(0).x,.01);assertEquals(360,s.blocks.get(0).width,.01);
    }
    @Test public void odtParagraphsAndHeadingsKeepDocumentOrderAndCdata()throws Exception {
        var s=DocumentEngine.open("x.odt",zip(Map.of("content.xml",odf("<text:p>Before</text:p><text:h>Heading</text:h><text:p><![CDATA[After & more]]></text:p>"))));assertEquals(List.of("Before","Heading","After & more"),s.blocks.stream().map(b->b.original).toList());var round=DocumentEngine.open("x.odt",s.export(Map.of("b2","Changed")));assertEquals("Changed",round.blocks.get(2).original);
    }
    @Test public void flatOdfExportsXmlRatherThanZip()throws Exception {
        byte[] original=odf("<text:p>Flat</text:p>").getBytes(StandardCharsets.UTF_8);var s=DocumentEngine.open("notes.fodt",original);assertArrayEquals(original,s.export(Map.of()));byte[] output=s.export(Map.of("b0","Edited"));assertEquals('<',output[0]);assertEquals("Edited",DocumentEngine.open("notes.fodt",output).blocks.get(0).original);
    }
    @Test public void xmlRequiresWellFormedSourceOnOpenAndExport()throws Exception {
        assertThrows(Exception.class,()->DocumentEngine.open("x.xml","<broken>".getBytes()));assertThrows(Exception.class,()->DocumentEngine.open("x.xml","<!DOCTYPE root><root/>".getBytes()));byte[] original="<?xml version='1.0'?><root><entry>Value</entry></root>".getBytes(StandardCharsets.UTF_8);var s=DocumentEngine.open("x.xml",original);assertArrayEquals(original,s.export(Map.of()));assertThrows(Exception.class,()->s.export(Map.of("text","<broken>")));assertEquals("<root>Changed</root>",new String(s.export(Map.of("text","<root>Changed</root>")),StandardCharsets.UTF_8));
    }
    @Test public void extendedSyntheticCorpusRoundtrips()throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("fixtures");if(!java.nio.file.Files.isDirectory(root))root=java.nio.file.Path.of("../fixtures");
        for(String name:List.of("studio-review.pptx","studio-budget.ods","studio-review.odp","studio-notes.odt","manifest.xml","flat-notes.fodt")) {
            byte[] bytes=java.nio.file.Files.readAllBytes(root.resolve(name));var s=DocumentEngine.open(name,bytes);assertArrayEquals(bytes,s.export(Map.of()));assertTrue("Readable corpus: "+name,!s.blocks.isEmpty());
            var editable=s.blocks.stream().filter(b->b.editable).findFirst().orElseThrow();String value=name.endsWith(".xml")?"<manifest>Changed</manifest>":"Changed fixture text";var round=DocumentEngine.open(name,s.export(Map.of(editable.id,value)));assertTrue(round.blocks.stream().anyMatch(b->b.original.equals(value)));
        }
    }
    @Test public void xmlEncodingChangesCannotSilentlyCorruptText()throws Exception {
        assertThrows(IOException.class,()->DocumentEngine.open("x.xml","<?xml version='1.0' encoding='ISO-8859-1'?><x/>".getBytes(StandardCharsets.UTF_8)));
        var session=DocumentEngine.open("x.xml","<x/>".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->session.export(Map.of("text","<?xml version='1.0' encoding='UTF-16'?><x>é</x>")));
    }
    @Test public void webFormatsAreInertEditableSource()throws Exception {
        for(String extension:List.of("html","htm","css")) {
            String source="<script>window.location='https://example.invalid'</script>";
            var session=DocumentEngine.open("x."+extension,source.getBytes(StandardCharsets.UTF_8));assertEquals(source,session.blocks.get(0).original);assertEquals("changed",new String(session.export(Map.of("text","changed")),StandardCharsets.UTF_8));
        }
        var svg=DocumentEngine.open("drawing.svg","<svg xmlns='http://www.w3.org/2000/svg'><script>inert()</script></svg>".getBytes(StandardCharsets.UTF_8));assertEquals(1,svg.blocks.size());assertThrows(Exception.class,()->svg.export(Map.of("text","<svg>")));
    }
    @Test public void odfExplicitWhitespaceRemainsVisibleWithoutUnsafeEditTargets()throws Exception {
        var session=DocumentEngine.open("x.odt",zip(Map.of("content.xml",odf("<text:p>A<text:s text:c='3'/>B<text:tab/>C<text:line-break/>D</text:p>"))));
        assertEquals("A   B\tC\nD",session.blocks.stream().map(b->b.original).collect(java.util.stream.Collectors.joining()));assertFalse(session.blocks.get(1).editable);
        assertThrows(IOException.class,()->session.export(Map.of(session.blocks.get(1).id,"spaces")));
    }
    @Test public void hostileSlideCanvasDimensionsFailBeforeUi()throws Exception {
        for(String size:List.of("cx='NaN' cy='6858000'","cx='12192000' cy='0.000000001'","cx='12192000' cy='1'")) {
            var parts=Map.of("ppt/presentation.xml","<p:presentation xmlns:p='"+P+"' xmlns:r='"+R+"'><p:sldIdLst><p:sldId r:id='one'/></p:sldIdLst><p:sldSz "+size+"/></p:presentation>","ppt/_rels/presentation.xml.rels",rels("one","slide","slides/slide1.xml"),"ppt/slides/slide1.xml",slide("Hello"));
            assertThrows(IOException.class,()->DocumentEngine.open("x.pptx",zip(parts)));
        }
    }
    @Test public void legacySessionsAreReadOnlyAndCopyOriginalBytes()throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("fixtures");if(!java.nio.file.Files.isDirectory(root))root=java.nio.file.Path.of("../fixtures");
        for(String ext:List.of("doc","xls","ppt")) {
            byte[] bytes=java.nio.file.Files.readAllBytes(root.resolve("legacy-basic."+ext));var session=DocumentEngine.open("legacy-basic."+ext,bytes);
            assertFalse(session.editable);assertFalse(session.blocks.isEmpty());assertFalse(session.blocks.get(0).editable);assertFalse(session.warnings.isEmpty());assertArrayEquals(bytes,session.export(Map.of()));assertArrayEquals(bytes,session.previewBytes(Map.of()));
            var block=session.blocks.get(0);assertArrayEquals(bytes,session.export(Map.of(block.id,block.original)));assertThrows(IOException.class,()->session.export(Map.of(block.id,"Changed")));
            assertTrue("Legacy text does not promise a slide or grid model",session.slides.isEmpty() && session.sheets.isEmpty());
        }
    }
}
