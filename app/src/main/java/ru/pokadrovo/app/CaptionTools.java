package ru.pokadrovo.app;

import java.util.*;
import java.util.regex.*;

/** SRT parsing and timecode routines shared by the editor and tests. */
public final class CaptionTools {
    private CaptionTools() {}
    private static final Pattern TIME = Pattern.compile("(?:^|\\n)\\s*(?:(\\d+)\\s*\\n)?\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*\\n");
    public static final class Caption {
        public final long startMs,endMs;
        public final String text;
        public Caption(long start,long end,String text){startMs=start;endMs=end;this.text=text;}
    }
    public static List<Caption> parse(String s,long shiftMs) {
        if(s==null||s.isEmpty())return Collections.emptyList();
        s=s.replace("\uFEFF","").replace("\r\n","\n").replace('\r','\n');
        ArrayList<Caption> result=new ArrayList<>();Matcher matcher=TIME.matcher(s);
        while(matcher.find()){
            int start=matcher.end();int end=s.indexOf("\n\n",start);if(end<0)end=s.length();
            String body=s.substring(start,end).trim();
            if(body.isEmpty())continue;
            long from=time(matcher,2),to=time(matcher,6);
            if(to<=from)continue;
            from=Math.max(0,from+shiftMs);to=Math.max(0,to+shiftMs);
            if(to-from>=100)result.add(new Caption(from,to,body));
        }
        result.sort(Comparator.comparingLong(c->c.startMs));return result;
    }
    private static long time(Matcher m,int index){
        return Long.parseLong(m.group(index))*3600000+Long.parseLong(m.group(index+1))*60000
                +Long.parseLong(m.group(index+2))*1000+Long.parseLong((m.group(index+3)+"000").substring(0,3));
    }
    public static String stamp(long milliseconds){
        long t=Math.max(0,milliseconds),sec=t/1000;
        return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",sec/3600,(sec/60)%60,sec%60,t%1000);
    }
}
