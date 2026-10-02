package org.smallthingz.alldoc.core;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, offline structured-text engine. It deliberately does not claim page-layout fidelity. */
public final class DocumentEngine {
    public static final int MAX_FILE = 32 * 1024 * 1024;
    public static final int MAX_EXPANDED = 64 * 1024 * 1024;
    public static final int MAX_PART = 8 * 1024 * 1024;
    public static final int MAX_BLOCKS = 12000;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String O = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String T = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    public static final class Block {
        public final String id, section, label, original;
        public final boolean editable;
        private final Node node;
        private final String part;
        Block(String id, String section, String label, String text, boolean editable, Node node, String part) {
            this.id=id; this.section=section; this.label=label; this.original=text; this.editable=editable; this.node=node; this.part=part;
        }
    }
    public static final class Session {
        public final String name, extension, description;
        public final List<Block> blocks = new ArrayList<>();
        public final byte[] originalBytes;
        public final boolean editable;
        private final LinkedHashMap<String,byte[]> entries = new LinkedHashMap<>();
        private final Map<String,Document> documents = new HashMap<>();
        private final Charset charset;
        private final byte[] bom;
        Session(String name, String extension, String description, byte[] originalBytes, boolean editable, Charset charset, byte[] bom) {
            this.name=name; this.extension=extension; this.description=description; this.originalBytes=originalBytes; this.editable=editable; this.charset=charset; this.bom=bom;
        }
        public byte[] export(Map<String,String> changes) throws Exception {
            if (changes.isEmpty()) return originalBytes.clone();
            if (!editable) throw new IOException("This document is read-only");
            Map<String,String> actual=new HashMap<>();
            Set<String> ids=new HashSet<>();
            for(Block b:blocks) {
                ids.add(b.id);
                if(changes.containsKey(b.id) && !Objects.equals(changes.get(b.id), b.original)) {
                    if(!b.editable) throw new IOException("This block cannot be changed safely");
                    String v=changes.get(b.id);
                    if(v==null || v.length()>1024*1024) throw new IOException("Edit is too large");
                    validateXmlCharacters(v);
                    actual.put(b.id,v);
                }
            }
            if(!ids.containsAll(changes.keySet())) throw new IOException("Unknown edit target");
            if(actual.isEmpty()) return originalBytes.clone();
            if(entries.isEmpty()) {
                String text=actual.getOrDefault("text",blocks.get(0).original);
                ByteBufferOutput out=new ByteBufferOutput(); out.write(bom); out.write(text.getBytes(charset)); return out.toByteArray();
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
                } else {
                    target.setNodeValue(actual.get(b.id));
                    if(target.getParentNode() instanceof Element && (extension.equals("docx") || extension.equals("pptx")))
                        ((Element)target.getParentNode()).setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve");
                }
            }
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(ZipOutputStream zip=new ZipOutputStream(out)) {
                for(Map.Entry<String,byte[]> e:entries.entrySet()) {
                    byte[] bytes=edited.containsKey(e.getKey())?serialize(edited.get(e.getKey())):e.getValue();
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
    private static final class ByteBufferOutput extends ByteArrayOutputStream {}
    public static Session open(String name, byte[] bytes) throws Exception {
        if(bytes.length>MAX_FILE) throw new IOException("File exceeds the 32 MiB safe import limit");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(Set.of("txt","md","csv","tsv","log","json","xml","yaml","yml").contains(ext)) {
            Charset cs=StandardCharsets.UTF_8; int offset=0;
            if(bytes.length>=3 && bytes[0]==(byte)239 && bytes[1]==(byte)187 && bytes[2]==(byte)191)offset=3;
            else if(bytes.length>=2 && bytes[0]==(byte)255 && bytes[1]==(byte)254){cs=StandardCharsets.UTF_16LE;offset=2;}
            else if(bytes.length>=2 && bytes[0]==(byte)254 && bytes[1]==(byte)255){cs=StandardCharsets.UTF_16BE;offset=2;}
            if(bytes.length>2*1024*1024)throw new IOException("Text editing is limited to 2 MiB; use a large-file editor");
            String text=cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
            if(text.indexOf('\0')>=0)throw new IOException("Binary content is not supported as text");
            Session s=new Session(name,ext,"Plain-text editor • original encoding and line endings retained",bytes,true,cs,Arrays.copyOf(bytes,offset));
            s.blocks.add(new Block("text","Document",ext.equals("csv")?"CSV source (no formula execution)":"Text",text,true,null,null)); return s;
        }
        if(!Set.of("docx","xlsx","pptx","odt","ods","odp").contains(ext))
            throw new IOException("."+ext+" is not supported by the offline editor. Original is unchanged. DOC, XLS, PPT and RTF need an external office app or conversion to an open XML format.");
        Session s=new Session(name,ext,"Structured content • not a page-layout preview. Export a copy; review it in your office app.",bytes,true,null,null);
        int total=0,count=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                String p=e.getName();
                if(++count>3000 || p.startsWith("/") || p.contains("\\") || Arrays.asList(p.split("/")).contains("..") || s.entries.containsKey(p))throw new IOException("Unsafe or ambiguous archive");
                byte[] data=readBounded(zip,MAX_PART); total+=data.length;
                if(total>MAX_EXPANDED)throw new IOException("Expanded archive exceeds safe limit");
                if(p.toLowerCase(Locale.ROOT).contains("vbaproject") || p.startsWith("_xmlsignatures/") || p.startsWith("META-INF/documentsignatures"))throw new IOException("Macro-enabled or signed documents require a trusted office application");
                s.entries.put(p,data);
            }
        }
        if(s.entries.isEmpty())throw new IOException("Not a supported ZIP document (it may be encrypted)");
        if(ext.startsWith("od")) {
            byte[] manifest=s.entries.get("META-INF/manifest.xml");
            if(manifest!=null && new String(manifest,StandardCharsets.UTF_8).contains("encryption-data"))throw new IOException("Encrypted ODF is not supported");
            Document doc=load(s,"content.xml");
            NodeList paras=doc.getElementsByTagNameNS(T,"p");
            for(int i=0;i<paras.getLength();i++)addTextNodes(s,paras.item(i),"content.xml","Content","Paragraph "+(i+1),!ext.equals("ods") || safeOdsCell(paras.item(i)));
            NodeList headings=doc.getElementsByTagNameNS(T,"h");
            for(int i=0;i<headings.getLength();i++)addTextNodes(s,headings.item(i),"content.xml","Headings","Heading "+(i+1),true);
        } else if(ext.equals("docx")) {
            load(s,"word/document.xml");
            for(String p:s.entries.keySet())if(p.equals("word/document.xml") || p.matches("word/(header|footer)[0-9]+\\.xml") || p.equals("word/footnotes.xml") || p.equals("word/endnotes.xml")) {
                Document doc=load(s,p);NodeList texts=doc.getElementsByTagNameNS(W,"t");
                for(int i=0;i<texts.getLength();i++)addTextNodes(s,texts.item(i),p,p.substring(5),"Text run "+(i+1),true);
            }
        } else if(ext.equals("pptx")) {
            List<String> parts=new ArrayList<>();for(String p:s.entries.keySet())if(p.matches("ppt/slides/slide[0-9]+\\.xml"))parts.add(p);
            parts.sort(Comparator.comparingInt(p->Integer.parseInt(p.replaceAll("\\D",""))));
            for(String p:parts){Document doc=load(s,p);NodeList texts=doc.getElementsByTagNameNS(A,"t");for(int i=0;i<texts.getLength();i++)addTextNodes(s,texts.item(i),p,"Slide "+p.replaceAll("\\D",""),"Text run "+(i+1),true);}
        } else {
            List<String> strings=new ArrayList<>();
            if(s.entries.containsKey("xl/sharedStrings.xml")){Document d=load(s,"xl/sharedStrings.xml");NodeList si=d.getElementsByTagNameNS(S,"si");for(int i=0;i<si.getLength();i++)strings.add(textElements(si.item(i),S,"t"));}
            List<String> sheets=new ArrayList<>();for(String p:s.entries.keySet())if(p.matches("xl/worksheets/sheet[0-9]+\\.xml"))sheets.add(p);
            sheets.sort(Comparator.comparingInt(p->Integer.parseInt(p.replaceAll("\\D",""))));
            for(String p:sheets){Document doc=load(s,p);NodeList cells=doc.getElementsByTagNameNS(S,"c");
                for(int i=0;i<cells.getLength();i++){
                    Element c=(Element)cells.item(i);String value=textElements(c,S,"v");String type=c.getAttribute("t");
                    if(type.equals("s")){try{value=strings.get(Integer.parseInt(value));}catch(Exception ex){throw new IOException("Invalid shared-string reference");}}
                    else if(type.equals("inlineStr"))value=textElements(c,S,"t");
                    boolean formula=c.getElementsByTagNameNS(S,"f").getLength()>0;
                    if(formula)value="="+textElements(c,S,"f")+"  [cached: "+value+"]";
                    add(s,new Block("b"+s.blocks.size(),"Sheet "+p.replaceAll("\\D",""),c.getAttribute("r")+(formula?" • formula, read-only":" • edits become text"),value,!formula,c,p));
                }
            }
        }
        if(s.blocks.isEmpty())throw new IOException("No editable text was found. Images, drawings and empty documents need a full office application.");
        return s;
    }
    private static boolean safeOdsCell(Node n) {
        for(Node p=n;p!=null;p=p.getParentNode())if(p instanceof Element && TABLE.equals(p.getNamespaceURI()) && "table-cell".equals(p.getLocalName())) {
            Element e=(Element)p;return !e.hasAttributeNS(TABLE,"formula") && "string".equals(e.getAttributeNS(O,"value-type")) && !e.hasAttributeNS(O,"string-value");
        }
        return false;
    }
    private static void addTextNodes(Session s,Node n,String part,String section,String label,boolean edit)throws IOException {
        if(n.getNodeType()==Node.TEXT_NODE && !n.getNodeValue().isEmpty())add(s,new Block("b"+s.blocks.size(),section,label,n.getNodeValue(),edit,n,part));
        else for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())addTextNodes(s,c,part,section,label,edit);
    }
    private static void add(Session s,Block b)throws IOException {if(s.blocks.size()>=MAX_BLOCKS)throw new IOException("Document exceeds 12,000 content blocks");s.blocks.add(b);}
    private static String textElements(Node n,String ns,String local){NodeList list=((Element)n).getElementsByTagNameNS(ns,local);StringBuilder b=new StringBuilder();for(int i=0;i<list.getLength();i++)b.append(list.item(i).getTextContent());return b.toString();}
    private static Document load(Session s,String p)throws Exception {if(!s.entries.containsKey(p))throw new IOException("Missing document part: "+p);Document d=parse(s.entries.get(p));s.documents.put(p,d);return d;}
    static Document parse(byte[] bytes)throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External entities are blocked");});
        return b.parse(new ByteArrayInputStream(bytes));
    }
    private static byte[] serialize(Document doc)throws Exception {
        TransformerFactory f=TransformerFactory.newInstance();
        try{f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet","");}catch(IllegalArgumentException ignored){/* Android transformer never resolves document content. */}
        Transformer t=f.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
        ByteArrayOutputStream out=new ByteArrayOutputStream();t.transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
    private static Node locate(Document doc,Node original) {
        List<Integer> path=new ArrayList<>();
        for(Node n=original;n.getParentNode()!=null;n=n.getParentNode()){int i=0;for(Node p=n.getPreviousSibling();p!=null;p=p.getPreviousSibling())i++;path.add(i);}
        Node n=doc;for(int i=path.size()-1;i>=0;i--)n=n.getChildNodes().item(path.get(i));return n;
    }
    private static void validateXmlCharacters(String s)throws IOException {for(int i=0;i<s.length();){int cp=s.codePointAt(i);if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xD7FF||cp>=0xE000&&cp<=0xFFFD||cp>=0x10000&&cp<=0x10FFFF))throw new IOException("Unsupported control character");i+=Character.charCount(cp);}}
    public static byte[] readBounded(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("File or archive part exceeds safe limit");out.write(buffer,0,n);}return out.toByteArray();}
}
