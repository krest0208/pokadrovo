package ru.pokadrovo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed encrypted storage for short-lived server access tokens. */
public final class SessionStore {
    private static final String KEY_ALIAS="pokadrovo.session.aes.v1";
    private final SharedPreferences prefs;
    public SessionStore(Context context){prefs=context.getApplicationContext().getSharedPreferences("session.v1",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SecretKey key=(SecretKey)store.getKey(KEY_ALIAS,null);if(key!=null)return key;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
    public void setAccessToken(String token){
        if(token==null||token.isEmpty()){logout();return;}
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
                Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            if(!prefs.edit().putString("access",value).commit())throw new IllegalStateException("Не удалось сохранить ключ сессии");
        }catch(Exception ex){throw new IllegalStateException("Ошибка безопасного хранилища",ex);}
    }
    public String accessToken(){
        String value=prefs.getString("access","");if(value.isEmpty())return "";
        try{
            String[] halves=value.split(":",2);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(halves[0],Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(halves[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception ex){logout();return "";}
    }
    public void logout(){prefs.edit().remove("access").apply();}
}
