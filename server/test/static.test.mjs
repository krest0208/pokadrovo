import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from '../index.mjs';
import {createDb} from '../db.mjs';

test('account, admin and reset pages are served with local-only scripts',async()=>{
  const db=createDb(':memory:');
  const cfg={publicBase:'http://localhost:3080',analyticsSecret:'integration-test-secret',googleId:'',googleSecret:'',vkId:'',resendKey:'',emailFrom:'',services:{}};
  const server=createServer(db,cfg);
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const url=`http://127.0.0.1:${server.address().port}`;
  try{
    for(const path of ['/','/auth.html','/admin.html','/reset.html','/app.js','/styles.css']){
      const result=await fetch(url+path);
      assert.equal(result.status,200,`Expected live page ${path}`);
      const body=await result.text();
      assert.ok(body.length>50,`Page empty: ${path}`);
      assert.equal(result.headers.get('x-content-type-options'),'nosniff');
    }
    const traversal=await fetch(url+'/server/db.mjs');assert.equal(traversal.status,404);
  }finally{await new Promise(resolve=>server.close(resolve));db.close();}
});
