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
  const title=document.createElement('button');title.className='toc-title';title.textContent=h.title;title.onclick=()=>{cancelReading();closePanels();render(h.block);};
  if(h.children.length){const arrow=document.createElement('button'),children=document.createElement('div');arrow.className='toc-toggle';children.className='toc-children';children.id='toc-children-'+h.block;children.hidden=h.level>2;
   function update(){arrow.textContent=children.hidden?'›':'⌄';arrow.setAttribute('aria-expanded',String(!children.hidden));arrow.setAttribute('aria-label',(children.hidden?'展开':'收起')+h.title);}
   arrow.setAttribute('aria-controls',children.id);arrow.onclick=()=>{children.hidden=!children.hidden;update();};update();row.append(arrow,title);wrapper.append(row,children);append(h.children,children);
  }else{const spacer=document.createElement('span');spacer.className='toc-spacer';row.append(spacer,title);wrapper.append(row);}
  parent.append(wrapper);
 }}
 append(roots,$('toc'));if(!book.headings.length)$('toc').textContent='此文档没有标题。';
}
function persist(){if(bridge)bridge.saveProgress(current,size,document.body.classList.contains('dark'));}
window.loadDocument=(text,anchor=0,font=20,dark=false,preserveNative=false)=>{if(!preserveNative)cancelReading();readingStatus('');book=InkEngine.indexDocument(text);size=Math.max(14,Math.min(32,font));$('content').style.fontSize=`${size}px`;document.body.classList.toggle('dark',dark);$('theme').textContent=dark?'日间模式':'夜间模式';buildToc();render(anchor);};
$('open').onclick=()=>{cancelReading();closePanels();bridge?.openDocument();};$('tocButton').onclick=()=>openPanel('tocPanel');
$('prev').onclick=()=>render(Math.max(0,(page-1)*40));$('next').onclick=()=>render(Math.min(book.blocks.length-1,(page+1)*40));
$('smaller').onclick=()=>{closePanels();size=Math.max(14,size-2);$('content').style.fontSize=`${size}px`;persist();};
$('larger').onclick=()=>{closePanels();size=Math.min(32,size+2);$('content').style.fontSize=`${size}px`;persist();};
$('theme').onclick=()=>{closePanels();document.body.classList.toggle('dark');$('theme').textContent=document.body.classList.contains('dark')?'日间模式':'夜间模式';persist();};
let speakSelection='',playing=false,reading=null,requestCounter=0,offlineQueueId=null;
function updatePlayButton(active){playing=!!active;$('playIcon').toggleAttribute('hidden',playing);$('stopIcon').toggleAttribute('hidden',!playing);$('speak').setAttribute('aria-label',playing?'停止朗读':'从当前段连续朗读或朗读所选文字');$('speak').title=playing?'停止朗读':'连续朗读';}
function readingStatus(message){$('playbackStatus').textContent=message;$('playbackStatus').hidden=!message;}
function clearHighlight(){document.querySelectorAll('.speaking').forEach(el=>el.classList.remove('speaking'));}
function cancelReading(){$('voicePauseResume').disabled=true;offlineQueueId=null;reading=null;clearHighlight();updatePlayButton(false);bridge?.stopSpeaking();}
window.stopForEngineChange=()=>{cancelReading();readingStatus('切换语音引擎，朗读已停止。');};
window.stopForBackground=()=>{if($('speechSource').value==='offline')return;reading=null;clearHighlight();updatePlayButton(false);readingStatus('云朗读在后台暂停；返回后重新点击朗读并确认片段。');};
function speechParts(text){return $('speechSource').value==='offline'?InkSpeech.splitOfflineText(text).filter(part=>part.trim()):InkSpeech.splitText(text,600);}
function blockSpeech(index){const block=book.blocks[index];if(!block||['heading','code','hr','displayMath'].includes(block.token.type))return '';
 const holder=document.createElement('div');holder.innerHTML=safeHtml(block);holder.querySelectorAll('.katex').forEach(el=>el.replaceWith(document.createTextNode('（公式）')));
 const value=(holder.textContent||'').trim();return value.replace(/[（()）\s]/g,'')==='公式'?'':value;
}
function sendReadingPart(){
 const job=reading;if(!job)return;
 if(job.mode==='continuous'){
  while(job.part>=job.parts.length){if(job.nextBlock>=book.blocks.length){reading=null;clearHighlight();updatePlayButton(false);readingStatus(job.sent?'全文朗读完成。':'没有可朗读的正文段落；标题、纯公式和代码块会跳过。');return;}
   job.block=job.nextBlock++;job.parts=speechParts(blockSpeech(job.block));job.part=0;
  }
  if(Math.floor(job.block/40)!==page)render(job.block);
  current=job.block;clearHighlight();const element=document.getElementById(`p-${job.block}`);element?.classList.add('speaking');element?.scrollIntoView({block:'nearest'});persist();
 }else if(job.part>=job.parts.length){reading=null;updatePlayButton(false);readingStatus('选中文字朗读完成。');return;}
 job.sent=(job.sent||0)+1;job.requestId='reader-'+(++requestCounter);readingStatus('正在提交语音片段…');updatePlayButton(true);
 if(bridge)bridge.speak(job.parts[job.part],job.requestId);else{reading=null;updatePlayButton(false);readingStatus('浏览器预览没有 Android 语音引擎。');}
}
$('speak').addEventListener('pointerdown',()=>{speakSelection=window.getSelection()?.toString()||'';});
$('speak').onclick=()=>{
 const captured=speakSelection;speakSelection='';
 if(playing){cancelReading();readingStatus('朗读已停止。');return;}
 const selected=(captured||window.getSelection()?.toString()||'').trim();
 if($('speechSource').value==='offline'&&bridge?.speakOfflineQueue){startOfflineQueue(selected);return;}
 reading=selected?{mode:'selection',parts:speechParts(selected),part:0}:{mode:'continuous',nextBlock:current,block:current,parts:[],part:0};sendReadingPart();
};
function startOfflineQueue(selected){
 const entries=[];
 if(selected){for(const text of InkSpeech.splitOfflineText(selected))if(text.trim())entries.push({text,block:-1});}
 else for(let block=current;block<book.blocks.length;block++)for(const text of InkSpeech.splitOfflineText(blockSpeech(block),entries.length===0))if(text.trim())entries.push({text,block});
 if(!entries.length){readingStatus('没有可朗读的正文段落；标题、纯公式和代码块会跳过。');return;}
 // No IPC Intent carries document text; native owns and validates the complete queue.
 const queueId='offline-'+Date.now()+'-'+(++requestCounter);
 const payload=JSON.stringify({queueId,entries});
 if(payload.length>12*1024*1024){readingStatus('朗读队列过大，请选取较短文字或拆分文档。没有提交部分队列。');return;}
 reading=null;offlineQueueId=queueId;updatePlayButton(true);readingStatus('正在准备后台离线朗读…');
 bridge.speakOfflineQueue(payload);
}
window.onOfflineQueueStatus=event=>{
 // Service snapshots may restore UI after Activity recreation; IDs isolate replaced jobs.
 if(offlineQueueId&&event.queueId!==offlineQueueId&&!event.restore)return;
 if(!offlineQueueId&&!event.restore)return;
 offlineQueueId=event.queueId||null;readingStatus(event.message||event.phase);
 const active=['queued','started','paused'].includes(event.phase);updatePlayButton(active);
 $('voicePauseResume').disabled=!active;$('voicePauseResume').dataset.paused=String(event.phase==='paused');
 $('voicePauseResume').textContent=event.phase==='paused'?'从当前片段开头恢复':'暂停（按片段恢复）';
 if(active&&Number.isInteger(event.block)&&event.block>=0&&event.block<book.blocks.length){
  if(Math.floor(event.block/40)!==page)render(event.block);
  current=event.block;clearHighlight();document.getElementById(`p-${current}`)?.classList.add('speaking');persist();
 }
 if(!active){offlineQueueId=null;clearHighlight();}
};
window.onSpeechStatus=event=>{
 if(event.source==='offline'&&offlineQueueId)return;
 const job=reading;if(job&&event.requestId!==job.requestId)return;
 if(!job&&event.requestId?.startsWith('reader-'))return;
 readingStatus(event.message||event.phase);
 if(event.phase==='queued'||event.phase==='started')updatePlayButton(true);
 else if(event.phase==='done'&&job){job.part++;setTimeout(()=>{if(reading===job)sendReadingPart();},120);}
 else{if(event.phase==='error'||event.phase==='stopped')reading=null;clearHighlight();updatePlayButton(false);}
};
window.addEventListener('scroll',()=>{clearTimeout(saveTimer);saveTimer=setTimeout(()=>{const sections=[...$('content').children];const visible=sections.find(el=>el.getBoundingClientRect().bottom>16);if(visible){current=Number(visible.dataset.index);persist();}},200);});
function showToolbar(visible){
 $('toolbar').classList.toggle('is-hidden',!visible);$('toolbar').inert=!visible;
 $('toolbar').setAttribute('aria-hidden',String(!visible));
}
function closePanels(){for(const id of ['tocPanel','voicePanel','morePanel'])$(id).hidden=true;bridge?.panelState(false);}
window.handleNativeBack=()=>{const open=['tocPanel','voicePanel','morePanel'].some(id=>!$(id).hidden);if(open){closePanels();showToolbar(true);}return open;};
function openPanel(id){cancelTap();closePanels();showToolbar(true);$(id).hidden=false;bridge?.panelState(true);}
$('moreButton').onclick=()=>openPanel('morePanel');$('closeMore').onclick=closePanels;
$('closeToc').onclick=closePanels;$('closeVoice').onclick=closePanels;
$('voiceSettings').onclick=()=>{openPanel('voicePanel');bridge?.getVoiceSettings();};
$('speechSource').onchange=()=>{cancelReading();bridge?.setSpeechSource($('speechSource').value);};
$('cloudStop').onclick=()=>{cancelReading();readingStatus('云朗读已停止，已发送的请求可能已产生费用。');};
$('configureCloud').onclick=()=>{cancelReading();bridge?.configureCloudVoice($('speechSource').value);};
$('previewCloud').onclick=()=>{cancelReading();bridge?.previewCloudVoice();};
function selectRequestedEngine(){cancelReading();$('voiceEngine').textContent='正在请求切换：'+$('engineChoice').value;$('voiceStatus').textContent='正在初始化所选引擎，请稍候；失败会保留选择并显示原因。';bridge?.selectVoiceEngine($('engineChoice').value);}
$('engineChoice').onchange=selectRequestedEngine;
$('switchEngine').onclick=selectRequestedEngine;
$('voiceRate').oninput=()=>{$('rateValue').textContent=Number($('voiceRate').value).toFixed(2)+'×';};
function saveVoice(preview){cancelReading();bridge?.saveVoiceSettings($('voiceChoice').value,Number($('voiceRate').value),preview);}
$('saveVoice').onclick=()=>saveVoice(false);$('previewVoice').onclick=()=>saveVoice(true);
$('voicePauseResume').onclick=()=>{if($('voicePauseResume').dataset.paused==='true')bridge?.resumeSpeaking();else bridge?.pauseSpeaking();};
$('voiceStop').onclick=()=>{cancelReading();readingStatus('朗读已停止。');};
window.setVoiceSettings=state=>{
 $('speechSource').value=state.source||'offline';
 const cloud=$('speechSource').value!=='offline';$('cloudControls').hidden=!cloud;$('offlineControls').hidden=cloud;
 $('voiceDiagnostics').textContent=state.diagnostics||'尚无朗读事件。';
 $('engineChoice').replaceChildren();
 const defaultEngine=document.createElement('option');defaultEngine.value='';defaultEngine.textContent='系统默认（重新检测）';$('engineChoice').append(defaultEngine);
 for(const engine of state.engines||[]){const option=document.createElement('option');option.value=engine.name;option.textContent=engine.label+' · '+engine.name;$('engineChoice').append(option);}
 if(state.enginePackage&&!(state.engines||[]).some(engine=>engine.name===state.enginePackage)){const unavailable=document.createElement('option');unavailable.value=state.enginePackage;unavailable.textContent='不可用（保留选择） · '+state.enginePackage;$('engineChoice').append(unavailable);}
 $('engineChoice').value=state.enginePackage||'';
 $('switchEngine').disabled=!bridge;
 $('voiceEngine').textContent=(state.loading?'初始化中：':'请求使用引擎：')+(state.engine||'系统默认');
 if(state.engineVerified===false&&state.engineStatus)$('voiceEngine').textContent+=' · '+state.engineStatus;
 $('voiceCurrent').textContent='当前语言 / 音色：'+(state.language||'未设置')+' / '+(state.current||'无可用音色');
 $('voiceChoice').replaceChildren();
 for(const voice of state.voices||[]){const option=document.createElement('option');option.value=voice.name;option.textContent=voice.language+' · '+voice.name;$('voiceChoice').append(option);}
 $('voiceChoice').value=state.current||'';
 const available=!state.loading&&!!(state.voices?.length);$('voiceChoice').disabled=!available;$('saveVoice').disabled=!available;$('previewVoice').disabled=!available;
 $('voiceRate').value=state.rate||1;$('voiceRate').oninput();
 $('voiceStatus').textContent=state.message||(cloud?'实验性云 TTS：每段发送前会单独确认，真实音色尚未实测。':available?'仅列出引擎报告为已安装的离线中文音色。':'没有可用的离线中文音色，请在手机系统 TTS 设置中安装后重启应用。');
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
