const {test}=require('node:test');
const assert=require('node:assert/strict');
const {indexDocument,headingAtBlock}=require('../app/src/main/assets/engine.js');
test('TOC predecessor follows all six levels, preface and exact heading edges',()=>{
 const book=indexDocument('前言\n\n'+Array.from({length:6},(_,i)=>'#'.repeat(i+1)+' 标题'+i+'\n\n正文'+i).join('\n\n'));
 assert.equal(headingAtBlock(book.headings,0),null);
 for(const h of book.headings){assert.equal(headingAtBlock(book.headings,h.block),h);assert.equal(headingAtBlock(book.headings,h.block+1),h);}
 for(const index of [-1,null,NaN,1.2])assert.equal(headingAtBlock(book.headings,index),null);
 assert.equal(headingAtBlock([],0),null);
});
test('TOC resolves inherited headings across lazy groups after fifty chapters',()=>{
 const book=indexDocument(Array.from({length:65},(_,i)=>`# 章${i+1}\n\n`+Array.from({length:43},(_,j)=>`段${j}`).join('\n\n')).join('\n\n'));
 const h=book.headings[54];assert.equal(headingAtBlock(book.headings,h.block+41),h);
 assert.equal(headingAtBlock(book.headings,h.block+44),book.headings[55]);
});

const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function harness(){
 const classes=()=>({toggle(){},add(){},remove(){},contains(){return false;}});
 const nodes={},events={},calls=[];
 function element(){return {children:[],dataset:{},attributes:{},hidden:false,style:{setProperty(){}},classList:classes(),scrollTop:0,
 append(...items){this.children.push(...items);},replaceChildren(){this.children=[];},setAttribute(k,v){this.attributes[k]=v;},removeAttribute(k){delete this.attributes[k];},toggleAttribute(){},addEventListener(){},querySelectorAll(){return [];},
 getBoundingClientRect(){return this.rect||{top:30,bottom:78,height:48};},scrollIntoView(){calls.push('scroll');},value:''};}
 const assets=path.join(__dirname,'../app/src/main/assets');
 for(const match of fs.readFileSync(path.join(assets,'index.html'),'utf8').matchAll(/id="([^"]+)"/g))nodes[match[1]]=element();
 nodes.tocPanel.hidden=true;nodes.tocPanel.rect={top:12,bottom:760,height:748};
 const context={console,performance,InkEngine:{indexDocument,headingAtBlock},Native:{ready(){},saveProgress(i){calls.push(['save',i]);},panelState(open){calls.push(['panel',open]);},stopSpeaking(){calls.push('stop');}},marked:{use(){}},katex:{},DOMPurify:{},ResizeObserver:class{observe(){}},setTimeout(){},clearTimeout(){},requestAnimationFrame(fn){fn();},
 document:{getElementById:id=>nodes[id]||nodes.content.children.find(el=>el.id===id),createElement:element,querySelectorAll:()=>[],addEventListener(){},body:{classList:classes()},documentElement:{style:{setProperty(){}}}},addEventListener(name,fn){events[name]=fn;}};
 context.window=context;vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(assets,'reader.js'),'utf8'),context);
 const run=code=>vm.runInContext(code,context);
 return {nodes,calls,run,context,load(source){context.source=source;run('book=InkEngine.indexDocument(source);buildToc();');},viewport(index){const el=element();el.dataset.index=index;nodes.content.children=[el];},selected(){return run('selectedToc?.block??null');}};
}
test('real TOC handlers synchronously sample browsing position and reveal only actual ancestors',()=>{
 const h=harness();h.load(Array.from({length:65},(_,i)=>'# 章'+i+'\n\n### 子'+i+'\n\n###### 细'+i+'\n\n正文').join('\n\n'));
 h.viewport(54*4+3);h.run('current=0;spokenBlock=1');h.nodes.tocButton.onclick();
 assert.equal(h.selected(),54*4+2);assert.equal(h.run('current'),54*4+3);
 assert.deepEqual(Array.from(h.run('[...tocEntries.values()].filter(n=>n.children&&!n.children.hidden).map(n=>n.block)')),[216,217]);
 assert.equal(h.run("selectedToc.title.attributes['aria-current']"),'location');
 assert.ok(h.calls.every(call=>call!=='scroll'&&call!=='stop'));
 h.run('selectedToc.parent.children.hidden=true');h.context.handleNativeBack();h.nodes.tocButton.onclick();assert.equal(h.run('selectedToc.parent.children.hidden'),false);
 h.viewport(5);h.nodes.tocButton.onclick();assert.equal(h.selected(),5);
 assert.equal(h.run("tocEntries.get(218).title.attributes['aria-current']"),undefined);
 assert.equal(h.run('spokenBlock'),1);
});
test('TOC opening scrolls only its panel and Back preserves browsing/speech anchors',()=>{
 const h=harness();h.load('# 标题\n\n## 小节\n\n正文');h.viewport(2);
 h.run('tocEntries.get(1).row.rect={top:1200,bottom:1248,height:48};spokenBlock=0;');
 h.nodes.tocButton.onclick();assert.ok(h.nodes.tocPanel.scrollTop>0);assert.equal(h.selected(),1);
 assert.equal(h.context.handleNativeBack(),true);assert.equal(h.nodes.tocPanel.hidden,true);assert.equal(h.run('current'),2);assert.equal(h.run('spokenBlock'),0);
 assert.ok(!h.calls.includes('scroll'));assert.ok(!h.calls.includes('stop'));
});
test('preface and empty documents clear stale current heading without choosing a future heading',()=>{
 const h=harness();h.load('前言\n\n# 标题\n\n正文');h.viewport(2);h.nodes.tocButton.onclick();assert.equal(h.selected(),1);
 h.viewport(0);h.nodes.tocButton.onclick();assert.equal(h.selected(),null);
 h.load('');h.nodes.content.children=[];h.nodes.tocButton.onclick();assert.equal(h.selected(),null);assert.equal(h.nodes.tocPanel.scrollTop,0);
});
