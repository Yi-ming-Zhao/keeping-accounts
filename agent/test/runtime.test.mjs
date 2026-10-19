import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs/promises';
import {randomUUID, randomBytes} from 'node:crypto';
const source=await fs.readFile('app/build/generated/agent/agent.js','utf8');
function runtime(respond, journal=new Map()) {
 let snapshot=[];
 const context=vm.createContext({hostUuid:randomUUID,hostRandom:n=>JSON.stringify([...randomBytes(n)]),
 hostRequest:async raw=>JSON.stringify(await respond(JSON.parse(raw))),
 hostSave:async raw=>{snapshot=JSON.parse(raw);return 'ok';},
 hostTool:async(name,args,id)=>{if(!journal.has(id)) journal.set(id,JSON.parse(args)); return JSON.stringify(journal.get(id));}});
 vm.runInContext(source,context);
 return {run:input=>context.LedgerAgent.run(input),snapshot:()=>snapshot,journal};
}
const end={choices:[{message:{content:'已保存'},finish_reason:'stop'}]};
const tool={choices:[{message:{tool_calls:[{id:'call_1',type:'function',function:{name:'record_probe',arguments:'{"id":"probe","amountMinor":1234,"label":"验证"}'}}]},finish_reason:'tool_calls'}]};
test('real Pi bundled without Node runs tool loop and saves transcript',async()=>{
 let n=0; const r=runtime(async request=>{assert.equal(request.tools.length,2);return n++===0?tool:end;});
 let raw; try { raw=await r.run({model:'test',prompt:'验证'}); } catch(e) { throw new Error(JSON.stringify(r.snapshot().at(-1))); } const output=JSON.parse(raw);
 assert.equal(output.rounds,2);assert.equal(r.journal.size,1,JSON.stringify(r.snapshot().filter(x=>x.role==='toolResult')));
 assert.deepEqual(r.snapshot().map(x=>x.role),['system','user','assistant','toolResult','assistant']);
});
test('new engine repairs interrupted tool call exactly once',async()=>{
 const r=runtime(async()=>end,new Map([['call_1',{id:'probe',amountMinor:1234,label:'验证'}]]));
 await r.run({model:'test',resume:true,messages:[{role:'user',content:'验证',timestamp:1},{role:'assistant',content:[{type:'toolCall',id:'call_1',name:'record_probe',arguments:{id:'probe',amountMinor:1234,label:'验证'}}],timestamp:2,stopReason:'toolUse'}]});
 assert.equal(r.journal.size,1);assert.equal(r.snapshot().filter(x=>x.role==='toolResult').length,1);
});
test('six round cap terminates runaway tools',async()=>{
 let n=0;const r=runtime(async()=>{n++;const value=structuredClone(tool);value.choices[0].message.tool_calls[0].id=`call_${n}`;return value;});
 await assert.rejects(()=>r.run({model:'test',prompt:'验证'}));assert.equal(n,6);
});
test('malformed tool arguments fail without invoking native tool',async()=>{
 const bad=structuredClone(tool);bad.choices[0].message.tool_calls[0].function.arguments='invalid';
 const r=runtime(async()=>bad);await assert.rejects(()=>r.run({model:'test',prompt:'验证'}));assert.equal(r.journal.size,0);
});
test('failed assistant tail does not hide an unfinished tool batch',async()=>{
 const r=runtime(async()=>end);
 await r.run({model:'test',resume:true,messages:[{role:'user',content:'验证',timestamp:1},{role:'assistant',content:[{type:'toolCall',id:'call_2',name:'record_probe',arguments:{id:'probe',amountMinor:1234,label:'验证'}}],timestamp:2,stopReason:'toolUse'},{role:'assistant',content:[],timestamp:3,stopReason:'error'}]});
 assert.equal(r.journal.size,1);assert.ok(r.snapshot().some(x=>x.role==='toolResult'&&x.toolCallId==='call_2'));
});

test('image reaches the model but never the persisted transcript',async()=>{
 let sawImage=false;
 const r=runtime(async request=>{sawImage=JSON.stringify(request).includes('c2FtcGxl');return end;});
 await r.run({model:'test',prompt:'识别金额',images:[{type:'image',mimeType:'image/png',data:'c2FtcGxl'}]});
 assert.equal(sawImage,true);assert.equal(JSON.stringify(r.snapshot()).includes('c2FtcGxl'),false);
});
