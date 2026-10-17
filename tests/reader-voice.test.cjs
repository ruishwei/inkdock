const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const InkSpeech=require('../app/src/main/assets/speech.js');

// Exercise the real reader handlers with a deliberately small DOM/Native substitute.
// This verifies state and routing, not browser rendering, Android audio, or networking.
function readerHarness(){
 const makeClasses=()=>{const values=new Set();return {
  add(value){values.add(value);},remove(value){values.delete(value);},contains(value){return values.has(value);},
  toggle(value,force){const on=force===undefined?!values.has(value):force;on?values.add(value):values.delete(value);return on;}
 };};
 function element(){return {
  value:'',hidden:false,disabled:false,children:[],dataset:{},style:{setProperty(){}},classList:makeClasses(),offsetHeight:50,
  replaceChildren(){this.children=[];},append(...items){this.children.push(...items);},addEventListener(){},setAttribute(){},
  toggleAttribute(){},scrollIntoView(){},getBoundingClientRect(){return {bottom:100};},querySelectorAll(){return [];}
 };}
 const nodes={},calls=[],timers=new Map();let sequence=0;
 const assets=path.join(__dirname,'../app/src/main/assets');
 for(const match of fs.readFileSync(path.join(assets,'index.html'),'utf8').matchAll(/id="([^"]+)"/g))nodes[match[1]]=element();
 const native={ready(){},panelState(){},saveProgress(){},getVoiceSettings(){}};
 for(const [method,label] of Object.entries({stopSpeaking:'stop',speak:'speak',selectVoiceEngine:'engine',setSpeechSource:'source',configureCloudVoice:'config',previewCloudVoice:'preview',saveVoiceSettings:'voice'})){
  native[method]=(...args)=>calls.push([label,...args]);
 }
 const context={console,performance,InkSpeech,Native:native,marked:{use(){}},katex:{},DOMPurify:{},ResizeObserver:class{observe(){}},
  setTimeout(callback){const id=++sequence;timers.set(id,callback);return id;},clearTimeout(id){timers.delete(id);},
  document:{getElementById:id=>nodes[id],createElement:element,querySelectorAll:()=>[],addEventListener(){},body:{classList:makeClasses()},documentElement:{style:{setProperty(){}}}},
  addEventListener(){},getSelection(){return {toString:()=>''};}
 };
 context.window=context;vm.createContext(context);
 vm.runInContext(fs.readFileSync(path.join(assets,'reader.js'),'utf8'),context,{filename:'reader.js'});
 return {nodes,calls,context,read:source=>vm.runInContext(source,context),
  state(value={}){context.setVoiceSettings({source:'offline',engines:[],voices:[],rate:1,...value});},
  start(text){context.testText=text;vm.runInContext('speakSelection=testText',context);nodes.speak.onclick();},
  flushTimers(){const queued=[...timers.values()];timers.clear();queued.forEach(callback=>callback());}
 };
}

test('reader offers default-engine retry even with no enumerated engines',()=>{
 const h=readerHarness();h.state({loading:true});
 assert.equal(h.nodes.switchEngine.disabled,false);
 assert.equal(h.nodes.engineChoice.children[0].value,'');
 assert.equal(h.nodes.previewVoice.disabled,true);
 h.nodes.switchEngine.onclick();assert.deepEqual(h.calls.slice(-2),[['stop'],['engine','']]);
 h.state({source:'QWEN'});assert.equal(h.nodes.cloudControls.hidden,false);assert.equal(h.nodes.offlineControls.hidden,true);
 h.state();assert.equal(h.nodes.cloudControls.hidden,true);assert.equal(h.nodes.offlineControls.hidden,false);
});

test('real reader splitting uses 600 cloud units and preserves synthetic Unicode text',()=>{
 const h=readerHarness();h.context.testText='合成测试😀。'.repeat(500);
 for(const source of ['QWEN','MINIMAX']){
  h.state({source});const parts=h.read('speechParts(testText)');
  assert.ok(parts.length>1);assert.ok(parts.every(part=>part.length<=600));
  assert.equal(parts.join(''),h.context.testText);
  assert.ok(parts.every(part=>!/[\uD800-\uDBFF]$/.test(part)&&!/[\uDC00-\uDFFF]/.test(part[0])));
 }
 h.state();const offline=h.read('speechParts(testText)');
 assert.ok(offline[0].length<=120);assert.ok(offline.slice(1).every(part=>part.length<=360));
 assert.equal(offline.join(''),h.context.testText);
});

test('source changes and cloud controls stop current reading before native routing',()=>{
 const h=readerHarness();h.state({source:'QWEN'});h.start('仅供本机测试的合成片段。');
 assert.notEqual(h.read('reading'),null);
 h.nodes.speechSource.value='MINIMAX';h.nodes.speechSource.onchange();
 assert.deepEqual(h.calls.slice(-2),[['stop'],['source','MINIMAX']]);assert.equal(h.read('reading'),null);
 h.nodes.configureCloud.onclick();assert.deepEqual(h.calls.slice(-2),[['stop'],['config','MINIMAX']]);
 h.nodes.previewCloud.onclick();assert.deepEqual(h.calls.slice(-2),[['stop'],['preview']]);
 h.nodes.cloudStop.onclick();assert.deepEqual(h.calls.at(-1),['stop']);
});

test('reader ignores stale request callbacks and advances only the current request',()=>{
 const h=readerHarness();h.state({source:'QWEN'});h.start('测试😀。'.repeat(400));
 const first=h.calls.at(-1);assert.equal(first[0],'speak');assert.ok(first[1].length<=600);
 h.context.onSpeechStatus({requestId:'reader-stale',phase:'error'});
 assert.equal(h.read('reading.requestId'),first[2]);
 h.context.onSpeechStatus({requestId:first[2],phase:'done'});h.flushTimers();
 const second=h.calls.at(-1);assert.equal(second[0],'speak');assert.notEqual(second[2],first[2]);
 h.context.onSpeechStatus({requestId:first[2],phase:'error'});assert.equal(h.read('reading.requestId'),second[2]);
 h.context.onSpeechStatus({requestId:second[2],phase:'error'});assert.equal(h.read('reading'),null);assert.equal(h.read('playing'),false);
});

test('engine/background stops invalidate pending continuous-read advancement',()=>{
 for(const stop of ['stopForEngineChange','stopForBackground']){
  const h=readerHarness();h.state({source:'QWEN'});h.start('测试😀。'.repeat(400));
  const first=h.calls.at(-1);h.context.onSpeechStatus({requestId:first[2],phase:'done'});
  h.context[stop]();h.flushTimers();
  assert.equal(h.calls.filter(call=>call[0]==='speak').length,1);
  assert.equal(h.read('reading'),null);assert.equal(h.read('playing'),false);
 }
});


test('offline sends complete selection once and never advances through WebView callbacks',()=>{
 const h=readerHarness();h.state();
 h.context.Native.speakOfflineQueue=payload=>h.calls.push(['queue',JSON.parse(payload)]);
 h.start('合成测试句。'.repeat(800));
 const request=h.calls.find(call=>call[0]==='queue')[1];
 assert.ok(request.entries.length>1);
 assert.ok(request.entries[0].text.length<=120);
 assert.ok(request.entries.slice(1).every(part=>part.text.length<=360));
 assert.equal(request.entries.map(part=>part.text).join(''),'合成测试句。'.repeat(800));
 assert.ok(request.entries.every(part=>part.block===-1));
 h.context.onOfflineQueueStatus({queueId:request.queueId,phase:'started',block:-1});
 h.context.stopForBackground();h.flushTimers();
 assert.equal(h.calls.filter(call=>call[0]==='queue').length,1);
 assert.equal(h.read('offlineQueueId'),request.queueId);
 h.context.onOfflineQueueStatus({queueId:request.queueId,phase:'done'});
 assert.equal(h.read('offlineQueueId'),null);
});
test('offline stop rejects stale updates and explicit restore accepts service snapshot',()=>{
 const h=readerHarness();h.state();h.context.Native.speakOfflineQueue=payload=>h.calls.push(['queue',JSON.parse(payload)]);
 h.start('合成测试');const request=h.calls.find(call=>call[0]==='queue')[1];
 h.nodes.speak.onclick();assert.equal(h.read('offlineQueueId'),null);
 h.context.onOfflineQueueStatus({queueId:request.queueId,phase:'started'});assert.equal(h.read('playing'),false);
 h.context.onOfflineQueueStatus({queueId:'restored',phase:'paused',restore:true});assert.equal(h.read('offlineQueueId'),'restored');
 h.context.onOfflineQueueStatus({phase:'error',restore:true});assert.equal(h.read('offlineQueueId'),null);assert.equal(h.read('playing'),false);
});

test('offline full queue shortens only its first utterance and keeps paragraph anchors',()=>{
 const h=readerHarness();h.state();h.context.Native.speakOfflineQueue=payload=>h.calls.push(['queue',JSON.parse(payload)]);
 h.context.testBlocks=['skipped before current','开头。  '+'甲'.repeat(800),'','乙'.repeat(720)];
 h.read('book={blocks:testBlocks.map(()=>({}))};current=1;blockSpeech=index=>testBlocks[index]');
 h.start('');const entries=h.calls.find(call=>call[0]==='queue')[1].entries;
 assert.equal(entries[0].text,'开头。  ');assert.equal(entries[0].block,1);
 assert.ok(entries[0].text.length<=120);assert.ok(entries.slice(1).every(entry=>entry.text.length<=360));
 assert.equal(entries.filter(entry=>entry.block===1).map(entry=>entry.text).join(''),h.context.testBlocks[1]);
 assert.equal(entries.filter(entry=>entry.block===3).map(entry=>entry.text).join(''),h.context.testBlocks[3]);
 assert.equal(entries.find(entry=>entry.block===3).text.length,360);
 assert.ok(entries.every(entry=>[1,3].includes(entry.block)));
});

test('offline queue skips whitespace-only slices and retains all spoken text',()=>{
 const h=readerHarness();h.state();h.context.Native.speakOfflineQueue=payload=>h.calls.push(['queue',JSON.parse(payload)]);
 h.start('A sentence. '+' '.repeat(1000)+'Next sentence.');
 const entries=h.calls.find(call=>call[0]==='queue')[1].entries;
 assert.ok(entries.every(entry=>entry.text.trim()&&entry.block===-1));
 assert.equal(entries.map(entry=>entry.text).join('').replace(/\s/g,''),'Asentence.Nextsentence.');
 assert.ok(entries[0].text.length<=120);assert.ok(entries.slice(1).every(entry=>entry.text.length<=360));
});

test('import closes more panel and reports native Back state before picker launch',()=>{
 const h=readerHarness();const order=[];
 h.context.Native.panelState=open=>order.push(['panel',open]);
 h.context.Native.openDocument=()=>order.push(['import',h.nodes.morePanel.hidden]);
 h.nodes.moreButton.onclick();h.nodes.open.onclick();
 assert.deepEqual(order.slice(-2),[['panel',false],['import',true]]);
});

test('engine dropdown switches immediately instead of waiting for a second button',()=>{
 const h=readerHarness();h.state();h.nodes.engineChoice.value='synthetic.vox';h.nodes.engineChoice.onchange();
 assert.deepEqual(h.calls.slice(-2),[['stop'],['engine','synthetic.vox']]);
 assert.match(h.nodes.voiceEngine.textContent,/synthetic.vox/);
});

test('unavailable requested engine remains a selectable retry target, never empty default',()=>{
 const h=readerHarness();h.state({enginePackage:'removed.synthetic.engine',engines:[]});
 assert.ok(h.nodes.engineChoice.children.some(option=>option.value==='removed.synthetic.engine'));
 h.nodes.switchEngine.onclick();assert.deepEqual(h.calls.at(-1),['engine','removed.synthetic.engine']);
});
