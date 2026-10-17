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
  toggleAttribute(){},scrollIntoView(){calls.push(['scroll',this.id]);},getBoundingClientRect(){return {bottom:100};},querySelectorAll(){return [];}
 };}
 const nodes={},calls=[],timers=new Map();let sequence=0;
 const assets=path.join(__dirname,'../app/src/main/assets');
 for(const match of fs.readFileSync(path.join(assets,'index.html'),'utf8').matchAll(/id="([^"]+)"/g))nodes[match[1]]=element();
 const native={ready(){},panelState(){},saveProgress(){},getVoiceSettings(){}};
 for(const [method,label] of Object.entries({stopSpeaking:'stop',speak:'speak',selectVoiceEngine:'engine',setSpeechSource:'source',configureCloudVoice:'config',previewCloudVoice:'preview',saveVoiceSettings:'voice'})){
  native[method]=(...args)=>calls.push([label,...args]);
 }
 const context={console,performance,InkSpeech,Native:native,marked:{use(){}},katex:{},DOMPurify:{},ResizeObserver:class{observe(){}},
  requestAnimationFrame(callback){callback();},setTimeout(callback){const id=++sequence;timers.set(id,callback);return id;},clearTimeout(id){timers.delete(id);},
  document:{getElementById:id=>nodes[id]||nodes.content.children.find(el=>el.id===id),createElement:element,querySelectorAll:()=>nodes.content.children.filter(el=>el.classList.contains('speaking')),addEventListener(){},body:{classList:makeClasses()},documentElement:{style:{setProperty(){}}}},
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


function locationHarness(){
 const h=readerHarness();h.state();
 h.context.Native.speakOfflineQueue=payload=>h.calls.push(['queue',JSON.parse(payload)]);
 h.read("book={blocks:Array.from({length:125},(_,i)=>({id:'p-'+i,token:{type:'paragraph'}})),chunks:Array.from({length:4},(_,i)=>({start:i*40,end:Math.min(125,(i+1)*40)}))};safeHtml=()=>'<p>合成测试正文。</p>';blockSpeech=()=> '合成测试正文。';render(0)");
 h.start('');const id=h.calls.find(c=>c[0]==='queue')[1].queueId;
 return {...h,event:(phase,block,extra={})=>h.context.onOfflineQueueStatus({queueId:id,phase,block,...extra})};
}
test('locator loads the correct lazy group using paragraph IDs without audio changes',()=>{
 const h=locationHarness();h.calls.length=0;
 h.event('queued',85);assert.equal(h.nodes.locateReading.disabled,true);
 h.event('started',85);assert.equal(h.read('page'),0);assert.equal(h.calls.length,0);
 h.nodes.locateReading.onclick();assert.equal(h.read('page'),2);assert.equal(h.nodes.content.children[0].id,'p-80');
 assert.equal(h.nodes.content.children.filter(e=>e.classList.contains('speaking'))[0].id,'p-85');
 assert.deepEqual(h.calls,[['scroll','p-85']]);
 h.calls.length=0;h.event('queued',120);assert.equal(h.read('spokenBlock'),85);
 h.event('started',86);assert.equal(h.read('spokenBlock'),86);assert.equal(h.calls.length,0);
 h.event('started',124);assert.equal(h.read('page'),2);
 h.nodes.locateReading.onclick();assert.equal(h.read('page'),3);assert.equal(h.nodes.content.children.length,5);
 assert.deepEqual(h.calls,[['scroll','p-124']]);
});
test('paused paragraph stays locatable while browsing other groups',()=>{
 const h=locationHarness();h.event('started',85);h.event('paused',85);
 h.nodes.next.onclick();assert.equal(h.read('page'),1);
 h.nodes.locateReading.onclick();assert.equal(h.read('page'),2);assert.equal(h.read('spokenBlock'),85);
 assert.equal(h.nodes.locateReading.disabled,false);
 h.event('paused',124,{restore:true});h.nodes.locateReading.onclick();assert.equal(h.read('page'),3);
});
test('terminal stop, expiry, error and completion clear the locator and reject late events',()=>{
 for(const phase of ['stopped','expired','error','done']){
  const h=locationHarness();h.event('started',1);h.event(phase,1);
  assert.equal(h.read('spokenBlock'),null);assert.equal(h.nodes.locateReading.disabled,true);
  assert.equal(h.nodes.content.children.some(e=>e.classList.contains('speaking')),false);
  h.event('started',2);assert.equal(h.read('spokenBlock'),null);
 }
});
test('document replacement and explicit stop clear old paragraph locations',()=>{
 const h=locationHarness();h.event('started',1);
 h.context.InkEngine={indexDocument:()=>({blocks:[],headings:[],chunks:[]})};
 h.context.loadDocument('全新的合成测试文档。');h.event('started',1);
 assert.equal(h.read('spokenBlock'),null);assert.equal(h.nodes.locateReading.disabled,true);
});
test('selection, malformed blocks and inactive restore never imply a paragraph',()=>{
 const h=locationHarness();
 for(const block of [-1,999,null,1.5,'1']){h.event('started',block,{restore:true});assert.equal(h.nodes.locateReading.disabled,true);}
 h.event('started',1,{restore:true,active:false});assert.equal(h.nodes.locateReading.disabled,true);
});
