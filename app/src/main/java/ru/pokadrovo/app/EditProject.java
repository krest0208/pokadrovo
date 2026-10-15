package ru.pokadrovo.app;

import java.util.*;

/** Timeline model modeled after the public-facing behavior and DEX field layout of Pokadrovo 2.0.
 * All durations and positions use milliseconds; source media is never modified. */
public final class EditProject {
    public String id = UUID.randomUUID().toString();
    public String name = "Новый ролик";
    public String ratio = "9:16";
    public int height = 720;
    public final ArrayList<Clip> main = new ArrayList<>();
    public final ArrayList<Clip> layers = new ArrayList<>();

    public static final long MIN_CLIP_MS = 100;
    public static final int MAX_CLIPS = 2000;

    public static final class Clip {
        public String id = UUID.randomUUID().toString();
        public String kind = "video";  // video, image, audio, text
        public String name = "Клип";
        public String uri = "";
        public String text = "";
        public long source = 5000; // length of source media
        public long in = 0;
        public long out = 5000;
        public long start = 0; // fixed position only for overlay layers
        public float speed = 1f;
        public float volume = 1f;
        public float pan = 0f;
        public float bass = 0f;
        public float treble = 0f;
        public float fadeIn = 0f;
        public float fadeOut = 0f;
        public boolean compress = false;
        public float brightness = 0f;
        public float contrast = 1f;
        public float zoom = 1f;
        public float opacity = 1f;
        public float rotation = 0f;
        public boolean flip = false;
        public boolean fill = false;
        public float x = 0.5f;
        public float y = 0.5f;
        public int size = 46;
        public int color = 0xFFFFFFFF;

        public long duration() {
            double result=(out-in)/Math.max(0.001,speed);
            return Math.max(0,Math.round(result));
        }
        public Clip copy(boolean regenerateId) {
            Clip c=new Clip();c.id=regenerateId?UUID.randomUUID().toString():id;
            c.kind=kind;c.name=name;c.uri=uri;c.text=text;c.source=source;
            c.in=in;c.out=out;c.start=start;c.speed=speed;c.volume=volume;
            c.pan=pan;c.bass=bass;c.treble=treble;c.fadeIn=fadeIn;c.fadeOut=fadeOut;
            c.compress=compress;c.brightness=brightness;c.contrast=contrast;c.zoom=zoom;
            c.opacity=opacity;c.rotation=rotation;c.flip=flip;c.fill=fill;c.x=x;c.y=y;
            c.size=size;c.color=color;return c;
        }
        public void validate() {
            if(!Arrays.asList("video","image","audio","text").contains(kind))
                throw new IllegalArgumentException("Неизвестный тип файла: "+kind);
            if(in<0||out<=in||out>Math.max(source,MIN_CLIP_MS))throw new IllegalArgumentException("Некорректные границы клипа");
            if(start<0)throw new IllegalArgumentException("Отрицательная позиция слоя");
            if(!Float.isFinite(speed)||speed<0.1f||speed>8f)throw new IllegalArgumentException("Некорректная скорость");
            if(!Float.isFinite(volume)||volume<0||volume>3f)throw new IllegalArgumentException("Некорректная громкость");
            if(!Float.isFinite(opacity)||opacity<0||opacity>1f)throw new IllegalArgumentException("Прозрачность вне диапазона");
            if(!Float.isFinite(zoom)||zoom<0.1f||zoom>10f)throw new IllegalArgumentException("Масштаб вне диапазона");
            if(!Float.isFinite(brightness)||!Float.isFinite(contrast)||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation))throw new IllegalArgumentException("Неверное значение эффекта");
            if(duration()<MIN_CLIP_MS)throw new IllegalArgumentException("Минимальная длина клипа 0,1 с");
            if((kind.equals("video")||kind.equals("image")||kind.equals("audio"))&&uri.isEmpty())
                throw new IllegalArgumentException("Медиа недоступно");
        }
        public Map<String,Object> json() {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id);m.put("kind",kind);m.put("name",name);m.put("uri",uri);m.put("text",text);
            m.put("source",source);m.put("in",in);m.put("out",out);m.put("start",start);
            m.put("speed",speed);m.put("volume",volume);m.put("pan",pan);
            m.put("bass",bass);m.put("treble",treble);m.put("fadeIn",fadeIn);m.put("fadeOut",fadeOut);
            m.put("compress",compress);m.put("brightness",brightness);m.put("contrast",contrast);
            m.put("zoom",zoom);m.put("opacity",opacity);m.put("rotation",rotation);
            m.put("flip",flip);m.put("fill",fill);m.put("x",x);m.put("y",y);
            m.put("size",size);m.put("color",color);return m;
        }
        public static Clip read(Object value) {
            Map<String,Object> m=Json.object(value);Clip c=new Clip();
            c.id=Json.str(m,"id",c.id);c.kind=Json.str(m,"kind",c.kind);c.name=Json.str(m,"name",c.name);
            c.uri=Json.str(m,"uri","");c.text=Json.str(m,"text","");
            c.source=(long)Json.num(m,"source",5000);c.in=(long)Json.num(m,"in",0);c.out=(long)Json.num(m,"out",c.source);
            c.start=(long)Json.num(m,"start",0);c.speed=(float)Json.num(m,"speed",1);
            c.volume=(float)Json.num(m,"volume",1);c.pan=(float)Json.num(m,"pan",0);
            c.bass=(float)Json.num(m,"bass",0);c.treble=(float)Json.num(m,"treble",0);
            c.fadeIn=(float)Json.num(m,"fadeIn",0);c.fadeOut=(float)Json.num(m,"fadeOut",0);
            c.compress=Json.bool(m,"compress",false);c.brightness=(float)Json.num(m,"brightness",0);
            c.contrast=(float)Json.num(m,"contrast",1);c.zoom=(float)Json.num(m,"zoom",1);
            c.opacity=(float)Json.num(m,"opacity",1);c.rotation=(float)Json.num(m,"rotation",0);
            c.flip=Json.bool(m,"flip",false);c.fill=Json.bool(m,"fill",false);
            c.x=(float)Json.num(m,"x",0.5);c.y=(float)Json.num(m,"y",0.5);
            c.size=(int)Json.num(m,"size",46);c.color=(int)(long)Json.num(m,"color",0xFFFFFFFFL);
            return c;
        }
    }

    public int width(){
        switch(ratio){case "16:9":return height*16/9;case "1:1":return height;
            case "4:5":return height*4/5;case "9:16":return height*9/16;
            default:throw new IllegalArgumentException("Формат: "+ratio);}
    }
    public long duration(){long ms=0;for(Clip c:main)ms+=c.duration();return ms;}
    public long start(Clip clip) {
        long ms=0;for(Clip c:main){if(c==clip)return ms;ms+=c.duration();}
        if(layers.contains(clip))return clip.start;
        throw new IllegalArgumentException("Клип не принадлежит проекту");
    }
    public Clip find(String clipId) {
        if(clipId==null)return null;
        for(Clip c:main)if(c.id.equals(clipId))return c;
        for(Clip c:layers)if(c.id.equals(clipId))return c;
        return null;
    }
    public void validate(){
        if(!Arrays.asList("9:16","16:9","1:1","4:5").contains(ratio)||height<240||height>2160)throw new IllegalArgumentException("Некорректный проект");
        if(main.size()+layers.size()>MAX_CLIPS)throw new IllegalArgumentException("Слишком много клипов");
        Set<String> ids=new HashSet<>();for(Clip c:concat()){
            c.validate();if(!ids.add(c.id))throw new IllegalArgumentException("Повтор ID");
        }
        if(duration()>24*3600*1000L)throw new IllegalArgumentException("Слишком длинный проект");
    }
    private List<Clip> concat(){List<Clip> all=new ArrayList<>(main);all.addAll(layers);return all;}
    /** Non-destructive split. Works on main track and positioned overlay tracks. */
    public void split(Clip clip,long timelineAtMs) {
        boolean base=main.contains(clip);List<Clip> target=base?main:layers;
        int i=target.indexOf(clip);if(i<0)throw new IllegalArgumentException("Клип не найден");
        long abs=start(clip),local=timelineAtMs-abs;
        if(local<MIN_CLIP_MS||clip.duration()-local<MIN_CLIP_MS)throw new IllegalArgumentException("Поставьте курсор внутри клипа");
        Clip right=clip.copy(true);
        long frame=Math.max(1,Math.round(local*clip.speed));
        long point=Math.min(clip.out-1,clip.in+frame);
        right.in=point;right.out=clip.out;right.start=timelineAtMs;
        clip.out=point;
        if(right.duration()<MIN_CLIP_MS||clip.duration()<MIN_CLIP_MS)throw new IllegalArgumentException("Слишком короткий фрагмент");
        target.add(i+1,right);
    }
    /** Applies deletion to overlays, preserving all portions that do not intersect the cut. */
    private void collapseLayers(long begin,long end){
        long delta=end-begin;
        ArrayList<Clip> updated=new ArrayList<>();
        for(Clip c:layers){
            long s=c.start,e=s+c.duration();
            if(e<=begin){updated.add(c);continue;}
            if(s>=end){c.start-=delta;updated.add(c);continue;}
            if(s<begin){Clip before=slice(c,s,begin);if(before!=null)updated.add(before);}
            if(e>end){Clip after=slice(c,end,e);if(after!=null){after.start=Math.max(begin,s)-delta+(end-Math.max(end,s));after.start=begin;updated.add(after);}}
        }
        layers.clear();layers.addAll(updated);
    }
    public void remove(Clip clip){
        if(layers.remove(clip))return;
        if(!main.contains(clip))throw new IllegalArgumentException("Клип не найден");
        long pos=start(clip),end=pos+clip.duration();main.remove(clip);
        collapseLayers(pos,end);
    }
    public Clip slice(Clip clip,long begin,long end) {
        long s=start(clip);long lo=Math.max(begin,s),hi=Math.min(end,s+clip.duration());
        if(hi-lo<MIN_CLIP_MS)return null;
        Clip result=clip.copy(true);
        result.in=clip.in+Math.round((lo-s)*clip.speed);
        result.out=Math.min(clip.out,clip.in+Math.round((hi-s)*clip.speed));
        result.start=lo;
        return result;
    }
    /** Ripple-delete a selected timeline range across the base track and overlays. */
    public void cut(long begin,long end){
        long total=duration();begin=Math.max(0,begin);end=Math.min(end,total);
        if(end-begin<MIN_CLIP_MS)throw new IllegalArgumentException("Некорректная область обрезки");
        ArrayList<Clip> updated=new ArrayList<>();long cursor=0;
        for(Clip c:main){long clipEnd=cursor+c.duration();
            if(clipEnd<=begin||cursor>=end)updated.add(c);
            else{
                Clip left=part(c,cursor,cursor,begin);
                Clip right=part(c,cursor,end,clipEnd);
                if(left!=null)updated.add(left);
                if(right!=null)updated.add(right);
            }
            cursor=clipEnd;
        }
        main.clear();main.addAll(updated);collapseLayers(begin,end);
    }
    private static Clip part(Clip c,long originalStart,long lo,long hi){
        long from=Math.max(originalStart,lo),to=Math.min(originalStart+c.duration(),hi);
        if(to-from<MIN_CLIP_MS)return null;
        Clip cp=c.copy(true);cp.in=c.in+Math.round((from-originalStart)*c.speed);
        cp.out=Math.min(c.out,c.in+Math.round((to-originalStart)*c.speed));return cp;
    }
    /** Reorder a clip on the sequential main track. */
    public void move(Clip c,int index){
        if(!main.remove(c))throw new IllegalArgumentException("Только основная дорожка");
        main.add(Math.max(0,Math.min(index,main.size())),c);
    }
    public String encode(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("name",name);m.put("ratio",ratio);m.put("height",height);
        ArrayList<Object> base=new ArrayList<>(),overlays=new ArrayList<>();
        for(Clip c:main)base.add(c.json());for(Clip c:layers)overlays.add(c.json());
        m.put("main",base);m.put("layers",overlays);return Json.stringify(m);
    }
    public static EditProject decode(String value){
        Map<String,Object> m=Json.object(Json.parse(value));EditProject p=new EditProject();
        p.id=Json.str(m,"id",p.id);p.name=Json.str(m,"name",p.name);p.ratio=Json.str(m,"ratio",p.ratio);
        p.height=(int)Json.num(m,"height",720);
        if(m.get("main")!=null)for(Object item:Json.array(m.get("main")))p.main.add(Clip.read(item));
        if(m.get("layers")!=null)for(Object item:Json.array(m.get("layers")))p.layers.add(Clip.read(item));
        p.validate();return p;
    }
}
