// Read-only Chrome diagnostics, plus local JPEG fetches. Never uploads a frame.
const targets=await(await fetch('http://127.0.0.1:19225/json/list')).json();
const target=targets.find(t=>t.type==='page' && t.url.startsWith('https://blindza.com/external_camera/'));
if(!target) throw new Error('The external_camera PWA page is not open in Chrome.');
const ws=new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
let id=0;
const pending=new Map(),localRequests=new Set();
const promptTest=process.argv.includes('--prompt-test');
const bypassWorker=process.argv.includes('--bypass-worker') || promptTest;
ws.onmessage=({data})=>{
 const message=JSON.parse(data);
 if(message.id) { const p=pending.get(message.id); pending.delete(message.id); message.error?p?.reject(message.error):p?.resolve(message.result); return; }
 const p=message.params;
 if(message.method==='Network.requestWillBeSent' && /^http:\/\/(localhost|127\.0\.0\.1)/.test(p.request.url)) {localRequests.add(p.requestId);console.log('LOCAL_REQUEST',JSON.stringify({id:p.requestId,method:p.request.method,url:p.request.url,headers:p.request.headers,initiator:p.initiator.type}));}
 if(message.method==='Network.loadingFailed' && localRequests.has(p.requestId)) console.log('LOCAL_FAILURE',JSON.stringify(p));
 if(message.method==='Network.responseReceived' && localRequests.has(p.requestId)) console.log('LOCAL_RESPONSE',JSON.stringify({status:p.response.status,headers:p.response.headers,ip:p.response.remoteIPAddress,serviceWorker:p.response.fromServiceWorker}));
 if(message.method==='Log.entryAdded' && /localhost|CORS|local.network|MJPEG/i.test(p.entry.text)) console.log('BROWSER_LOG',JSON.stringify(p.entry));
 if(message.method==='Audits.issueAdded') console.log('BROWSER_ISSUE',JSON.stringify(p.issue));
};
const call=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,{resolve,reject});ws.send(JSON.stringify({id:key,method,params}));});
const evaluate=async expression=>{
 const response=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true,userGesture:true});
 console.log('RESULT',JSON.stringify(response));
};
try {
 await call('Runtime.enable');await call('Network.enable');await call('Log.enable');await call('Audits.enable');
 if(process.argv.includes('--reload')) { await call('Page.reload'); await new Promise(resolve=>setTimeout(resolve,3000)); }
 if(bypassWorker) { await call('Network.setBypassServiceWorker',{bypass:true}); console.log('SERVICE_WORKER_BYPASSED_FOR_TEST'); }
 console.log('BROWSER',JSON.stringify(await call('Browser.getVersion')));
 await evaluate(`(async()=>({origin:location.origin,secure:isSecureContext,worker:navigator.serviceWorker.controller?.scriptURL,diagnostics:document.getElementById('mjpeg_diagnostics')?.textContent,permission:await navigator.permissions.query({name:'local-network-access'}).then(p=>p.state).catch(e=>e.message)}))()`);
 for(const url of promptTest?['http://localhost:8080/snapshot']:['http://localhost:8080/snapshot','http://127.0.0.1:8080/snapshot']) {
  await evaluate(`(async()=>{try{const r=await fetch(${JSON.stringify(url)},{mode:'cors',cache:'no-store',signal:AbortSignal.timeout(${promptTest?60000:8000})});const b=await r.blob();const image=b.type==='image/jpeg'?await createImageBitmap(b):null;const result={url:${JSON.stringify(url)},status:r.status,type:b.type,bytes:b.size,width:image?.width,height:image?.height};image?.close();return result;}catch(e){return {url:${JSON.stringify(url)},error:e.name,message:e.message};}})()`);
 }
} finally {if(bypassWorker) await call('Network.setBypassServiceWorker',{bypass:false});ws.close();}
