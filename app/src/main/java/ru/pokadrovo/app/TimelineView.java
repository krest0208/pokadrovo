package ru.pokadrovo.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Two-track native Canvas timeline, scrubbing, clip picking and pinch zoom. */
public final class TimelineView extends View {
    public interface Listener {
        void onSeek(long ms);
        void onSelect(EditProject.Clip clip);
        void onActions(EditProject.Clip clip);
    }
    private EditProject project;
    private String selected;
    private long cursor;
    private float scale=80f,offset=0f,last=0f;
    private float downX,downY;
    private boolean moved=false;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final GestureDetector gestures;
    private final ScaleGestureDetector zoom;
    public TimelineView(Context ctx,Listener callback){
        super(ctx);listener=callback;setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        gestures=new GestureDetector(ctx,new GestureDetector.SimpleOnGestureListener(){
            @Override public void onLongPress(MotionEvent e){
                EditProject.Clip c=hit(e.getX(),e.getY());if(c!=null)listener.onActions(c);
            }
            @Override public boolean onSingleTapUp(MotionEvent e){
                EditProject.Clip c=hit(e.getX(),e.getY());
                if(c!=null)listener.onSelect(c);else listener.onSeek(time(e.getX()));return true;
            }
        });
        zoom=new ScaleGestureDetector(ctx,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                float before=scale;scale=Math.min(300,Math.max(30,scale*d.getScaleFactor()));
                float x=d.getFocusX();offset=(offset+x)/before*scale-x;invalidate();return true;
            }
        });
    }
    public void bind(EditProject prj,String selectedId,long timeMs){project=prj;selected=selectedId;cursor=timeMs;invalidate();}
    public void position(long timeMs){cursor=timeMs;invalidate();}
    private float dp(float v){return getResources().getDisplayMetrics().density*v;}
    private float px(long ms){return dp(82)+(ms/1000f)*dp(scale/2)-offset;}
    private long time(float x){return Math.max(0,Math.round((x+offset-dp(82))/dp(scale/2)*1000));}
    private EditProject.Clip hit(float x,float y){
        if(project==null)return null;
        float row=dp(48),base=dp(42);
        if(y>=base&&y<base+row){long t=time(x);long pos=0;
            for(EditProject.Clip c:project.main){if(t>=pos&&t<pos+c.duration())return c;pos+=c.duration();}
        }else if(y>=base+row&&y<base+row*2){long t=time(x);
            for(int k=project.layers.size()-1;k>=0;k--){EditProject.Clip c=project.layers.get(k);
                if(t>=c.start&&t<c.start+c.duration())return c;
            }
        }
        return null;
    }
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(25,28,40));
        if(project==null)return;
        float h=getHeight();float unit=dp(scale/2),zero=px(0);
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(10));p.setStyle(Paint.Style.FILL);
        for(long t=0;t<=project.duration()+5000;t+=2000){float xx=px(t);if(xx<dp(73)||xx>getWidth())continue;
            p.setColor(0xFF636B83);c.drawLine(xx,dp(20),xx,h,p);
            p.setColor(0xFFB5BCCB);c.drawText(t/1000+"s",xx+dp(2),dp(15),p);
        }
        p.setColor(0xFFE0E4F0);p.setTextSize(dp(10));
        c.drawText("ВИДЕО",dp(8),dp(70),p);c.drawText("СЛОИ",dp(9),dp(118),p);
        long start=0;
        for(EditProject.Clip clip:project.main){drawClip(c,clip,start,dp(42),dp(42));start+=clip.duration();}
        for(EditProject.Clip clip:project.layers)drawClip(c,clip,clip.start,dp(90),dp(40));
        p.setColor(0xFFFFF3AD);p.setStrokeWidth(dp(2));float xx=px(cursor);
        c.drawLine(xx,0,xx,h,p);p.setStyle(Paint.Style.FILL);
        c.drawCircle(xx,dp(22),dp(4),p);
    }
    private void drawClip(Canvas canvas,EditProject.Clip clip,long start,float top,float ht){
        float left=px(start),right=px(start+clip.duration());if(right<dp(82)||left>getWidth())return;
        int color=clip.kind.equals("audio")?0xFF21A997:clip.kind.equals("text")?0xFFB28CFA:
                clip.kind.equals("image")?0xFFF2A85D:0xFF6658E8;
        p.setColor(color);p.setStyle(Paint.Style.FILL);RectF r=new RectF(Math.max(dp(82),left)+dp(1),top+dp(1),Math.min(right,getWidth())-dp(1),top+ht);
        if(r.right<r.left)return;canvas.drawRoundRect(r,dp(6),dp(6),p);
        if(clip.id.equals(selected)) {p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(Color.WHITE);
            canvas.drawRoundRect(r,dp(6),dp(6),p);p.setStyle(Paint.Style.FILL);}
        p.setColor(Color.WHITE);p.setTextSize(dp(11));
        String label=clip.kind.equals("text")?clip.text:clip.name;
        if(label.length()>25)label=label.substring(0,24)+"…";
        canvas.save();canvas.clipRect(r);canvas.drawText(label,left+dp(7),top+dp(25),p);canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(project==null)return false;zoom.onTouchEvent(e);
        if(e.getPointerCount()>1)return true;
        gestures.onTouchEvent(e);
        if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();last=downX;moved=false;return true;}
        if(e.getAction()==MotionEvent.ACTION_MOVE){
            float dx=e.getX()-last;
            if(Math.abs(e.getX()-downX)>dp(8))moved=true;
            if(moved&&!zoom.isInProgress()){
                offset=Math.max(0,Math.min(dp(100000),offset-dx));invalidate();
            }
            last=e.getX();return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP){if(moved)listener.onSeek(time(e.getX()));return true;}
        return true;
    }
}
