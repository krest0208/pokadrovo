package ru.pokadrovo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native account UI. Every login method requires a working, trusted HTTPS backend. */
public final class AccountActivity extends Activity {
    private final int BG=0xFF10131C,CARD=0xFF202437,INK=0xFFF7F7FB,MUTED=0xFFACB5C9,ACCENT=0xFF7061F5;
    private ApiClient api;
    private SessionStore session;
    private EditText email,password;
    private TextView info;
    private LinearLayout content;
    private boolean busy;
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private TextView text(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(INK);t.setPadding(0,dp(7),0,dp(7));return t;}
    private Button button(String name,boolean primary,Runnable fn){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);
        b.setTextColor(INK);b.setBackground(shape(primary?ACCENT:CARD));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(47));params.topMargin=dp(8);content.addView(b,params);
        b.setOnClickListener(v->{if(!busy)fn.run();});return b;
    }
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        api=new ApiClient(this);session=new SessionStore(this);
        build();handleCallback(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleCallback(intent);}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(21),dp(24),dp(21),dp(20));scroll.addView(content);setContentView(scroll);
        Button back=button("← Назад в редактор",false,this::finish);back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        TextView title=text("Мой аккаунт",28);title.setTypeface(null,Typeface.BOLD);content.addView(title);
        TextView caption=text("Вход, регистрация и безопасность",14);caption.setTextColor(MUTED);content.addView(caption);
        info=text(ApiClient.available()?"Проверяем сессию…":"Вход пока недоступен: сервер авторизации не настроен в APK.",14);
        info.setTextColor(0xFFF3C783);content.addView(info);
        email=new EditText(this);email.setSingleLine(true);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setHint("Электронная почта");email.setTextColor(INK);email.setHintTextColor(MUTED);
        content.addView(email,new LinearLayout.LayoutParams(-1,dp(55)));
        password=new EditText(this);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("Пароль");password.setTextColor(INK);password.setHintTextColor(MUTED);
        content.addView(password,new LinearLayout.LayoutParams(-1,dp(55)));
        Button login=button("Войти по почте",true,()->passwordAuth(false));
        Button register=button("Зарегистрироваться",false,()->passwordAuth(true));
        Button reset=button("Восстановить пароль",false,this::resetPassword);
        Button google=button("Продолжить с Google",false,()->oauth("google"));
        Button vk=button("Продолжить с VK",false,()->oauth("vk"));
        boolean enabled=ApiClient.available();for(Button b:new Button[]{login,register,reset,google,vk})b.setEnabled(enabled);
        button("Обновить профиль",false,this::loadProfile).setEnabled(enabled);
        button("Админ-панель",false,()->startActivity(new Intent(this,AdminActivity.class))).setEnabled(enabled);
        button("Выйти",false,()->{
            if(session.accessToken().isEmpty()){info.setText("Вы уже вышли из аккаунта");return;}
            api.post("/api/auth/logout",new LinkedHashMap<>(),true,(r,error,code)->{
                session.logout();info.setText(error==null?"Вы вышли из аккаунта":"Вы вышли локально. Сервер: "+error);
            });
        });
        TextView hint=text("Учётные записи и покупки существуют только на сервере. Данные пользователей не хранятся в APK. Google/VK откроются во внешнем браузере после настройки backend OAuth и PKCE.",12);
        hint.setTextColor(MUTED);content.addView(hint);
        if(enabled&& !session.accessToken().isEmpty())loadProfile();
    }
    private void passwordAuth(boolean registration){
        String address=email.getText().toString().trim();String pass=password.getText().toString();
        if(!address.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")||pass.length()<10){
            alert("Укажите корректную почту и пароль (от 10 символов)");return;
        }
        Map<String,Object> fields=new LinkedHashMap<>();fields.put("email",address);fields.put("password",pass);
        busy=true;info.setText("Подключение к серверу…");
        api.post(registration?"/api/auth/register":"/api/auth/login",fields,false,(result,error,status)->{
            busy=false;
            if(error!=null){info.setText(error);return;}
            String token=Json.str(result,"token","");
            if(token.isEmpty()){
                info.setText(registration?"Регистрация отправлена. Проверьте почту для подтверждения.":"Сервер не вернул токен сессии");
                return;
            }
            try{session.setAccessToken(token);password.setText("");loadProfile();}
            catch(Exception ex){info.setText("Не удалось сохранить сессию: "+ex.getMessage());}
        });
    }
    private void resetPassword(){
        String address=email.getText().toString().trim();
        if(!address.contains("@")){alert("Укажите почту для восстановления");return;}
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("email",address);
        api.post("/api/auth/reset/request",payload,false,(r,error,status)->{
            info.setText(error==null?"Если адрес существует, письмо с инструкциями отправлено.":error);
        });
    }
    private static String random(int bytes){
        byte[] value=new byte[bytes];new SecureRandom().nextBytes(value);
        return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
    }
    private void oauth(String provider){
        if(!ApiClient.available())return;
        try{
            String verifier=random(32);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge=Base64.encodeToString(digest,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
            SharedPreferences p=getSharedPreferences("oauth_pending",MODE_PRIVATE);
            p.edit().putString("provider",provider).putString("verifier",verifier)
                .putLong("issued",System.currentTimeMillis()).apply();
            // The server creates/verifies provider state and returns a Google/VK consent URL.
            api.get("/api/auth/oauth/"+provider+"/start?mode=app&challenge="+challenge,false,(result,error,status)->{
                if(error!=null){info.setText(error);p.edit().clear().apply();return;}
                String url=Json.str(result,"url","");
                if(!url.startsWith("https://")){info.setText("Сервер не вернул HTTPS-ссылку OAuth");return;}
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
                    info.setText("Открыт браузер для входа через "+provider.toUpperCase());}
                catch(Exception ex){info.setText("Не удалось открыть браузер: "+ex.getMessage());}
            });
        }catch(Exception ex){alert("Не удалось открыть авторизацию: "+ex.getMessage());}
    }
    private void handleCallback(Intent intent){
        if(intent==null||intent.getData()==null)return;
        Uri uri=intent.getData();
        if(!"pokadrovo".equals(uri.getScheme())||!"auth".equals(uri.getHost()))return;
        SharedPreferences prefs=getSharedPreferences("oauth_pending",MODE_PRIVATE);
        String verifier=prefs.getString("verifier","");
        long issued=prefs.getLong("issued",0);
        String code=uri.getQueryParameter("code");
        if(verifier.isEmpty()||System.currentTimeMillis()-issued>600_000||code==null||code.isEmpty()){
            info.setText("Вход отклонён: отсутствует код или истёк срок OAuth");prefs.edit().clear().apply();return;
        }
        prefs.edit().clear().apply();
        Map<String,Object> body=new LinkedHashMap<>();body.put("code",code);body.put("verifier",verifier);
        api.post("/api/auth/oauth/exchange",body,false,(result,error,status)->{
            if(error!=null){info.setText(error);return;}
            String token=Json.str(result,"token","");
            if(token.isEmpty()){info.setText("Ответ сервера без токена");return;}
            try{session.setAccessToken(token);loadProfile();}
            catch(Exception ex){info.setText("Ошибка защищённой сессии: "+ex.getMessage());}
        });
    }
    private void loadProfile(){
        api.get("/api/auth/me",true,(r,error,status)->{
            if(error!=null){info.setText(error);return;}
            Map<String,Object> user;
            try{user=Json.object(r.get("user"));}catch(Exception e){info.setText("Некорректный ответ сервера");return;}
            String address=Json.str(user,"email","аккаунт");String role=Json.str(user,"role","user");
            info.setText("Вы вошли: "+address+("admin".equals(role)?" · администратор":""));
        });
    }
    private void alert(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно",null).show();}
}
