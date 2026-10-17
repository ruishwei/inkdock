'use strict';
const $=id=>document.getElementById(id);
let book={blocks:[],headings:[],chunks:[]}, page=0, current=0, size=20, saveTimer;
const bridge=window.Native;
for(const display of [true,false]) marked.use({extensions:[{name:display?'displayMath':'inlineMath',renderer(token){return katex.renderToString(token.text,{displayMode:token.display,throwOnError:false,trust:false,strict:'warn',maxExpand:1000});}}]});
// Escape raw HTML before it reaches the sanitizer, while allowing trusted KaTeX styles.
marked.use({renderer:{html(token){return token.text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}}});
function safeHtml(block){const tokens=[block.token];tokens.links=book.links;return DOMPurify.sanitize(marked.parser(tokens),{USE_PROFILES:{html:true,mathMl:true,svg:true},FORBID_TAGS:['img','iframe','form','input'],FORBID_ATTR:['id','name','href','src','xlink:href']});}
function render(target=0){
  if(!book.blocks.length){current=0;page=0;$('content').textContent='文档为空，请导入 UTF-8 Markdown 文件。';$('prev').disabled=true;$('next').disabled=true;$('status').textContent='0 段';return;}
  current=Math.max(0,Math.min(target,book.blocks.length-1));page=Math.floor(current/40);
  const range=book.chunks[page];$('content').replaceChildren();
  for(let i=range.start;i<range.end;i++){const section=document.createElement('section');section.id=book.blocks[i].id;section.dataset.index=i;section.innerHTML=safeHtml(book.blocks[i]);$('content').append(section);}
  $('prev').disabled=page===0;$('next').disabled=page===book.chunks.length-1;
  $('status').textContent=`${page+1} / ${book.chunks.length} 组 · ${book.blocks.length} 段`;
  requestAnimationFrame(()=>{document.getElementById(`p-${current}`)?.scrollIntoView();persist();});
}
function buildToc(){
  $('toc').replaceChildren();const stack=[{level:0,el:$('toc')}];
  for(const h of book.headings){while(stack.length>1&&stack.at(-1).level>=h.level)stack.pop();
    const details=document.createElement('details');details.open=h.level<=2;
    const summary=document.createElement('summary');summary.textContent=h.title;
    const button=document.createElement('button');button.textContent='阅读此节';button.onclick=()=>{closePanels();render(h.block);};
    details.append(summary,button);stack.at(-1).el.append(details);stack.push({level:h.level,el:details});
  }
  if(!book.headings.length)$('toc').textContent='此文档没有 # 标题。';
}
function persist(){if(bridge)bridge.saveProgress(current,size,document.body.classList.contains('dark'));}
window.loadDocument=(text,anchor=0,font=20,dark=false)=>{book=InkEngine.indexDocument(text);size=Math.max(14,Math.min(32,font));$('content').style.fontSize=`${size}px`;document.body.classList.toggle('dark',dark);buildToc();render(anchor);};
$('open').onclick=()=>bridge?.openDocument();$('tocButton').onclick=()=>openPanel('tocPanel');
$('prev').onclick=()=>render(Math.max(0,(page-1)*40));$('next').onclick=()=>render(Math.min(book.blocks.length-1,(page+1)*40));
$('smaller').onclick=()=>{size=Math.max(14,size-2);$('content').style.fontSize=`${size}px`;persist();};
$('larger').onclick=()=>{size=Math.min(32,size+2);$('content').style.fontSize=`${size}px`;persist();};
$('theme').onclick=()=>{document.body.classList.toggle('dark');persist();};
let speakSelection='';
$('speak').addEventListener('pointerdown',()=>{speakSelection=window.getSelection()?.toString()||'';});
$('speak').onclick=()=>{const selection=speakSelection||window.getSelection()?.toString();speakSelection='';bridge?.speak((selection||document.getElementById(`p-${current}`)?.innerText||'').slice(0,3500));};
$('stop').onclick=()=>bridge?.stopSpeaking();
window.addEventListener('scroll',()=>{clearTimeout(saveTimer);saveTimer=setTimeout(()=>{const sections=[...$('content').children];const visible=sections.find(el=>el.getBoundingClientRect().bottom>16);if(visible){current=Number(visible.dataset.index);persist();}},200);});
function showToolbar(visible){
 $('toolbar').classList.toggle('is-hidden',!visible);$('toolbar').inert=!visible;
 $('toolbar').setAttribute('aria-hidden',String(!visible));$('showTools').hidden=visible;
}
function closePanels(){for(const id of ['tocPanel','voicePanel'])$(id).hidden=true;}
function openPanel(id){closePanels();showToolbar(true);$(id).hidden=false;}
$('showTools').onclick=()=>showToolbar(true);
$('closeToc').onclick=closePanels;$('closeVoice').onclick=closePanels;
$('voiceSettings').onclick=()=>{openPanel('voicePanel');bridge?.getVoiceSettings();};
$('voiceRate').oninput=()=>{$('rateValue').textContent=Number($('voiceRate').value).toFixed(2)+'×';};
function saveVoice(preview){bridge?.saveVoiceSettings($('voiceChoice').value,Number($('voiceRate').value),preview);}
$('saveVoice').onclick=()=>saveVoice(false);$('previewVoice').onclick=()=>saveVoice(true);
$('voiceStop').onclick=()=>bridge?.stopSpeaking();
window.setVoiceSettings=state=>{
 $('voiceEngine').textContent='系统默认引擎：'+(state.engine||'不可用');
 $('voiceCurrent').textContent='当前语言 / 音色：'+(state.language||'未设置')+' / '+(state.current||'无可用音色');
 $('voiceChoice').replaceChildren();
 for(const voice of state.voices||[]){const option=document.createElement('option');option.value=voice.name;option.textContent=voice.language+' · '+voice.name;$('voiceChoice').append(option);}
 $('voiceChoice').value=state.current||'';
 const available=!!(state.voices?.length);$('voiceChoice').disabled=!available;$('saveVoice').disabled=!available;$('previewVoice').disabled=!available;
 $('voiceRate').value=state.rate||1;$('voiceRate').oninput();
 $('voiceStatus').textContent=state.message||(available?'仅列出引擎报告为已安装的离线中文音色。':'没有可用的离线中文音色，请在手机系统 TTS 设置中安装后重启应用。');
};
// Passive touch handling never preventDefault()s scrolling, selection or system navigation.
let gesture=null;
function hasSelection(){return !!window.getSelection()?.toString();}
function excludedTarget(target){return !!target.closest('button,a,input,select,textarea,summary,.panel,#toolbar,pre,code,table,.katex,.katex-display');}
$('content').addEventListener('touchstart',event=>{
 if(event.touches.length!==1){gesture=null;return;}
 const t=event.touches[0];gesture={x:t.clientX,y:t.clientY,at:performance.now(),blocked:excludedTarget(event.target),selected:hasSelection()};
},{passive:true});
$('content').addEventListener('touchmove',event=>{if(event.touches.length!==1)gesture=null;},{passive:true});
$('content').addEventListener('touchcancel',()=>{gesture=null;},{passive:true});
$('content').addEventListener('touchend',event=>{
 const start=gesture;gesture=null;if(!start||event.changedTouches.length!==1||event.touches.length)return;
 const t=event.changedTouches[0];const action=InkGestures.classify({dx:t.clientX-start.x,dy:t.clientY-start.y,duration:performance.now()-start.at,startX:start.x,width:innerWidth,blocked:start.blocked||!$('tocPanel').hidden||!$('voicePanel').hidden,selected:start.selected||hasSelection()});
 if(action==='show')showToolbar(true);if(action==='hide')showToolbar(false);if(action==='toc')openPanel('tocPanel');
},{passive:true});
function measureTools(){document.documentElement.style.setProperty('--tools-height',$('toolbar').offsetHeight+'px');}
new ResizeObserver(measureTools).observe($('toolbar'));measureTools();
document.addEventListener('keydown',e=>{if(e.key==='Escape'){closePanels();showToolbar(true);}});
if(bridge)bridge.ready();
else window.setVoiceSettings({engine:'浏览器预览（无 Android TTS）',voices:[],rate:1});
