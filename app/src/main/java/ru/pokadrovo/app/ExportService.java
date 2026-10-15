package ru.pokadrovo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Foreground MP4 export. Transformer writes an app-private temp file, then copies to SAF URI. */
@UnstableApi
public final class ExportService extends Service {
    public static final String ACTION_START="ru.pokadrovo.app.EXPORT_START";
    public static final String ACTION_CANCEL="ru.pokadrovo.app.EXPORT_CANCEL";
    public static final String ACTION_STATUS="ru.pokadrovo.app.EXPORT_STATUS";
    private static final String CHANNEL="pokadrovo_exports";
    private static final int NOTIFY=4102;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private File output;
    private Uri target;
    private boolean running;
    private final Runnable progress=new Runnable(){@Override public void run(){
        if(!running || transformer==null)return;
        try {
            ProgressHolder holder=new ProgressHolder();
            int state=transformer.getProgress(holder);
            if(state==Transformer.PROGRESS_STATE_AVAILABLE)publish("Обработка видео",Math.min(95,holder.progress));
        } catch(Exception ignored){}
        main.postDelayed(this,850);
    }};

    @Override public void onCreate(){super.onCreate();
        NotificationManager n=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26) n.createNotificationChannel(
            new NotificationChannel(CHANNEL,"Экспорт Покадрово",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_CANCEL.equals(intent.getAction())){
            if(transformer!=null)transformer.cancel();
            complete("Экспорт отменён",0,true);return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(intent.getAction()))return START_NOT_STICKY;
        if(running){publish("Экспорт уже запущен",0);return START_NOT_STICKY;}
        startForeground(NOTIFY,notification("Подготовка экспорта",0));
        try{
            String projectId=intent.getStringExtra("project");
            String raw=intent.getStringExtra("destination");
            if(projectId==null||raw==null)throw new IllegalArgumentException("Не указан проект или файл");
            EditProject edit=new ProjectStore(this).load(projectId);
            if(edit.main.isEmpty())throw new IllegalArgumentException("Проект без видео");
            target=Uri.parse(raw);
            if(!"content".equals(target.getScheme()))throw new IllegalArgumentException("Используйте системный выбор файла");
            output=File.createTempFile("pokadrovo-export-",".mp4",getCacheDir());
            Composition composition=NativeEngine.composition(this,edit);
            running=true;
            transformer=new Transformer.Builder(this).addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition c,ExportResult result){
                    main.removeCallbacks(progress);
                    publish("Сохранение MP4",96);
                    File source=output;
                    Uri destination=target;
                    new Thread(()->copyToDestination(source,destination),"pokadrovo-save-mp4").start();
                }
                @Override public void onError(Composition c,ExportResult result,ExportException e){
                    complete("Ошибка кодирования: "+e.getMessage(),0,true);
                }
            }).build();
            transformer.start(composition,output.getAbsolutePath());
            main.post(progress);
            publish("Обработка видео",1);
        }catch(Exception e){complete("Ошибка: "+e.getMessage(),0,true);}
        return START_NOT_STICKY;
    }
    private void copyToDestination(File source,Uri destination){
        try{
            // SAF keeps permissions for the selected URI; no storage permission is needed.
            try(InputStream in=new FileInputStream(source);
                OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                if(out==null)throw new IllegalStateException("Не удалось открыть файл назначения");
                byte[] buf=new byte[131072];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
            }
            main.post(()->complete("Готово! Видео сохранено",100,false));
        }catch(Exception e){
            main.post(()->complete("Ошибка сохранения: "+e.getMessage(),0,true));
        }
    }
    private void publish(String message,int percent){
        if(!running)return;
        Intent broadcast=new Intent(ACTION_STATUS).setPackage(getPackageName());
        broadcast.putExtra("message",message);broadcast.putExtra("progress",percent);
        sendBroadcast(broadcast);
        getSystemService(NotificationManager.class).notify(NOTIFY,notification(message,percent));
    }
    private Notification notification(String message,int percent){
        Intent cancel=new Intent(this,ExportService.class).setAction(ACTION_CANCEL);
        PendingIntent pending=PendingIntent.getService(this,0,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=Build.VERSION.SDK_INT>=26?
            new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        n.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Покадрово · экспорт MP4")
            .setContentText(message).setOngoing(percent<100).setOnlyAlertOnce(true)
            .setProgress(100,percent,percent==0);
        if(percent<100)n.addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отменить",pending);
        return n.build();
    }
    private void complete(String message,int percent,boolean failed){
        if(!running && transformer==null){stopSelf();return;}
        boolean wasRunning=running;
        running=false;main.removeCallbacks(progress);
        transformer=null;
        Intent broadcast=new Intent(ACTION_STATUS).setPackage(getPackageName());
        broadcast.putExtra("message",message).putExtra("progress",failed?-1:percent);
        sendBroadcast(broadcast);
        if(output!=null){output.delete();output=null;}
        if(wasRunning){
            if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            NotificationManager notificationManager=getSystemService(NotificationManager.class);
            notificationManager.notify(NOTIFY,notification(message,100));
        }
        stopSelf();
    }
    @Override public void onDestroy(){
        main.removeCallbacks(progress);
        if(transformer!=null)transformer.cancel();
        if(output!=null)output.delete();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
