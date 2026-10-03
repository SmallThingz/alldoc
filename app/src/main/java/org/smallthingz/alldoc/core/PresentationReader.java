package org.smallthingz.alldoc.core;

import org.w3c.dom.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Conservative presentation metadata reader. Never loads a URL or decodes raster pixels. */
final class PresentationReader {
    private static final String A="http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String P="http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String D="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String ST="urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String T="urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String O="urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String FO="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String XL="http://www.w3.org/1999/xlink";
    private final Map<String,byte[]> entries;
    private final Map<String,Document> docs;
    private final List<String> warnings;
    private final Map<String,Map<String,String>> relationCache=new HashMap<>();
    private final Map<String,Raster> rasters=new HashMap<>();
    private final Set<String> rejectedRasters=new HashSet<>();
    private final Map<Node,Integer> zOrders=new IdentityHashMap<>();
    static final int MAX_PREVIEW_OBJECTS=1024, MAX_PREVIEW_VISITS=4096;
    private int acceptedBytes,acceptedPixels,placements,previewObjects,previewVisits;
    private final Map<Document,List<Element>> pptPictureNodes=new IdentityHashMap<>(),pptShapeNodes=new IdentityHashMap<>();
    private final Map<Node,Map<String,Element>> descendantCache=new IdentityHashMap<>();
    private final Map<Node,double[]> boundsCache=new IdentityHashMap<>();
    private final Map<Document,Theme> themeCache=new IdentityHashMap<>();
    private List<String> slideWarnings=new ArrayList<>();
    private List<DocumentEngine.SlideOmission> omissions=new ArrayList<>();
    private List<DocumentEngine.SlideFill> fills=new ArrayList<>();
    private Map<String,Element> odfStyles;
    private final Map<Document,Map<String,Element>> placeholders=new IdentityHashMap<>();
    private final Map<Element,Style> odfResolvedStyles=new IdentityHashMap<>();
    PresentationReader(Map<String,byte[]> entries,Map<String,Document> docs,List<String> warnings) {
        this.entries=entries;this.docs=docs;this.warnings=warnings;
    }
    private void warn(String text){if(!warnings.contains(text))warnings.add(text);if(!slideWarnings.contains(text))slideWarnings.add(text);}
    private void omitted(String id,String reason,double[] bounds,int order){warn(reason);if(bounds[2]>0 && omissions.size()<512 && reservePreviewObject())omissions.add(new DocumentEngine.SlideOmission(id,reason,bounds,order));}
    private boolean previewExpansionAvailable() {
        if(previewObjects>=MAX_PREVIEW_OBJECTS || previewVisits>=MAX_PREVIEW_VISITS){warn("Additional slide graphics exceed the shared preview complexity limit and are omitted.");return false;}
        return true;
    }
    private boolean visitPreviewShape(){if(!previewExpansionAvailable())return false;previewVisits++;return true;}
    private boolean reservePreviewObject() {
        if(previewObjects>=MAX_PREVIEW_OBJECTS){warn("Additional slide graphics exceed the shared preview complexity limit and are omitted.");return false;}
        previewObjects++;return true;
    }
    private List<Element> pptNodes(Document doc,String local,Map<Document,List<Element>> cache) {
        List<Element> result=cache.get(doc);if(result!=null)return result;
        result=new ArrayList<>();NodeList nodes=doc.getElementsByTagNameNS(P,local);
        for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));
        cache.put(doc,result);return result;
    }
    private Document doc(String part)throws Exception {
        if(part==null)return null;
        if(!entries.containsKey(part))throw new IOException("Missing presentation part: "+part);
        if(!docs.containsKey(part))docs.put(part,DocumentEngine.parse(entries.get(part)));
        return docs.get(part);
    }
    private Map<String,String> relations(String source,String type)throws Exception {
        String key=source+"#"+type;if(relationCache.containsKey(key))return relationCache.get(key);
        int slash=source.lastIndexOf('/');String base=slash<0?"":source.substring(0,slash+1);
        String path=base+"_rels/"+source.substring(slash+1)+".rels";
        Map<String,String> result=new LinkedHashMap<>();relationCache.put(key,result);
        if(!entries.containsKey(path))return result;
        NodeList list=doc(path).getElementsByTagNameNS(REL,"Relationship");Set<String> ids=new HashSet<>();
        for(int i=0;i<list.getLength();i++) {
            Element e=(Element)list.item(i);String id=e.getAttribute("Id");
            if(id.isEmpty() || !ids.add(id))throw new IOException("Ambiguous presentation relationship");
            if(!e.getAttribute("Type").equals(R+"/"+type))continue;
            if(e.getAttribute("TargetMode").equalsIgnoreCase("External")){warn("External presentation resources are omitted; no network requests are made.");continue;}
            result.put(id,partTarget(base,e.getAttribute("Target")));
        }
        return result;
    }
    private String related(String source,String type)throws Exception {
        Map<String,String> map=relations(source,type);
        if(map.size()>1)throw new IOException("Ambiguous "+type+" relationship");
        return map.isEmpty()?null:map.values().iterator().next();
    }
    private static String partTarget(String base,String target)throws IOException {
        try {
            URI raw=new URI(target);
            if(target.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c|00).*"))throw new IOException("Ambiguous encoded presentation resource target");
            if(target.isEmpty() || target.indexOf('\\')>=0 || raw.getScheme()!=null || raw.getAuthority()!=null || raw.getQuery()!=null || raw.getFragment()!=null)throw new IOException("Unsafe presentation resource target");
            URI uri=new URI("/"+base).resolve(raw).normalize();String path=uri.getPath();
            if(path==null || !path.startsWith("/") || path.indexOf('\0')>=0 || path.indexOf('\\')>=0)throw new IOException("Unsafe presentation resource target");
            for(String segment:path.substring(1).split("/",-1))if(segment.isEmpty() || segment.equals(".") || segment.equals(".."))throw new IOException("Unsafe presentation resource target");
            return path.substring(1);
        } catch(java.net.URISyntaxException ex){throw new IOException("Invalid presentation resource target",ex);}
    }
    DocumentEngine.Slide pptx(String part,String title,int number,double width,double height,List<DocumentEngine.Block> source)throws Exception {
        slideWarnings=new ArrayList<>();omissions=new ArrayList<>();fills=new ArrayList<>();
        if(!previewExpansionAvailable())return outline(part,title,number,width,height,source);
        Document slide=doc(part);String layoutPart=related(part,"slideLayout"),masterPart=layoutPart==null?null:related(layoutPart,"slideMaster");
        Document layout=doc(layoutPart),master=doc(masterPart);
        String themePart=masterPart==null?null:related(masterPart,"theme");
        Theme theme=new Theme(doc(themePart));
        theme.colorMap(master);theme.colorMap(layout);theme.colorMap(slide);
        List<DocumentEngine.Block> blocks=new ArrayList<>();
        for(DocumentEngine.Block block:source) {
            if(!visitPreviewShape()){blocks.add(outlineBlock(block));continue;}
            Element shape=asElement(DocumentEngine.ancestorShape(block.node));
            Element layoutShape=placeholder(layout,shape),masterShape=placeholder(master,layoutShape==null?shape:layoutShape);
            double[] bounds=shapeBounds(shape);
            // A present but unsupported transform must never be replaced by a guessed inherited one.
            if(bounds[2]<0 && shape!=null && ancestor(shape,P,"grpSp")==null && first(shape,A,"xfrm")==null) {
                bounds=shapeBounds(layoutShape);
                if(bounds[2]<0 && (layoutShape==null || ancestor(layoutShape,P,"grpSp")==null && first(layoutShape,A,"xfrm")==null))bounds=shapeBounds(masterShape);
            }
            Style style=new Style();Element paragraph=ancestor(block.node,A,"p");int level=paragraphLevel(paragraph);
            Document presentation=entries.containsKey("ppt/presentation.xml")?doc("ppt/presentation.xml"):null;
            applyPptList(style,presentation==null?null:first(presentation,P,"defaultTextStyle"),level,theme);
            if(master!=null) {
                String kind=placeholderKind(shape);String group=kind.equals("title") || kind.equals("ctrTitle")?"titleStyle":kind.equals("body") || kind.equals("subTitle") || kind.equals("obj")?"bodyStyle":"otherStyle";
                applyPptList(style,first(master,P,group),level,theme);
            }
            applyPptShape(style,masterShape,level,theme,true);applyPptShape(style,layoutShape,level,theme,true);applyPptShape(style,shape,level,theme,false);
            applyParagraph(style,child(paragraph,A,"pPr"),theme);
            Element run=ancestor(block.node,A,"r");if(run==null)run=ancestor(block.node,A,"fld");
            applyRun(style,child(run,A,"rPr"),theme);
            Integer fill=null;
            Element geometry=shapeGeometry(shape);if(geometry==null)geometry=shapeGeometry(layoutShape);if(geometry==null)geometry=shapeGeometry(masterShape);
            if(geometry!=null && "prstGeom".equals(geometry.getLocalName()) && "rect".equals(geometry.getAttribute("prst"))) {
                fill=shapeFill(shape,theme);if(fill==null)fill=shapeFill(layoutShape,theme);if(fill==null)fill=shapeFill(masterShape,theme);
            } else if(child(child(shape,P,"spPr"),A,"solidFill")!=null || child(child(layoutShape,P,"spPr"),A,"solidFill")!=null || child(child(masterShape,P,"spPr"),A,"solidFill")!=null) warn("Unknown or non-rectangular shape fills are omitted from preview.");
            if(bounds[2]<0)warn("Some text frames have unsupported or missing geometry; those slides use an outline.");
            blocks.add(DocumentEngine.presentationBlock(block,style.value(),bounds,zOrder(shape),fill));
        }
        List<DocumentEngine.SlideImage> images=new ArrayList<>();
        if(master!=null && !falseAttr(slide.getDocumentElement(),"showMasterSp") && (layout==null || !falseAttr(layout.getDocumentElement(),"showMasterSp"))){pptImages(master,masterPart,-200000,images);pptFills(master,masterPart,-200000,theme);}
        if(layout!=null){pptImages(layout,layoutPart,-100000,images);pptFills(layout,layoutPart,-100000,theme);}
        pptImages(slide,part,0,images);pptFills(slide,part,0,theme);
        Integer background=background(slide,theme);if(background==null)background=background(layout,theme);if(background==null)background=background(master,theme);
        if(master!=null || layout!=null)warn("Master/layout placeholder styles, pictures and solid rectangles are supported; other decorative master text and vector shapes are omitted.");
        if(first(slide,P,"graphicFrame")!=null || first(slide,P,"cxnSp")!=null)warn("Charts, tables, diagrams and vector connectors are not rendered in the slide preview.");
        return new DocumentEngine.Slide(part,title,number,width,height,blocks,images,background,omissions,slideWarnings,fills);
    }
    private DocumentEngine.Slide outline(String id,String title,int number,double width,double height,List<DocumentEngine.Block> source) {
        List<DocumentEngine.Block> blocks=new ArrayList<>();for(DocumentEngine.Block block:source)blocks.add(outlineBlock(block));
        return new DocumentEngine.Slide(id,title,number,width,height,blocks,Collections.emptyList(),null,omissions,slideWarnings,fills);
    }
    private static DocumentEngine.Block outlineBlock(DocumentEngine.Block block) {
        return DocumentEngine.presentationBlock(block,DocumentEngine.TextStyle.DEFAULT,new double[]{-1,-1,-1,-1},0,null);
    }
    private double[] shapeBounds(Node shape) {
        if(shape==null)return new double[]{-1,-1,-1,-1};
        double[] bounds=boundsCache.get(shape);if(bounds==null){bounds=DocumentEngine.shapeBounds(shape);boundsCache.put(shape,bounds);}return bounds;
    }
    private static boolean falseAttr(Element e,String name){return e!=null && (e.getAttribute(name).equals("0") || e.getAttribute(name).equals("false"));}
    private Element placeholder(Document doc,Element shape) {
        if(doc==null || shape==null)return null;Element ph=first(shape,P,"ph");if(ph==null)return null;
        String idx=ph.hasAttribute("idx")?ph.getAttribute("idx"):"0",kind=ph.hasAttribute("type")?ph.getAttribute("type"):"obj";
        if(idx.length()>128 || kind.length()>128){warn("Oversized placeholder identifiers are omitted.");return null;}
        Map<String,Element> index=placeholders.get(doc);
        if(index==null) {
            index=new HashMap<>();placeholders.put(doc,index);NodeList shapes=doc.getElementsByTagNameNS(P,"sp");
            for(int i=0;i<shapes.getLength();i++) {
                Element candidate=(Element)shapes.item(i),other=first(candidate,P,"ph");if(other==null)continue;
                String otherIdx=other.hasAttribute("idx")?other.getAttribute("idx"):"0",otherType=other.hasAttribute("type")?other.getAttribute("type"):"obj";
                if(otherIdx.length()<=128 && otherType.length()<=128){index.putIfAbsent("i:"+otherIdx,candidate);index.putIfAbsent("t:"+otherType,candidate);}
            }
        }
        // Master placeholders inherit by type; layout placeholders inherit primarily by index.
        boolean master="sldMaster".equals(doc.getDocumentElement().getLocalName());
        Element found=index.get((master?"t:":"i:")+(master?kind:idx));
        return found==null?index.get((master?"i:":"t:")+(master?idx:kind)):found;
    }
    private String placeholderKind(Element shape){Element ph=first(shape,P,"ph");return ph==null?"":ph.hasAttribute("type")?ph.getAttribute("type"):"obj";}
    private static int paragraphLevel(Element p){try {String value=child(p,A,"pPr").getAttribute("lvl");int level=Integer.parseInt(value);return level>=0 && level<=8?level:0;}catch(Exception ex){return 0;}}
    private void applyPptShape(Style style,Element shape,int level,Theme theme,boolean inheritParagraphDefault) {
        if(shape==null)return;Element body=child(shape,P,"txBody");
        Element ref=first(child(shape,P,"style"),A,"fontRef");
        if(ref!=null){String idx=ref.getAttribute("idx");if(idx.equals("major"))style.font=theme.major;else if(idx.equals("minor"))style.font=theme.minor;Integer color=theme.color(ref);if(color!=null)style.color=color;}
        applyPptList(style,child(body,A,"lstStyle"),level,theme);
        if(inheritParagraphDefault){Element paragraph=child(body,A,"p");applyParagraph(style,child(paragraph,A,"pPr"),theme);}
    }
    private void applyPptList(Style style,Element list,int level,Theme theme){applyParagraph(style,child(list,A,"defPPr"),theme);applyParagraph(style,child(list,A,"lvl"+(level+1)+"pPr"),theme);}
    private void applyParagraph(Style style,Element p,Theme theme) {
        if(p==null)return;String align=p.getAttribute("algn");if(!align.isEmpty())style.align=align.equals("ctr")?"center":align.equals("r")?"right":align.equals("just")?"justify":"left";
        if(first(p,A,"buChar")!=null || first(p,A,"buAutoNum")!=null)warn("Bullets and automatic numbering are not reproduced in this compact preview.");
        applyRun(style,child(p,A,"defRPr"),theme);
    }
    private void applyRun(Style style,Element run,Theme theme) {
        if(run==null)return;
        if(run.hasAttribute("sz"))try{double size=Double.parseDouble(shortValue(run.getAttribute("sz")))/100;if(Double.isFinite(size) && size>=1 && size<=400)style.size=size;else warn("Out-of-range font sizes are omitted from preview.");}catch(NumberFormatException ex){warn("Invalid font sizes are omitted from preview.");}
        if(run.hasAttribute("b"))style.bold=on(run.getAttribute("b"));if(run.hasAttribute("i"))style.italic=on(run.getAttribute("i"));
        if(run.hasAttribute("u"))style.underline=!run.getAttribute("u").equals("none");if(run.hasAttribute("strike"))style.strike=!run.getAttribute("strike").equals("noStrike");
        Element latin=child(run,A,"latin");if(latin!=null){String font=latin.getAttribute("typeface");style.font=font.equals("+mj-lt")?theme.major:font.equals("+mn-lt")?theme.minor:safeFont(font);}
        Integer color=theme.color(child(run,A,"solidFill"));if(color!=null)style.color=color;
        if(child(run,A,"gradFill")!=null)warn("Gradient text fills are not reproduced in preview.");
    }
    private static Element shapeGeometry(Element shape) {
        Element props=child(shape,P,"spPr"),custom=child(props,A,"custGeom");
        return custom!=null?custom:child(props,A,"prstGeom");
    }
    private Integer shapeFill(Element shape,Theme theme) {
        Element props=child(shape,P,"spPr");if(props==null)return null;
        if(child(props,A,"noFill")!=null)return 0;
        Integer fill=theme.color(child(props,A,"solidFill"));
        Element geom=child(props,A,"prstGeom");
        if(fill!=null && (child(props,A,"custGeom")!=null || (geom!=null && !geom.getAttribute("prst").equals("rect")))){warn("Non-rectangular shape fills are omitted from preview.");return null;}
        if(fill==null && (child(props,A,"gradFill")!=null || child(props,A,"blipFill")!=null))warn("Gradient and picture shape fills are not reproduced in preview.");
        return fill;
    }
    private void pptFills(Document doc,String part,int layer,Theme theme) {
        if(!previewExpansionAvailable())return;
        List<Element> shapes=pptNodes(doc,"sp",pptShapeNodes);
        for(int i=0;i<shapes.size();i++) {
            if(!visitPreviewShape())return;
            Element shape=shapes.get(i);if(first(shape,A,"t")!=null)continue;
            Element props=child(shape,P,"spPr"),geometry=child(props,A,"prstGeom");
            if(geometry==null || !geometry.getAttribute("prst").equals("rect")){warn("Non-rectangular vector shapes are omitted from preview.");continue;}
            double[] bounds=shapeBounds(shape);Integer color=shapeFill(shape,theme);
            if(bounds[2]<0 || color==null){warn("A vector shape has unsupported geometry or fill and is omitted.");continue;}
            if(fills.size()<512 && reservePreviewObject())fills.add(new DocumentEngine.SlideFill(part+"#fill"+i,bounds,layer+zOrder(shape),color));
            if(child(props,A,"ln")!=null || child(props,A,"effectLst")!=null)warn("Rectangle borders and effects are omitted from preview.");
        }
    }
    private Integer background(Document doc,Theme theme) {
        Element bg=first(doc,P,"bg");if(bg==null)return null;
        Integer color=theme.color(child(child(bg,P,"bgPr"),A,"solidFill"));
        if(color==null)warn("A non-solid or theme-style slide background is omitted from preview.");return color;
    }
    private void pptImages(Document doc,String part,int layer,List<DocumentEngine.SlideImage> out)throws Exception {
        if(!previewExpansionAvailable())return;
        List<Element> pictures=pptNodes(doc,"pic",pptPictureNodes);if(pictures.isEmpty())return;
        Map<String,String> links=relations(part,"image");
        for(int i=0;i<pictures.size();i++) {
            if(!visitPreviewShape())return;
            Element pic=pictures.get(i);double[] bounds=shapeBounds(pic);
            if(bounds[2]<0){warn("Images with missing geometry, rotation, flips or grouped transforms are omitted.");continue;}
            Element fill=child(pic,P,"blipFill"),blip=child(fill,A,"blip"),crop=child(fill,A,"srcRect");
            if(blip==null)continue;
            if(crop!=null && nonzeroCrop(crop) || child(fill,A,"tile")!=null || hasPictureEffects(blip) || first(pic,A,"effectLst")!=null && first(pic,A,"effectLst").hasChildNodes()) {
                omitted(part+"#pic"+i,"Cropped, tiled or effect-treated image omitted",bounds,layer+zOrder(pic));continue;
            }
            Element geometry=child(child(pic,P,"spPr"),A,"prstGeom");
            if(geometry!=null && !geometry.getAttribute("prst").equals("rect") || first(pic,A,"custGeom")!=null){omitted(part+"#pic"+i,"Non-rectangular image mask omitted",bounds,layer+zOrder(pic));continue;}
            if(blip.hasAttributeNS(R,"link")){omitted(part+"#pic"+i,"External image omitted; no network request",bounds,layer+zOrder(pic));continue;}
            String target=links.get(blip.getAttributeNS(R,"embed"));
            if(target==null){omitted(part+"#pic"+i,"Missing or unsupported image relationship",bounds,layer+zOrder(pic));continue;}
            addImage(out,part+"#pic"+i,target,entries.get(target),bounds,layer+zOrder(pic));
        }
    }
    private static boolean nonzeroCrop(Element e){for(String name:new String[]{"l","t","r","b"})if(e.hasAttribute(name) && !e.getAttribute(name).matches("[+]?0+"))return true;return false;}
    private static boolean hasPictureEffects(Element blip){for(Node n=blip.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)return true;return false;}
    private int zOrder(Node shape) {
        if(shape==null || shape.getParentNode()==null)return 0;
        if(!zOrders.containsKey(shape)){int order=0;for(Node n=shape.getParentNode().getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)zOrders.put(n,order++);}
        return zOrders.getOrDefault(shape,0);
    }
    private static String shortValue(String value){if(value.length()>64)throw new NumberFormatException("Oversized number");return value;}
    private static String safeFont(String value){return value.length()<=128 && value.chars().allMatch(c->c>=32)?value:"";}
    private static boolean on(String value){return value.equals("1") || value.equals("true");}
    private final class Theme {
        final Map<String,Integer> colors=new HashMap<>();final Map<String,String> mapping=new HashMap<>();String major="",minor="";
        Theme(Document doc) {
            mapping.put("bg1","lt1");mapping.put("tx1","dk1");mapping.put("bg2","lt2");mapping.put("tx2","dk2");
            Theme cached=doc==null?null:themeCache.get(doc);
            if(cached!=null){colors.putAll(cached.colors);major=cached.major;minor=cached.minor;return;}
            Element scheme=first(doc,A,"clrScheme");
            if(scheme!=null)for(Node n=scheme.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element){Integer value=color((Element)n);if(value!=null)colors.put(n.getLocalName(),value);}
            Element fonts=first(doc,A,"fontScheme"),mj=child(child(fonts,A,"majorFont"),A,"latin"),mn=child(child(fonts,A,"minorFont"),A,"latin");
            if(mj!=null)major=safeFont(mj.getAttribute("typeface"));if(mn!=null)minor=safeFont(mn.getAttribute("typeface"));
            if(doc!=null)themeCache.put(doc,this);
        }
        void colorMap(Document doc) {
            Element map=first(doc,P,"clrMap");if(map==null)map=first(doc,A,"overrideClrMapping");
            if(map!=null){NamedNodeMap attrs=map.getAttributes();for(int i=0;i<attrs.getLength();i++){Node a=attrs.item(i);if(a.getNamespaceURI()==null)mapping.put(a.getNodeName(),a.getNodeValue());}}
        }
        Integer color(Element container) {
            if(container==null)return null;Element color=null;
            for(Node n=container.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element && A.equals(n.getNamespaceURI()) && Set.of("srgbClr","schemeClr","sysClr").contains(n.getLocalName())){color=(Element)n;break;}
            if(color==null)return null;Integer value=null;
            String val=color.getAttribute(color.getLocalName().equals("sysClr")?"lastClr":"val");
            if(color.getLocalName().equals("schemeClr"))value=colors.get(mapping.getOrDefault(val,val));else if(val.matches("[0-9a-fA-F]{6}"))value=(int)(0xff000000L|Long.parseLong(val,16));
            if(value==null)return null;
            int alpha=255;double red=(value>>16)&255,green=(value>>8)&255,blue=value&255;
            for(Node n=color.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element) {
                Element effect=(Element)n;double fraction;
                try {fraction=Integer.parseInt(shortValue(effect.getAttribute("val")))/100000.0;if(fraction<0 || fraction>1)throw new NumberFormatException();}
                catch(NumberFormatException ex){warn("Unsupported theme color transforms use an unresolved preview color.");return null;}
                switch(effect.getLocalName()) {
                    case "alpha":alpha=(int)Math.round(fraction*255);break;
                    case "tint":red+=(255-red)*fraction;green+=(255-green)*fraction;blue+=(255-blue)*fraction;break;
                    case "shade":red*=fraction;green*=fraction;blue*=fraction;break;
                    case "lumMod":case "lumOff":double[] adjusted=luminosity(red,green,blue,effect.getLocalName().equals("lumMod")?fraction:1,effect.getLocalName().equals("lumOff")?fraction:0);red=adjusted[0];green=adjusted[1];blue=adjusted[2];break;
                    default:warn("Unsupported theme color transforms use an unresolved preview color.");return null;
                }
            }
            return alpha<<24|clamp(red)<<16|clamp(green)<<8|clamp(blue);
        }
    }
    private static double[] luminosity(double r,double g,double b,double multiplier,double offset) {
        r/=255;g/=255;b/=255;double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min,light=(max+min)/2;
        double saturation=delta==0?0:delta/(1-Math.abs(2*light-1)),hue=0;
        if(delta!=0){hue=max==r?((g-b)/delta)%6:max==g?(b-r)/delta+2:(r-g)/delta+4;hue/=6;if(hue<0)hue+=1;}
        light=Math.max(0,Math.min(1,light*multiplier+offset));double chroma=(1-Math.abs(2*light-1))*saturation,x=chroma*(1-Math.abs((hue*6)%2-1)),m=light-chroma/2;
        int segment=(int)(hue*6);double[] rgb=segment==0?new double[]{chroma,x,0}:segment==1?new double[]{x,chroma,0}:segment==2?new double[]{0,chroma,x}:segment==3?new double[]{0,x,chroma}:segment==4?new double[]{x,0,chroma}:new double[]{chroma,0,x};
        return new double[]{(rgb[0]+m)*255,(rgb[1]+m)*255,(rgb[2]+m)*255};
    }
    private static int clamp(double value){return (int)Math.round(Math.max(0,Math.min(255,value)));}
    private static final class Style {
        String font="",align="left";double size=-1;Integer color=null;boolean bold,italic,underline,strike;int mask;
        void apply(Style s){if((s.mask&1)!=0)font=s.font;if((s.mask&2)!=0)size=s.size;if((s.mask&4)!=0)color=s.color;if((s.mask&8)!=0)bold=s.bold;if((s.mask&16)!=0)italic=s.italic;if((s.mask&32)!=0)underline=s.underline;if((s.mask&64)!=0)strike=s.strike;if((s.mask&128)!=0)align=s.align;mask|=s.mask;}
        DocumentEngine.TextStyle value(){return new DocumentEngine.TextStyle(font,size,color,bold,italic,underline,strike,align);}
    }
    DocumentEngine.Slide odf(Element page,String id,String title,int number,double width,double height,List<DocumentEngine.Block> source)throws Exception {
        slideWarnings=new ArrayList<>();omissions=new ArrayList<>();fills=new ArrayList<>();
        if(!previewExpansionAvailable())return outline(id,title,number,width,height,source);
        initOdfStyles();List<DocumentEngine.Block> blocks=new ArrayList<>();
        for(DocumentEngine.Block block:source) {
            if(!visitPreviewShape()){blocks.add(outlineBlock(block));continue;}
            Style style=new Style();applyOdfStyle(style,odfStyles.get("graphic#"),new HashSet<>(),0);applyOdfStyle(style,odfStyles.get("paragraph#"),new HashSet<>(),0);
            List<Element> lineage=new ArrayList<>();for(Node n=block.node;n!=null && n!=page;n=n.getParentNode())if(n instanceof Element)lineage.add((Element)n);
            Collections.reverse(lineage);Integer fill=null;
            for(Element e:lineage) {
                String family=D.equals(e.getNamespaceURI())?"graphic":T.equals(e.getNamespaceURI()) && e.getLocalName().equals("span")?"text":"paragraph";
                String name=e.getAttributeNS(D,"style-name");if(name.isEmpty())name=e.getAttributeNS(T,"style-name");
                Element named=odfStyles.get(family+"#"+name);if(!name.isEmpty()){applyOdfStyle(style,named,new HashSet<>(),0);Integer candidate=odfFill(named,new HashSet<>(),0);if(candidate!=null)fill=candidate;}
            }
            Element shape=asElement(DocumentEngine.ancestorShape(block.node));double[] bounds=shapeBounds(shape);
            if(bounds[2]<0)warn("Some text frames have unsupported or missing geometry; those slides use an outline.");
            blocks.add(DocumentEngine.presentationBlock(block,style.value(),bounds,zOrder(shape),fill));
        }
        List<DocumentEngine.SlideImage> images=new ArrayList<>();NodeList pictureNodes=page.getElementsByTagNameNS(D,"image");
        for(int i=0;i<pictureNodes.getLength();i++) {
            if(!visitPreviewShape())break;
            Element e=(Element)pictureNodes.item(i),frame=ancestor(e,D,"frame");
            if(ancestor(e,"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0","notes")!=null)continue;
            double[] bounds=shapeBounds(frame);
            if(bounds[2]<0){warn("Images with missing geometry, rotation, flips or grouped transforms are omitted.");continue;}
            Element graphic=frame==null?null:odfStyles.get("graphic#"+frame.getAttributeNS(D,"style-name"));
            if(odfImageTransform(graphic,new HashSet<>(),0)){omitted(id+"#image"+i,"Cropped, mirrored or effect-treated image omitted",bounds,zOrder(frame));continue;}
            String target=e.getAttributeNS(XL,"href"),key;byte[] data;
            if(!target.isEmpty()) {
                try {key=partTarget("",target);}catch(IOException ex){omitted(id+"#image"+i,"External or unsafe image omitted; no network request",bounds,zOrder(frame));continue;}
                data=entries.get(key);
            } else {
                Element binary=child(e,O,"binary-data");if(binary==null){omitted(id+"#image"+i,"Missing embedded image",bounds,zOrder(frame));continue;}
                String text=binary.getTextContent();
                if(text.length()>DocumentEngine.MAX_IMAGE_BYTES*2){omitted(id+"#image"+i,"Image exceeds safe preview byte limit",bounds,zOrder(frame));continue;}
                StringBuilder base64=new StringBuilder();
                for(int c=0;c<text.length();c++){char v=text.charAt(c);if(v!=' ' && v!='\t' && v!='\r' && v!='\n')base64.append(v);}
                try{data=Base64.getDecoder().decode(base64.toString());}catch(IllegalArgumentException ex){omitted(id+"#image"+i,"Invalid embedded image data",bounds,zOrder(frame));continue;}
                key=id+"#binary"+i;
            }
            addImage(images,id+"#image"+i,key,data,bounds,zOrder(frame));
        }
        NodeList rectangles=page.getElementsByTagNameNS(D,"rect");
        for(int i=0;i<rectangles.getLength();i++) {
            if(!visitPreviewShape())break;
            Element rect=(Element)rectangles.item(i);double[] bounds=shapeBounds(rect);
            Integer color=odfFill(odfStyles.get("graphic#"+rect.getAttributeNS(D,"style-name")),new HashSet<>(),0);
            if(bounds[2]>0 && color!=null && !rect.hasAttributeNS(D,"corner-radius") && fills.size()<512 && reservePreviewObject())fills.add(new DocumentEngine.SlideFill(id+"#fill"+i,bounds,zOrder(rect),color));
            else warn("A vector shape has unsupported geometry or fill and is omitted.");
        }
        Integer background=odfFill(odfStyles.get("drawing-page#"+page.getAttributeNS(D,"style-name")),new HashSet<>(),0);
        if(first(page,D,"custom-shape")!=null || first(page,D,"line")!=null || first(page,D,"path")!=null)warn("Vector shapes and connectors are not rendered in the slide preview.");
        return new DocumentEngine.Slide(id,title,number,width,height,blocks,images,background,omissions,slideWarnings,fills);
    }
    private void initOdfStyles()throws Exception {
        if(odfStyles!=null)return;odfStyles=new HashMap<>();
        for(String part:new String[]{"styles.xml","content.xml"})if(entries.containsKey(part)) {
            Document d=doc(part);
            for(String local:new String[]{"default-style","style"}) {
                NodeList list=d.getElementsByTagNameNS(ST,local);
                for(int i=0;i<list.getLength();i++){Element e=(Element)list.item(i);odfStyles.put(e.getAttributeNS(ST,"family")+"#"+e.getAttributeNS(ST,"name"),e);}
            }
        }
    }
    private void applyOdfStyle(Style style,Element e,Set<Element> seen,int depth) {
        if(e==null)return;
        if(depth>=16 || !seen.add(e)){warn("Cyclic or deep style inheritance is omitted beyond the safe preview limit.");return;}
        Style cached=odfResolvedStyles.get(e);if(cached!=null){style.apply(cached);return;}
        Style destination=style;style=new Style();
        String parent=e.getAttributeNS(ST,"parent-style-name"),family=e.getAttributeNS(ST,"family");
        if(!parent.isEmpty())applyOdfStyle(style,odfStyles.get(family+"#"+parent),seen,depth+1);
        Element text=child(e,ST,"text-properties"),p=child(e,ST,"paragraph-properties");
        if(p!=null && p.hasAttributeNS(FO,"text-align")){String align=p.getAttributeNS(FO,"text-align");style.align=align.equals("center")?"center":align.equals("right") || align.equals("end")?"right":align.equals("justify")?"justify":"left";style.mask|=128;}
        if(text==null){odfResolvedStyles.put(e,style);destination.apply(style);return;}
        String size=text.getAttributeNS(FO,"font-size");if(size.length()>64){warn("Oversized style attributes are omitted.");size="";}
        if(!size.isEmpty())try {
            double value=size.endsWith("%") && style.size>0?Double.parseDouble(size.substring(0,size.length()-1))*style.size/100:DocumentEngine.odfLength(size);
            if(Double.isFinite(value) && value>=1 && value<=400){style.size=value;style.mask|=2;}else warn("Out-of-range font sizes are omitted from preview.");
        }catch(Exception ex){warn("An unsupported font size is omitted from preview.");}
        Integer color=hexColor(text.getAttributeNS(FO,"color"));if(color!=null){style.color=color;style.mask|=4;}
        if(text.hasAttributeNS(FO,"font-weight")){String weight=text.getAttributeNS(FO,"font-weight");style.bold=weight.equals("bold") || weight.matches("[6-9]00");style.mask|=8;}
        if(text.hasAttributeNS(FO,"font-style")){style.italic=text.getAttributeNS(FO,"font-style").equals("italic") || text.getAttributeNS(FO,"font-style").equals("oblique");style.mask|=16;}
        if(text.hasAttributeNS(ST,"text-underline-style")){style.underline=!text.getAttributeNS(ST,"text-underline-style").equals("none");style.mask|=32;}
        if(text.hasAttributeNS(ST,"text-line-through-style")){style.strike=!text.getAttributeNS(ST,"text-line-through-style").equals("none");style.mask|=64;}
        String font=text.getAttributeNS(FO,"font-family");if(font.isEmpty())font=text.getAttributeNS(ST,"font-name");
        if(!font.isEmpty()){style.font=safeFont(font.replace("'",""));style.mask|=1;}
        odfResolvedStyles.put(e,style);destination.apply(style);
    }
    private Integer odfFill(Element style,Set<Element> seen,int depth) {
        if(style==null || depth>=16 || !seen.add(style))return null;
        Element props=child(style,ST,"graphic-properties");if(props==null)props=child(style,ST,"drawing-page-properties");
        if(props!=null){String fill=props.getAttributeNS(D,"fill");if(fill.equals("none"))return 0;if(fill.equals("solid")){Integer color=hexColor(props.getAttributeNS(D,"fill-color"));if(color!=null)return color;}
            if(!fill.isEmpty() && !fill.equals("solid"))warn("Non-solid ODF backgrounds and shape fills are omitted from preview.");}
        return odfFill(odfStyles.get(style.getAttributeNS(ST,"family")+"#"+style.getAttributeNS(ST,"parent-style-name")),seen,depth+1);
    }
    private boolean odfImageTransform(Element style,Set<Element> seen,int depth) {
        if(style==null)return false;if(depth>=16 || !seen.add(style))return true;
        Element props=child(style,ST,"graphic-properties");
        if(props!=null) {
            String clip=props.getAttributeNS(FO,"clip"),mirror=props.getAttributeNS(ST,"mirror");
            if(!clip.isEmpty() && !clip.equals("auto") || !mirror.isEmpty() && !mirror.equals("none"))return true;
            for(String name:new String[]{"opacity","red","green","blue","luminance","contrast","gamma","color-inversion"})if(props.hasAttributeNS(D,name))return true;
        }
        String parent=style.getAttributeNS(ST,"parent-style-name");return !parent.isEmpty() && odfImageTransform(odfStyles.get(style.getAttributeNS(ST,"family")+"#"+parent),seen,depth+1);
    }
    private static Integer hexColor(String value){if(value.matches("#[0-9a-fA-F]{6}"))return (int)(0xff000000L|Long.parseLong(value.substring(1),16));if(value.equals("transparent"))return 0;return null;}
    private void addImage(List<DocumentEngine.SlideImage> out,String id,String key,byte[] data,double[] bounds,int order) {
        if(++placements>512){omitted(id,"Image placement limit reached",bounds,order);return;}
        if(rejectedRasters.contains(key)){omitted(id,"Previously rejected or over-budget image omitted",bounds,order);return;}
        Raster raster=rasters.get(key);
        if(raster==null) {
            if(data==null || data.length==0){rejectedRasters.add(key);omitted(id,"Missing embedded image data",bounds,order);return;}
            if(data.length>DocumentEngine.MAX_IMAGE_BYTES || data.length>DocumentEngine.MAX_IMAGE_TOTAL_BYTES-acceptedBytes){rejectedRasters.add(key);omitted(id,"Image exceeds safe preview byte budget",bounds,order);return;}
            raster=inspectRaster(data);
            if(raster==null){rejectedRasters.add(key);omitted(id,"Invalid or unsupported image; only bounded, unrotated PNG/JPEG previewed",bounds,order);return;}
            long pixels=(long)raster.width*raster.height;
            if(raster.width<1 || raster.height<1 || raster.width>DocumentEngine.MAX_IMAGE_DIMENSION || raster.height>DocumentEngine.MAX_IMAGE_DIMENSION || pixels>DocumentEngine.MAX_IMAGE_PIXELS || pixels>DocumentEngine.MAX_IMAGE_TOTAL_PIXELS-acceptedPixels){rejectedRasters.add(key);omitted(id,"Image exceeds safe preview pixel budget",bounds,order);return;}
            acceptedBytes+=data.length;acceptedPixels+=(int)pixels;rasters.put(key,raster);
        }
        if(reservePreviewObject())out.add(new DocumentEngine.SlideImage(id,raster.mime,bounds,order,raster.width,raster.height,raster.data));
    }
    private static final class Raster {final String mime;final int width,height;final byte[] data;Raster(String mime,int width,int height,byte[] data){this.mime=mime;this.width=width;this.height=height;this.data=data;}}
    private static Raster inspectRaster(byte[] b) {
        if(b.length>=33 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71 && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10) {
            if(be32(b,8)!=13 || !ascii(b,12,"IHDR"))return null;
            int width=be32(b,16),height=be32(b,20),at=8,chunks=0;boolean data=false,end=false;
            while(at<=b.length-12 && ++chunks<=4096) {
                int length=be32(b,at);if(length<0 || length>b.length-at-12)return null;
                if(ascii(b,at+4,"acTL"))return null; // Do not silently turn animation into a still image.
                if(ascii(b,at+4,"IDAT"))data=true;
                java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(b,at+4,length+4);
                if((int)crc.getValue()!=be32(b,at+8+length))return null;
                boolean last=ascii(b,at+4,"IEND");at+=length+12;
                if(last){if(length!=0)return null;end=true;break;}
            }
            return end && data && at==b.length?new Raster("image/png",width,height,b):null;
        }
        if(b.length<4 || (b[0]&255)!=255 || (b[1]&255)!=216 || (b[b.length-2]&255)!=255 || (b[b.length-1]&255)!=217)return null;
        int at=2,width=0,height=0,segments=0;
        while(at<b.length-2 && ++segments<=4096) {
            if((b[at++]&255)!=255)return null;while(at<b.length && (b[at]&255)==255)at++;if(at>=b.length)return null;
            int marker=b[at++]&255;if(marker==0xd9)break;if(marker==0x01 || marker>=0xd0 && marker<=0xd7)continue;
            if(at+2>b.length)return null;int length=be16(b,at);if(length<2 || length>b.length-at)return null;
            if(marker==0xe1 && length>=8 && ascii(b,at+2,"Exif") && !unrotatedExif(b,at+8,at+length))return null;
            if(marker==0xc0 || marker==0xc1 || marker==0xc2) {if(length<8)return null;height=be16(b,at+3);width=be16(b,at+5);}
            if(marker==0xda)return width>0 && height>0?new Raster("image/jpeg",width,height,b):null;
            at+=length;
        }
        return null;
    }
    private static boolean unrotatedExif(byte[] b,int start,int end) {
        if(end-start<8)return false;boolean little=b[start]=='I' && b[start+1]=='I';if(!little && !(b[start]=='M' && b[start+1]=='M'))return false;
        if(u16(b,start+2,little)!=42)return false;long offset=u32(b,start+4,little);if(offset<8 || offset>end-start-2)return false;
        int at=start+(int)offset,count=u16(b,at,little);at+=2;if(count>(end-at)/12)return false;
        for(int i=0;i<count;i++,at+=12)if(u16(b,at,little)==0x112)return u16(b,at+2,little)==3 && u32(b,at+4,little)==1 && u16(b,at+8,little)==1;
        return true;
    }
    private static int u16(byte[] b,int at,boolean little){return little?(b[at]&255)|((b[at+1]&255)<<8):be16(b,at);}
    private static long u32(byte[] b,int at,boolean little){return little?((long)u16(b,at+2,true)<<16)|u16(b,at,true):Integer.toUnsignedLong(be32(b,at));}
    private static int be16(byte[] b,int at){return (b[at]&255)<<8|(b[at+1]&255);}
    private static int be32(byte[] b,int at){return be16(b,at)<<16|be16(b,at+2);}
    private static boolean ascii(byte[] b,int at,String text){if(at+text.length()>b.length)return false;for(int i=0;i<text.length();i++)if(b[at+i]!=(byte)text.charAt(i))return false;return true;}
    private static Element asElement(Node node){return node instanceof Element?(Element)node:null;}
    private Element first(Node node,String ns,String local) {
        if(node==null)return null;String key=ns+"#"+local;
        Map<String,Element> cache=descendantCache.computeIfAbsent(node,n->new HashMap<>());
        if(cache.containsKey(key))return cache.get(key);
        NodeList list=node instanceof Document?((Document)node).getElementsByTagNameNS(ns,local):node instanceof Element?((Element)node).getElementsByTagNameNS(ns,local):null;
        Element found=list==null?null:(Element)list.item(0);cache.put(key,found);return found;
    }
    private static Element child(Node node,String ns,String local){if(node==null)return null;for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element && ns.equals(n.getNamespaceURI()) && local.equals(n.getLocalName()))return (Element)n;return null;}
    private static Element ancestor(Node node,String ns,String local){for(Node n=node;n!=null;n=n.getParentNode())if(n instanceof Element && ns.equals(n.getNamespaceURI()) && local.equals(n.getLocalName()))return (Element)n;return null;}
}
