package ru.pokadrovo.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only administrative dashboard. Role enforcement is exclusively on the API server. */
public final class AdminActivity extends Activity {
    private final int BG=0xFF10131C,INK=0xFFF7F7FB,MUTED=0xFFACB5C9;
    private ApiClient api;
    private LinearLayout root,series,users;
    private TextView status,summary;
    private EditText search;
    private int page=1;
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView label(String content,int size){TextView t=new TextView(this);t.setText(content);t.setTextColor(INK);t.setTextSize(size);t.setPadding(dp(5),dp(8),dp(5),dp(8));return t;}
    private Button action(String name,Runnable onClick){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(INK);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333857));b.setOnClickListener(v->onClick.run());return b;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);api=new ApiClient(this);build();loadOverview();loadUsers();}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(15),dp(15),dp(15),dp(35));
        scroll.addView(root);setContentView(scroll);
        root.addView(action("← Вернуться",this::finish));
        root.addView(label("Статистика Покадрово",25));
        status=label("Данные загружаются…",13);status.setTextColor(MUTED);root.addView(status);
        summary=label("",16);root.addView(summary);
        series=new LinearLayout(this);series.setOrientation(LinearLayout.VERTICAL);root.addView(series);
        root.addView(label("Зарегистрированные пользователи",20));
        search=new EditText(this);search.setHint("Поиск по e-mail / имени");search.setSingleLine(true);
        search.setTextColor(INK);search.setHintTextColor(MUTED);root.addView(search);
        root.addView(action("Найти",()->{page=1;loadUsers();}));
        users=new LinearLayout(this);users.setOrientation(LinearLayout.VERTICAL);root.addView(users);
        LinearLayout paging=new LinearLayout(this);root.addView(paging);
        Button prev=action("← Назад",()->{page=Math.max(1,page-1);loadUsers();});
        Button next=action("Далее →",()->{page++;loadUsers();});
        paging.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));
        paging.addView(next,new LinearLayout.LayoutParams(0,dp(46),1));
        root.addView(label("Администратор определяется на сервере. Наличие кнопки в приложении не даёт доступа к данным.",12));
    }
    private void loadOverview(){
        if(!ApiClient.available()){status.setText("Сервер не подключён: статистика недоступна");return;}
        api.get("/api/admin/stats?days=30",true,(data,error,code)->{
            if(error!=null){status.setText(code==403?"Нет прав администратора (403)":error);return;}
            status.setText("Данные получены с сервера");
            Map<String,Object> totals;
            try{totals=Json.object(data.get("summary"));}catch(Exception e){status.setText("Неверный ответ статистики");return;}
            summary.setText("Посещения: "+value(totals,"visits")+"  ·  Регистрации: "+value(totals,"registrations")+
                "\nПокупатели: "+value(totals,"buyers")+"  ·  Покупки: "+value(totals,"paid_orders")+
                "\nВыручка (мин. ед.): "+value(totals,"revenue_minor"));
            series.removeAllViews();
            Object days=data.get("days");if(!(days instanceof List)){series.addView(label("Данные для графиков пока отсутствуют",14));return;}
            List<?> raw=(List<?>)days;
            if(raw.isEmpty()){series.addView(label("Нет событий за выбранный период",14));return;}
            addGraph("Посещения по дням",raw,"visits",0xFF7061F5);
            addGraph("Регистрации по дням",raw,"registrations",0xFF44B5DD);
            addGraph("Покупки по дням",raw,"paid",0xFFFFAE6C);
            addGraph("Выручка по дням (мин. ед.)",raw,"revenue_minor",0xFF6BD0A6);
        });
    }
    private static String value(Map<String,Object> row,String key){Object obj=row.get(key);return obj==null?"—":obj.toString();}
    private void addGraph(String title,List<?> raw,String key,int color){
        series.addView(label(title,16));List<Float> values=new ArrayList<>();
        for(Object item:raw){if(!(item instanceof Map))continue;
            Object x=((Map<?,?>)item).get(key);
            values.add(x instanceof Number?Math.max(0,((Number)x).floatValue()):0f);
        }
        if(values.isEmpty())return;
        MiniGraph graph=new MiniGraph(values,color);series.addView(graph,new LinearLayout.LayoutParams(-1,dp(125)));
    }
    private void loadUsers(){
        users.removeAllViews();
        if(!ApiClient.available())return;
        String query=search.getText().toString().trim();String encoded;
        try{encoded=URLEncoder.encode(query,"UTF-8");}catch(Exception e){encoded="";}
        api.get("/api/admin/users?page="+page+"&search="+encoded,true,(data,error,code)->{
            users.removeAllViews();
            if(error!=null){users.addView(label(code==403?"Доступ запрещён: требуется роль администратора":error,15));return;}
            Object records=data.get("users");
            if(!(records instanceof List)){users.addView(label("Пользователи отсутствуют в ответе сервера",14));return;}
            for(Object obj:(List<?>)records){if(!(obj instanceof Map))continue;
                Map<?,?> u=(Map<?,?>)obj;
                String name=field(u,"email","—");
                String provider=field(u,"providers","email");
                String joined=field(u,"created_at","—");
                String purchased=field(u,"purchases","0");
                users.addView(label(name+"\nВход: "+provider+" · Дата: "+joined+" · Покупок: "+purchased,14));
                View rule=new View(this);rule.setBackgroundColor(0xFF35394B);users.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
            }
            if(((List<?>)records).isEmpty())users.addView(label("На этой странице пользователей нет",14));
        });
    }
    private static String field(Map<?,?> data,String key,String fallback){Object o=data.get(key);return o==null?fallback:o.toString();}
    private final class MiniGraph extends View {
        private final List<Float> values;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;
        MiniGraph(List<Float> data,int line){super(AdminActivity.this);values=data;color=line;setBackgroundColor(0xFF202437);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);
            float max=1;for(float v:values)max=Math.max(max,v);
            float l=dp(13),r=getWidth()-dp(13),top=dp(13),bottom=getHeight()-dp(15);
            p.setColor(0xFF444A5F);p.setStrokeWidth(dp(1));
            for(int i=0;i<=4;i++){float y=top+(bottom-top)*i/4;c.drawLine(l,y,r,y,p);}
            p.setColor(color);p.setStrokeWidth(dp(2));
            for(int i=1;i<values.size();i++){
                float x0=l+(r-l)*(i-1)/(Math.max(1,values.size()-1));
                float x1=l+(r-l)*i/(Math.max(1,values.size()-1));
                c.drawLine(x0,bottom-(bottom-top)*values.get(i-1)/max,x1,bottom-(bottom-top)*values.get(i)/max,p);
            }
            if(values.size()==1)c.drawCircle(l,bottom-(bottom-top)*values.get(0)/max,dp(3),p);
        }
    }
}
