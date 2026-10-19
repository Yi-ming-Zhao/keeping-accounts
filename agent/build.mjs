import { build } from 'esbuild';
import { mkdir } from 'node:fs/promises';
await mkdir('app/build/generated/agent', {recursive:true});
await build({entryPoints:['agent/src/runtime.ts'], outfile:'app/build/generated/agent/agent.js',
 bundle:true, format:'iife', globalName:'LedgerAgent', platform:'browser', target:'es2022',
 minify:true, sourcemap:false, metafile:true,
 define:{'process.env.NODE_ENV':'"production"'}}).then(async r=>{
 const fs=await import('node:fs/promises');
 await fs.writeFile('app/build/generated/agent/meta.json',JSON.stringify(r.metafile));
});
