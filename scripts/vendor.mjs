import {cp,copyFile,mkdir} from 'node:fs/promises';
const dest='app/src/main/assets/vendor';
await mkdir(dest,{recursive:true});
for(const [src,name] of [['marked/lib/marked.umd.js','marked.js'],['dompurify/dist/purify.min.js','purify.js'],['katex/dist/katex.min.js','katex.js'],['katex/dist/katex.min.css','katex.css'],['katex/LICENSE','LICENSE-katex'],['marked/LICENSE.md','LICENSE-marked'],['dompurify/LICENSE','LICENSE-dompurify']]) await copyFile(`node_modules/${src}`,`${dest}/${name}`);
await cp('node_modules/katex/dist/fonts',`${dest}/fonts`,{recursive:true});
