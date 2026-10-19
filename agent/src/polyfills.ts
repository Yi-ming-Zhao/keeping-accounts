import URLPolyfill from 'core-js-pure/features/url/index.js';
// Only the small host surface used by Pi; no filesystem, eval tools or arbitrary network.
const g = globalThis as any;
if (!g.AbortController) {
 class Signal {
  aborted=false; reason:any; listeners=new Set<Function>();
  addEventListener(type:string, fn:Function) { if(type==='abort') this.listeners.add(fn); }
  removeEventListener(type:string, fn:Function) { if(type==='abort') this.listeners.delete(fn); }
  throwIfAborted() { if(this.aborted) throw this.reason; }
 }
 g.AbortController=class {
  signal=new Signal();
  abort(reason:any=new Error('已取消')) {
   if(this.signal.aborted) return;
   this.signal.aborted=true; this.signal.reason=reason;
   for(const fn of this.signal.listeners) fn({type:'abort'});
  }
 };
}
if (!g.crypto) g.crypto={getRandomValues:(a:Uint8Array)=>{
 const values=JSON.parse(g.hostRandom(a.length)); a.set(values); return a;
}, randomUUID:()=>g.hostUuid()};
// Pi tool arguments are JSON values. Reject unsupported values instead of silently losing data.
if (!g.structuredClone) g.structuredClone=(value:any)=>JSON.parse(JSON.stringify(value));
if (!g.TextEncoder) g.TextEncoder=class {
 encoding='utf-8';
 encode(value='') {
  const bytes:number[]=[];
  for(const char of String(value)) {
   let cp=char.codePointAt(0)!;
   if(cp>=0xD800&&cp<=0xDFFF) cp=0xFFFD;
   if(cp<0x80) bytes.push(cp);
   else if(cp<0x800) bytes.push(0xC0|(cp>>6),0x80|(cp&63));
   else if(cp<0x10000) bytes.push(0xE0|(cp>>12),0x80|((cp>>6)&63),0x80|(cp&63));
   else bytes.push(0xF0|(cp>>18),0x80|((cp>>12)&63),0x80|((cp>>6)&63),0x80|(cp&63));
  }
  return new Uint8Array(bytes);
 }
};

if (!g.URL) g.URL=URLPolyfill;
if (!g.performance) g.performance={now:()=>g.hostNow?g.hostNow():Date.now()};
