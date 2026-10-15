package ru.pokadrovo.app;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An explicit, server-backed API contract. This class never simulates user accounts or metrics. */
public final class ApiClient {
    public interface Callback {void result(Map<String,Object> data,String error,int code);}
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Handler UI=new Handler(Looper.getMainLooper());
    public static final String BASE=BuildConfig.API_BASE;
    private final SessionStore session;
    public ApiClient(android.content.Context context){session=new SessionStore(context);}
    public static boolean available(){return BASE!=null&&BASE.startsWith("https://")&&!BASE.contains(" ");}
    public static String base(){return available()?BASE.replaceAll("/+$",""):"";}
    public void get(String path,boolean auth,Callback callback){call("GET",path,null,auth,callback);}
    public void post(String path,Map<String,Object> payload,boolean auth,Callback callback){call("POST",path,payload,auth,callback);}
    public void call(String method,String path,Map<String,Object> payload,boolean auth,Callback callback){
        if(!available()){UI.post(()->callback.result(Collections.emptyMap(),"Сервер API не настроен",0));return;}
        if(!(path.startsWith("/")&&!path.startsWith("//")&&!path.contains(".."))){
            UI.post(()->callback.result(Collections.emptyMap(),"Неверный путь запроса",0));return;
        }
        IO.execute(()->{
            Map<String,Object> response=Collections.emptyMap();String error=null;int code=0;
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(base()+path).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000);connection.setReadTimeout(16000);
                connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
                if(auth){
                    String token=session.accessToken();
                    if(token.isEmpty())throw new IllegalStateException("Сначала войдите в аккаунт");
                    connection.setRequestProperty("Authorization","Bearer "+token);
                }
                if("POST".equals(method)){
                    connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    byte[] bytes=Json.stringify(payload==null?Collections.emptyMap():payload).getBytes(StandardCharsets.UTF_8);
                    try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
                }
                code=connection.getResponseCode();
                if(code>=300 && code<400)throw new IllegalStateException("Перенаправление API запрещено");
                InputStream stream=code>=400?connection.getErrorStream():connection.getInputStream();
                if(stream!=null){
                    try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buf=new byte[4096];int n;
                        while((n=in.read(buf))!=-1){if(out.size()+n>1_000_000)throw new IllegalStateException("Слишком большой ответ API");out.write(buf,0,n);}
                        Object parsed=Json.parse(out.toString("UTF-8"));response=Json.object(parsed);
                    }
                }
                if(code<200||code>=300)error=Json.str(response,"error","Ошибка сервера (HTTP "+code+")");
            }catch(Exception e){error=e.getMessage()==null?"Не удалось подключиться к серверу":e.getMessage();}
            finally{if(connection!=null)connection.disconnect();}
            Map<String,Object> result=response;String problem=error;int status=code;
            UI.post(()->callback.result(result,problem,status));
        });
    }
}
