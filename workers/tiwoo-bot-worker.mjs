import http from 'node:http';

const BOT_HANDLE=String(process.env.BOT_HANDLE||'').trim().toLowerCase();
const SUPABASE_URL=String(process.env.SUPABASE_URL||'').replace(/\/$/,'');
const WORKER_SECRET=String(process.env.TIWOO_AI_WORKER_SECRET||'').trim();
const POLL_MS=Math.max(2000,Math.min(Number(process.env.WORKER_POLL_MS)||5000,60000));
const PORT=Math.max(1,Number(process.env.PORT)||10000);
const INSTANCE_ID=String(process.env.RENDER_INSTANCE_ID||process.env.HOSTNAME||crypto.randomUUID()).slice(0,120);

if(!['@luna','@lina'].includes(BOT_HANDLE)) throw new Error('BOT_HANDLE must be @luna or @lina');
if(!SUPABASE_URL) throw new Error('SUPABASE_URL is required');
if(!WORKER_SECRET) throw new Error('TIWOO_AI_WORKER_SECRET is required');

let stopped=false;
let running=false;
let lastOkAt=0;
let lastResult='starting';
let failures=0;

const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const compact=(value,max=240)=>String(value??'').replace(/\s+/g,' ').trim().slice(0,max);

async function cycle(){
  if(running||stopped)return;
  running=true;
  try{
    const response=await fetch(`${SUPABASE_URL}/functions/v1/tiwoo-ai-dispatch-worktest`,{
      method:'POST',
      headers:{'content-type':'application/json','x-tiwoo-worker-secret':WORKER_SECRET},
      body:JSON.stringify({botHandle:BOT_HANDLE,instanceId:INSTANCE_ID}),
      signal:AbortSignal.timeout(120000)
    });
    const data=await response.json().catch(()=>({}));
    if(!response.ok)throw new Error(`cycle_${response.status}_${compact(data?.error||'failed',120)}`);
    lastOkAt=Date.now();
    failures=0;
    lastResult=compact(JSON.stringify({kind:data?.kind||'idle',eventId:data?.eventId||null,postId:data?.postId||null,action:data?.action||null}),220);
    if(data?.kind&&data.kind!=='idle')console.log('worker_cycle',BOT_HANDLE,lastResult);
  }catch(error){
    failures+=1;
    lastResult=compact(error?.message||error,220);
    console.error('worker_cycle_failed',BOT_HANDLE,lastResult);
  }finally{
    running=false;
  }
}

async function loop(){
  console.log('worker_started',BOT_HANDLE,{pollMs:POLL_MS,instanceId:INSTANCE_ID});
  while(!stopped){
    await cycle();
    const backoff=failures?Math.min(POLL_MS*Math.max(1,failures),30000):POLL_MS;
    await sleep(backoff);
  }
}

const server=http.createServer((req,res)=>{
  if(req.url==='/healthz'||req.url==='/'){
    const healthy=lastOkAt===0||Date.now()-lastOkAt<Math.max(POLL_MS*12,120000);
    res.writeHead(healthy?200:503,{'content-type':'application/json','cache-control':'no-store'});
    res.end(JSON.stringify({ok:healthy,botHandle:BOT_HANDLE,running,lastOkAt:lastOkAt?new Date(lastOkAt).toISOString():null,lastResult}));
    return;
  }
  res.writeHead(404,{'content-type':'application/json'});
  res.end(JSON.stringify({ok:false,error:'not_found'}));
});

server.listen(PORT,'0.0.0.0',()=>console.log('health_server_listening',BOT_HANDLE,PORT));
for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{stopped=true;server.close(()=>process.exit(0));setTimeout(()=>process.exit(0),5000).unref();});
loop().catch(error=>{console.error('worker_fatal',compact(error?.stack||error,1000));process.exit(1);});
