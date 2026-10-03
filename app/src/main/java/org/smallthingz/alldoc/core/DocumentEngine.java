package org.smallthingz.alldoc.core;

import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, offline document engine. Presentation preview is a declared subset, not office rendering. */
public final class DocumentEngine {
    public static final int MAX_FILE = 32 * 1024 * 1024;
    public static final int MAX_EXPANDED = 64 * 1024 * 1024;
    public static final int MAX_PART = 8 * 1024 * 1024;
    public static final int MAX_BLOCKS = 12000;
    public static final int MAX_TEXT = 2 * 1024 * 1024;
    public static final int MAX_EDIT_CHARACTERS = 4 * 1024 * 1024;
    public static final int MAX_XML_DEPTH = 128;
    public static final int MAX_XML_NODES = 100000;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    private static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    public static final int MAX_ROWS = 1048576, MAX_COLUMNS = 16384;
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String O = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String T = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    public static final int MAX_IMAGE_BYTES=2*1024*1024, MAX_IMAGE_TOTAL_BYTES=8*1024*1024;
    public static final int MAX_IMAGE_DIMENSION=8192, MAX_IMAGE_PIXELS=16*1024*1024, MAX_IMAGE_TOTAL_PIXELS=32*1024*1024;
    /** Resolved preview style. Fonts may be substituted by the host; source style XML is preserved. */
    public static final class TextStyle {
        public final String fontFamily, alignment;
        public final double fontSize;
        public final Integer color;
        public final boolean bold, italic, underline, strike;
        public static final TextStyle DEFAULT=new TextStyle("",-1,null,false,false,false,false,"left");
        TextStyle(String fontFamily,double fontSize,Integer color,boolean bold,boolean italic,boolean underline,boolean strike,String alignment) {
            this.fontFamily=fontFamily;this.fontSize=fontSize;this.color=color;this.bold=bold;this.italic=italic;this.underline=underline;this.strike=strike;this.alignment=alignment;
        }
    }
    /** Bounded embedded PNG/JPEG only. bytes() never exposes the preserved package array. */
    public static final class SlideImage {
        public final String id,mimeType;
        public final double x,y,width,height;
        public final int zOrder,pixelWidth,pixelHeight;
        private final byte[] data;
        SlideImage(String id,String mimeType,double[] bounds,int zOrder,int pixelWidth,int pixelHeight,byte[] data) {
            this.id=id;this.mimeType=mimeType;this.x=bounds[0];this.y=bounds[1];this.width=bounds[2];this.height=bounds[3];this.zOrder=zOrder;
            this.pixelWidth=pixelWidth;this.pixelHeight=pixelHeight;this.data=data;
        }
        public byte[] bytes(){return data.clone();}
    }
    /** A truthful placeholder only when the omitted image has safe, untransformed bounds. */
    public static final class SlideFill {
        public final String id;
        public final double x,y,width,height;
        public final int zOrder,color;
        SlideFill(String id,double[] bounds,int zOrder,int color){this.id=id;this.x=bounds[0];this.y=bounds[1];this.width=bounds[2];this.height=bounds[3];this.zOrder=zOrder;this.color=color;}
    }
    public static final class SlideOmission {
        public final String id,reason;
        public final double x,y,width,height;
        public final int zOrder;
        SlideOmission(String id,String reason,double[] bounds,int zOrder) {
            this.id=id;this.reason=reason;this.x=bounds[0];this.y=bounds[1];this.width=bounds[2];this.height=bounds[3];this.zOrder=zOrder;
        }
    }
    public static final class Block {
        public final String id, paragraphId, section, label, original, shapeId, valueType, formula;
        public final boolean editable;
        /** One-based worksheet positions; zero means this is not a cell. Repeats are compact ranges. */
        public final int row, column, rowSpan, columnSpan, rowRepeat, columnRepeat;
        /** Text frame bounds in points, or -1 when layout cannot be represented reliably. */
        public final double x, y, width, height;
        public final TextStyle style;
        public final int zOrder;
        public final Integer fillColor;
        final Node node;
        final String part;
        Block(String id, String section, String label, String text, boolean editable, Node node, String part) {
            this(id,section,label,text,editable,node,part,0,0,1,1,1,1,"","");
        }
        Block(String id, String section, String label, String text, boolean editable, Node node, String part,
              int row, int column, int rowSpan, int columnSpan, int rowRepeat, int columnRepeat, String valueType, String formula) {
            this.id=id; this.paragraphId=paragraphId(node,part,id); this.section=section; this.label=label; this.original=text; this.editable=editable; this.node=node; this.part=part;
            this.row=row;this.column=column;this.rowSpan=rowSpan;this.columnSpan=columnSpan;this.rowRepeat=rowRepeat;this.columnRepeat=columnRepeat;this.valueType=valueType;this.formula=formula;
            Node shape=ancestorShape(node);this.shapeId=shape==null?paragraphId:nodePath(shape,part);
            double[] bounds=shapeBounds(shape);this.x=bounds[0];this.y=bounds[1];this.width=bounds[2];this.height=bounds[3];
            this.style=TextStyle.DEFAULT;this.zOrder=0;this.fillColor=null;
        }
        private Block(Block b,TextStyle style,double[] bounds,int zOrder,Integer fillColor,String paragraphId) {
            this.id=b.id;this.paragraphId=paragraphId;this.section=b.section;this.label=b.label;this.original=b.original;this.editable=b.editable;
            this.node=b.node;this.part=b.part;this.row=b.row;this.column=b.column;this.rowSpan=b.rowSpan;this.columnSpan=b.columnSpan;
            this.rowRepeat=b.rowRepeat;this.columnRepeat=b.columnRepeat;this.valueType=b.valueType;this.formula=b.formula;this.shapeId=b.shapeId;
            this.x=bounds[0];this.y=bounds[1];this.width=bounds[2];this.height=bounds[3];this.style=style;this.zOrder=zOrder;this.fillColor=fillColor;
        }
    }
    // Copy only preview metadata; edit targets still refer to original DOM nodes.
    static Block presentationBlock(Block b,TextStyle style,double[] bounds,int zOrder,Integer fillColor) {
        return new Block(b,style,bounds,zOrder,fillColor,b.paragraphId);
    }
    public static final class Slide {
        public final String id, title;
        public final int number;
        public final double width, height;
        public final List<Block> blocks;
        public final List<SlideImage> images;
        public final List<SlideOmission> omissions;
        public final List<SlideFill> fills;
        public final List<String> warnings;
        public final Integer backgroundColor;
        Slide(String id,String title,int number,double width,double height,List<Block> blocks) {
            this(id,title,number,width,height,blocks,Collections.emptyList(),null,Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
        }
        Slide(String id,String title,int number,double width,double height,List<Block> blocks,List<SlideImage> images,Integer backgroundColor,List<SlideOmission> omissions,List<String> warnings,List<SlideFill> fills) {
            this.id=id;this.title=title;this.number=number;this.width=width;this.height=height;
            this.blocks=Collections.unmodifiableList(new ArrayList<>(blocks));
            this.images=Collections.unmodifiableList(new ArrayList<>(images));this.backgroundColor=backgroundColor;
            this.omissions=Collections.unmodifiableList(new ArrayList<>(omissions));this.warnings=Collections.unmodifiableList(new ArrayList<>(warnings));
            this.fills=Collections.unmodifiableList(new ArrayList<>(fills));
        }
    }
    public static final class Sheet {
        public final String id, name;
        public final List<Block> blocks;
        Sheet(String id,String name,List<Block> blocks) {this.id=id;this.name=name;this.blocks=Collections.unmodifiableList(new ArrayList<>(blocks));}
    }
    public static final class Session {
        public final String name, extension, description;
        public final List<Block> blocks = new ArrayList<>();
        public final List<Slide> slides = new ArrayList<>();
        public final List<Sheet> sheets = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        private boolean flatOdf;
        private RtfDocument rtf;
        private long extractedCharacters;
        private final byte[] originalBytes;
        public final boolean editable;
        private final LinkedHashMap<String,byte[]> entries = new LinkedHashMap<>();
        private final Map<String,Document> documents = new HashMap<>();
        private final XmlBudget xmlBudget = new XmlBudget();
        private final Charset charset;
        private final byte[] bom;
        Session(String name, String extension, String description, byte[] originalBytes, boolean editable, Charset charset, byte[] bom) {
            this.name=name; this.extension=extension; this.description=description; this.originalBytes=originalBytes.clone(); this.editable=editable; this.charset=charset; this.bom=bom;
        }
        public byte[] export(Map<String,String> changes) throws Exception {return export(changes,true);}
        /** Strip alternate ZIP header names and extras before a second parser sees this package. */
        public byte[] previewBytes(Map<String,String> changes) throws Exception {return export(changes,false);}
        private byte[] export(Map<String,String> changes,boolean preserveUnchanged) throws Exception {
            if (changes.isEmpty() && preserveUnchanged) return originalBytes.clone();
            Map<String,String> actual=new HashMap<>();
            Set<String> ids=new HashSet<>();
            int editedCharacters=0;
            for(Block b:blocks) {
                ids.add(b.id);
                if(changes.containsKey(b.id) && !Objects.equals(changes.get(b.id), b.original)) {
                    if(!b.editable) throw new IOException("This block cannot be changed safely");
                    String v=changes.get(b.id);
                    if(v==null || v.length()>MAX_TEXT) throw new IOException("Edit is too large");
                    editedCharacters+=v.length();
                    if(editedCharacters>MAX_EDIT_CHARACTERS) throw new IOException("Combined edits exceed the safe limit");
                    validateXmlCharacters(v);
                    if(extension.equals("ods") || extension.equals("fods"))validateOdfEditComplexity(v);
                    actual.put(b.id,v);
                }
            }
            if(!ids.containsAll(changes.keySet())) throw new IOException("Unknown edit target");
            if(actual.isEmpty() && (preserveUnchanged || !editable)) return originalBytes.clone();
            if(!editable)throw new IOException("This document is read-only");
            if(rtf!=null)return rtf.export(actual);
            if(entries.isEmpty()) {
                String text=actual.getOrDefault("text",blocks.get(0).original);
                LimitedOutput out=new LimitedOutput(MAX_TEXT); out.write(bom); out.write(text.getBytes(charset));
                byte[] result=out.toByteArray();
                if((extension.equals("xml") || extension.equals("svg"))){validateXmlEncoding(text,charset);validateXml(result,new XmlBudget());}
                return result;
            }
            // Reparse for every export. Failed/cancelled exports cannot mutate the session.
            Map<String,Document> edited=new HashMap<>();
            for(Block b:blocks) if(actual.containsKey(b.id)) {
                Document doc=edited.computeIfAbsent(b.part,p->{try{return parse(entries.get(p));}catch(Exception e){throw new IllegalStateException(e);}});
                Node target=locate(doc,b.node);
                if(extension.equals("xlsx")) {
                    Element cell=(Element)target;
                    while(cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
                    cell.setAttribute("t","inlineStr");
                    Element is=doc.createElementNS(S,"is"), t=doc.createElementNS(S,"t");
                    t.setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve");
                    t.setTextContent(actual.get(b.id)); is.appendChild(t); cell.appendChild(is);
                } else if(extension.equals("ods") || extension.equals("fods")) {
                    Element cell=(Element)target;
                    // Only plain string cells are editable; retain cell attributes and style references.
                    while(cell.hasChildNodes())cell.removeChild(cell.getFirstChild());
                    cell.removeAttributeNS(O,"string-value");
                    cell.setAttributeNS(O,"office:value-type","string");
                    String[] lines=actual.get(b.id).split("\\n",-1);
                    for(String line:lines){Element paragraph=doc.createElementNS(T,"text:p");appendOdfText(doc,paragraph,line);cell.appendChild(paragraph);}
                } else {
                    target.setNodeValue(actual.get(b.id));
                    if(target.getParentNode() instanceof Element && (extension.equals("docx") || extension.equals("pptx")))
                        ((Element)target.getParentNode()).setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve");
                }
            }
            XmlBudget exportBudget=new XmlBudget();
            Map<String,byte[]> replacements=new HashMap<>();
            for(Map.Entry<String,Document> e:edited.entrySet())replacements.put(e.getKey(),serialize(e.getValue()));
            for(Map.Entry<String,byte[]> e:entries.entrySet()) {
                String path=e.getKey().toLowerCase(Locale.ROOT);
                if(path.endsWith(".xml") || path.endsWith(".rels") || replacements.containsKey(e.getKey()))validateXml(replacements.getOrDefault(e.getKey(),e.getValue()),exportBudget);
            }
            if(flatOdf)return replacements.getOrDefault("content.xml",originalBytes).clone();
            LimitedOutput out=new LimitedOutput(MAX_FILE);
            try(ZipOutputStream zip=new ZipOutputStream(out)) {
                List<Map.Entry<String,byte[]>> ordered=new ArrayList<>(entries.entrySet());
                ordered.sort(Comparator.comparingInt(e->e.getKey().equals("mimetype")?0:1));
                int expanded=0;
                for(Map.Entry<String,byte[]> e:ordered) {
                    byte[] bytes=replacements.getOrDefault(e.getKey(),e.getValue());
                    expanded+=bytes.length;
                    if(expanded>MAX_EXPANDED) throw new IOException("Expanded export exceeds safe limit");
                    ZipEntry ze=new ZipEntry(e.getKey());
                    // ODF requires the first mimetype member to be uncompressed.
                    if(e.getKey().equals("mimetype")){ze.setMethod(ZipEntry.STORED); ze.setSize(bytes.length); CRC32 crc=new CRC32();crc.update(bytes);ze.setCrc(crc.getValue());}
                    zip.putNextEntry(ze); zip.write(bytes); zip.closeEntry();
                }
            }
            if(out.size()>MAX_FILE) throw new IOException("Export exceeds size limit");
            return out.toByteArray();
        }
    }
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int max;
        LimitedOutput(int max){this.max=max;}
        @Override public void write(int value)throws IOException {check(1);bytes.write(value);}
        @Override public void write(byte[] value,int offset,int length)throws IOException {check(length);bytes.write(value,offset,length);}
        private void check(int length)throws IOException {if(length>max-bytes.size())throw new IOException("Output exceeds safe size limit");}
        int size(){return bytes.size();}
        byte[] toByteArray(){return bytes.toByteArray();}
    }
    private static final class XmlBudget { int nodes; }
    public static Session open(String name, byte[] bytes) throws Exception {
        if(bytes.length>MAX_FILE) throw new IOException("File exceeds the 32 MiB safe import limit");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(Set.of("txt","md","csv","tsv","log","json","xml","yaml","yml","html","htm","css","svg").contains(ext)) {
            Charset cs=StandardCharsets.UTF_8; int offset=0;
            if(bytes.length>=3 && bytes[0]==(byte)239 && bytes[1]==(byte)187 && bytes[2]==(byte)191)offset=3;
            else if(bytes.length>=2 && bytes[0]==(byte)255 && bytes[1]==(byte)254){cs=StandardCharsets.UTF_16LE;offset=2;}
            else if(bytes.length>=2 && bytes[0]==(byte)254 && bytes[1]==(byte)255){cs=StandardCharsets.UTF_16BE;offset=2;}
            if(bytes.length>MAX_TEXT)throw new IOException("Text editing is limited to 2 MiB; use a large-file editor");
            String text=cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
            if(text.indexOf('\0')>=0)throw new IOException("Binary content is not supported as text");
            if((ext.equals("xml") || ext.equals("svg"))){validateXmlEncoding(text,cs);validateXml(bytes,new XmlBudget());}
            Session s=new Session(name,ext,(ext.equals("xml") || ext.equals("svg"))?"Validated XML source • well-formedness checked on open and export; DTDs and external entities blocked":Set.of("html","htm","css").contains(ext)?"Inert source editor • scripts, styles and external resources are never executed":"Plain-text editor • original encoding and line endings retained",bytes,true,cs,Arrays.copyOf(bytes,offset));
            s.blocks.add(new Block("text","Document",ext.equals("csv")?"CSV source (no formula execution)":"Text",text,true,null,null)); return s;
        }
        if(ext.equals("rtf")) {
            RtfDocument parsed=RtfDocument.open(bytes);
            Session s=new Session(name,ext,"RTF formatted text • bounded style preview; source-preserving text edits",bytes,parsed.editable,null,null);
            s.rtf=parsed;s.warnings.addAll(parsed.warnings);
            for(RtfDocument.Run run:parsed.runs) {
                Block b=new Block(run.id,"Document","Text run",run.text,run.editable,null,null);
                TextStyle style=new TextStyle("",run.fontSizeHalfPoints/2.0,null,run.bold,run.italic,run.underline,run.strike,"left");
                add(s,new Block(b,style,new double[]{-1,-1,-1,-1},0,null,run.paragraphId));
            }
            if(s.blocks.isEmpty())s.warnings.add("No visible text was found. Embedded objects and pictures remain preserved in the original.");
            return s;
        }
        if(Set.of("doc","xls","ppt").contains(ext)) {
            LegacyOfficeExtractor.Result extracted=LegacyOfficeExtractor.extract(ext,bytes);
            Session legacy=new Session(name,ext,"Legacy Office • read-only text extraction; original-byte copies only",bytes,false,null,null);
            for(LegacyOfficeExtractor.Section section:extracted.sections)if(!section.text.isEmpty())
                add(legacy,new Block("b"+legacy.blocks.size(),section.title,"Extracted text • read-only",section.text,false,null,null));
            if(legacy.blocks.isEmpty())throw new IOException("No readable text was found in this legacy document");
            legacy.warnings.add(extracted.warning);return legacy;
        }
        if(Set.of("fodt","fods","fodp").contains(ext)) {
            if(bytes.length>MAX_PART)throw new IOException("Flat OpenDocument exceeds the 8 MiB XML limit");
            Session flat=new Session(name,ext,"Flat OpenDocument • structured content, not full office layout",bytes,true,null,null);
            flat.flatOdf=true;validateXml(bytes,flat.xmlBudget);flat.entries.put("content.xml",bytes);openOdf(flat);return flat;
        }
        if(!Set.of("docx","xlsx","pptx","odt","ods","odp").contains(ext))
            throw new IOException("."+ext+" is not supported by the offline editor. Original is unchanged. Convert this format in a trusted office application.");
        Session s=new Session(name,ext,"Structured content • not a page-layout preview. Export a copy; review it in your office app.",bytes,true,null,null);
        int total=0,count=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                String p=e.getName();
                if(++count>3000 || unsafeEntryName(p) || s.entries.containsKey(p))throw new IOException("Unsafe or ambiguous archive");
                byte[] data=readBounded(zip,MAX_PART); total+=data.length;
                if(total>MAX_EXPANDED)throw new IOException("Expanded archive exceeds safe limit");
                if(p.toLowerCase(Locale.ROOT).contains("vbaproject") || p.startsWith("_xmlsignatures/") || p.matches("META-INF/(document|macro|package)signatures.*") || p.startsWith("Basic/") || p.startsWith("Scripts/"))throw new IOException("Macro-enabled or signed documents require a trusted office application");
                String lower=p.toLowerCase(Locale.ROOT);
                if(lower.endsWith(".xml") || lower.endsWith(".rels"))validateXml(data,s.xmlBudget);
                s.entries.put(p,data);
            }
        }
        if(s.entries.isEmpty())throw new IOException("Not a supported ZIP document (it may be encrypted)");
        if(!ext.startsWith("od"))validateReferencedXml(s);
        if(ext.startsWith("od")) {
            openOdf(s);
        } else if(ext.equals("docx")) {
            load(s,"word/document.xml");
            boolean protectedDocument=false;
            if(s.entries.containsKey("word/settings.xml")) {
                NodeList protection=load(s,"word/settings.xml").getElementsByTagNameNS(W,"documentProtection");
                for(int i=0;i<protection.getLength();i++){String enforced=((Element)protection.item(i)).getAttributeNS(W,"enforcement");if(!enforced.equals("0") && !enforced.equals("false") && !enforced.equals("off"))protectedDocument=true;}
            }
            if(protectedDocument)s.warnings.add("Document editing protection is enabled; text is read-only.");
            for(String p:s.entries.keySet())if(p.equals("word/document.xml") || p.matches("word/(header|footer)[0-9]+\\.xml") || p.equals("word/footnotes.xml") || p.equals("word/endnotes.xml")) {
                Document doc=load(s,p);NodeList texts=doc.getElementsByTagNameNS(W,"t");
                for(int i=0;i<texts.getLength();i++)addTextNodes(s,texts.item(i),p,p.substring(5),"Text run "+(i+1),!protectedDocument);
            }
        } else if(ext.equals("pptx")) {
            openPptx(s);
        } else {
            openXlsx(s);
        }
        if(s.blocks.isEmpty() && s.slides.isEmpty() && s.sheets.isEmpty())throw new IOException("No readable text was found. Images and drawings need a full office application.");
        return s;
    }
    private static void openPptx(Session s)throws Exception {
        List<String> parts=orderedParts(s,"ppt/presentation.xml",P,"sldId","slide","ppt/slides/slide[0-9]+\\.xml");
        double width=960,height=540;boolean protectedPresentation=false;
        if(s.entries.containsKey("ppt/presentation.xml")) {
            Document presentation=load(s,"ppt/presentation.xml");
            protectedPresentation=presentation.getElementsByTagNameNS(P,"modifyVerifier").getLength()>0;
            NodeList sizes=presentation.getElementsByTagNameNS(P,"sldSz");
            if(sizes.getLength()>0){Element size=(Element)sizes.item(0);width=positiveNumber(size.getAttribute("cx"))/12700; height=positiveNumber(size.getAttribute("cy"))/12700;}
            else s.warnings.add("Slide size is missing; preview uses a 16:9 canvas.");
            validateCanvas(width,height);
        }
        PresentationReader preview=new PresentationReader(s.entries,s.documents,s.warnings);
        for(String part:parts) {
            Document doc=load(s,part);int start=s.blocks.size(),number=s.slides.size()+1;
            NodeList texts=doc.getElementsByTagNameNS(A,"t");
            for(int i=0;i<texts.getLength();i++)addTextNodes(s,texts.item(i),part,"Slide "+number,"Text run "+(i+1),!protectedPresentation);
            List<Block> blocks=new ArrayList<>(s.blocks.subList(start,s.blocks.size()));
            Slide slide=preview.pptx(part,slideTitle(blocks,"Slide "+number),number,width,height,blocks);
            for(int i=0;i<slide.blocks.size();i++)s.blocks.set(start+i,slide.blocks.get(i));
            s.slides.add(slide);
        }
        s.warnings.add("Slide preview supports text styles, inherited placeholder layout and embedded PNG/JPEG. Font substitution and text wrapping may differ; vectors, charts, effects and animations require an office app.");
    }
    private static List<String> orderedParts(Session s,String main,String ns,String local,String relation,String fallback)throws Exception {
        List<String> result=new ArrayList<>();
        if(!s.entries.containsKey(main)) {
            for(String part:s.entries.keySet())if(part.matches(fallback))result.add(part);
            result.sort(Comparator.comparingInt((String p)->p.replaceAll("\\D","").length()).thenComparing(p->p.replaceAll("\\D","")));
            s.warnings.add("Document order metadata is missing; showing numbered parts in file order.");
            return result;
        }
        Map<String,String> relationships=relationships(s,main,relation);
        NodeList ordered=load(s,main).getElementsByTagNameNS(ns,local);
        Set<String> seen=new HashSet<>();
        for(int i=0;i<ordered.getLength();i++) {
            Element item=(Element)ordered.item(i);String part=relationships.get(item.getAttributeNS(R,"id"));
            if(part==null || !s.entries.containsKey(part) || !seen.add(part))throw new IOException("Missing, duplicate or unsafe "+relation+" relationship");
            result.add(part);
        }
        return result;
    }
    private static Map<String,String> relationships(Session s,String source,String type)throws Exception {
        int slash=source.lastIndexOf('/');String base=slash<0?"":source.substring(0,slash+1);
        String rel=base+"_rels/"+source.substring(slash+1)+".rels";
        Map<String,String> result=new HashMap<>();Set<String> ids=new HashSet<>();
        NodeList list=load(s,rel).getElementsByTagNameNS(REL,"Relationship");
        for(int i=0;i<list.getLength();i++) {
            Element e=(Element)list.item(i);String id=e.getAttribute("Id");
            if(id.isEmpty() || !ids.add(id))throw new IOException("Ambiguous document relationship");
            if(!e.getAttribute("Type").equals(R+"/"+type))continue;
            if("External".equalsIgnoreCase(e.getAttribute("TargetMode")))throw new IOException("External document parts are not supported");
            result.put(id,resolvePart(base,e.getAttribute("Target")));
        }
        return result;
    }
    private static void openXlsx(Session s)throws Exception {
        List<String> strings=new ArrayList<>();
        if(s.entries.containsKey("xl/sharedStrings.xml")){Document d=load(s,"xl/sharedStrings.xml");NodeList si=d.getElementsByTagNameNS(S,"si");for(int i=0;i<si.getLength();i++)strings.add(textElements(si.item(i),S,"t"));}
        List<String> parts=orderedParts(s,"xl/workbook.xml",S,"sheet","worksheet","xl/worksheets/sheet[0-9]+\\.xml");
        NodeList names=s.entries.containsKey("xl/workbook.xml")?load(s,"xl/workbook.xml").getElementsByTagNameNS(S,"sheet"):null;
        Set<String> usedNames=new HashSet<>();
        for(int sheet=0;sheet<parts.size();sheet++) {
            String part=parts.get(sheet),name=names==null?"Sheet "+(sheet+1):((Element)names.item(sheet)).getAttribute("name");
            if(name.isEmpty() || !usedNames.add(name))throw new IOException("Ambiguous worksheet name");
            Document doc=load(s,part);int start=s.blocks.size();
            boolean protectedSheet=doc.getElementsByTagNameNS(S,"sheetProtection").getLength()>0;
            Map<String,int[]> spans=new HashMap<>();NodeList merges=doc.getElementsByTagNameNS(S,"mergeCell");
            for(int i=0;i<merges.getLength();i++) {
                String[] range=((Element)merges.item(i)).getAttribute("ref").split(":",-1);
                if(range.length!=2)throw new IOException("Invalid merged cell range");
                int[] a=cellPosition(range[0]),b=cellPosition(range[1]);
                if(b[0]<a[0] || b[1]<a[1] || spans.put(range[0],new int[]{b[0]-a[0]+1,b[1]-a[1]+1})!=null)throw new IOException("Invalid merged cell range");
            }
            NodeList rows=doc.getElementsByTagNameNS(S,"row");Set<String> addresses=new HashSet<>();int previousRow=0;
            for(int r=0;r<rows.getLength();r++) {
                Element row=(Element)rows.item(r);int rowIndex=row.hasAttribute("r")?boundedCount(row.getAttribute("r"),MAX_ROWS):previousRow+1;
                if(rowIndex>MAX_ROWS)throw new IOException("Worksheet exceeds safe row limit");previousRow=rowIndex;int previousColumn=0;
                for(Node node=row.getFirstChild();node!=null;node=node.getNextSibling()) {
                    if(!(node instanceof Element) || !S.equals(node.getNamespaceURI()) || !"c".equals(node.getLocalName()))continue;
                    Element cell=(Element)node;String address=cell.getAttribute("r");int[] position;
                    if(address.isEmpty()){position=new int[]{rowIndex,previousColumn+1};address=cellAddress(position[0],position[1]);}
                    else position=cellPosition(address);
                    if(position[0]!=rowIndex || position[1]>MAX_COLUMNS || !addresses.add(address))throw new IOException("Invalid or duplicate worksheet cell address");
                    previousColumn=position[1];String type=cell.getAttribute("t"),value=textElements(cell,S,"v");
                    if(type.equals("s")){try{value=strings.get(Integer.parseInt(value));}catch(Exception ex){throw new IOException("Invalid shared-string reference");}}
                    else if(type.equals("inlineStr"))value=textElements(cell,S,"t");
                    else if(type.equals("b"))value=value.equals("1")?"TRUE":value.equals("0")?"FALSE":value;
                    String formula=textElements(cell,S,"f");boolean hasFormula=cell.getElementsByTagNameNS(S,"f").getLength()>0;
                    if(hasFormula)value="="+formula+"  [cached: "+value+"]";
                    int[] span=spans.getOrDefault(address,new int[]{1,1});
                    boolean edit=!hasFormula&&!protectedSheet;
                    add(s,new Block("b"+s.blocks.size(),name,address+(hasFormula?" • formula, read-only":protectedSheet?" • protected, read-only":" • edits become text"),value,edit,cell,part,position[0],position[1],span[0],span[1],1,1,type.isEmpty()?"number":type,formula));
                }
            }
            s.sheets.add(new Sheet(part,name,s.blocks.subList(start,s.blocks.size())));
        }
    }
    private static void openOdf(Session s)throws Exception {
        Document doc=load(s,"content.xml");String ext=s.extension;
        byte[] mime=s.entries.get("mimetype");
        String expected="application/vnd.oasis.opendocument."+(ext.endsWith("dt")?"text":ext.endsWith("ds")?"spreadsheet":"presentation");
        if(mime!=null && !expected.equals(new String(mime,StandardCharsets.US_ASCII)))throw new IOException("OpenDocument type does not match its extension");
        if(ext.endsWith("ds")){openOds(s,doc);return;}
        if(ext.endsWith("dp")) {
            NodeList pages=doc.getElementsByTagNameNS(DRAW,"page");
            PresentationReader preview=new PresentationReader(s.entries,s.documents,s.warnings);
            for(int i=0;i<pages.getLength();i++) {
                Element page=(Element)pages.item(i);int start=s.blocks.size();String section="Slide "+(i+1),title=page.getAttributeNS(DRAW,"name");
                addOdfParagraphs(s,page,section);double[] size=odfPageSize(s,page);
                List<Block> blocks=new ArrayList<>(s.blocks.subList(start,s.blocks.size()));
                Slide slide=preview.odf(page,"content.xml#page"+(i+1),title.isEmpty()?slideTitle(blocks,section):title,i+1,size[0],size[1],blocks);
                for(int j=0;j<slide.blocks.size();j++)s.blocks.set(start+j,slide.blocks.get(j));
                s.slides.add(slide);
            }
            s.warnings.add("Slide preview supports text styles and embedded PNG/JPEG. Font substitution and text wrapping may differ; master graphics, vector shapes, transitions and complex transforms require an office app.");
        } else addOdfParagraphs(s,doc,"Document");
        if(s.blocks.isEmpty() && s.slides.isEmpty())throw new IOException("No readable OpenDocument content was found");
    }
    private static void addOdfParagraphs(Session s,Node root,String section)throws IOException {
        if(root instanceof Element && T.equals(root.getNamespaceURI()) && ("p".equals(root.getLocalName()) || "h".equals(root.getLocalName()))) {
            addTextNodes(s,root,"content.xml",section,"h".equals(root.getLocalName())?"Heading":"Paragraph",true);return;
        }
        // Notes and annotations are not slide body text.
        if(root instanceof Element && ((O.equals(root.getNamespaceURI()) && "annotation".equals(root.getLocalName())) ||
            ("urn:oasis:names:tc:opendocument:xmlns:presentation:1.0".equals(root.getNamespaceURI()) && "notes".equals(root.getLocalName()))))return;
        for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling())addOdfParagraphs(s,n,section);
    }
    private static void openOds(Session s,Document doc)throws Exception {
        NodeList tables=doc.getElementsByTagNameNS(TABLE,"table");Set<String> names=new HashSet<>();
        for(int t=0;t<tables.getLength();t++) {
            Element table=(Element)tables.item(t);if(ancestor(table.getParentNode(),TABLE,"table")!=null)continue;
            String name=table.getAttributeNS(TABLE,"name");if(name.isEmpty())name="Sheet "+(s.sheets.size()+1);
            if(!names.add(name))throw new IOException("Ambiguous worksheet name");
            int start=s.blocks.size(),rowIndex=1;boolean protectedTable="true".equals(table.getAttributeNS(TABLE,"protected"));
            NodeList rows=table.getElementsByTagNameNS(TABLE,"table-row");
            for(int r=0;r<rows.getLength();r++) {
                Element row=(Element)rows.item(r);if(ancestor(row,TABLE,"table")!=table)continue;
                int rowRepeat=odfCount(row,"number-rows-repeated",MAX_ROWS);
                if(rowRepeat>MAX_ROWS-rowIndex+1)throw new IOException("Worksheet exceeds safe row limit");
                int column=1;
                for(Node n=row.getFirstChild();n!=null;n=n.getNextSibling()) {
                    if(!(n instanceof Element) || !TABLE.equals(n.getNamespaceURI()) || !("table-cell".equals(n.getLocalName()) || "covered-table-cell".equals(n.getLocalName())))continue;
                    Element cell=(Element)n;int repeat=odfCount(cell,"number-columns-repeated",MAX_COLUMNS);
                    if(repeat>MAX_COLUMNS-column+1)throw new IOException("Worksheet exceeds safe column limit");
                    int rs=odfCount(cell,"number-rows-spanned",MAX_ROWS),cs=odfCount(cell,"number-columns-spanned",MAX_COLUMNS);
                    if(rs>MAX_ROWS-rowIndex+1 || cs>MAX_COLUMNS-column+1)throw new IOException("Merged cell exceeds safe dimensions");
                    String type=cell.getAttributeNS(O,"value-type"),formula=cell.getAttributeNS(TABLE,"formula"),value=odfCellText(cell);
                    if("table-cell".equals(n.getLocalName()) && (!value.isEmpty() || !type.isEmpty() || !formula.isEmpty())) {
                        String address=cellAddress(rowIndex,column);
                        boolean edit=!protectedTable && !"true".equals(cell.getAttributeNS(TABLE,"protected")) && !cell.getAttributeNS(TABLE,"protect").contains("protected") && formula.isEmpty() && type.equals("string") && rowRepeat==1 && repeat==1 && plainOdfCell(cell);
                        String label=address+(formula.isEmpty()?edit?" • text":" • read-only":" • formula, read-only");
                        if(rowRepeat>1 || repeat>1)label+=" • repeated "+cellAddress(rowIndex+rowRepeat-1,column+repeat-1);
                        if(!formula.isEmpty())value=formula+"  [cached: "+value+"]";
                        add(s,new Block("b"+s.blocks.size(),name,label,value,edit,cell,"content.xml",rowIndex,column,rs,cs,rowRepeat,repeat,type,formula));
                    }
                    column+=repeat;
                }
                rowIndex+=rowRepeat;
            }
            s.sheets.add(new Sheet("content.xml#table"+(t+1),name,s.blocks.subList(start,s.blocks.size())));
        }
        s.warnings.add("Repeated cells are compact ranges. Numeric, formula, protected and rich-text cells are read-only; formulas are not recalculated.");
    }
    private static String odfCellText(Element cell)throws IOException {
        StringBuilder result=new StringBuilder();boolean first=true;
        for(Node n=cell.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element && T.equals(n.getNamespaceURI()) && ("p".equals(n.getLocalName()) || "h".equals(n.getLocalName()))) {
            if(!first)result.append('\n');first=false;appendOdfDisplay(n,result);
        }
        if(result.length()>0)return result.toString();
        for(String name:new String[]{"string-value","value","boolean-value","date-value","time-value"})if(cell.hasAttributeNS(O,name))return cell.getAttributeNS(O,name);
        return "";
    }
    private static void appendOdfDisplay(Node node,StringBuilder out)throws IOException {
        if(node.getNodeType()==Node.TEXT_NODE || node.getNodeType()==Node.CDATA_SECTION_NODE)out.append(node.getNodeValue());
        else if(T.equals(node.getNamespaceURI()) && "s".equals(node.getLocalName())) {
            Element e=(Element)node;int count=e.hasAttributeNS(T,"c")?boundedCount(e.getAttributeNS(T,"c"),MAX_TEXT):1;
            if(count>MAX_TEXT-out.length())throw new IOException("Expanded text exceeds safe limit");
            for(int i=0;i<count;i++)out.append(' ');
        } else if(T.equals(node.getNamespaceURI()) && "tab".equals(node.getLocalName()))out.append('\t');
        else if(T.equals(node.getNamespaceURI()) && "line-break".equals(node.getLocalName()))out.append('\n');
        else if(!(O.equals(node.getNamespaceURI()) && "annotation".equals(node.getLocalName())))for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())appendOdfDisplay(n,out);
        if(out.length()>MAX_TEXT)throw new IOException("Cell text exceeds safe limit");
    }
    private static boolean plainOdfCell(Node node) {
        for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling()) {
            if(n instanceof Element && !(T.equals(n.getNamespaceURI()) && Set.of("p","s","tab","line-break").contains(n.getLocalName())))return false;
            if(!plainOdfCell(n))return false;
        }
        return true;
    }
    private static void validateOdfEditComplexity(String text)throws IOException {
        int transitions=1;boolean previousSpecial=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);boolean special=c==' ' || c=='\t' || c=='\r' || c=='\n';
            if(special || previousSpecial)if(++transitions>MAX_XML_NODES/4)throw new IOException("Edited cell exceeds safe XML complexity");
            previousSpecial=special;
        }
    }
    private static void appendOdfText(Document doc,Element paragraph,String text) {
        StringBuilder plain=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c==' ' || c=='\t' || c=='\r') {
                if(plain.length()>0){paragraph.appendChild(doc.createTextNode(plain.toString()));plain.setLength(0);}
                Element e=doc.createElementNS(T,c==' '?"text:s":c=='\t'?"text:tab":"text:line-break");
                if(c==' ') {
                    int count=1;while(i+1<text.length() && text.charAt(i+1)==' '){count++;i++;}
                    if(count>1)e.setAttributeNS(T,"text:c",Integer.toString(count));
                }
                paragraph.appendChild(e);
            } else plain.append(c);
        }
        if(plain.length()>0)paragraph.appendChild(doc.createTextNode(plain.toString()));
    }
    private static int odfCount(Element e,String attribute,int max)throws IOException {return e.hasAttributeNS(TABLE,attribute)?boundedCount(e.getAttributeNS(TABLE,attribute),max):1;}
    private static int boundedCount(String value,int max)throws IOException {
        try{long number=Long.parseLong(value);if(number<1 || number>max)throw new NumberFormatException();return (int)number;}
        catch(NumberFormatException ex){throw new IOException("Invalid or excessive document dimension");}
    }
    private static int[] cellPosition(String address)throws IOException {
        if(!address.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw new IOException("Invalid worksheet cell address");
        int column=0,index=0;while(index<address.length() && Character.isLetter(address.charAt(index)))column=column*26+address.charAt(index++)-'A'+1;
        int row=boundedCount(address.substring(index),MAX_ROWS);if(column>MAX_COLUMNS)throw new IOException("Worksheet exceeds safe column limit");return new int[]{row,column};
    }
    private static String cellAddress(int row,int column)throws IOException {
        if(row<1 || row>MAX_ROWS || column<1 || column>MAX_COLUMNS)throw new IOException("Worksheet address exceeds safe dimensions");
        StringBuilder name=new StringBuilder();for(int n=column;n>0;n=(n-1)/26)name.insert(0,(char)('A'+(n-1)%26));return name.toString()+row;
    }
    private static double positiveNumber(String value)throws IOException {
        try{double number=Double.parseDouble(value);if(!Double.isFinite(number) || number<1 || number>1e10)throw new NumberFormatException();return number;}
        catch(NumberFormatException ex){throw new IOException("Invalid slide dimension");}
    }
    private static String slideTitle(List<Block> blocks,String fallback) {
        StringBuilder text=new StringBuilder();String paragraph=blocks.isEmpty()?"":blocks.get(0).paragraphId;
        for(Block block:blocks){if(!paragraph.equals(block.paragraphId))break;text.append(block.original);if(text.length()>100)break;}
        String title=text.toString().trim();return title.isEmpty()?fallback:title.substring(0,Math.min(100,title.length()));
    }
    private static Element ancestor(Node n,String ns,String local) {
        for(Node p=n;p!=null;p=p.getParentNode())if(p instanceof Element && ns.equals(p.getNamespaceURI()) && local.equals(p.getLocalName()))return (Element)p;return null;
    }
    static Node ancestorShape(Node n) {
        for(Node p=n;p!=null;p=p.getParentNode())if(p instanceof Element && (P.equals(p.getNamespaceURI()) && ("sp".equals(p.getLocalName()) || "graphicFrame".equals(p.getLocalName())) || DRAW.equals(p.getNamespaceURI()) && ("frame".equals(p.getLocalName()) || "custom-shape".equals(p.getLocalName()))))return p;return null;
    }
    private static String nodePath(Node node,String part) {
        StringBuilder path=new StringBuilder();for(Node current=node;current.getParentNode()!=null;current=current.getParentNode()){int index=0;for(Node before=current.getPreviousSibling();before!=null;before=before.getPreviousSibling())index++;path.insert(0,"/"+index);}return part+path;
    }
    static double[] shapeBounds(Node shape) {
        double[] unknown={-1,-1,-1,-1};if(!(shape instanceof Element))return unknown;
        Element e=(Element)shape;
        try {
            if(P.equals(e.getNamespaceURI())) {
                if(ancestor(e.getParentNode(),P,"grpSp")!=null)return unknown;
                NodeList transforms=e.getElementsByTagNameNS(A,"xfrm");if(transforms.getLength()!=1)return unknown;
                Element transform=(Element)transforms.item(0);
                if(transform.hasAttribute("rot") || transform.hasAttribute("flipH") || transform.hasAttribute("flipV"))return unknown;
                NodeList offsets=transform.getElementsByTagNameNS(A,"off"),extents=transform.getElementsByTagNameNS(A,"ext");
                if(offsets.getLength()!=1 || extents.getLength()!=1)return unknown;
                Element offset=(Element)offsets.item(0),extent=(Element)extents.item(0);
                return finiteBounds(Double.parseDouble(offset.getAttribute("x"))/12700,Double.parseDouble(offset.getAttribute("y"))/12700,positiveNumber(extent.getAttribute("cx"))/12700,positiveNumber(extent.getAttribute("cy"))/12700);
            }
            if(ancestor(e.getParentNode(),DRAW,"g")!=null || e.hasAttributeNS(DRAW,"transform"))return unknown;
            return finiteBounds(odfLength(e.getAttributeNS(SVG,"x")),odfLength(e.getAttributeNS(SVG,"y")),odfLength(e.getAttributeNS(SVG,"width")),odfLength(e.getAttributeNS(SVG,"height")));
        }catch(Exception ignored){return unknown;}
    }
    private static double[] finiteBounds(double x,double y,double width,double height)throws IOException {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height) || Math.abs(x)>1e8 || Math.abs(y)>1e8 || width<=0 || height<=0 || width>1e8 || height>1e8)throw new IOException("Invalid text frame bounds");return new double[]{x,y,width,height};
    }
    private static void validateCanvas(double width,double height)throws IOException {
        if(width<1 || height<1 || width>100000 || height>100000 || width/height<0.01 || width/height>100)
            throw new IOException("Slide canvas dimensions exceed safe preview limits");
    }
    static double odfLength(String value)throws IOException {
        if(!value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(cm|mm|in|pt|pc|px)"))throw new IOException("Unsupported length");
        String unit=value.substring(value.length()-2);double n=Double.parseDouble(value.substring(0,value.length()-2));
        return n*(unit.equals("cm")?72/2.54:unit.equals("mm")?72/25.4:unit.equals("in")?72:unit.equals("pc")?12:unit.equals("px")?0.75:1);
    }
    private static double[] odfPageSize(Session s,Element page)throws Exception {
        String masterName=page.getAttributeNS(DRAW,"master-page-name"),layout="";
        List<Document> docs=new ArrayList<>();docs.add(load(s,"content.xml"));if(s.entries.containsKey("styles.xml"))docs.add(load(s,"styles.xml"));
        for(Document doc:docs){NodeList masters=doc.getElementsByTagNameNS(STYLE,"master-page");for(int i=0;i<masters.getLength();i++){Element master=(Element)masters.item(i);if(masterName.equals(master.getAttributeNS(STYLE,"name")))layout=master.getAttributeNS(STYLE,"page-layout-name");}}
        for(Document doc:docs){NodeList layouts=doc.getElementsByTagNameNS(STYLE,"page-layout");for(int i=0;i<layouts.getLength();i++) {
            Element candidate=(Element)layouts.item(i);if(!layout.equals(candidate.getAttributeNS(STYLE,"name")))continue;
            NodeList properties=candidate.getElementsByTagNameNS(STYLE,"page-layout-properties");if(properties.getLength()==0)continue;Element e=(Element)properties.item(0);
            try{double w=odfLength(e.getAttributeNS(FO,"page-width")),h=odfLength(e.getAttributeNS(FO,"page-height"));finiteBounds(0,0,w,h);validateCanvas(w,h);return new double[]{w,h};}catch(IOException ignored){}
        }}
        return new double[]{960,540};
    }
    private static String paragraphId(Node node,String part,String fallback) {
        for(Node parent=node;parent!=null;parent=parent.getParentNode()) {
            String namespace=parent.getNamespaceURI(), local=parent.getLocalName();
            if((W.equals(namespace) || A.equals(namespace)) && "p".equals(local) || T.equals(namespace) && ("p".equals(local) || "h".equals(local))) {
                StringBuilder path=new StringBuilder();
                for(Node current=parent;current.getParentNode()!=null;current=current.getParentNode()) {
                    int index=0;for(Node before=current.getPreviousSibling();before!=null;before=before.getPreviousSibling())index++;
                    path.insert(0,"/"+index);
                }
                return part+path;
            }
        }
        return fallback;
    }
    // OOXML parsers follow relationship targets regardless of file extension. Validate those too.
    private static void validateReferencedXml(Session s)throws Exception {
        Set<String> checked=new HashSet<>();
        for(String p:s.entries.keySet())if(p.toLowerCase(Locale.ROOT).endsWith(".xml")||p.toLowerCase(Locale.ROOT).endsWith(".rels"))checked.add(p);
        if(s.entries.containsKey("[Content_Types].xml")) {
            Document types=parse(s.entries.get("[Content_Types].xml"));
            NodeList overrides=types.getElementsByTagNameNS("*","Override");
            for(int i=0;i<overrides.getLength();i++) {
                Element e=(Element)overrides.item(i);String type=e.getAttribute("ContentType").toLowerCase(Locale.ROOT);
                if(type.endsWith("+xml")||type.endsWith("/xml"))validatePart(s,resolvePart("",e.getAttribute("PartName")),checked);
            }
            NodeList defaults=types.getElementsByTagNameNS("*","Default");
            for(int i=0;i<defaults.getLength();i++) {
                Element e=(Element)defaults.item(i);String type=e.getAttribute("ContentType").toLowerCase(Locale.ROOT);
                if(type.endsWith("+xml")||type.endsWith("/xml"))for(String p:s.entries.keySet())if(p.toLowerCase(Locale.ROOT).endsWith("."+e.getAttribute("Extension").toLowerCase(Locale.ROOT)))validatePart(s,p,checked);
            }
        }
        Set<String> binary=new HashSet<>(Arrays.asList("image","audio","video","media","font","oleObject","package","thumbnail","hyperlink","aFChunk","printerSettings"));
        for(String p:s.entries.keySet())if(p.toLowerCase(Locale.ROOT).endsWith(".rels")) {
            Document rels=parse(s.entries.get(p));NodeList list=rels.getElementsByTagNameNS("*","Relationship");
            String base=p.equals("_rels/.rels")?"":p.substring(0,p.lastIndexOf("/_rels/")+1);
            for(int i=0;i<list.getLength();i++) {
                Element e=(Element)list.item(i);
                if("External".equalsIgnoreCase(e.getAttribute("TargetMode")))continue;
                String type=e.getAttribute("Type");type=type.substring(type.lastIndexOf('/')+1);
                if(!binary.contains(type))validatePart(s,resolvePart(base,e.getAttribute("Target")),checked);
            }
        }
    }
    private static String resolvePart(String base,String target)throws IOException {
        try {
            java.net.URI uri=new java.net.URI("/"+base).resolve(new java.net.URI(target)).normalize();
            if(uri.getScheme()!=null||uri.getAuthority()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPath()==null||!uri.getPath().startsWith("/")||uri.getPath().contains("/../"))throw new IOException("Unsafe OOXML part target");
            return uri.getPath().substring(1);
        }catch(java.net.URISyntaxException e){throw new IOException("Invalid OOXML part target",e);}
    }
    private static void validatePart(Session s,String path,Set<String> checked)throws Exception {
        if(checked.contains(path))return;
        byte[] bytes=s.entries.get(path);if(bytes==null)throw new IOException("Missing referenced XML part: "+path);
        validateXml(bytes,s.xmlBudget);checked.add(path);
    }
    private static void addTextNodes(Session s,Node n,String part,String section,String label,boolean edit)throws IOException {
        if(n instanceof Element && T.equals(n.getNamespaceURI()) && Set.of("s","tab","line-break").contains(n.getLocalName())) {
            String text;
            if("s".equals(n.getLocalName())) {
                Element element=(Element)n;int count=element.hasAttributeNS(T,"c")?boundedCount(element.getAttributeNS(T,"c"),MAX_TEXT):1;
                char[] spaces=new char[count];Arrays.fill(spaces,' ');text=new String(spaces);
            } else text="tab".equals(n.getLocalName())?"\t":"\n";
            add(s,new Block("b"+s.blocks.size(),section,label,text,false,n,part));
        } else if((n.getNodeType()==Node.TEXT_NODE || n.getNodeType()==Node.CDATA_SECTION_NODE) && !n.getNodeValue().isEmpty())add(s,new Block("b"+s.blocks.size(),section,label,n.getNodeValue(),edit,n,part));
        else for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())addTextNodes(s,c,part,section,label,edit);
    }
    private static void add(Session s,Block b)throws IOException {
        if(s.blocks.size()>=MAX_BLOCKS)throw new IOException("Document exceeds 12,000 content blocks");
        s.extractedCharacters+=b.original.length();if(s.extractedCharacters>MAX_EXPANDED)throw new IOException("Expanded document text exceeds safe limit");s.blocks.add(b);
    }
    private static String textElements(Node n,String ns,String local){NodeList list=((Element)n).getElementsByTagNameNS(ns,local);StringBuilder b=new StringBuilder();for(int i=0;i<list.getLength();i++)b.append(list.item(i).getTextContent());return b.toString();}
    private static Document load(Session s,String p)throws Exception {
        if(s.documents.containsKey(p))return s.documents.get(p);
        if(!s.entries.containsKey(p))throw new IOException("Missing document part: "+p);
        Document d=parse(s.entries.get(p));s.documents.put(p,d);return d;
    }
    private static boolean unsafeEntryName(String name) {
        if(name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0')>=0 || name.matches("^[A-Za-z]:.*"))return true;
        String[] segments=name.split("/",-1);
        for(int i=0;i<segments.length;i++)if(segments[i].equals(".") || segments[i].equals("..") || segments[i].isEmpty() && i<segments.length-1)return true;
        return false;
    }
    private static void validateXml(byte[] bytes,XmlBudget budget)throws Exception {
        // Stream a structural budget before allocating a DOM, including empty-element attacks.
        SAXParserFactory sf=SAXParserFactory.newInstance();sf.setNamespaceAware(true);
        sf.setFeature("http://xml.org/sax/features/external-general-entities",false);
        sf.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        XMLReader reader=sf.newSAXParser().getXMLReader();
        DefaultHandler guard=new DefaultHandler() {
            int depth;
            private void node()throws SAXException {if(++budget.nodes>MAX_XML_NODES)throw new SAXException("XML exceeds safe node limit");}
            @Override public void startElement(String uri,String local,String q,Attributes attributes)throws SAXException {
                node();if(++depth>MAX_XML_DEPTH || attributes.getLength()>128)throw new SAXException("XML exceeds safe complexity limit");
                if(uri.equals("http://www.w3.org/2000/09/xmldsig#") && local.equals("Signature"))
                    throw new SAXException("Signed documents require a trusted office application");
                if((O.equals(uri) && local.equals("script")) ||
                    (uri.equals("urn:oasis:names:tc:opendocument:xmlns:script:1.0") && local.equals("event-listener")) ||
                    (uri.equals("urn:oasis:names:tc:opendocument:xmlns:manifest:1.0") && local.equals("encryption-data")))
                    throw new SAXException("Active scripts or encrypted document parts are not supported");
                if(uri.equals("http://schemas.openxmlformats.org/package/2006/content-types") || uri.equals("http://schemas.openxmlformats.org/package/2006/relationships"))
                    for(int i=0;i<attributes.getLength();i++) {
                        String value=attributes.getValue(i).toLowerCase(Locale.ROOT);
                        if(value.contains("macroenabled") || value.contains("vbaproject") || value.contains("digital-signature"))
                            throw new SAXException("Macro-enabled or signed documents require a trusted office application");
                    }
            }
            @Override public void startPrefixMapping(String prefix,String uri)throws SAXException {node();}
            @Override public void endElement(String uri,String local,String q){depth--;}
            @Override public void characters(char[] chars,int start,int length)throws SAXException {node();}
            @Override public void processingInstruction(String target,String data)throws SAXException {node();}
            @Override public void error(SAXParseException failure)throws SAXException {throw failure;}
            @Override public void fatalError(SAXParseException failure)throws SAXException {throw failure;}
        };
        reader.setContentHandler(guard);reader.setErrorHandler(guard);
        reader.setEntityResolver((publicId,systemId)->{throw new SAXException("External entities are blocked");});
        // Android's SAX parser does not implement disallow-doctype-decl; LexicalHandler is portable.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",new org.xml.sax.ext.DefaultHandler2() {
            @Override public void startDTD(String name,String publicId,String systemId)throws SAXException {throw new SAXException("DOCTYPE is blocked");}
            @Override public void comment(char[] chars,int start,int length)throws SAXException {if(++budget.nodes>MAX_XML_NODES)throw new SAXException("XML exceeds safe node limit");}
        });
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
    }
    static Document parse(byte[] bytes)throws Exception {
        validateXml(bytes,new XmlBudget());
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        // The mandatory SAX pass above rejects DTDs on Android, whose DOM factory does not
        // implement these optional Xerces flags. Keep the flags as defense in depth on JDKs.
        optionalFeature(f,"http://apache.org/xml/features/disallow-doctype-decl",true);
        optionalFeature(f,"http://xml.org/sax/features/external-general-entities",false);
        optionalFeature(f,"http://xml.org/sax/features/external-parameter-entities",false);
        try{f.setXIncludeAware(false);}catch(UnsupportedOperationException ignored){}
        f.setExpandEntityReferences(false);
        DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External entities are blocked");});
        return b.parse(new ByteArrayInputStream(bytes));
    }
    private static void optionalFeature(DocumentBuilderFactory factory,String feature,boolean value) {
        try{factory.setFeature(feature,value);}catch(ParserConfigurationException ignored){/* SAX guard remains mandatory. */}
    }
    private static byte[] serialize(Document doc)throws Exception {
        TransformerFactory f=TransformerFactory.newInstance();
        try{f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet","");}catch(IllegalArgumentException ignored){/* Android transformer never resolves document content. */}
        Transformer t=f.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
        LimitedOutput out=new LimitedOutput(MAX_PART);t.transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
    private static Node locate(Document doc,Node original) {
        List<Integer> path=new ArrayList<>();
        for(Node n=original;n.getParentNode()!=null;n=n.getParentNode()){int i=0;for(Node p=n.getPreviousSibling();p!=null;p=p.getPreviousSibling())i++;path.add(i);}
        Node n=doc;for(int i=path.size()-1;i>=0;i--)n=n.getChildNodes().item(path.get(i));return n;
    }
    private static void validateXmlEncoding(String text,Charset charset)throws IOException {
        if(!text.startsWith("<?xml"))return;
        int end=text.indexOf("?>");if(end<0)return;
        java.util.regex.Matcher encoding=java.util.regex.Pattern.compile("encoding\\s*=\\s*(['\"])([^'\"]+)\\1").matcher(text.substring(0,end));
        if(!encoding.find())return;
        String declared=encoding.group(2).toUpperCase(Locale.ROOT),actual=charset.name().toUpperCase(Locale.ROOT);
        if(!(declared.equals(actual) || declared.equals("UTF-16") && actual.startsWith("UTF-16")))throw new IOException("XML encoding declaration must match the original UTF-8 or UTF-16 encoding");
    }
    private static void validateXmlCharacters(String s)throws IOException {for(int i=0;i<s.length();){int cp=s.codePointAt(i);if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xD7FF||cp>=0xE000&&cp<=0xFFFD||cp>=0x10000&&cp<=0x10FFFF))throw new IOException("Unsupported control character");i+=Character.charCount(cp);}}
    public static byte[] readBounded(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("File or archive part exceeds safe limit");out.write(buffer,0,n);}return out.toByteArray();}
}
