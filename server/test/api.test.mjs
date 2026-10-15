import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../index.mjs';
import { createDb } from '../db.mjs';

async function fixture(){
  const db=createDb(':memory:');
  const cfg={publicBase:'http://127.0.0.1:3080',analyticsSecret:'test-secret-abc123',googleId:'',googleSecret:'',vkId:'',resendKey:'',emailFrom:'',services:{basic:29900}};
  const server=createServer(db,cfg);
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  async function request(path,method='GET',body,token){
    const response=await fetch(base+path,{method,headers:{
      ...(body?{'content-type':'application/json'}:{}),
      ...(token?{authorization:'Bearer '+token}:{})
    },body:body?JSON.stringify(body):undefined});
    return {status:response.status, data:await response.json()};
  }
  async function close(){await new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve()));db.close();}
  return {db,server,request,close};
}

test('registration, sessions, admin role, visits, purchases and reset security',async()=>{
  const f=await fixture();
  try{
    let x=await f.request('/api/health');assert.equal(x.status,200);assert.equal(x.data.ok,true);
    x=await f.request('/api/auth/register','POST',{email:'person@example.org',password:'SecretPassword2026'});
    assert.equal(x.status,201);assert.ok(x.data.token);assert.equal(x.data.user.role,'user');
    const token=x.data.token;
    x=await f.request('/api/auth/register','POST',{email:'person@example.org',password:'SecretPassword2026'});
    assert.equal(x.status,409);
    x=await f.request('/api/auth/login','POST',{email:'person@example.org',password:'wrongpassword22'});
    assert.equal(x.status,401);
    x=await f.request('/api/auth/login','POST',{email:'person@example.org',password:'SecretPassword2026'});
    assert.equal(x.status,200);assert.ok(x.data.token);
    x=await f.request('/api/auth/me','GET',null,token);assert.equal(x.data.user.email,'person@example.org');
    x=await f.request('/api/admin/stats','GET',null,token);assert.equal(x.status,403);
    x=await f.request('/api/orders','POST',{service_id:'basic'},token);assert.equal(x.status,201);
    const order=x.data.order.id;assert.equal(x.data.order.status,'pending');
    x=await f.request('/api/admin/orders/'+order+'/confirm','POST',{},token);assert.equal(x.status,403);
    x=await f.request('/api/track/visit','POST',{visitor_id:'visitor_1234567890'});
    assert.equal(x.status,200);
    x=await f.request('/api/track/visit','POST',{visitor_id:'visitor_1234567890'});
    assert.equal(x.status,200);
    f.db.prepare("UPDATE users SET role='admin' WHERE email='person@example.org'").run();
    x=await f.request('/api/admin/stats?days=3','GET',null,token);
    assert.equal(x.status,200);assert.equal(x.data.summary.visits,1);
    assert.equal(x.data.summary.registrations,1);
    assert.equal(x.data.summary.paid_orders,0);
    x=await f.request('/api/admin/orders/'+order+'/confirm','POST',{},token);
    assert.equal(x.status,200);
    x=await f.request('/api/admin/orders/'+order+'/confirm','POST',{},token);
    assert.equal(x.status,404);
    x=await f.request('/api/admin/stats?days=3','GET',null,token);
    assert.equal(x.status,200);
    assert.equal(x.data.summary.paid_orders,1);
    assert.equal(x.data.summary.buyers,1);
    assert.equal(x.data.summary.revenue_minor,29900);
    x=await f.request('/api/admin/users?search=person','GET',null,token);
    assert.equal(x.status,200);assert.equal(x.data.users.length,1);
    assert.ok(!JSON.stringify(x.data).includes('password_hash'));
    x=await f.request('/api/auth/reset/request','POST',{email:'unknown@example.org'});
    assert.equal(x.status,200);
    x=await f.request('/api/auth/reset/confirm','POST',{token:'wrong',password:'SecretPassword2027'});
    assert.equal(x.status,400);
    x=await f.request('/api/auth/logout','POST',{},token);assert.equal(x.status,200);
    x=await f.request('/api/auth/me','GET',null,token);assert.equal(x.status,401);
  }finally{await f.close();}
});

test('the API does not expose filesystem and refuses inactive OAuth providers',async()=>{
  const f=await fixture();try{
    let x=await f.request('/api/auth/oauth/google/start?mode=app&challenge=abc');assert.equal(x.status,503);
    x=await f.request('/api/auth/oauth/vk/start?mode=app&challenge=abc');assert.equal(x.status,503);
    x=await f.request('/api/nonexistent');assert.equal(x.status,404);
    x=await f.request('/server/.env.example');assert.equal(x.status,404);
    x=await f.request('/api/track/visit','POST',{visitor_id:'short'});assert.equal(x.status,400);
  }finally{await f.close();}
});
