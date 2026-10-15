import ru.pokadrovo.app.*;
import java.util.*;

public final class CoreTests {
 static int count=0;
 static void ok(boolean flag,String message){count++;if(!flag)throw new AssertionError(message);}
 static EditProject.Clip sample(long ms){EditProject.Clip c=new EditProject.Clip();c.uri="content://test/video";c.source=ms;c.in=0;c.out=ms;return c;}
 static void exception(Runnable r){boolean failed=false;try{r.run();}catch(IllegalArgumentException e){failed=true;}ok(failed,"expected validation error");}
 public static void main(String[] args){
  EditProject p=new EditProject();EditProject.Clip a=sample(10_000);p.main.add(a);
  ok(p.duration()==10000,"duration");ok(p.start(a)==0,"start");
  p.split(a,4000);ok(p.main.size()==2,"split count");ok(p.duration()==10000,"split duration");
  ok(p.main.get(0).duration()==4000,"split left");ok(p.main.get(1).duration()==6000,"split right");
  EditProject.Clip overlay=sample(9000);overlay.kind="image";overlay.start=2000;p.layers.add(overlay);
  p.cut(3000,5000);ok(p.duration()==8000,"ripple length");
  ok(p.layers.size()==2,"split overlay by ripple cut");
  ok(p.layers.get(0).start==2000&&p.layers.get(1).start==3000,"overlay shifts");
  String json=p.encode();EditProject re=EditProject.decode(json);
  ok(re.encode().equals(json),"roundtrip JSON");ok(re.find(p.main.get(0).id)!=null,"find");
  re.move(re.main.get(0),re.main.size());ok(re.duration()==8000,"move clips");
  List<CaptionTools.Caption> captions=CaptionTools.parse("1\n00:00:01,250 --> 00:00:02,900\nПривет!\n\n2\n00:00:03.000 --> 00:00:04.000\nМир",0);
  ok(captions.size()==2,"SRT count");ok(captions.get(0).startMs==1250,"SRT time");
  ok(captions.get(0).text.equals("Привет!"),"SRT text");
  ok(CaptionTools.stamp(3723105).equals("01:02:03.105"),"timestamp");
  ok(Json.object(Json.parse("{\"x\":true,\"items\":[1,\"yes\"]}")).size()==2,"JSON parser");
  exception(()->Json.parse("{\"bad\":1,\"bad\":2}"));
  exception(()->Json.parse("{\"a\":1} garbage"));
  exception(()->p.split(p.main.get(0),0));
  p.validate();
  Random rnd=new Random(9);
  for(int test=0;test<300;test++){
   EditProject f=new EditProject();for(int n=0;n<1+rnd.nextInt(15);n++)f.main.add(sample(1000+rnd.nextInt(11000)));
   long before=f.duration();long point=100+rnd.nextInt((int)before-200);
   EditProject.Clip chosen=null;for(EditProject.Clip c:f.main)if(f.start(c)+100<point&&point<f.start(c)+c.duration()-100){chosen=c;break;}
   if(chosen!=null){f.split(chosen,point);ok(Math.abs(f.duration()-before)<=1,"fuzz split preserves length");}
   f.validate();
   ok(EditProject.decode(f.encode()).duration()==f.duration(),"fuzz serialization");
  }
  System.out.println("OK: "+count+" core assertions");
 }
}
