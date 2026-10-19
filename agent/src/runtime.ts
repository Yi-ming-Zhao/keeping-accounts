import './polyfills';
import { Agent } from '@earendil-works/pi-agent-core';
import { Type } from 'typebox';

declare const hostRequest: (body:string)=>Promise<string>;
declare const hostTool: (name:string, args:string, callId:string)=>Promise<string>;
declare const hostSave: (messages:string)=>Promise<string>;
const zeroUsage={input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,
 cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}};

export function toWire(messages:any[], system:string):any[] {
 const output:any[]=[{role:'system',content:system}];
 for(const m of messages) {
  if(m.role==='user') output.push({role:'user',content:typeof m.content==='string'?m.content:m.content.map((x:any)=>x.type==='text'?{type:'text',text:x.text}:{type:'image_url',image_url:{url:`data:${x.mimeType};base64,${x.data}`}})});
  if(m.role==='assistant') {
   if(m.stopReason==='error'||m.stopReason==='aborted') continue;
   const toolCalls=m.content.filter((x:any)=>x.type==='toolCall');
   output.push({role:'assistant',content:m.content.filter((x:any)=>x.type==='text').map((x:any)=>x.text).join('')||null,
    ...(toolCalls.length?{tool_calls:toolCalls.map((x:any)=>({id:x.id,type:'function',function:{name:x.name,arguments:JSON.stringify(x.arguments)}}))}:{} )});
  }
  if(m.role==='toolResult') output.push({role:'tool',tool_call_id:m.toolCallId,content:m.content.map((x:any)=>x.text||'').join('\n')});
 }
 return output;
}
export async function run(input:any) {
 const model:any={id:input.model,name:input.model,api:'openai-completions',provider:'custom',baseUrl:'',
  reasoning:false,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},contextWindow:32000,maxTokens:4096};
 let rounds=0;
 let saving:Promise<any>=Promise.resolve();
 const tools=(input.tools||[
  {name:'record_probe',description:'保存验证记录。幂等：同一个 id 只保存一次。',parameters:Type.Object({id:Type.String(),amountMinor:Type.Integer(),label:Type.String()})},
  {name:'read_probe',description:'读取已经保存的验证记录。',parameters:Type.Object({id:Type.String()})}
 ]).map((t:any)=>({...t,label:t.name,executionMode:'sequential',
  execute:async(id:string,args:any)=>{
   await saving; // Commit the pending assistant call before a native write can start.
   const result=await hostTool(t.name,JSON.stringify(args),id);
   return {content:[{type:'text',text:result}],details:{}};
  }}));
 const system=input.system||'你在验证手机端记账运行环境。只使用提供的工具。按照用户指定的数据执行，不能虚构工具结果。记录金额的单位为分。';
 const agent=new Agent({initialState:{systemPrompt:system,model,tools},toolExecution:'sequential',
  transformContext:async(messages:any[])=>{const users=messages.map((m,i)=>m.role==='user'?i:-1).filter(i=>i>=0);const start=users.at(-3)??users[0]??1;return messages.slice(start);},
  streamFn:async(_model:any,context:any,options:any)=>{
   if(++rounds>6) throw new Error('达到六轮上限');
   options?.signal?.throwIfAborted();
   const raw=JSON.parse(await hostRequest(JSON.stringify({model:input.model,
    messages:toWire(context.messages,system),tools:tools.map((t:any)=>({type:'function',function:{name:t.name,description:t.description,parameters:t.parameters}})),
    tool_choice:'auto',stream:false,max_tokens:4096,temperature:0.1})));
   const c=raw.choices?.[0]; if(!c?.message) throw new Error('模型返回格式错误');
   const content:any[]=[];
   if(c.message.content) content.push({type:'text',text:c.message.content});
   for(const t of c.message.tool_calls||[]) content.push({type:'toolCall',id:t.id,name:t.function.name,arguments:JSON.parse(t.function.arguments)});
   const message:any={role:'assistant',content,api:model.api,provider:model.provider,model:model.id,
    usage:zeroUsage,stopReason:c.message.tool_calls?.length?'toolUse':c.finish_reason==='length'?'length':'stop',timestamp:Date.now()};
   return {async *[Symbol.asyncIterator](){yield {type:'done',reason:message.stopReason,message};},result:async()=>message} as any;
  }});
 if(input.messages?.length) agent.state.messages=input.messages;
 agent.subscribe((event:any)=>{
  if(event.type==='message_end'||event.type==='agent_end') {
   const snapshot=JSON.stringify(agent.state.messages,(_key,value)=>value?.type==='image'?{type:'text',text:'[图片不持久化，恢复时使用文字证据]'}:value);
   saving=saving.then(()=>hostSave(snapshot));
  }
 });
 try {
 // Repair an interrupted tool batch. Native tools journal call IDs atomically with writes.
 if(input.resume) {
  while ((agent.state.messages.at(-1) as any)?.role==='assistant' && ['error','aborted'].includes((agent.state.messages.at(-1) as any).stopReason)) agent.state.messages=agent.state.messages.slice(0,-1);
  const messages=agent.state.messages as any[];
  let lastAssistant=-1;
  for(let i=messages.length-1;i>=0;i--) if(messages[i].role==='assistant'){lastAssistant=i;break;}
  if(lastAssistant>=0) {
   const finished=new Set(messages.slice(lastAssistant+1).filter(m=>m.role==='toolResult').map(m=>m.toolCallId));
   for(const call of messages[lastAssistant].content.filter((c:any)=>c.type==='toolCall')) {
    if(finished.has(call.id)) continue;
    const tool=tools.find((t:any)=>t.name===call.name);
    if(!tool) throw new Error('恢复任务包含未知工具');
    const result=await tool.execute(call.id,call.arguments);
    messages.push({role:'toolResult',toolCallId:call.id,toolName:call.name,content:result.content,isError:false,timestamp:Date.now()});
    await hostSave(JSON.stringify(messages));
   }
  }
  const tail=agent.state.messages.at(-1) as any;
  if(tail?.role==='assistant'&&tail.stopReason==='stop') return JSON.stringify({rounds:0,messages:agent.state.messages});
  if(tail?.role==='assistant'&&(tail.stopReason==='error'||tail.stopReason==='aborted')) agent.state.messages=agent.state.messages.slice(0,-1);
  await agent.continue();
 } else await agent.prompt(input.prompt,input.images);
 } finally { await saving; }
 const failure=(agent.state.messages as any[]).findLast(m=>m.role==='assistant');
 if(failure?.stopReason==='error'||failure?.stopReason==='aborted') throw new Error('模型运行未完成');
 return JSON.stringify({rounds,messages:agent.state.messages});
}
