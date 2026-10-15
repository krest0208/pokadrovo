package ru.pokadrovo.app;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.text.TextPaint;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.*;
import androidx.media3.transformer.*;
import com.google.common.collect.ImmutableList;
import java.io.InputStream;
import java.util.*;

/** Native Media3 preview/export graph; no WebView or web rendering. */
@UnstableApi
public final class NativeEngine {
    private NativeEngine(){}

    public static Composition composition(Context context,EditProject project){
        if(project.main.isEmpty())throw new IllegalArgumentException("Сначала добавьте видео или фото");
        project.validate();
        List<EditedMediaItem> videos=new ArrayList<>();
        for(EditProject.Clip c:project.main)videos.add(item(c,project,false));
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(EditedMediaItemSequence.withAudioAndVideoFrom(videos));
        // A separate audio sequence supports offsets through explicitly silent gaps.
        // Media3 performs mixing; a file may not contain an audio track and is handled by Media3.
        for(EditProject.Clip c:project.layers){
            if(!c.kind.equals("audio")||c.start>=project.duration())continue;
            EditedMediaItemSequence.Builder audio = new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_AUDIO));
            if(c.start>0)audio.addGap(c.start*1000);
            EditProject.Clip track=c.copy(false);
            long maxSourceMs=Math.round((project.duration()-track.start)*track.speed);
            track.out=Math.min(track.out,track.in+maxSourceMs);
            if(track.duration()<EditProject.MIN_CLIP_MS)continue;
            audio.addItem(item(track,project,true));
            sequences.add(audio.build());
        }
        Composition.Builder builder=new Composition.Builder(sequences);
        ArrayList<Effect> effects=new ArrayList<>();
        effects.add(Presentation.createForWidthAndHeight(project.width(),project.height(),Presentation.LAYOUT_SCALE_TO_FIT));
        boolean overlays=false;
        for(EditProject.Clip c:project.layers)if(!c.kind.equals("audio")){overlays=true;break;}
        if(overlays)effects.add(new OverlayEffect(ImmutableList.of(new Layers(context,project))));
        builder.setEffects(new Effects(ImmutableList.of(),effects));
        return builder.build();
    }

    private static EditedMediaItem item(EditProject.Clip c,EditProject p,boolean audioOnly){
        MediaItem.Builder source=new MediaItem.Builder().setUri(Uri.parse(c.uri));
        if(c.kind.equals("image"))source.setImageDurationMs(c.duration());
        else source.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.in).setEndPositionMs(c.out).build());
        EditedMediaItem.Builder builder=new EditedMediaItem.Builder(source.build());
        builder.setDurationUs((c.kind.equals("image")?c.duration():c.out-c.in)*1000);
        builder.setFrameRate(30);
        if(audioOnly)builder.setRemoveVideo(true);
        if(!audioOnly&&c.volume==0)builder.setRemoveAudio(true);
        if(Math.abs(c.speed-1)>0.0001f&& !c.kind.equals("image")){
            builder.setSpeed(new SpeedProvider(){
                @Override public float getSpeed(long timeUs){return c.speed;}
                @Override public long getNextSpeedChangeTimeUs(long timeUs){return C.TIME_UNSET;}
            });
        }
        ArrayList<AudioProcessor> audio=new ArrayList<>();
        if(!c.kind.equals("image"))audio.add(new EditAudioProcessor(c));
        ArrayList<Effect> visual=new ArrayList<>();
        if(!audioOnly){
            if(Math.abs(c.brightness)>0.0001f)visual.add(new Brightness(c.brightness));
            if(Math.abs(c.contrast-1)>0.0001f)visual.add(new Contrast(Math.max(-1f,Math.min(1f,c.contrast-1f))));
            if(Math.abs(c.opacity-1)>0.0001f)visual.add(new AlphaScale(c.opacity));
            if(Math.abs(c.rotation)>0.001f){
                visual.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(c.rotation).build());
            }
        }
        if(!visual.isEmpty()||!audio.isEmpty())builder.setEffects(new Effects(audio,visual));
        return builder.build();
    }

    /** Canvas overlay is composited into both preview and exported MP4. */
    public static final class Layers extends CanvasOverlay {
        private final EditProject p;
        private final Context appContext;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Map<String,Bitmap> cache=new HashMap<>();
        Layers(Context context,EditProject project){super(true);this.appContext=context.getApplicationContext();p=project;}
        @Override public void onDraw(Canvas canvas,long timeUs){
            long t=timeUs/1000;
            for(EditProject.Clip c:p.layers){
                if(c.kind.equals("audio")||t<c.start||t>=c.start+c.duration())continue;
                paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);paint.setAlpha((int)(c.opacity*255));
                canvas.save();
                float cx=c.x*canvas.getWidth(),cy=c.y*canvas.getHeight();
                canvas.rotate(c.rotation,cx,cy);
                if(c.kind.equals("text")){
                    paint.setColor(c.color);paint.setTextSize(Math.max(10,c.size)*canvas.getHeight()/720f);
                    paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
                    paint.setTextAlign(Paint.Align.CENTER);
                    String[] lines=c.text.split("\\n",-1);float dy=paint.getTextSize()*1.2f;
                    for(int k=0;k<lines.length;k++){
                        float y=cy+(k-(lines.length-1)/2f)*dy;
                        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,paint.getTextSize()/25f));
                        paint.setColor(Color.BLACK);canvas.drawText(lines[k],cx,y,paint);
                        paint.setStyle(Paint.Style.FILL);paint.setColor(c.color);paint.setAlpha((int)(c.opacity*255));
                        canvas.drawText(lines[k],cx,y,paint);
                    }
                }else if(c.kind.equals("image")){
                    Bitmap image=bitmap(c);
                    if(image!=null){
                        float base=Math.min(canvas.getWidth()/(float)image.getWidth(),canvas.getHeight()/(float)image.getHeight());
                        float w=image.getWidth()*base*c.zoom,h=image.getHeight()*base*c.zoom;
                        RectF dst=new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2);
                        if(c.flip){canvas.scale(-1,1,cx,cy);}
                        canvas.drawBitmap(image,null,dst,paint);
                    }
                }
                canvas.restore();
            }
        }
        private Bitmap bitmap(EditProject.Clip c){
            if(cache.containsKey(c.uri))return cache.get(c.uri);
            Bitmap result=null;
            try(InputStream in=appContext.getContentResolver().openInputStream(Uri.parse(c.uri))){
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                BitmapFactory.decodeStream(in,null,o);
                o.inSampleSize=1;while(o.outWidth/o.inSampleSize>1600||o.outHeight/o.inSampleSize>1600)o.inSampleSize*=2;
                o.inJustDecodeBounds=false;
                try(InputStream src=appContext.getContentResolver().openInputStream(Uri.parse(c.uri))) {
                    result=BitmapFactory.decodeStream(src,null,o);
                }
            }catch(Exception ignored){}
            if(cache.size()<48)cache.put(c.uri,result);return result;
        }
        @Override public void release(){
            super.release();for(Bitmap b:cache.values())if(b!=null&&!b.isRecycled())b.recycle();cache.clear();
        }
    }
}
