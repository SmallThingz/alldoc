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

/** Bounded, offline structured-text engine. It deliberately does not claim page-layout fidelity. */
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
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String O = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String T = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    public static final class Block {
        public final String id, paragraphId, section, label, original;
        public final boolean editable;
        private final Node node;
        private final String part;
        Block(String id, String section, String label, String text, boolean editable, Node node, String part) {
            this.id=id; this.paragraphId=paragraphId(node,part,id); this.section=section; this.label=label; this.original=text; this.editable=editable; this.node=node; this.part=part;
        }
    }
    public static final class Session {
        public final String name, extension, description;
        public final List<Block> blocks = new ArrayList<>();
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
            if (!editable) throw new IOException("This document is read-only");
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
                    actual.put(b.id,v);
                }
            }
            if(!ids.containsAll(changes.keySet())) throw new IOException("Unknown edit target");
            if(actual.isEmpty() && preserveUnchanged) return originalBytes.clone();
            if(entries.isEmpty()) {
                String text=actual.getOrDefault("text",blocks.get(0).original);
                LimitedOutput out=new LimitedOutput(MAX_TEXT); out.write(bom); out.write(text.getBytes(charset)); return out.toByteArray();
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
            LimitedOutput out=new LimitedOutput(MAX_FILE);
            try(ZipOutputStream zip=new ZipOutputStream(out)) {
                List<Map.Entry<String,byte[]>> ordered=new ArrayList<>(entries.entrySet());
                ordered.sort(Comparator.comparingInt(e->e.getKey().equals("mimetype")?0:1));
                int expanded=0;
                for(Map.Entry<String,byte[]> e:ordered) {
                    byte[] bytes=edited.containsKey(e.getKey())?serialize(edited.get(e.getKey())):e.getValue();
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
        if(Set.of("txt","md","csv","tsv","log","json","xml","yaml","yml").contains(ext)) {
            Charset cs=StandardCharsets.UTF_8; int offset=0;
            if(bytes.length>=3 && bytes[0]==(byte)239 && bytes[1]==(byte)187 && bytes[2]==(byte)191)offset=3;
            else if(bytes.length>=2 && bytes[0]==(byte)255 && bytes[1]==(byte)254){cs=StandardCharsets.UTF_16LE;offset=2;}
            else if(bytes.length>=2 && bytes[0]==(byte)254 && bytes[1]==(byte)255){cs=StandardCharsets.UTF_16BE;offset=2;}
            if(bytes.length>MAX_TEXT)throw new IOException("Text editing is limited to 2 MiB; use a large-file editor");
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
            byte[] manifest=s.entries.get("META-INF/manifest.xml");
            if(manifest!=null && new String(manifest,StandardCharsets.UTF_8).contains("encryption-data"))throw new IOException("Encrypted ODF is not supported");
            Document doc=load(s,"content.xml");
            NodeList paras=doc.getElementsByTagNameNS(T,"p");
            for(int i=0;i<paras.getLength();i++)addTextNodes(s,paras.item(i),"content.xml","Content","Paragraph "+(i+1),!ext.equals("ods") || safeOdsCell(paras.item(i)));
            NodeList headings=doc.getElementsByTagNameNS(T,"h");
            for(int i=0;i<headings.getLength();i++)addTextNodes(s,headings.item(i),"content.xml","Headings","Heading "+(i+1),!ext.equals("ods") || safeOdsCell(headings.item(i)));
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
        Set<String> binary=new HashSet<>(Arrays.asList("image","audio","video","media","font","oleObject","package","thumbnail","hyperlink","aFChunk"));
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
    private static boolean safeOdsCell(Node n) {
        Element cell=null;
        for(Node p=n;p!=null;p=p.getParentNode())if(p instanceof Element && TABLE.equals(p.getNamespaceURI())) {
            Element e=(Element)p;
            if(e.hasAttributeNS(TABLE,"number-columns-repeated") && !"1".equals(e.getAttributeNS(TABLE,"number-columns-repeated")) ||
                e.hasAttributeNS(TABLE,"number-rows-repeated") && !"1".equals(e.getAttributeNS(TABLE,"number-rows-repeated")))return false;
            if("table-cell".equals(p.getLocalName()))cell=e;
        }
        return cell!=null && !cell.hasAttributeNS(TABLE,"formula") && "string".equals(cell.getAttributeNS(O,"value-type")) && !cell.hasAttributeNS(O,"string-value");
    }
    private static void addTextNodes(Session s,Node n,String part,String section,String label,boolean edit)throws IOException {
        if(n.getNodeType()==Node.TEXT_NODE && !n.getNodeValue().isEmpty())add(s,new Block("b"+s.blocks.size(),section,label,n.getNodeValue(),edit,n,part));
        else for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())addTextNodes(s,c,part,section,label,edit);
    }
    private static void add(Session s,Block b)throws IOException {if(s.blocks.size()>=MAX_BLOCKS)throw new IOException("Document exceeds 12,000 content blocks");s.blocks.add(b);}
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
    private static void validateXmlCharacters(String s)throws IOException {for(int i=0;i<s.length();){int cp=s.codePointAt(i);if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xD7FF||cp>=0xE000&&cp<=0xFFFD||cp>=0x10000&&cp<=0x10FFFF))throw new IOException("Unsupported control character");i+=Character.charCount(cp);}}
    public static byte[] readBounded(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("File or archive part exceeds safe limit");out.write(buffer,0,n);}return out.toByteArray();}
}
