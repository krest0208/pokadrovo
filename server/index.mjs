import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { randomUUID,createHash,randomBytes } from 'node:crypto';
import { createDb,rnd,sha,pw,matches,issueSession,sessionUser,publicUser,stats } from './db.mjs';

const noCache={'cache-control':'no-store','x-content-type-options':'nosniff',
  'referrer-policy':'no-referrer','x-frame-options':'DENY'};
const jsonHeaders={...noCache,'content-type':'application/json; charset=utf-8'};
const contentSecurity="default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'";
const emailOk=(x)=>typeof x==='string'&&x.length<=254&&/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(x);
const passwordOk=(x)=>typeof x==='string'&&x.length>=10&&x.length<=200;
const sendJson=(res,code,data)=>{res.writeHead(code,jsonHeaders);res.end(JSON.stringify(data));};
const respond=(res,code,message)=>sendJson(res,code,{error:message});
const safeMode=(m)=>m==='app'?'app':'web';
const loadConfig=()=>({
  publicBase:process.env.PUBLIC_BASE_URL||'http://localhost:3080',
  analyticsSecret:process.env.ANALYTICS_SECRET||randomBytes(32).toString('hex'),
  googleId:process.env.GOOGLE_CLIENT_ID||'',googleSecret:process.env.GOOGLE_CLIENT_SECRET||'',
  vkId:process.env.VK_CLIENT_ID||'',resendKey:process.env.RESEND_API_KEY||'',
  emailFrom:process.env.EMAIL_FROM||'',services:JSON.parse(process.env.SERVICES_JSON||'{}')
});
const formBody=(object)=>new URLSearchParams(object).toString();
const challenge=(verifier)=>createHash('sha256').update(verifier).digest('base64url');
async function readBody(req){
  let total=0,parts=[];for await(const chunk of req){total+=chunk.length;
    if(total>32768)throw Object.assign(new Error('Request too large'),{status:413});parts.push(chunk);
  }
  const source=Buffer.concat(parts).toString('utf8');
  try{return source?JSON.parse(source):{};}catch{throw Object.assign(new Error('Invalid JSON'),{status:400});}
}
function authUser(req,db){
  const bearer=/^Bearer ([A-Za-z0-9_-]{30,})$/.exec(req.headers.authorization||'');
  return bearer?sessionUser(db,bearer[1]):null;
}
const redirect=(res,href)=>{res.writeHead(302,{...noCache,location:href});res.end();};
async function sendResetMail(cfg,to,token){
  if(!cfg.resendKey||!cfg.emailFrom)return false;
  const href=new URL('/reset.html',cfg.publicBase);href.searchParams.set('token',token);
  const r=await fetch('https://api.resend.com/emails',{method:'POST',headers:{
    'authorization':'Bearer '+cfg.resendKey,'content-type':'application/json'
  },body:JSON.stringify({from:cfg.emailFrom,to:[to],subject:'Покадрово — восстановление пароля',
    text:'Перейдите по ссылке в течение 15 минут: '+href.toString()}),signal:AbortSignal.timeout(9000)});
  if(!r.ok)throw new Error('Mail provider rejected request');return true;
}
function socialUser(db,provider,subject,email,verified){
  const old=db.prepare('SELECT user_id FROM identities WHERE provider=? AND subject=?').get(provider,subject);
  if(old)return old.user_id;
  let matched=null;
  if(verified&&emailOk(email))matched=db.prepare('SELECT id FROM users WHERE email=?').get(email.toLowerCase());
  const id=matched?.id||randomUUID();
  const uniqueEmail=verified&&emailOk(email)?email.toLowerCase():`${provider}_${subject}@external.invalid`;
  if(!matched){
    db.prepare('INSERT INTO users(id,email,email_verified,created_at) VALUES(?,?,?,?)')
      .run(id,uniqueEmail,verified?1:0,Date.now());
  }
  db.prepare('INSERT INTO identities(provider,subject,user_id) VALUES(?,?,?)').run(provider,subject,id);
  return id;
}

/** Create an HTTP server that accepts only bearer access tokens; role is read from SQLite. */
export function createServer(db=createDb(process.env.SQLITE_PATH||'./data/pokadrovo.sqlite'),cfg=loadConfig()){
  const limit=new Map();
  const host=new URL(cfg.publicBase).host;
  const limitAuth=(req)=>{
    const ip=req.socket.remoteAddress||'unknown';const now=Date.now();
    let row=limit.get(ip);if(!row||now-row.since>60000)row={since:now,n:0};
    row.n++;limit.set(ip,row);return row.n<=40;
  };
  return http.createServer(async(req,res)=>{
    try{
      const u=new URL(req.url,'http://localhost');const path=u.pathname;
      const method=req.method||'GET';
      res.setHeader('Content-Security-Policy',contentSecurity);
      if(path.startsWith('/api/')){
        if(method==='OPTIONS')return respond(res,405,'Method not allowed');
        if(!['GET','POST'].includes(method))return respond(res,405,'Method not allowed');
        if(method==='POST'&&req.headers.origin){
          let origin;try{origin=new URL(req.headers.origin).origin;}catch{}
          if(origin!==new URL(cfg.publicBase).origin)return respond(res,403,'Origin rejected');
        }
        if(method==='GET'&&path==='/api/health')return sendJson(res,200,{ok:true,service:'pokadrovo'});
        if(method==='GET'&&path==='/api/config')return sendJson(res,200,{
          google:!!(cfg.googleId&&cfg.googleSecret),vk:!!cfg.vkId,
          email:!!(cfg.resendKey&&cfg.emailFrom),services:cfg.services
        });
        if(method==='POST'&&path==='/api/track/visit'){
          const body=await readBody(req),id=body.visitor_id;
          if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{16,100}$/.test(id))return respond(res,400,'Invalid visitor ID');
          const day=new Date().toISOString().slice(0,10);
          const key=sha(cfg.analyticsSecret+':'+id);
          db.prepare('INSERT OR IGNORE INTO visits(day,visitor_hash,created_at) VALUES(?,?,?)')
            .run(day,key,Date.now());return sendJson(res,200,{ok:true});
        }
        if(path.startsWith('/api/auth/')&&(!limitAuth(req)))return respond(res,429,'Too many requests');
        if(method==='POST'&&path==='/api/auth/register'){
          const b=await readBody(req),email=typeof b.email==='string'?b.email.trim().toLowerCase():'';
          if(!emailOk(email)||!passwordOk(b.password))return respond(res,400,'Введите почту и пароль от 10 символов');
          if(db.prepare('SELECT 1 FROM users WHERE email=?').get(email))return respond(res,409,'Email already registered');
          const hash=pw(b.password),id=randomUUID();
          db.prepare('INSERT INTO users(id,email,password_salt,password_hash,created_at) VALUES(?,?,?,?,?)')
            .run(id,email,hash.salt,hash.hash,Date.now());
          const token=issueSession(db,id);
          return sendJson(res,201,{token,user:publicUser(db.prepare('SELECT * FROM users WHERE id=?').get(id))});
        }
        if(method==='POST'&&path==='/api/auth/login'){
          const b=await readBody(req),email=typeof b.email==='string'?b.email.trim().toLowerCase():'';
          if(!emailOk(email)||typeof b.password!=='string'||b.password.length>200)return respond(res,401,'Неверная почта или пароль');
          const user=db.prepare('SELECT * FROM users WHERE email=?').get(email);
          // Always run a KDF to reduce timing disparity for non-existing accounts.
          if(!user){pw(b.password);return respond(res,401,'Неверная почта или пароль');}
          if(!matches(b.password,user))return respond(res,401,'Неверная почта или пароль');
          const token=issueSession(db,user.id);
          return sendJson(res,200,{token,user:publicUser(user)});
        }
        if(method==='POST'&&path==='/api/auth/reset/request'){
          const b=await readBody(req),email=typeof b.email==='string'?b.email.trim().toLowerCase():'';
          if(emailOk(email)&&cfg.resendKey&&cfg.emailFrom){
            const user=db.prepare('SELECT id FROM users WHERE email=?').get(email);
            if(user){const token=rnd();
              db.prepare('INSERT INTO reset_tokens(token_hash,user_id,expires_at) VALUES(?,?,?)')
                .run(sha(token),user.id,Date.now()+15*60000);
              try{await sendResetMail(cfg,email,token);}catch(e){console.error('Reset email failed (no secrets printed)');}
            }
          }
          return sendJson(res,200,{ok:true});
        }
        if(method==='POST'&&path==='/api/auth/reset/confirm'){
          const b=await readBody(req);
          if(!passwordOk(b.password)||typeof b.token!=='string')return respond(res,400,'Invalid token or password');
          const row=db.prepare('SELECT * FROM reset_tokens WHERE token_hash=? AND expires_at>?')
            .get(sha(b.token),Date.now());
          if(!row)return respond(res,400,'Token expired or invalid');
          const hashed=pw(b.password);
          db.prepare('UPDATE users SET password_hash=?,password_salt=? WHERE id=?').run(hashed.hash,hashed.salt,row.user_id);
          db.prepare('DELETE FROM reset_tokens WHERE user_id=?').run(row.user_id);
          db.prepare('DELETE FROM sessions WHERE user_id=?').run(row.user_id);
          return sendJson(res,200,{ok:true});
        }
        if(method==='GET'&&/^(\/api\/auth\/oauth\/(google|vk)\/start)$/.test(path)){
          const provider=path.split('/')[4];
          if(provider==='google'&&!(cfg.googleId&&cfg.googleSecret)||provider==='vk'&&!cfg.vkId)
            return respond(res,503,'OAuth provider is not configured');
          const mode=safeMode(u.searchParams.get('mode'));
          const clientChallenge=u.searchParams.get('challenge')||'';
          if(mode==='app'&&!/^[A-Za-z0-9_-]{43}$/.test(clientChallenge))return respond(res,400,'PKCE challenge required');
          const state=rnd(),verifier=rnd();
          db.prepare('INSERT INTO oauth_states(state_hash,provider,verifier,mode,client_challenge,created_at) VALUES(?,?,?,?,?,?)')
            .run(sha(state),provider,verifier,mode,clientChallenge,Date.now());
          const callback=cfg.publicBase+'/api/auth/oauth/'+provider+'/callback';
          let target;
          if(provider==='google'){
            target=new URL('https://accounts.google.com/o/oauth2/v2/auth');
            Object.entries({client_id:cfg.googleId,redirect_uri:callback,response_type:'code',
              scope:'openid email profile',state,code_challenge:challenge(verifier),
              code_challenge_method:'S256',access_type:'online'}).forEach(([k,v])=>target.searchParams.set(k,v));
          }else{
            target=new URL('https://id.vk.ru/authorize');
            Object.entries({client_id:cfg.vkId,redirect_uri:callback,response_type:'code',
              scope:'email',state,code_challenge:challenge(verifier),code_challenge_method:'S256'}).forEach(([k,v])=>target.searchParams.set(k,v));
          }
          return sendJson(res,200,{url:target.toString()});
        }
        if(method==='GET'&&/^\/api\/auth\/oauth\/(google|vk)\/callback$/.test(path)){
          const provider=path.split('/')[4];
          let payload={};try{payload=JSON.parse(u.searchParams.get('payload')||'{}');}catch{}
          const state=u.searchParams.get('state')||payload.state||'';
          const row=db.prepare('SELECT * FROM oauth_states WHERE state_hash=? AND provider=?')
            .get(sha(state),provider);
          if(!row||Date.now()-row.created_at>10*60000)return respond(res,400,'OAuth state expired');
          db.prepare('DELETE FROM oauth_states WHERE state_hash=?').run(sha(state));
          const code=u.searchParams.get('code')||payload.code||'';
          if(typeof code!=='string'||!code||u.searchParams.has('error'))return respond(res,400,'Authorization cancelled or failed');
          const callback=cfg.publicBase+'/api/auth/oauth/'+provider+'/callback';
          let sub,email,verified=false;
          if(provider==='google'){
            const tokens=await fetch('https://oauth2.googleapis.com/token',{method:'POST',
              headers:{'content-type':'application/x-www-form-urlencoded'},body:formBody({
                code,client_id:cfg.googleId,client_secret:cfg.googleSecret,
                redirect_uri:callback,grant_type:'authorization_code',code_verifier:row.verifier
              }),signal:AbortSignal.timeout(10000)});
            if(!tokens.ok)return respond(res,502,'Google token exchange failed');
            const t=await tokens.json();
            const profile=await fetch('https://openidconnect.googleapis.com/v1/userinfo',{
              headers:{authorization:'Bearer '+t.access_token},signal:AbortSignal.timeout(10000)});
            if(!profile.ok)return respond(res,502,'Google profile request failed');
            const info=await profile.json();sub=info.sub;email=info.email;verified=info.email_verified===true;
            if(!sub||!verified||!emailOk(email))return respond(res,403,'Google email is not verified');
          }else{
            const device=u.searchParams.get('device_id')||payload.device_id||'';
            if(!device)return respond(res,400,'VK device_id missing');
            const q=new URLSearchParams({grant_type:'authorization_code',redirect_uri:callback,
              client_id:cfg.vkId,code_verifier:row.verifier,state:rnd(),device_id:device});
            const result=await fetch('https://id.vk.ru/oauth2/auth?'+q.toString(),{
              method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},
              body:formBody({code}),signal:AbortSignal.timeout(10000)});
            if(!result.ok)return respond(res,502,'VK token exchange failed');
            const t=await result.json();if(!t.access_token)return respond(res,502,'VK login failed');
            const profile=await fetch('https://id.vk.ru/oauth2/user_info?client_id='+encodeURIComponent(cfg.vkId),{
              method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},
              body:formBody({access_token:t.access_token}),signal:AbortSignal.timeout(10000)});
            if(!profile.ok)return respond(res,502,'VK profile request failed');
            const info=await profile.json();const user=info.user||{};
            sub=user.user_id!=null?String(user.user_id):'';email=user.email||'';
            // VK email is NOT trusted for linking to existing password accounts.
            verified=false;if(!sub)return respond(res,502,'VK identity is missing');
          }
          const id=socialUser(db,provider,sub,email,verified);
          const transferCode=rnd();
          db.prepare('INSERT INTO oauth_codes(code_hash,user_id,client_challenge,expires_at) VALUES(?,?,?,?)')
            .run(sha(transferCode),id,row.client_challenge,Date.now()+60000);
          return redirect(res,row.mode==='app'?'pokadrovo://auth?code='+encodeURIComponent(transferCode)
            :'/auth.html#code='+encodeURIComponent(transferCode));
        }
        if(method==='POST'&&path==='/api/auth/oauth/exchange'){
          const b=await readBody(req);if(typeof b.code!=='string')return respond(res,400,'Invalid code');
          const row=db.prepare('SELECT * FROM oauth_codes WHERE code_hash=? AND expires_at>?')
            .get(sha(b.code),Date.now());
          if(!row)return respond(res,401,'Code expired');
          if(row.client_challenge&&challenge(String(b.verifier||''))!==row.client_challenge)
            return respond(res,403,'PKCE proof rejected');
          db.prepare('DELETE FROM oauth_codes WHERE code_hash=?').run(sha(b.code));
          const user=db.prepare('SELECT * FROM users WHERE id=?').get(row.user_id);
          return sendJson(res,200,{token:issueSession(db,row.user_id),user:publicUser(user)});
        }
        const user=authUser(req,db);
        if(method==='GET'&&path==='/api/auth/me'){
          if(!user)return respond(res,401,'Not authorized');return sendJson(res,200,{user:publicUser(user)});
        }
        if(method==='POST'&&path==='/api/auth/logout'){
          const match=/^Bearer ([A-Za-z0-9_-]{30,})$/.exec(req.headers.authorization||'');
          if(match)db.prepare('DELETE FROM sessions WHERE token_hash=?').run(sha(match[1]));
          return sendJson(res,200,{ok:true});
        }
        if(method==='GET'&&path==='/api/orders'){
          if(!user)return respond(res,401,'Not authorized');
          return sendJson(res,200,{orders:db.prepare('SELECT id,service_id,price_minor,status,created_at,paid_at FROM orders WHERE user_id=? ORDER BY created_at DESC').all(user.id)});
        }
        if(method==='POST'&&path==='/api/orders'){
          if(!user)return respond(res,401,'Not authorized');
          const b=await readBody(req);const service=b.service_id;
          const price=Object.hasOwn(cfg.services,service)?cfg.services[service]:null;
          if(typeof price!=='number'||!Number.isSafeInteger(price)||price<0)return respond(res,400,'Service is not configured');
          const id=randomUUID();db.prepare('INSERT INTO orders(id,user_id,service_id,price_minor,created_at) VALUES(?,?,?,?,?)')
            .run(id,user.id,service,price,Date.now());
          return sendJson(res,201,{order:{id,status:'pending',service_id:service,price_minor:price}});
        }
        if(path.startsWith('/api/admin/')){
          if(!user||user.role!=='admin')return respond(res,403,'Admin access required');
          if(method==='GET'&&path==='/api/admin/stats'){
            const days=Math.max(1,Math.min(90,Number(u.searchParams.get('days'))||14));
            return sendJson(res,200,stats(db,days));
          }
          if(method==='GET'&&path==='/api/admin/users'){
            const page=Math.max(1,Math.min(10000,Number(u.searchParams.get('page'))||1));
            const search=(u.searchParams.get('search')||'').slice(0,100);
            const count=db.prepare('SELECT COUNT(*) n FROM users WHERE email LIKE ?').get('%'+search+'%').n;
            const users=db.prepare(`SELECT u.id,u.email,u.role,u.email_verified,u.created_at,
              (SELECT GROUP_CONCAT(i.provider) FROM identities i WHERE i.user_id=u.id) providers,
              (SELECT COUNT(*) FROM orders o WHERE o.user_id=u.id AND o.status='paid') purchases
              FROM users u WHERE u.email LIKE ? ORDER BY u.created_at DESC LIMIT 50 OFFSET ?`).all('%'+search+'%',(page-1)*50);
            return sendJson(res,200,{page,total:count,users});
          }
          if(method==='GET'&&path==='/api/admin/orders'){
            return sendJson(res,200,{orders:db.prepare(`SELECT o.id,o.service_id,o.price_minor,o.status,o.created_at,o.paid_at,
              u.email FROM orders o JOIN users u ON u.id=o.user_id ORDER BY o.created_at DESC LIMIT 250`).all()});
          }
          const manual=/^\/api\/admin\/orders\/([0-9a-f-]{36})\/confirm$/.exec(path);
          if(method==='POST'&&manual){
            const id=manual[1];
            const done=db.prepare("UPDATE orders SET status='paid',paid_at=? WHERE id=? AND status='pending'")
              .run(Date.now(),id);
            if(!done.changes)return respond(res,404,'Pending order not found');
            return sendJson(res,200,{ok:true});
          }
        }
        return respond(res,404,'Unknown API endpoint');
      }
      if(method!=='GET')return respond(res,405,'Method not allowed');
      // Tight static allowlist: never serve .env, SQLite data or source files.
      const files={'/':'index.html','/index.html':'index.html','/admin.html':'admin.html',
        '/auth.html':'auth.html','/reset.html':'reset.html','/app.js':'app.js','/styles.css':'styles.css'};
      const local=files[path];if(!local)return respond(res,404,'Not found');
      const data=await readFile(new URL('./public/'+local,import.meta.url));
      res.writeHead(200,{...noCache,'content-type':local.endsWith('.js')?'text/javascript; charset=utf-8':
        local.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8'});
      res.end(data);
    }catch(e){
      if(e.status)return respond(res,e.status,e.message);
      console.error('Unexpected API error:',e.message);
      if(!res.headersSent)respond(res,500,'Internal server error');else res.end();
    }
  });
}
if(process.argv[1]&&pathToFileURL(process.argv[1]).href===import.meta.url){
  const host=process.env.HOST||'127.0.0.1',port=Number(process.env.PORT||3080);
  const server=createServer();server.listen(port,host,()=>console.log(`Pokadrovo API listening at http://${host}:${port}`));
}
