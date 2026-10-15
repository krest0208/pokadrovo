package ru.pokadrovo.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.CompositionPlayer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native editing UI: projects -> editor -> native Media3 preview + timeline + export. */
@UnstableApi
public final class MainActivity extends Activity {
    private static final int PICK_MEDIA=11,PICK_SRT=12,CREATE_MP4=13;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private ProjectStore store;
    private EditProject project;
    private CompositionPlayer player;
    private LinearLayout root,properties;
    private FrameLayout preview;
    private SurfaceView surface;
    private SeekBar scrub;
    private TimelineView timeline;
    private TextView time,status;
    private Button play;
    private String selected,panel="Медиа",importKind="video",exportSeen="";
    private boolean dark=true,busy=false,dragged=false;
    private float downX,downY,oldX,oldY;
    private int bg,card,ink,muted;
    private BroadcastReceiver exportReceiver;
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(project!=null&&player!=null&&time!=null){
            try{
                long at=player.getCurrentPosition();
                if(player.isPlaying()){cursor=Math.min(project.duration(),at);timeline.position(cursor);
                    if(scrub!=null){scrub.setProgress((int)Math.min(Integer.MAX_VALUE,cursor));}
                }
                time.setText(CaptionTools.stamp(cursor)+" / "+CaptionTools.stamp(project.duration()));
            }catch(Exception ignored){}
        }
        handler.postDelayed(this,140);
    }};
    private long cursor=0;

    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().setStatusBarColor(0xFF10131C);getWindow().setNavigationBarColor(0xFF10131C);
        store=new ProjectStore(this);dark=getPreferences(0).getBoolean("dark",true);colors();
        handler.post(tick);
        if(state!=null&&state.containsKey("project_id")){
            try{project=store.load(state.getString("project_id"));editor();return;}catch(Exception ignored){}
        }
        home();
    }
    private void colors(){
        bg=dark?0xFF10131C:0xFFF4F5FB;
        card=dark?0xFF202437:0xFFFFFFFF;
        ink=dark?0xFFF7F7FB:0xFF141522;
        muted=dark?0xFFACB5C9:0xFF69728A;
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private GradientDrawable shape(int color,int corner){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(corner));return d;
    }
    private TextView text(String value,int size){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(ink);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout l,int px){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(px)));}
    private Button button(String caption,boolean primary,Runnable fn){
        Button b=new Button(this);b.setAllCaps(false);b.setText(caption);b.setTextSize(13);
        b.setPadding(dp(8),0,dp(8),0);b.setTextColor(primary?Color.WHITE:ink);
        b.setBackground(shape(primary?0xFF6F61F1:card,12));b.setOnClickListener(v->fn.run());return b;
    }
    private void pair(LinearLayout target,Button... buttons){
        for(Button b:buttons){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);lp.setMargins(0,0,dp(8),0);target.addView(b,lp);}
    }
    private void install(LinearLayout contents){
        root=col();root.setBackgroundColor(bg);setContentView(root);
        root.addView(contents,new LinearLayout.LayoutParams(-1,-1));
    }
    private void notice(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void ask(String title,String initial,java.util.function.Consumer<String> done){
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(initial);
        field.setSelection(field.getText().length());
        LinearLayout holder=col();holder.setPadding(dp(16),dp(8),dp(16),dp(8));holder.addView(field);
        new AlertDialog.Builder(this).setTitle(title).setView(holder).setNegativeButton("Отмена",null)
            .setPositiveButton("Готово",(d,w)->done.accept(field.getText().toString().trim())).show();
    }
    private void home(){
        release();project=null;selected=null;cursor=0;undo.clear();redo.clear();
        LinearLayout column=col();column.setPadding(dp(18),dp(24),dp(18),dp(8));install(column);
        TextView name=text("Покадрово",30);name.setTypeface(null,1);column.addView(name);
        TextView tagline=text("Твоя история. Твой монтаж.",15);tagline.setTextColor(muted);column.addView(tagline);
        gap(column,22);
        LinearLayout buttons=row();pair(buttons,
            button("+ Новый проект",true,()->ask("Название проекта","Новый ролик",this::create)),
            button("Аккаунт",false,()->startActivity(new Intent(this,AccountActivity.class))));
        column.addView(buttons);gap(column,10);
        LinearLayout extras=row();pair(extras,
            button(dark?"☀ Светлая":"☾ Тёмная",false,()->{dark=!dark;getPreferences(0).edit().putBoolean("dark",dark).apply();colors();home();}),
            button("Помощь",false,this::help));column.addView(extras);gap(column,25);
        TextView title=text("Мои проекты",21);title.setTypeface(null,1);column.addView(title);gap(column,8);
        ScrollView scroll=new ScrollView(this);column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout list=col();scroll.addView(list);
        List<EditProject> projects=store.list();
        if(projects.isEmpty()){
            TextView hint=text("Здесь появятся твои видеопроекты.\nНажми «Новый проект», чтобы начать монтаж.",15);
            hint.setTextColor(muted);hint.setPadding(dp(12),dp(25),dp(12),dp(25));list.addView(hint);
        }
        for(EditProject p:projects){
            LinearLayout cardView=col();cardView.setPadding(dp(14),dp(12),dp(14),dp(12));cardView.setBackground(shape(card,14));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(6),0,dp(6));list.addView(cardView,cp);
            TextView label=text(p.name,18);label.setTypeface(null,1);cardView.addView(label);
            TextView meta=text("Формат "+p.ratio+" · "+CaptionTools.stamp(p.duration())+" · "+p.main.size()+" клипов",12);
            meta.setTextColor(muted);cardView.addView(meta);
            LinearLayout controls=row();gap(cardView,10);
            pair(controls,button("Открыть проект",true,()->{project=p;undo.clear();redo.clear();editor();}),
                button("⋮",false,()->new AlertDialog.Builder(this).setTitle(p.name)
                    .setItems(new String[]{"Переименовать","Удалить проект"},(d,index)->{
                        if(index==0)ask("Переименовать",p.name,x->{if(!x.isEmpty()){p.name=x;save(p);home();}});
                        else new AlertDialog.Builder(this).setMessage("Удалить проект? Исходные файлы останутся на телефоне.")
                          .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(di,b)->{store.delete(p.id);home();}).show();
                    }).show()));cardView.addView(controls);
        }
        TextView footer=text("Покадрово · нативный Android-редактор · локальные проекты",12);
        footer.setTextColor(muted);footer.setPadding(0,dp(12),0,0);column.addView(footer);
    }
    private void create(String title){
        if(title==null||title.trim().isEmpty()){notice("Введите название проекта");return;}
        project=new EditProject();project.name=title.trim();
        String[] ratios={"9:16 · Shorts / TikTok","16:9 · YouTube","1:1 · Квадрат","4:5 · Портрет"};
        new AlertDialog.Builder(this).setTitle("Формат видео").setItems(ratios,(d,pos)->{
            project.ratio=ratios[pos].split(" ")[0];save(project);editor();
        }).setNegativeButton("Отмена",(d,pos)->{project=null;home();}).show();
    }
    private void help(){new AlertDialog.Builder(this).setTitle("Покадрово")
        .setMessage("Нативный редактор. Добавьте видео, фото или музыку. Тап по клипу выбирает его; долгое удержание открывает действия. Два пальца масштабируют таймлайн.\n\nВсе проекты хранятся локально. Импорт через Android гарантирует выбор файлов владельцем. Для экспорта используется аппаратный медиакодек и Android Media3.")
        .setPositiveButton("Понятно",null).show();}
    private void editor(){
        if(project==null){home();return;}
        colors();LinearLayout content=col();content.setPadding(dp(10),dp(5),dp(10),dp(5));install(content);
        LinearLayout top=row();content.addView(top,new LinearLayout.LayoutParams(-1,dp(48)));
        Button back=button("←",false,this::home);top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(43)));
        TextView label=text(project.name,18);label.setTypeface(null,1);label.setPadding(dp(9),0,0,0);
        top.addView(label,new LinearLayout.LayoutParams(0,-1,1));
        Button un=button("↶",false,()->history(false)),re=button("↷",false,()->history(true));
        top.addView(un,new LinearLayout.LayoutParams(dp(45),dp(42)));top.addView(re,new LinearLayout.LayoutParams(dp(45),dp(42)));
        Button exp=button("Экспорт",true,this::export);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(93),dp(42));ep.setMargins(dp(5),0,0,0);top.addView(exp,ep);
        gap(content,5);
        preview=new FrameLayout(this);preview.setBackgroundColor(Color.BLACK);
        int maxHeight=Math.min(dp(360),(int)(getResources().getDisplayMetrics().heightPixels*0.31f));
        content.addView(preview,new LinearLayout.LayoutParams(-1,maxHeight));
        surface=new SurfaceView(this);surface.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);
        preview.addView(surface,sp);
        status=text("Добавьте видео или фото",14);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);
        preview.addView(status,new FrameLayout.LayoutParams(-1,-1));
        preview.setOnTouchListener((v,event)->drag(event));
        gap(content,6);
        LinearLayout transport=row();content.addView(transport,new LinearLayout.LayoutParams(-1,dp(42)));
        play=button("▶",true,()->{
            if(player==null){notice("Сначала добавьте видео или фото");return;}
            if(player.isPlaying()){player.pause();play.setText("▶");}
            else{player.play();play.setText("Ⅱ");}
        });transport.addView(play,new LinearLayout.LayoutParams(dp(50),dp(40)));
        time=text("00:00:00.000 / 00:00:00.000",12);time.setGravity(Gravity.CENTER);
        transport.addView(time,new LinearLayout.LayoutParams(0,-1,1));
        Button reset=button("⟲",false,()->seek(0));transport.addView(reset,new LinearLayout.LayoutParams(dp(49),dp(40)));
        scrub=new SeekBar(this);scrub.setMax((int)Math.min(Integer.MAX_VALUE,project.duration()));
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){if(player!=null)player.pause();}
            public void onStopTrackingTouch(SeekBar s){seek(s.getProgress());}
            public void onProgressChanged(SeekBar s,int value,boolean from){
                if(from){cursor=value;timeline.position(cursor);time.setText(CaptionTools.stamp(cursor));}
            }
        });content.addView(scrub,new LinearLayout.LayoutParams(-1,dp(36)));
        timeline=new TimelineView(this,new TimelineView.Listener(){
            @Override public void onSeek(long time){seek(time);}
            @Override public void onSelect(EditProject.Clip clip){select(clip);}
            @Override public void onActions(EditProject.Clip clip){select(clip);menu(clip);}
        });content.addView(timeline,new LinearLayout.LayoutParams(-1,dp(148)));
        gap(content,7);
        HorizontalScrollView tools=new HorizontalScrollView(this);tools.setHorizontalScrollBarEnabled(false);
        LinearLayout items=row();items.setPadding(0,0,dp(6),0);tools.addView(items);
        String[] actions={"+ Видео / фото","+ Наложение","+ Звук","+ Текст","Импорт SRT","Разделить","Обрезка","Удалить диапазон","Дублировать","Удалить","Настройки","ИИ"};
        for(String a:actions){Button b=button(a,false,()->tool(a));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(43));lp.setMargins(0,0,dp(7),0);items.addView(b,lp);}
        content.addView(tools,new LinearLayout.LayoutParams(-1,dp(47)));
        ScrollView settings=new ScrollView(this);settings.setFillViewport(true);
        properties=col();properties.setPadding(dp(5),dp(10),dp(5),dp(16));settings.addView(properties);
        content.addView(settings,new LinearLayout.LayoutParams(-1,0,1));
        render();reload();
    }
    private void tool(String a){
        switch(a){
            case "+ Видео / фото":pick("main");break;
            case "+ Наложение":pick("overlay");break;
            case "+ Звук":pick("audio");break;
            case "+ Текст":addText();break;
            case "Импорт SRT":document(PICK_SRT,"text/*",false,"subtitle");break;
            case "Разделить":{EditProject.Clip c=project.find(selected);if(c!=null)change(()->project.split(c,cursor));else notice("Выберите клип на таймлайне");break;}
            case "Обрезка":{EditProject.Clip c=project.find(selected);if(c!=null)trim(c);else notice("Выберите клип на таймлайне");break;}
            case "Удалить диапазон":{cutRange();break;}
            case "Дублировать":{EditProject.Clip c=project.find(selected);if(c!=null)duplicate(c);else notice("Выберите клип на таймлайне");break;}
            case "Удалить":{EditProject.Clip c=project.find(selected);if(c!=null)remove(c);else notice("Выберите клип на таймлайне");break;}
            case "Настройки":{panel="Настройки";render();break;}
            case "ИИ":new AlertDialog.Builder(this).setTitle("ИИ")
                    .setMessage("ИИ ещё не подключён к нативной версии. Нужна серверная интеграция и отдельные ключи API. Ручной монтаж работает локально.")
                    .setPositiveButton("Понятно",null).show();break;
        }
    }
    private void select(EditProject.Clip c){selected=c.id;panel="Клип";render();}
    private void seek(long pos){cursor=Math.max(0,Math.min(project.duration(),pos));
        if(player!=null)try{player.seekTo(cursor);}catch(Exception ignored){}
        if(timeline!=null)timeline.position(cursor);if(scrub!=null)scrub.setProgress((int)Math.min(Integer.MAX_VALUE,cursor));
    }
    private void snapshot(){if(project==null)return;undo.push(project.encode());if(undo.size()>32)undo.removeLast();redo.clear();}
    private void history(boolean forward){
        ArrayDeque<String> source=forward?redo:undo,target=forward?undo:redo;
        if(source.isEmpty()){notice("История пуста");return;}
        target.push(project.encode());project=EditProject.decode(source.pop());selected=null;
        save(project);editor();
    }
    private void change(Runnable task){
        if(project==null)return;
        String prev=project.encode();
        try{task.run();project.validate();if(prev.equals(project.encode()))return;
            undo.push(prev);if(undo.size()>32)undo.removeLast();redo.clear();
            save(project);render();reload();
        }catch(Exception ex){try{project=EditProject.decode(prev);}catch(Exception ignored){}
            notice("Не удалось выполнить действие: "+ex.getMessage());render();}
    }
    private void save(EditProject p){try{store.save(p);}catch(Exception e){notice("Не удалось сохранить проект: "+e.getMessage());}}
    private void render(){if(project==null||properties==null)return;
        timeline.bind(project,selected,cursor);
        scrub.setMax((int)Math.min(Integer.MAX_VALUE,project.duration()));scrub.setProgress((int)Math.min(Integer.MAX_VALUE,cursor));
        properties.removeAllViews();
        EditProject.Clip c=project.find(selected);
        if(panel.equals("Настройки")){settings();return;}
        if(c==null){TextView hint=text(project.main.isEmpty()?"Добавьте видео или фото":"Выберите клип на таймлайне — здесь появятся настройки",15);
            hint.setTextColor(muted);properties.addView(hint);
            return;}
        TextView header=text("Настройки · "+c.name,17);header.setTypeface(null,1);properties.addView(header);
        gap(properties,10);
        if(c.kind.equals("video")||c.kind.equals("audio")){
            slider(c,"Громкость",0f,2f,c.volume,value->c.volume=value);
            slider(c,"Баланс",-1f,1f,c.pan,value->c.pan=value);
            slider(c,"Скорость",0.25f,4f,c.speed,value->c.speed=value);
            slider(c,"Плавное начало, с",0,5,c.fadeIn,value->c.fadeIn=value);
            slider(c,"Плавный конец, с",0,5,c.fadeOut,value->c.fadeOut=value);
        }
        if(c.kind.equals("video")||c.kind.equals("image")){
            slider(c,"Яркость",-1f,1f,c.brightness,value->c.brightness=value);
            slider(c,"Контраст",0f,2f,c.contrast,value->c.contrast=value);
            slider(c,"Поворот",-180f,180f,c.rotation,value->c.rotation=value);
        }
        if(c.kind.equals("text")||c.kind.equals("image")){
            slider(c,"Положение X",0,1,c.x,value->c.x=value);
            slider(c,"Положение Y",0,1,c.y,value->c.y=value);
            slider(c,"Масштаб",0.1f,3,c.zoom,value->c.zoom=value);
            slider(c,"Прозрачность",0,1,c.opacity,value->c.opacity=value);
            if(c.kind.equals("text")){
                slider(c,"Размер текста",12,108,c.size,value->c.size=(int)value);
                Button edit=button("Изменить текст",false,()->ask("Текст в кадре",c.text,
                    t->{if(!t.isEmpty())change(()->c.text=t);}));
                properties.addView(edit,new LinearLayout.LayoutParams(-1,dp(45)));
            }
        }
        gap(properties,10);
        LinearLayout controls=row();pair(controls,button("Обрезка",false,()->trim(c)),button("Действия",false,()->menu(c)));
        properties.addView(controls);
    }
    private interface Edit {void set(float value);}
    private void slider(EditProject.Clip clip,String label,float min,float max,float current,Edit edit){
        LinearLayout holder=col();properties.addView(holder);
        TextView caption=text(label+" · "+String.format(Locale.ROOT,"%.2f",current),12);caption.setTextColor(muted);holder.addView(caption);
        SeekBar seek=new SeekBar(this);seek.setMax(1000);
        seek.setProgress(Math.round(1000*(current-min)/(max-min)));
        final float[] original={current};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean from){
                if(from)caption.setText(label+" · "+String.format(Locale.ROOT,"%.2f",min+(max-min)*p/1000f));
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){float v=min+(max-min)*s.getProgress()/1000f;
                // Commit on release so one slider move creates one undo action.
                change(()->edit.set(v));
            }
        });holder.addView(seek,new LinearLayout.LayoutParams(-1,dp(36)));
    }
    private void settings(){
        TextView h=text("Формат видео · "+project.ratio+" · "+project.height+"p",17);properties.addView(h);gap(properties,14);
        LinearLayout choices=row();pair(choices,button("9:16",false,()->change(()->project.ratio="9:16")),
            button("16:9",false,()->change(()->project.ratio="16:9")),
            button("1:1",false,()->change(()->project.ratio="1:1")));
        properties.addView(choices);gap(properties,12);
        LinearLayout controls=row();pair(controls,
            button("720p",false,()->change(()->project.height=720)),
            button("1080p",false,()->change(()->project.height=1080)));
        properties.addView(controls);gap(properties,10);
        Button theme=button(dark?"Светлая тема":"Тёмная тема",false,()->{dark=!dark;getPreferences(0).edit().putBoolean("dark",dark).apply();colors();editor();});
        properties.addView(theme,new LinearLayout.LayoutParams(-1,dp(44)));
        gap(properties,10);
        Button account=button("Вход и профиль",false,()->startActivity(new Intent(this,AccountActivity.class)));
        properties.addView(account,new LinearLayout.LayoutParams(-1,dp(44)));
    }
    private void addText(){if(project.main.isEmpty()){notice("Сначала добавьте видео или фото");return;}
        ask("Текст в кадре","Твой текст",t->{if(t.isEmpty())return;
            change(()->{EditProject.Clip c=new EditProject.Clip();c.kind="text";c.name="Текст";
                c.text=t;c.start=cursor;c.source=Math.min(4000,Math.max(300,project.duration()-cursor));
                c.in=0;c.out=c.source;project.layers.add(c);selected=c.id;});
        });
    }
    private void cutRange(){
        if(project==null||project.duration()<300){notice("Нет видео для обрезки");return;}
        LinearLayout box=col();box.setPadding(dp(18),0,dp(18),0);
        EditText begin=new EditText(this),end=new EditText(this);
        begin.setInputType(8194);end.setInputType(8194);
        begin.setText(String.format(Locale.ROOT,"%.3f",cursor/1000.0));
        end.setText(String.format(Locale.ROOT,"%.3f",Math.min(project.duration(),cursor+2000)/1000.0));
        box.addView(text("Начало на таймлайне, с",14));box.addView(begin);
        box.addView(text("Конец на таймлайне, с",14));box.addView(end);
        new AlertDialog.Builder(this).setTitle("Удалить часть видео (со сдвигом)").setView(box)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Удалить",(d,w)->{
                try{
                    long left=Math.round(Double.parseDouble(begin.getText().toString())*1000);
                    long right=Math.round(Double.parseDouble(end.getText().toString())*1000);
                    change(()->{project.cut(left,right);selected=null;cursor=Math.min(cursor,project.duration());});
                }catch(Exception ex){notice("Неверный временной интервал");}
            }).show();
    }
    private void trim(EditProject.Clip clip){
        LinearLayout box=col();box.setPadding(dp(18),0,dp(18),0);
        EditText begin=new EditText(this),end=new EditText(this);
        begin.setInputType(8194);end.setInputType(8194);
        begin.setText(String.format(Locale.ROOT,"%.3f",clip.in/1000.0));
        end.setText(String.format(Locale.ROOT,"%.3f",clip.out/1000.0));
        box.addView(text("Начало в исходнике, с",14));box.addView(begin);
        box.addView(text("Конец в исходнике, с",14));box.addView(end);
        new AlertDialog.Builder(this).setTitle("Длительность / обрезка").setView(box)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Применить",(d,w)->{
                try{long l=Math.round(Double.parseDouble(begin.getText().toString())*1000);
                    long r=Math.round(Double.parseDouble(end.getText().toString())*1000);
                    change(()->{clip.in=l;clip.out=r;});
                }catch(Exception ex){notice("Проверьте границы: минимум 0,1 с");}
            }).show();
    }
    private void menu(EditProject.Clip clip){
        boolean base=project.main.contains(clip);
        String[] options=base?new String[]{"Разделить","Обрезка","Дублировать","Сдвинуть левее","Сдвинуть правее","Удалить","Отразить"}
                :new String[]{"Разделить","Обрезка","Дублировать","Удалить","Отразить"};
        new AlertDialog.Builder(this).setTitle(clip.name).setItems(options,(d,index)->{
            switch(options[index]){
                case "Разделить":change(()->project.split(clip,cursor));break;
                case "Обрезка":trim(clip);break;
                case "Дублировать":duplicate(clip);break;
                case "Сдвинуть левее":change(()->project.move(clip,project.main.indexOf(clip)-1));break;
                case "Сдвинуть правее":change(()->project.move(clip,project.main.indexOf(clip)+1));break;
                case "Удалить":remove(clip);break;
                case "Отразить":change(()->clip.flip=!clip.flip);break;
            }
        }).show();
    }
    private void duplicate(EditProject.Clip clip){change(()->{
        EditProject.Clip copy=clip.copy(true);copy.name="Копия · "+copy.name;
        if(project.main.contains(clip))project.main.add(project.main.indexOf(clip)+1,copy);
        else{copy.start=Math.min(project.duration(),copy.start+500);project.layers.add(copy);}
        selected=copy.id;
    });}
    private void remove(EditProject.Clip clip){
        new AlertDialog.Builder(this).setTitle("Удалить клип?")
            .setMessage("Исходный файл сохранится. Удаление с основной дорожки сокращает все дорожки синхронно.")
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->change(()->{
                project.remove(clip);selected=null;cursor=Math.min(cursor,project.duration());
            })).show();
    }
    private boolean drag(android.view.MotionEvent event){
        EditProject.Clip c=project.find(selected);
        if(c==null||!(c.kind.equals("text")||c.kind.equals("image")))return false;
        if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){
            downX=event.getX();downY=event.getY();oldX=c.x;oldY=c.y;dragged=true;return true;
        }
        if(event.getAction()==android.view.MotionEvent.ACTION_MOVE&&dragged){return true;}
        if(event.getAction()==android.view.MotionEvent.ACTION_UP&&dragged){
            float nx=Math.max(0,Math.min(1,oldX+(event.getX()-downX)/preview.getWidth()));
            float ny=Math.max(0,Math.min(1,oldY+(event.getY()-downY)/preview.getHeight()));
            dragged=false;change(()->{c.x=nx;c.y=ny;});return true;
        }
        return false;
    }
    private void reload(){
        if(project==null||surface==null)return;
        release();
        status.setVisibility(project.main.isEmpty()?View.VISIBLE:View.GONE);
        if(project.main.isEmpty())return;
        try{
            player=new CompositionPlayer.Builder(this).build();player.setVideoSurfaceView(surface);
            player.setComposition(NativeEngine.composition(this,project),cursor);player.prepare();
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_ENDED){if(play!=null)play.setText("▶");}
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException error){notice("Предпросмотр: "+error.getMessage());}
            });
        }catch(Exception ex){release();status.setText("Предпросмотр недоступен: "+ex.getMessage());status.setVisibility(View.VISIBLE);}
    }
    private void release(){if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}}
    private void pick(String kind){importKind=kind;document(PICK_MEDIA,
            kind.equals("audio")?"audio/*":kind.equals("overlay")?"image/*":"*/*",true,kind);}
    private void document(int code,String mime,boolean multiple,String purpose){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if("main".equals(purpose))intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/*"});
        startActivityForResult(intent,code);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null&&data.getClipData()==null)return;
        if(request==CREATE_MP4){
            Uri uri=data.getData();if(uri==null)return;save(project);
            Intent intent=new Intent(this,ExportService.class);
            intent.setAction(ExportService.ACTION_START);intent.putExtra("project",project.id);
            intent.putExtra("destination",uri.toString());
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            showExport();return;
        }
        if(request==PICK_SRT){try{
            String body=read(getContentResolver().openInputStream(data.getData()),2_000_000);
            List<CaptionTools.Caption> subs=CaptionTools.parse(body,0);
            if(subs.isEmpty()){notice("Субтитры не найдены");return;}
            change(()->{for(CaptionTools.Caption sub:subs){
                if(sub.startMs>=project.duration())continue;
                EditProject.Clip clip=new EditProject.Clip();clip.kind="text";clip.name="Субтитры";
                clip.text=sub.text;clip.start=sub.startMs;
                clip.source=Math.min(project.duration(),sub.endMs)-sub.startMs;
                clip.in=0;clip.out=clip.source;
                if(clip.duration()>=EditProject.MIN_CLIP_MS)project.layers.add(clip);
            }});notice("Импортировано субтитров: "+subs.size());
        }catch(Exception e){notice("Ошибка импорта SRT: "+e.getMessage());}return;}
        if(request==PICK_MEDIA){
            ArrayList<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null){android.content.ClipData clips=data.getClipData();
                for(int i=0;i<clips.getItemCount()&&i<100;i++)uris.add(clips.getItemAt(i).getUri());
            }else if(data.getData()!=null)uris.add(data.getData());
            if(uris.isEmpty())return;
            ArrayList<EditProject.Clip> clips=new ArrayList<>();
            for(Uri uri:uris){
                try{
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    clips.add(readMedia(uri,importKind));
                }catch(Exception ex){notice("Медиа недоступно: "+ex.getMessage());}
            }
            if(!clips.isEmpty())change(()->{
                for(EditProject.Clip clip:clips){
                    if(clip.kind.equals("audio")||"overlay".equals(importKind)){
                        clip.start=cursor;project.layers.add(clip);
                    }else project.main.add(clip);
                }
            });
        }
    }
    private EditProject.Clip readMedia(Uri uri,String desired){
        String mime=getContentResolver().getType(uri),name="Медиа";
        try(Cursor cur=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cur!=null&&cur.moveToFirst())name=cur.getString(0);
        }catch(Exception ignored){}
        EditProject.Clip clip=new EditProject.Clip();clip.uri=uri.toString();clip.name=name;
        clip.kind="audio".equals(desired)?"audio":(mime!=null&&mime.startsWith("image/")?"image":"video");
        clip.source=5000;
        if(!clip.kind.equals("image")){
            MediaMetadataRetriever metadata=new MediaMetadataRetriever();
            try{
                metadata.setDataSource(this,uri);
                String duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if(duration!=null)clip.source=Math.max(100,Long.parseLong(duration));
            }finally{metadata.release();}
        }
        clip.out=clip.source;
        if(clip.kind.equals("audio"))clip.start=cursor;
        return clip;
    }
    private String read(InputStream stream,int limit)throws IOException{
        if(stream==null)throw new IOException("Файл недоступен");
        try(InputStream input=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=input.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Файл слишком большой");out.write(buf,0,n);}
            return out.toString("UTF-8");
        }
    }
    private void export(){
        if(project==null||project.main.isEmpty()){notice("Сначала добавьте видео или фото");return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");intent.putExtra(Intent.EXTRA_TITLE,project.name+".mp4");
        startActivityForResult(intent,CREATE_MP4);
    }
    private void showExport(){
        if(exportReceiver==null){
            exportReceiver=new BroadcastReceiver(){@Override public void onReceive(Context ctx,Intent intent){
                String message=intent.getStringExtra("message");int progress=intent.getIntExtra("progress",0);
                if(message!=null){exportSeen=message;
                    if(progress>=100||progress<0){notice("Экспорт: "+message);
                        if(status!=null)status.setVisibility(View.GONE);
                    }else if(status!=null&&project!=null){status.setVisibility(View.VISIBLE);
                        status.setText("Экспорт MP4: "+message+" · "+progress+"%");}
                }
            }};
            IntentFilter filter=new IntentFilter(ExportService.ACTION_STATUS);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(exportReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(exportReceiver,filter);
        }
        new AlertDialog.Builder(this).setTitle("Экспорт MP4")
            .setMessage("Видео обрабатывается на телефоне. Можно наблюдать за прогрессом через уведомления.\n\nОтменить экспорт?")
            .setNegativeButton("Позже →",null)
            .setPositiveButton("Отменить экспорт",(d,w)->{
                Intent cancel=new Intent(this,ExportService.class);cancel.setAction(ExportService.ACTION_CANCEL);
                startService(cancel);
            }).show();
    }
    @Override public void onBackPressed(){if(project!=null)home();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(project!=null){save(project);out.putString("project_id",project.id);}}
    @Override protected void onStop(){super.onStop();if(project!=null)save(project);}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);release();
        if(exportReceiver!=null){try{unregisterReceiver(exportReceiver);}catch(Exception ignored){}}
        super.onDestroy();
    }
}
