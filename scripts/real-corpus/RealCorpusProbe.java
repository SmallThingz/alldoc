package org.smallthingz.alldoc.core;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** One inert bounded input per JVM. No renderer or document application is launched. */
public final class RealCorpusProbe {
    private static final Map<String,Object> result = new LinkedHashMap<>();
    private static Map<String,Object> stage(String status, Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<>(); m.put("status", status);
        for (int i=0; i<pairs.length; i+=2) m.put((String)pairs[i],pairs[i+1]); return m;
    }
    private static Map<String,Object> error(Throwable e) {
        return stage("REFUSED", "exception",e.getClass().getName(),"message",String.valueOf(e.getMessage()));
    }
    private static void require(boolean condition,String message) throws IOException {
        if (!condition) throw new IOException("Corpus invariant: "+message);
    }
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map){List<String> p=new ArrayList<>(); for(Object raw:((Map<?,?>)value).entrySet()){Map.Entry<?,?> e=(Map.Entry<?,?>)raw;p.add(json(e.getKey().toString())+":"+json(e.getValue()));}return "{"+String.join(",",p)+"}";}
        if(value instanceof Iterable){List<String> p=new ArrayList<>();for(Object x:(Iterable<?>)value)p.add(json(x));return "["+String.join(",",p)+"]";}
        StringBuilder s=new StringBuilder("\"");for(char c:value.toString().toCharArray()){
            switch(c){case '\\':s.append("\\\\");break;case '"':s.append("\\\"");break;case '\n':s.append("\\n");break;case '\r':s.append("\\r");break;case '\t':s.append("\\t");break;default:if(c<32)s.append(String.format("\\u%04x",(int)c));else s.append(c);}
        }return s.append('"').toString();
    }
    private static Map<String,byte[]> entries(byte[] bytes) throws IOException {
        Map<String,byte[]> m=new LinkedHashMap<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=in.getNextEntry())!=null)m.put(e.getName(),DocumentEngine.readBounded(in,DocumentEngine.MAX_PART));}
        return m;
    }
    private static Map<String,Object> model(DocumentEngine.Session s) throws Exception {
        Set<String> ids=new HashSet<>();int editable=0,formula=0,images=0,omissions=0;long chars=0;
        for(DocumentEngine.Block b:s.blocks){require(ids.add(b.id),"duplicate block ID");chars+=b.original.length();if(b.editable)editable++;if(!b.formula.isEmpty()){formula++;require(!b.editable,"formula editable");}require(b.row>=0 && b.column>=0,"negative cell position");}
        for(DocumentEngine.Slide slide:s.slides){require(Double.isFinite(slide.width)&&slide.width>0&&Double.isFinite(slide.height)&&slide.height>0,"invalid canvas");images+=slide.images.size();omissions+=slide.omissions.size();}
        require(s.blocks.size()<=DocumentEngine.MAX_BLOCKS,"block cap exceeded");
        return stage("PASS","blocks",s.blocks.size(),"editable_blocks",editable,"characters",chars,"formula_blocks",formula,"sheets",s.sheets.size(),"slides",s.slides.size(),"images",images,"omissions",omissions,"warnings",s.warnings);
    }
    public static void main(String[] args) {
        for(String step:Arrays.asList("open","preview_model","edit","export_unchanged","reopen_unchanged","export_edited","reopen_edited"))result.put(step,stage("NOT_RUN"));
        result.put("native_android_render",stage("NOT_RUN","reason","Requires Android device/emulator; this harness checks core models only"));
        result.put("docx_browser_render",stage("NOT_RUN","reason","Separate JS/browser/device verification required"));
        try {
            if(args.length==1 && args[0].equals("--network-probe")) {
                try(Socket ignored=new Socket("127.0.0.1",9)){result.put("network_probe",stage("FAIL","reason","socket unexpectedly permitted"));}
                catch(java.net.SocketException expected){result.put("network_probe",stage(expected.getMessage()!=null&&expected.getMessage().contains("Operation not permitted")?"PASS":"FAIL","message",expected.getMessage()));}
                System.out.println(json(result));return;
            }
            String name=args.length>1?args[1]:Paths.get(args[0]).getFileName().toString();
            byte[] source;try(InputStream in=Files.newInputStream(Paths.get(args[0]))){source=DocumentEngine.readBounded(in,DocumentEngine.MAX_FILE);}
            if(name.toLowerCase(Locale.ROOT).endsWith(".pdf")){result.put("open",stage("NOT_RUN","reason","PDF uses native Android renderer, outside JVM DocumentEngine"));System.out.println(json(result));return;}
            DocumentEngine.Session session;
            try{session=DocumentEngine.open(name,source);result.put("open",stage("PASS","session_editable",session.editable));}
            catch(Throwable e){result.put("open",error(e));System.out.println(json(result));return;}
            try{result.put("preview_model",model(session));byte[] preview=session.previewBytes(Collections.emptyMap());DocumentEngine.Session normalized=DocumentEngine.open(name,preview);require(normalized.blocks.size()==session.blocks.size(),"normalized-preview block count changed");}
            catch(Throwable e){result.put("preview_model",stage("FAIL","exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
            try{byte[] unchanged=session.export(Collections.emptyMap());require(Arrays.equals(source,unchanged),"unchanged export altered bytes");result.put("export_unchanged",stage("PASS","bytes",unchanged.length));DocumentEngine.Session reopened=DocumentEngine.open(name,unchanged);require(reopened.blocks.size()==session.blocks.size(),"unchanged reopen block count changed");result.put("reopen_unchanged",stage("PASS"));}
            catch(Throwable e){result.put("export_unchanged",stage("FAIL","exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
            DocumentEngine.Block target=null;for(DocumentEngine.Block b:session.blocks)if(b.editable){target=b;break;}
            if(target==null){
                if(session.blocks.isEmpty())result.put("edit",stage("NOT_APPLICABLE","reason","No text-edit target"));
                else {try{session.export(Collections.singletonMap(session.blocks.get(0).id,"CORPUS SHOULD BE REFUSED"));result.put("edit",stage("FAIL","reason","Read-only edit succeeded"));}
                catch(Exception expected){result.put("edit",stage("REFUSED_EXPECTED","message",expected.getMessage()));}}
            }else{
                String marker="alldoc corpus roundtrip Ω";
                String edited=target.original+((session.extension.equals("xml")||session.extension.equals("svg"))?"\n<!-- "+marker+" -->":" "+marker);
                Map<String,String> edits=Collections.singletonMap(target.id,edited);
                result.put("edit",stage("PASS","target_id",target.id,"target_label",target.label));
                try{
                    byte[] output=session.export(edits);
                    int preserved=0;
                    if(source.length>3&&source[0]=='P'&&source[1]=='K'){
                        Map<String,byte[]> before=entries(source),after=entries(output);require(before.keySet().equals(after.keySet()),"ZIP member names changed");
                        for(String part:before.keySet())if(!part.equals(target.part)){require(Arrays.equals(before.get(part),after.get(part)),"untouched ZIP member changed: "+part);preserved++;}
                    }
                    result.put("export_edited",stage("PASS","bytes",output.length,"untouched_members_verified",preserved));
                    try{DocumentEngine.Session reopened=DocumentEngine.open(name,output);boolean found=false;for(DocumentEngine.Block b:reopened.blocks)if(b.original.contains(marker))found=true;require(found,"edited marker missing after reopen");require(Arrays.equals(source,session.export(Collections.emptyMap())),"edited export poisoned original session");result.put("reopen_edited",stage("PASS"));}
                    catch(Throwable e){result.put("reopen_edited",stage("FAIL","exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
                }catch(Throwable e){result.put("export_edited",stage("FAIL","exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
            }
        }catch(Throwable e){result.put("harness",stage("FAIL","exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
        System.out.println(json(result));
    }
}
