package org.smallthingz.alldoc.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class PresentationFidelityTest {
    private static java.nio.file.Path fixture(String name) {
        java.nio.file.Path root=java.nio.file.Path.of("fixtures");
        if(!java.nio.file.Files.isDirectory(root)) root=java.nio.file.Path.of("../fixtures");
        return root.resolve(name);
    }
    static final String P="http://schemas.openxmlformats.org/presentationml/2006/main", A="http://schemas.openxmlformats.org/drawingml/2006/main",R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static String wrap(String body){return "<p:sld xmlns:p='"+P+"' xmlns:a='"+A+"' xmlns:r='"+R+"'><p:cSld><p:spTree>"+body+"</p:spTree></p:cSld></p:sld>";}
    static String bounds(){return "<a:xfrm><a:off x='127000' y='254000'/><a:ext cx='1270000' cy='635000'/></a:xfrm>";}
    static String picture(String crop){return "<p:pic><p:blipFill><a:blip r:embed='im'/>"+crop+"<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>"+bounds()+"<a:prstGeom prst='rect'/></p:spPr></p:pic>";}
    static String shape(String properties,String text){return "<p:sp><p:spPr>"+properties+"</p:spPr><p:txBody><a:p>"+text+"</a:p></p:txBody></p:sp>";}
    static String rel(String id,String type,String target){return "<Relationship Id='"+id+"' Type='"+R+"/"+type+"' Target='"+target+"'/>";}
    static String rels(String body){return "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"+body+"</Relationships>";}
    static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static byte[] zip(Map<String,byte[]> parts)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(var e:parts.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return out.toByteArray();}
    static byte[] png(int width,int height)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(header);data.writeInt(width);data.writeInt(height);data.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",header.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DeflaterOutputStream def=new DeflaterOutputStream(compressed)){def.write(new byte[]{0,(byte)255,0,0,(byte)255});}chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    static void chunk(ByteArrayOutputStream out,String name,byte[] bytes)throws Exception{DataOutputStream d=new DataOutputStream(out);d.writeInt(bytes.length);byte[] type=utf(name);d.write(type);d.write(bytes);CRC32 crc=new CRC32();crc.update(type);crc.update(bytes);d.writeInt((int)crc.getValue());}
    static Map<String,byte[]> imageDeck(String image,String picture)throws Exception {
        Map<String,byte[]> parts=new LinkedHashMap<>();parts.put("ppt/slides/slide1.xml",utf(wrap(picture)));parts.put("ppt/slides/_rels/slide1.xml.rels",utf(rels(rel("im","image",image))));return parts;
    }
    @Test public void explicitStylesImagesAndOrderRemainSourcePreserving()throws Exception {
        String text="<a:pPr algn='ctr'/><a:r><a:rPr sz='2400' b='1' i='1' u='sng'><a:solidFill><a:srgbClr val='FFFFFF'/></a:solidFill><a:latin typeface='Example Serif'/></a:rPr><a:t>Title</a:t></a:r>";
        Map<String,byte[]> parts=imageDeck("../media/image.png",picture("")+shape(bounds()+"<a:prstGeom prst='rect'/><a:solidFill><a:srgbClr val='004488'/></a:solidFill>",text));parts.put("ppt/media/image.png",png(1,1));
        byte[] bytes=zip(parts);var session=DocumentEngine.open("slides.pptx",bytes);var slide=session.slides.get(0);var b=slide.blocks.get(0);var image=slide.images.get(0);
        assertEquals(24,b.style.fontSize,0);assertEquals("Example Serif",b.style.fontFamily);assertEquals(Integer.valueOf(0xffffffff),b.style.color);assertEquals(Integer.valueOf(0xff004488),b.fillColor);assertTrue(b.style.bold);assertTrue(b.style.italic);assertTrue(b.style.underline);assertEquals("center",b.style.alignment);
        assertEquals(10,image.x,0);assertEquals(20,image.y,0);assertEquals(100,image.width,0);assertEquals(50,image.height,0);assertTrue(image.zOrder<b.zOrder);assertEquals(1,image.pixelWidth);assertEquals("image/png",image.mimeType);
        byte[] copy=image.bytes();copy[0]=0;assertEquals((byte)137,image.bytes()[0]);assertArrayEquals(bytes,session.export(Map.of()));
        byte[] edited=session.export(Map.of("b0","Updated"));assertArrayEquals(parts.get("ppt/media/image.png"),DocumentEngineTest.unzip(edited).get("ppt/media/image.png"));assertEquals("Updated",DocumentEngine.open("slides.pptx",edited).blocks.get(0).original);assertArrayEquals(bytes,session.export(Map.of()));
    }
    @Test public void themeAndPlaceholderLayoutInheritance()throws Exception {
        Map<String,byte[]> parts=new LinkedHashMap<>();
        parts.put("ppt/slides/slide1.xml",utf(wrap("<p:sp><p:nvSpPr><p:nvPr><p:ph type='title' idx='0'/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:rPr><a:solidFill><a:schemeClr val='accent1'/></a:solidFill><a:latin typeface='+mj-lt'/></a:rPr><a:t>Inherited</a:t></a:r></a:p></p:txBody></p:sp>")));
        parts.put("ppt/slides/_rels/slide1.xml.rels",utf(rels(rel("layout","slideLayout","../slideLayouts/layout.xml"))));
        parts.put("ppt/slideLayouts/layout.xml",utf(wrap("<p:sp><p:nvSpPr><p:nvPr><p:ph type='title' idx='0'/></p:nvPr></p:nvSpPr><p:spPr>"+bounds()+"</p:spPr><p:txBody><a:lstStyle><a:lvl1pPr><a:defRPr sz='3200'/></a:lvl1pPr></a:lstStyle></p:txBody></p:sp>")));
        parts.put("ppt/slideLayouts/_rels/layout.xml.rels",utf(rels(rel("master","slideMaster","../slideMasters/master.xml"))));parts.put("ppt/slideMasters/master.xml",utf(wrap("")));
        parts.put("ppt/slideMasters/_rels/master.xml.rels",utf(rels(rel("theme","theme","../theme/theme.xml"))));parts.put("ppt/theme/theme.xml",utf("<a:theme xmlns:a='"+A+"'><a:themeElements><a:clrScheme><a:accent1><a:srgbClr val='00AA44'/></a:accent1></a:clrScheme><a:fontScheme><a:majorFont><a:latin typeface='Theme Serif'/></a:majorFont></a:fontScheme></a:themeElements></a:theme>"));
        var b=DocumentEngine.open("slides.pptx",zip(parts)).blocks.get(0);assertEquals(10,b.x,0);assertEquals(100,b.width,0);assertEquals(32,b.style.fontSize,0);assertEquals("Theme Serif",b.style.fontFamily);assertEquals(Integer.valueOf(0xff00aa44),b.style.color);
    }
    @Test public void croppedImageHasPlaceholderRatherThanIncorrectRaster()throws Exception {var parts=imageDeck("../media/x.png",picture("<a:srcRect l='10000'/>"));parts.put("ppt/media/x.png",png(1,1));var s=DocumentEngine.open("s.pptx",zip(parts));assertTrue(s.slides.get(0).images.isEmpty());assertEquals(1,s.slides.get(0).omissions.size());assertTrue(s.slides.get(0).warnings.stream().anyMatch(w->w.contains("Cropped")));}
    @Test public void rotatedImageHasNoGuessedGeometry()throws Exception {var parts=imageDeck("../media/x.png",picture("").replace("<a:xfrm>","<a:xfrm rot='60000'>"));parts.put("ppt/media/x.png",png(1,1));var s=DocumentEngine.open("s.pptx",zip(parts));assertTrue(s.slides.get(0).images.isEmpty());assertTrue(s.slides.get(0).omissions.isEmpty());assertTrue(s.slides.get(0).warnings.stream().anyMatch(w->w.contains("rotation")));}
    @Test public void externalImagesNeverLoad()throws Exception {var parts=imageDeck("https://example.com/private.png",picture(""));parts.put("ppt/slides/_rels/slide1.xml.rels",utf(rels(rel("im","image","https://example.com/x.png").replace("/>"," TargetMode='External'/>"))));var s=DocumentEngine.open("s.pptx",zip(parts));assertTrue(s.slides.get(0).images.isEmpty());assertFalse(s.slides.get(0).warnings.isEmpty());}
    @Test public void unsafeAndDuplicateImageRelationshipsFailClosed()throws Exception {for(String path:new String[]{"../../../escape.png","../media/%2e%2e/../escape.png","file:///etc/passwd","//example.org/x","../media/x.png?q=1"}){var parts=imageDeck(path,picture(""));assertThrows(Exception.class,()->DocumentEngine.open("s.pptx",zip(parts)));}var parts=imageDeck("../media/x.png",picture(""));parts.put("ppt/slides/_rels/slide1.xml.rels",utf(rels(rel("im","image","../media/x.png")+rel("im","image","../media/y.png"))));assertThrows(Exception.class,()->DocumentEngine.open("s.pptx",zip(parts)));}
    @Test public void oversizedDimensionsAndInvalidBytesAreOmitted()throws Exception {for(byte[] image:new byte[][]{png(9000,1),png(8192,8192),utf("<svg onload='evil()'/>")}){var parts=imageDeck("../media/x.png",picture(""));parts.put("ppt/media/x.png",image);var s=DocumentEngine.open("s.pptx",zip(parts));assertTrue(s.slides.get(0).images.isEmpty());assertEquals(1,s.slides.get(0).omissions.size());}}
    @Test public void imagePixelBudgetIsAggregate()throws Exception {var parts=imageDeck("../media/x.png",picture(""));parts.put("ppt/media/x.png",png(4096,4096));for(int i=2;i<=3;i++){parts.put("ppt/slides/slide"+i+".xml",utf(wrap(picture(""))));parts.put("ppt/slides/_rels/slide"+i+".xml.rels",utf(rels(rel("im","image","../media/x"+i+".png"))));parts.put("ppt/media/x"+i+".png",png(4096,4096));}var s=DocumentEngine.open("s.pptx",zip(parts));assertEquals(1,s.slides.get(0).images.size());assertEquals(1,s.slides.get(1).images.size());assertEquals(0,s.slides.get(2).images.size());}
    @Test public void standaloneRectanglesKeepLayerOrder()throws Exception {String rect="<p:sp><p:spPr>"+bounds()+"<a:prstGeom prst='rect'/><a:solidFill><a:srgbClr val='123456'/></a:solidFill></p:spPr></p:sp>";var s=DocumentEngine.open("s.pptx",zip(Map.of("ppt/slides/slide1.xml",utf(wrap(rect+shape(bounds(),"<a:r><a:t>Text</a:t></a:r>"))))));assertEquals(1,s.slides.get(0).fills.size());assertEquals(0xff123456,s.slides.get(0).fills.get(0).color);assertTrue(s.slides.get(0).fills.get(0).zOrder<s.blocks.get(0).zOrder);}
    static String odf(String picture){return "<office:document xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:draw='urn:oasis:names:tc:opendocument:xmlns:drawing:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0' xmlns:style='urn:oasis:names:tc:opendocument:xmlns:style:1.0' xmlns:fo='urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0' xmlns:svg='urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0' xmlns:xlink='http://www.w3.org/1999/xlink'><office:automatic-styles><style:style style:name='base' style:family='paragraph'><style:text-properties fo:font-size='18pt' fo:color='#AABBCC' fo:font-weight='bold'/></style:style><style:style style:name='child' style:family='paragraph' style:parent-style-name='base'><style:paragraph-properties fo:text-align='center'/></style:style></office:automatic-styles><office:body><office:presentation><draw:page draw:name='Example'><draw:frame svg:x='1in' svg:y='1in' svg:width='2in' svg:height='1in'><draw:text-box><text:p text:style-name='child'>Styled</text:p></draw:text-box></draw:frame><draw:frame svg:x='3in' svg:y='1in' svg:width='1in' svg:height='1in'>"+picture+"</draw:frame></draw:page></office:presentation></office:body></office:document>";}
    @Test public void flatOdfStylesAndInlineRasterRoundTrip()throws Exception {byte[] original=utf(odf("<draw:image><office:binary-data>"+Base64.getEncoder().encodeToString(png(1,1))+"</office:binary-data></draw:image>"));var s=DocumentEngine.open("slides.fodp",original);assertEquals(1,s.slides.get(0).images.size());assertEquals(18,s.blocks.get(0).style.fontSize,0);assertTrue(s.blocks.get(0).style.bold);assertEquals("center",s.blocks.get(0).style.alignment);assertEquals(Integer.valueOf(0xffaabbcc),s.blocks.get(0).style.color);assertArrayEquals(original,s.export(Map.of()));var edited=DocumentEngine.open("slides.fodp",s.export(Map.of("b0","Updated")));assertEquals("Updated",edited.blocks.get(0).original);assertEquals(1,edited.slides.get(0).images.size());}
    @Test public void packagedOdfRasterIsPreservedAndExternalOmitted()throws Exception {byte[] original=zip(Map.of("content.xml",utf(odf("<draw:image xlink:href='Pictures/a.png'/>")),"Pictures/a.png",png(1,1)));var s=DocumentEngine.open("slides.odp",original);assertEquals(1,s.slides.get(0).images.size());assertArrayEquals(png(1,1),DocumentEngineTest.unzip(s.export(Map.of("b0","New"))).get("Pictures/a.png"));var external=DocumentEngine.open("slides.fodp",utf(odf("<draw:image xlink:href='https://example.com/a.png'/>")));assertTrue(external.slides.get(0).images.isEmpty());assertEquals(1,external.slides.get(0).omissions.size());}

    @Test public void jpegHeaderAndSourceBytesAreBounded()throws Exception {
        byte[] jpeg=java.nio.file.Files.readAllBytes(fixture("presentation-raster.jpg"));
        var parts=imageDeck("../media/x.jpg",picture(""));parts.put("ppt/media/x.jpg",jpeg);var s=DocumentEngine.open("s.pptx",zip(parts));
        assertEquals("image/jpeg",s.slides.get(0).images.get(0).mimeType);assertEquals(320,s.slides.get(0).images.get(0).pixelWidth);assertArrayEquals(jpeg,s.slides.get(0).images.get(0).bytes());
        parts.put("ppt/media/x.jpg",Arrays.copyOf(jpeg,jpeg.length-1));assertTrue(DocumentEngine.open("s.pptx",zip(parts)).slides.get(0).images.isEmpty());
    }
    @Test public void badPngCrcAndByteBudgetAreOmitted()throws Exception {
        byte[] png=png(1,1);png[29]^=1;var parts=imageDeck("../media/x.png",picture(""));parts.put("ppt/media/x.png",png);assertTrue(DocumentEngine.open("s.pptx",zip(parts)).slides.get(0).images.isEmpty());
        parts.put("ppt/media/x.png",new byte[DocumentEngine.MAX_IMAGE_BYTES+1]);var s=DocumentEngine.open("s.pptx",zip(parts));assertTrue(s.slides.get(0).images.isEmpty());assertTrue(s.slides.get(0).warnings.stream().anyMatch(w->w.contains("byte budget")));
    }
    @Test public void generatedFidelityFixturesOpenAndRemainExact()throws Exception {
        for(String suffix:new String[]{"pptx","odp","fodp"}){byte[] bytes=java.nio.file.Files.readAllBytes(fixture("presentation-fidelity."+suffix));var s=DocumentEngine.open("fixture."+suffix,bytes);assertFalse(s.slides.get(0).images.isEmpty());assertArrayEquals(bytes,s.export(Map.of()));assertTrue(s.blocks.get(0).style.fontSize>0);}
    }
    @Test public void odfCyclicStylesAndHostileFontSizesStayBounded()throws Exception {
        String xml=odf("<draw:image/>").replace("style:name='base' style:family='paragraph'","style:name='base' style:family='paragraph' style:parent-style-name='child'").replace("18pt","1e999pt");
        var s=DocumentEngine.open("s.fodp",utf(xml));assertEquals(-1,s.blocks.get(0).style.fontSize,0);assertTrue(s.warnings.stream().anyMatch(w->w.contains("Cyclic")));assertEquals("b0",s.blocks.get(0).id);
    }
    @Test public void rtfEngineAdapterPreservesStylesParagraphsAndEdits()throws Exception {
        byte[] bytes="{\\rtf1\\ansi First \\b bold\\b0\\par Second}".getBytes(StandardCharsets.US_ASCII);
        var s=DocumentEngine.open("s.rtf",bytes);assertTrue(s.blocks.stream().anyMatch(b->b.style.bold));assertTrue(s.blocks.stream().map(b->b.paragraphId).distinct().count()>1);assertArrayEquals(bytes,s.export(Map.of()));
        var target=s.blocks.stream().filter(b->b.original.contains("bold")).findFirst().orElseThrow();byte[] edited=s.export(Map.of(target.id,"changed"));assertTrue(DocumentEngine.open("s.rtf",edited).blocks.stream().anyMatch(b->b.original.contains("changed")));assertArrayEquals(bytes,s.export(Map.of()));
    }
    @Test public void unspecifiedStyleColorRemainsUnresolved()throws Exception {
        var s=DocumentEngine.open("s.fodp",utf(odf("<draw:image/>").replace(" fo:color='#AABBCC'","")));assertNull(s.blocks.get(0).style.color);assertEquals(18,s.blocks.get(0).style.fontSize,0);
    }

    private static Map<String,byte[]> inheritedDeck(String shapeBody,String layoutShape,String masterShape) {
        Map<String,byte[]> parts=new LinkedHashMap<>();
        parts.put("ppt/slides/slide1.xml",utf(wrap(shapeBody)));
        parts.put("ppt/slides/_rels/slide1.xml.rels",utf(rels(rel("layout","slideLayout","../slideLayouts/layout.xml"))));
        parts.put("ppt/slideLayouts/layout.xml",utf(wrap(layoutShape)));
        parts.put("ppt/slideLayouts/_rels/layout.xml.rels",utf(rels(rel("master","slideMaster","../slideMasters/master.xml"))));
        parts.put("ppt/slideMasters/master.xml",utf(wrap(masterShape)));
        parts.put("ppt/slideMasters/_rels/master.xml.rels",utf(rels(rel("theme","theme","../theme/theme.xml"))));
        parts.put("ppt/theme/theme.xml",utf("<a:theme xmlns:a='"+A+"'><a:themeElements><a:fontScheme><a:majorFont><a:latin typeface='Major Font'/></a:majorFont><a:minorFont><a:latin typeface='Minor Font'/></a:minorFont></a:fontScheme></a:themeElements></a:theme>"));
        return parts;
    }
    private static String placeholderShape(String props,String style,String text) {
        return "<p:sp><p:nvSpPr><p:nvPr><p:ph type='title' idx='0'/></p:nvPr></p:nvSpPr><p:spPr>"+props+"</p:spPr>"+style+"<p:txBody><a:p><a:r><a:t>"+text+"</a:t></a:r></a:p></p:txBody></p:sp>";
    }
    @Test public void groupedPlaceholderNeverBorrowsUntransformedLayoutBounds()throws Exception {
        String local=placeholderShape("","","Grouped text");
        var parts=inheritedDeck("<p:grpSp><p:grpSpPr><a:xfrm rot='60000'/></p:grpSpPr>"+local+"</p:grpSp>",placeholderShape(bounds(),"",""),"");
        var session=DocumentEngine.open("s.pptx",zip(parts));
        assertTrue(session.blocks.get(0).width<0);
        assertTrue(session.slides.get(0).warnings.stream().anyMatch(w->w.contains("geometry")));
        assertEquals("Grouped text",session.blocks.get(0).original);
    }
    @Test public void customGeometryNeverTurnsIntoRectangularTextFill()throws Exception {
        String fill="<a:solidFill><a:srgbClr val='FF0000'/></a:solidFill>";
        var parts=inheritedDeck(placeholderShape(bounds()+"<a:custGeom/>"+fill,"","Vector text"),placeholderShape(bounds()+"<a:prstGeom prst='rect'/>"+fill,"",""),"");
        var session=DocumentEngine.open("s.pptx",zip(parts));
        assertNull(session.blocks.get(0).fillColor);
        assertTrue(session.slides.get(0).warnings.stream().anyMatch(w->w.contains("non-rectangular")));
    }
    @Test public void explicitLocalMinorFontReferenceOverridesInheritedMajor()throws Exception {
        String major="<p:style><a:fontRef idx='major'/></p:style>",minor="<p:style><a:fontRef idx='minor'/></p:style>";
        var parts=inheritedDeck(placeholderShape(bounds(),minor,"Local minor"),placeholderShape(bounds(),major,""),placeholderShape(bounds(),major,""));
        assertEquals("Minor Font",DocumentEngine.open("s.pptx",zip(parts)).blocks.get(0).style.fontFamily);
    }
    @Test public void groupedLayoutPlaceholderNeverBorrowsPlainMasterBounds()throws Exception {
        String layout="<p:grpSp><p:grpSpPr><a:xfrm rot='60000'/></p:grpSpPr>"+placeholderShape("","","")+"</p:grpSp>";
        var parts=inheritedDeck(placeholderShape("","","Body"),layout,placeholderShape(bounds(),"",""));
        var session=DocumentEngine.open("s.pptx",zip(parts));
        assertTrue(session.blocks.get(0).width<0);
        assertTrue(session.slides.get(0).warnings.stream().anyMatch(w->w.contains("geometry")));
    }
    @Test public void localFirstParagraphDoesNotSetLaterParagraphDefaults()throws Exception {
        String text="<a:pPr algn='ctr'><a:defRPr b='1' sz='3600'/></a:pPr><a:r><a:t>First</a:t></a:r></a:p><a:p><a:r><a:t>Second</a:t></a:r>";
        var session=DocumentEngine.open("s.pptx",zip(Map.of("ppt/slides/slide1.xml",utf(wrap(shape(bounds(),text))))));
        assertEquals("center",session.blocks.get(0).style.alignment);assertTrue(session.blocks.get(0).style.bold);
        assertEquals(36,session.blocks.get(0).style.fontSize,0);
        assertEquals("left",session.blocks.get(1).style.alignment);assertFalse(session.blocks.get(1).style.bold);
        assertEquals(-1,session.blocks.get(1).style.fontSize,0);
    }
    private static Map<String,byte[]> repeatedMaster(String master,int slides) {
        var parts=inheritedDeck(placeholderShape(bounds(),"","Retained text"),"",master);
        for(int i=2;i<=slides;i++) {
            parts.put("ppt/slides/slide"+i+".xml",parts.get("ppt/slides/slide1.xml"));
            parts.put("ppt/slides/_rels/slide"+i+".xml.rels",parts.get("ppt/slides/_rels/slide1.xml.rels"));
        }
        return parts;
    }
    @Test public void repeatedMasterGraphicsHaveOneGlobalDerivedObjectBudget()throws Exception {
        String rectangle="<p:sp><p:spPr>"+bounds()+"<a:prstGeom prst='rect'/><a:solidFill><a:srgbClr val='123456'/></a:solidFill></p:spPr></p:sp>";
        byte[] original=zip(repeatedMaster(rectangle.repeat(48),24));
        var session=DocumentEngine.open("s.pptx",original);
        int objects=session.slides.stream().mapToInt(s->s.images.size()+s.fills.size()+s.omissions.size()).sum();
        assertEquals(PresentationReader.MAX_PREVIEW_OBJECTS,objects);
        assertEquals(24,session.blocks.size());
        assertTrue(session.warnings.stream().anyMatch(w->w.contains("shared preview complexity")));
        assertArrayEquals(original,session.export(Map.of()));
    }
    @Test public void repeatedUnsupportedMasterShapesHaveAGlobalProcessingBudget()throws Exception {
        String ignored="<p:sp><p:spPr><a:prstGeom prst='ellipse'/></p:spPr></p:sp>";
        var session=DocumentEngine.open("s.pptx",zip(repeatedMaster(ignored.repeat(48),90)));
        assertEquals(90,session.blocks.size());
        assertEquals(0,session.slides.stream().mapToInt(s->s.images.size()+s.fills.size()+s.omissions.size()).sum());
        assertTrue(session.warnings.stream().anyMatch(w->w.contains("shared preview complexity")));
    }

}
