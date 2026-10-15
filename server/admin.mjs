import { createDb,pw } from './db.mjs';
import { randomUUID } from 'node:crypto';
const email=process.argv[2]?.trim().toLowerCase();
const password=process.env.ADMIN_INITIAL_PASSWORD;
if(!email||!email.includes('@')||!password||password.length<12){
  console.error('Usage: ADMIN_INITIAL_PASSWORD="<12+ chars>" npm run bootstrap-admin -- admin@example.com');
  process.exit(1);
}
const db=createDb(process.env.SQLITE_PATH||'./data/pokadrovo.sqlite');
const existing=db.prepare('SELECT id FROM users WHERE email=?').get(email);
const {salt,hash}=pw(password);
if(existing){db.prepare("UPDATE users SET password_salt=?,password_hash=?,email_verified=1,role='admin' WHERE id=?")
  .run(salt,hash,existing.id);
}else{db.prepare("INSERT INTO users(id,email,password_salt,password_hash,email_verified,role,created_at) VALUES(?,?,?,?,1,'admin',?)")
  .run(randomUUID(),email,salt,hash,Date.now());}
console.log('Admin provisioned:',email);
db.close();
