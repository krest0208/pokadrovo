import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { randomBytes,scryptSync,timingSafeEqual,createHash } from 'node:crypto';
export const rnd=()=>randomBytes(32).toString('base64url');
export const sha=(s)=>createHash('sha256').update(s).digest('hex');
export function pw(password){
  const salt=randomBytes(16).toString('hex');
  return {salt,hash:scryptSync(password,Buffer.from(salt,'hex'),64).toString('hex')};
}
export function matches(password,user){
  if(!user?.password_hash||!user.password_salt)return false;
  const actual=scryptSync(password,Buffer.from(user.password_salt,'hex'),64);
  const stored=Buffer.from(user.password_hash,'hex');
  return stored.length===actual.length&&timingSafeEqual(stored,actual);
}
export function createDb(path){
  if(path!==':memory:')mkdirSync(dirname(path),{recursive:true});
  const db=new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL;
    PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,email TEXT NOT NULL UNIQUE,password_salt TEXT,password_hash TEXT,
      email_verified INTEGER NOT NULL DEFAULT 0,role TEXT NOT NULL DEFAULT 'user'
        CHECK(role IN('admin','user')),created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS identities (
      provider TEXT NOT NULL,subject TEXT NOT NULL,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      PRIMARY KEY(provider,subject)
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
    CREATE TABLE IF NOT EXISTS reset_tokens (
      token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS oauth_states (
      state_hash TEXT PRIMARY KEY,provider TEXT NOT NULL,verifier TEXT NOT NULL,mode TEXT NOT NULL,client_challenge TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS oauth_codes (
      code_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      client_challenge TEXT NOT NULL DEFAULT '',expires_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS visits (
      day TEXT NOT NULL,visitor_hash TEXT NOT NULL,created_at INTEGER NOT NULL,
      PRIMARY KEY(day,visitor_hash)
    );
    CREATE TABLE IF NOT EXISTS orders (
      id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      service_id TEXT NOT NULL,price_minor INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'pending'
        CHECK(status IN ('pending','paid','cancelled')),
      created_at INTEGER NOT NULL,paid_at INTEGER
    );
    CREATE INDEX IF NOT EXISTS orders_user ON orders(user_id);
  `);
  return db;
}
export function issueSession(db,userId){
  const token=rnd(),expiry=Date.now()+30*86400000;
  db.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').run(sha(token),userId,expiry);
  return token;
}
export function sessionUser(db,token){
  if(!token||token.length>300)return null;
  return db.prepare(`SELECT u.id,u.email,u.role,u.email_verified,u.created_at
    FROM users u JOIN sessions s ON s.user_id=u.id
    WHERE s.token_hash=? AND s.expires_at>?`).get(sha(token),Date.now());
}
export const publicUser=(u)=>u&&({id:u.id,email:u.email,role:u.role,
  email_verified:!!u.email_verified,created_at:u.created_at});
export function stats(db,days=14){
  const now=Date.now();const since=new Date(now-(days-1)*86400000).toISOString().slice(0,10);
  const summary={
    visits:db.prepare('SELECT COUNT(*) n FROM visits').get().n,
    registrations:db.prepare('SELECT COUNT(*) n FROM users').get().n,
    buyers:db.prepare("SELECT COUNT(DISTINCT user_id) n FROM orders WHERE status='paid'").get().n,
    paid_orders:db.prepare("SELECT COUNT(*) n FROM orders WHERE status='paid'").get().n,
    revenue_minor:db.prepare("SELECT COALESCE(SUM(price_minor),0) n FROM orders WHERE status='paid'").get().n
  };
  const rows=[];
  for(let i=days-1;i>=0;i--){
    const date=new Date(now-i*86400000).toISOString().slice(0,10);
    const visits=db.prepare('SELECT COUNT(*) n FROM visits WHERE day=?').get(date).n;
    const registrations=db.prepare('SELECT COUNT(*) n FROM users WHERE created_at>=? AND created_at<?')
      .get(Date.parse(date),Date.parse(date)+86400000).n;
    const paid=db.prepare("SELECT COUNT(*) n FROM orders WHERE status='paid' AND paid_at>=? AND paid_at<?")
      .get(Date.parse(date),Date.parse(date)+86400000).n;
    const revenue_minor=db.prepare("SELECT COALESCE(SUM(price_minor),0) n FROM orders WHERE status='paid' AND paid_at>=? AND paid_at<?")
      .get(Date.parse(date),Date.parse(date)+86400000).n;
    rows.push({date,visits,registrations,paid,revenue_minor});
  }
  return {summary,days:rows};
}
