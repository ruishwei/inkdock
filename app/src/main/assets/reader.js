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
 $('toc').replaceChildren();const roots=[],stack=[];
 for(const h of book.headings){const node={...h,children:[]};while(stack.length&&stack.at(-1).level>=h.level)stack.pop();(stack.length?stack.at(-1).children:roots).push(node);stack.push(node);}
 function append(nodes,parent){for(const h of nodes){
  const wrapper=document.createElement('div'),row=document.createElement('div');row.className='toc-row';
  const title=document.createElement('button');title.className='toc-title';title.textContent=h.title;title.onclick=()=>{closePanels();render(h.block);};
  if(h.children.length){const arrow=document.createElement('button'),children=document.createElement('div');arrow.className='toc-toggle';children.className='toc-children';children.id='toc-children-'+h.block;children.hidden=h.level>2;
   function update(){arrow.textContent=children.hidden?'›':'⌄';arrow.setAttribute('aria-expanded',String(!children.hidden));arrow.setAttribute('aria-label',(children.hidden?'展开':'收起')+h.title);}
   arrow.setAttribute('aria-controls',children.id);arrow.onclick=()=>{children.hidden=!children.hidden;update();};update();row.append(arrow,title);wrapper.append(row,children);append(h.children,children);
  }else{const spacer=document.createElement('span');spacer.className='toc-spacer';row.append(spacer,title);wrapper.append(row);}
  parent.append(wrapper);
 }}
 append(roots,$('toc'));if(!book.headings.length)$('toc').textContent='此文档没有标题。';
}
function persist(){if(bridge)bridge.saveProgress(current,size,document.body.classList.contains('dark'));}
window.loadDocument=(text,anchor=0,font=20,dark=false)=>{book=InkEngine.indexDocument(text);size=Math.max(14,Math.min(32,font));$('content').style.fontSize=`${size}px`;document.body.classList.toggle('dark',dark);$('theme').textContent=dark?'日间模式':'夜间模式';buildToc();render(anchor);};
$('open').onclick=()=>bridge?.openDocument();$('tocButton').onclick=()=>openPanel('tocPanel');
$('prev').onclick=()=>render(Math.max(0,(page-1)*40));$('next').onclick=()=>render(Math.min(book.blocks.length-1,(page+1)*40));
$('smaller').onclick=()=>{size=Math.max(14,size-2);$('content').style.fontSize=`${size}px`;persist();};
$('larger').onclick=()=>{size=Math.min(32,size+2);$('content').style.fontSize=`${size}px`;persist();};
$('theme').onclick=()=>{document.body.classList.toggle('dark');$('theme').textContent=document.body.classList.contains('dark')?'日间模式':'夜间模式';persist();};
let speakSelection='';
$('speak').addEventListener('pointerdown',()=>{speakSelection=window.getSelection()?.toString()||'';});
$('speak').onclick=()=>{if(playing){bridge?.stopSpeaking();return;}const selection=speakSelection||window.getSelection()?.toString();speakSelection='';bridge?.speak((selection||document.getElementById(`p-${current}`)?.innerText||'').slice(0,3500));};
let playing=false;
window.setPlaybackState=active=>{playing=!!active;$('playIcon').toggleAttribute('hidden',playing);$('stopIcon').toggleAttribute('hidden',!playing);$('speak').setAttribute('aria-label',playing?'停止朗读':'朗读当前段落或所选文字');$('speak').title=playing?'停止朗读':'朗读';};
window.addEventListener('scroll',()=>{clearTimeout(saveTimer);saveTimer=setTimeout(()=>{const sections=[...$('content').children];const visible=sections.find(el=>el.getBoundingClientRect().bottom>16);if(visible){current=Number(visible.dataset.index);persist();}},200);});
function showToolbar(visible){
 $('toolbar').classList.toggle('is-hidden',!visible);$('toolbar').inert=!visible;
 $('toolbar').setAttribute('aria-hidden',String(!visible));
}
function closePanels(){for(const id of ['tocPanel','voicePanel','morePanel'])$(id).hidden=true;}
function openPanel(id){cancelTap();closePanels();showToolbar(true);$(id).hidden=false;}
$('moreButton').onclick=()=>openPanel('morePanel');$('closeMore').onclick=closePanels;
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
// Only a stationary, short tap toggles tools. Swipes never trigger actions.
let tap=null,tapTimer,lastScroll=-Infinity,lastTap=-Infinity;
function hasSelection(){return !!window.getSelection()?.toString();}
function excludedTarget(target){return !!target.closest('button,a,input,select,textarea,summary,.panel,#toolbar,pre,code,table,.katex,.katex-display');}
function cancelTap(){tap=null;clearTimeout(tapTimer);}
window.addEventListener('scroll',()=>{lastScroll=performance.now();cancelTap();},{capture:true,passive:true});
$('content').addEventListener('pointerdown',event=>{
 clearTimeout(tapTimer);const now=performance.now();
 if(!event.isPrimary||event.button!==0){cancelTap();return;}
 tap={id:event.pointerId,x:event.clientX,y:event.clientY,at:now,distance:0,blocked:excludedTarget(event.target)||now-lastTap<350,selected:hasSelection()};
});
$('content').addEventListener('pointermove',event=>{if(tap&&tap.id===event.pointerId)tap.distance=Math.max(tap.distance,Math.hypot(event.clientX-tap.x,event.clientY-tap.y));},{passive:true});
$('content').addEventListener('pointercancel',cancelTap,{passive:true});
$('content').addEventListener('touchstart',event=>{if(event.touches.length>1)cancelTap();},{passive:true});
$('content').addEventListener('pointerup',event=>{
 const start=tap;tap=null;if(!start||start.id!==event.pointerId)return;
 const now=performance.now();lastTap=now;
 const accepted=InkGestures.isTap({distance:Math.max(start.distance,Math.hypot(event.clientX-start.x,event.clientY-start.y)),duration:now-start.at,sinceScroll:now-lastScroll,blocked:start.blocked||['tocPanel','voicePanel','morePanel'].some(id=>!$(id).hidden),selected:start.selected||hasSelection()});
 if(accepted)tapTimer=setTimeout(()=>{if(!hasSelection()&&performance.now()-lastScroll>=450)showToolbar($('toolbar').classList.contains('is-hidden'));},320);
});
$('content').addEventListener('keydown',event=>{if(event.key==='Enter'&&event.target===$('content')&&!hasSelection()){event.preventDefault();showToolbar($('toolbar').classList.contains('is-hidden'));}});
function measureTools(){document.documentElement.style.setProperty('--tools-height',$('toolbar').offsetHeight+'px');}
new ResizeObserver(measureTools).observe($('toolbar'));measureTools();
document.addEventListener('keydown',e=>{if(e.key==='Escape'){closePanels();showToolbar(true);}});
if(bridge)bridge.ready();
else window.setVoiceSettings({engine:'浏览器预览（无 Android TTS）',voices:[],rate:1});
