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
 assert.ok(offline.some(part=>part.length>600));assert.ok(offline.every(part=>part.length<=2800));
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
