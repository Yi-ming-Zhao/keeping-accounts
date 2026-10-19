// Diagnostic only: this is not the Android gate.
import fs from 'node:fs/promises';
import vm from 'node:vm';
import {randomUUID,randomBytes} from 'node:crypto';
import {spawn} from 'node:child_process';
const cfg=JSON.parse(await fs.readFile('model-config.local.json','utf8'));
const key=(await fs.readFile('model-key.txt','utf8')).trim();
const records=new Map();let requests=0;let snapshot;
const context=vm.createContext({hostUuid:randomUUID,hostRandom:n=>JSON.stringify([...randomBytes(n)]),hostSave:async s=>{snapshot=JSON.parse(s);return 'ok';},
 hostTool:async(name,s)=>{const args=JSON.parse(s);if(name==='record_probe')records.set(args.id,args);return JSON.stringify(records.get(args.id));},
 hostRequest:async body=>{
  requests++;
  const payload={...JSON.parse(body),thinking:{type:'disabled'}};
  const configuration=`url = ${JSON.stringify(cfg.baseUrl+'/chat/completions')}\nheader = ${JSON.stringify('Authorization: Bearer '+key)}\nheader = "Content-Type: application/json"\ndata = ${JSON.stringify(JSON.stringify(payload))}\n`;
  return await new Promise((resolve,reject)=>{
   const p=spawn('curl',['--silent','--show-error','--max-time','90','--config','-']);
   let out='';p.stdout.on('data',x=>out+=x);p.stderr.resume();
   p.on('close',code=>code?reject(new Error(`curl exit ${code}`)):resolve(out));p.stdin.end(configuration);
  });
 }});
vm.runInContext(await fs.readFile('app/build/generated/agent/agent.js','utf8'),context);
try {
 await context.LedgerAgent.run({model:cfg.model,prompt:'调用 record_probe 保存 id=host_probe、amountMinor=1234、label=验证。成功后只回复已保存。'});
 if(records.get('host_probe')?.amountMinor!==1234)throw new Error('tool not called');
 console.log(JSON.stringify({model:cfg.model,requests,toolCalls:records.size,persistedRoles:snapshot.map(x=>x.role),status:'PASS (host only)'}));
} catch(e) { console.log('FAIL (host only): '+String(e.message).replaceAll(key,'[REDACTED]'));process.exitCode=1; }
