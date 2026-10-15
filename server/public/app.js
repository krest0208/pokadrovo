'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const page = document.body.dataset.page;
  const token = () => sessionStorage.getItem('pokadrovo_access_token') || '';
  const setNotice = (message, error=false) => {const element=$('notice');if(element){element.textContent=message;element.className='notice '+(error?'error':'success');}};
  async function api(path, {method='GET',data=null,auth=true}={}) {
    const headers={'accept':'application/json'};
    if(data!==null)headers['content-type']='application/json';
    if(auth&&token())headers.authorization='Bearer '+token();
    let response;
    try{response=await fetch(path,{method,headers,body:data===null?undefined:JSON.stringify(data),cache:'no-store'});}
    catch{throw new Error('Нет соединения с сервером');}
    let result;
    try{result=await response.json();}catch{throw new Error('Сервер вернул некорректный ответ');}
    if(!response.ok)throw new Error(result.error||`Ошибка ${response.status}`);
    return result;
  }
  function displayTable(container, columns, rows, action) {
    const table=document.createElement('table'),head=document.createElement('thead'),tr=document.createElement('tr');
    for(const [_,label] of columns){const th=document.createElement('th');th.textContent=label;tr.append(th);}if(action){const th=document.createElement('th');th.textContent='Действие';tr.append(th);}head.append(tr);table.append(head);
    const body=document.createElement('tbody');
    for(const row of rows){const line=document.createElement('tr');for(const [key] of columns){const cell=document.createElement('td');cell.textContent=String(row[key]??'—');line.append(cell);}if(action){const cell=document.createElement('td');const button=document.createElement('button');button.textContent='Подтвердить';button.disabled=row.status!=='pending';button.addEventListener('click',()=>action(row));cell.append(button);line.append(cell);}body.append(line);}table.append(body);
    container.replaceChildren(table);
  }
  async function trackVisit() {
    let id=localStorage.getItem('pokadrovo_visit_id');
    if(!id||!/^[a-zA-Z0-9_-]{16,100}$/.test(id)){id=crypto.randomUUID().replace(/-/g,'');localStorage.setItem('pokadrovo_visit_id',id);}
    try{await api('/api/track/visit',{method:'POST',auth:false,data:{visitor_id:id}});}catch{}
  }
  async function oauth(provider){
    try{const r=await api('/api/auth/oauth/'+provider+'/start',{auth:false});window.location.assign(r.url);}catch(e){setNotice(e.message,true);}
  }
  function authPage(){
    const showProfile=async()=>{
      try{
        const {user}=await api('/api/auth/me');$('auth-box').classList.add('hidden');$('profile-box').classList.remove('hidden');
        $('profile').textContent=`${user.email} · ${user.role==='admin'?'администратор':'пользователь'}`;
        const {orders}=await api('/api/orders');
        displayTable($('orders'),[['service_id','Услуга'],['price_minor','Цена (мин. ед.)'],['status','Статус']],orders);
      }catch(e){$('auth-box').classList.remove('hidden');$('profile-box').classList.add('hidden');setNotice(e.message,true);}
    };
    const login=async(register)=>{
      const email=$('email').value.trim(),password=$('password').value;
      if(password.length<10){setNotice('Пароль должен содержать минимум 10 символов',true);return;}
      try{const result=await api('/api/auth/'+(register?'register':'login'),{method:'POST',data:{email,password},auth:false});
        sessionStorage.setItem('pokadrovo_access_token',result.token);$('password').value='';setNotice(register?'Аккаунт создан':'Вы вошли');await showProfile();
      }catch(e){setNotice(e.message,true);}
    };
    $('login').addEventListener('click',()=>login(false));$('register').addEventListener('click',()=>login(true));
    $('google').addEventListener('click',()=>oauth('google'));$('vk').addEventListener('click',()=>oauth('vk'));
    $('reset-request').addEventListener('click',async()=>{try{await api('/api/auth/reset/request',{method:'POST',auth:false,data:{email:$('email').value.trim()}});setNotice('Если адрес существует и почта настроена, письмо отправлено.');}catch(e){setNotice(e.message,true);}});
    $('logout').addEventListener('click',async()=>{try{await api('/api/auth/logout',{method:'POST',data:{}});}catch{}sessionStorage.removeItem('pokadrovo_access_token');$('profile-box').classList.add('hidden');$('auth-box').classList.remove('hidden');setNotice('Вы вышли');});
    $('refresh').addEventListener('click',showProfile);
    $('buy').addEventListener('click',async()=>{if(!$('service').value){setNotice('Выберите услугу',true);return;}try{await api('/api/orders',{method:'POST',data:{service_id:$('service').value}});setNotice('Заказ создан, требуется подтверждение оплаты');await showProfile();}catch(e){setNotice(e.message,true);}});
    api('/api/config',{auth:false}).then(r=>{for(const [key,value] of Object.entries(r.services||{})){const opt=document.createElement('option');opt.value=key;opt.textContent=`${key} — ${value} (мин. ед.)`;$('service').append(opt);}for(const provider of ['google','vk']){$(provider).disabled=!r[provider];}}).catch(()=>{});
    const oauthCode=location.hash.match(/^#code=([^&]+)/);
    if(oauthCode){location.hash='';api('/api/auth/oauth/exchange',{method:'POST',auth:false,data:{code:decodeURIComponent(oauthCode[1])}}).then(r=>{sessionStorage.setItem('pokadrovo_access_token',r.token);setNotice('Вход выполнен');showProfile();}).catch(e=>setNotice(e.message,true));}
    else if(token())showProfile();
  }
  function lineGraph(id,rows,key,color){
    const canvas=$(id);if(!canvas)return;
    const ctx=canvas.getContext('2d'),scale=window.devicePixelRatio||1;
    const width=Math.max(260,Math.round(canvas.getBoundingClientRect().width));canvas.width=width*scale;canvas.height=170*scale;ctx.scale(scale,scale);
    const pad=26,bottom=145,top=18,right=width-17,max=Math.max(1,...rows.map(d=>Number(d[key])||0));
    ctx.font='11px system-ui';ctx.textBaseline='middle';ctx.strokeStyle='#414a62';ctx.fillStyle='#b7c3dc';
    for(let i=0;i<5;i++){const y=top+(bottom-top)*i/4;ctx.beginPath();ctx.moveTo(pad,y);ctx.lineTo(right,y);ctx.stroke();ctx.fillText(String(Math.round(max*(4-i)/4)),4,y);}
    ctx.strokeStyle=color;ctx.lineWidth=3;ctx.beginPath();rows.forEach((point,index)=>{const x=pad+(right-pad)*index/Math.max(1,rows.length-1),y=bottom-(bottom-top)*(Number(point[key])||0)/max;if(index===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);});ctx.stroke();
    if(rows.length){ctx.fillText(rows[0].date,pad,160);ctx.fillText(rows[rows.length-1].date,right-70,160);}
  }
  async function adminPage(){
    if(!token()){setNotice('Сначала войдите в аккаунт администратора на странице «Мой аккаунт».',true);return;}
    try{
      const data=await api('/api/admin/stats?days=30');$('dashboard').classList.remove('hidden');setNotice('Статистика загружена с сервера');
      const cards=[['visits','Посещений'],['registrations','Регистраций'],['buyers','Покупателей'],['paid_orders','Оплачено заказов'],['revenue_minor','Выручка (мин. ед.)']];
      $('summary').replaceChildren(...cards.map(([field,title])=>{const d=document.createElement('div');d.className='metric';const b=document.createElement('b'),s=document.createElement('span');b.textContent=String(data.summary[field]??0);s.textContent=title;d.append(b,s);return d;}));
      const rows=data.days||[];
      lineGraph('graph-visits',rows,'visits','#947eff');lineGraph('graph-signups',rows,'registrations','#63bde7');lineGraph('graph-orders',rows,'paid','#f3a36e');lineGraph('graph-revenue',rows,'revenue_minor','#68d4a8');
      let currentPage=1;
      const loadUsers=async()=>{const data=await api('/api/admin/users?page='+currentPage+'&search='+encodeURIComponent($('search').value.trim()));displayTable($('users'),[['email','Почта'],['role','Роль'],['email_verified','Email подтверждён'],['providers','Вход'],['purchases','Покупки']],data.users);$('pagination').textContent=`Страница ${data.page} · всего ${data.total}`;};
      const loadOrders=async()=>{const r=await api('/api/admin/orders');displayTable($('admin-orders'),[['email','Клиент'],['service_id','Услуга'],['price_minor','Цена'],['status','Оплата'],['created_at','Создан']],r.orders,async item=>{
        if(!window.confirm('Подтвердить ручную оплату заказа '+item.id+'?'))return;
        try{await api('/api/admin/orders/'+encodeURIComponent(item.id)+'/confirm',{method:'POST',data:{}});setNotice('Оплата подтверждена');await loadOrders();}catch(e){setNotice(e.message,true);}
      });};
      $('find').addEventListener('click',()=>{currentPage=1;loadUsers().catch(e=>setNotice(e.message,true));});
      $('prev').addEventListener('click',()=>{currentPage=Math.max(1,currentPage-1);loadUsers().catch(e=>setNotice(e.message,true));});
      $('next').addEventListener('click',()=>{currentPage++;loadUsers().catch(e=>setNotice(e.message,true));});
      await Promise.all([loadUsers(),loadOrders()]);
    }catch(e){setNotice(e.message,true);$('dashboard').classList.add('hidden');}
  }
  if(page==='home')trackVisit();
  if(page==='auth'){trackVisit();authPage();}
  if(page==='admin'){trackVisit();adminPage();}
  if(page==='reset'){$('reset-confirm').addEventListener('click',async()=>{try{await api('/api/auth/reset/confirm',{method:'POST',data:{token:new URLSearchParams(location.search).get('token'),password:$('password').value},auth:false});setNotice('Пароль изменён. Вернитесь на страницу входа.');}catch(e){setNotice(e.message,true);}});}
})();
