const {chromium}=require('playwright-core');
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.calls=[];window.Native={ready(){},panelState(open){window.panelOpen=open;},saveProgress(){},stopSpeaking(){calls.push('stop');},speakOfflineQueue(payload){window.queue=JSON.parse(payload);}};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
 const text='前言段落。\n\n'+Array.from({length:65},(_,i)=>Array.from({length:6},(_,l)=>'#'.repeat(l+1)+` 第${i+1}章层${l+1}\n\n`+(`正文 ${i+1}-${l+1}。`.repeat(30))).join('\n\n')).join('\n\n');
 await page.evaluate(text=>loadDocument(text),text);await page.waitForTimeout(50);
 const selected=()=>page.locator('.toc-title[aria-current="location"]');
 const open=()=>page.evaluate(()=>document.getElementById('tocButton').click());
 const back=()=>page.evaluate(()=>handleNativeBack());
 await open();assert.equal(await selected().count(),0);await back();
 // H1-H6, including skipped levels, exact heading and paragraph anchors.
 for(let level=1;level<=6;level++){
  await page.evaluate(level=>render(book.headings.find(h=>h.title===`第55章层${level}`).block),level);await page.waitForTimeout(30);await open();
  assert.equal(await selected().textContent(),`第55章层${level}`);
  assert.equal(await page.locator('.toc-toggle[aria-expanded="true"]').count(),level-1);
  const box=await selected().boundingBox(),panel=await page.locator('#tocPanel').boundingBox();assert.ok(box.y>=panel.y&&box.y+box.height<=panel.y+panel.height);
  await back();
 }
 // Opening samples the viewport synchronously, even before debounced progress saves.
 await page.evaluate(()=>{render(book.headings.find(h=>h.title==='第55章层2').block);});await page.waitForTimeout(30);
 await page.evaluate(()=>{const h=book.headings.find(h=>h.title==='第55章层3');document.getElementById(book.blocks[h.block+1].id).scrollIntoView();document.getElementById('tocButton').click();});
 assert.equal(await selected().textContent(),'第55章层3');
 const before=await page.evaluate(()=>scrollY);await back();assert.equal(await page.evaluate(()=>scrollY),before);assert.equal(await page.evaluate(()=>panelOpen),false);
 // Queued, started, paused and restored audio never override the browsed chapter.
 await page.evaluate(()=>{document.getElementById('speechSource').value='offline';document.getElementById('speak').click();window.calls=[];});
 for(const phase of ['queued','started','paused']){
  await page.evaluate(phase=>onOfflineQueueStatus({queueId:queue.queueId,phase,block:book.headings[10].block+1,restore:true}),phase);
  await open();assert.equal(await selected().textContent(),'第55章层3');await back();assert.equal(await page.evaluate(()=>scrollY),before);
 }
 // Explicit locator changes the viewport; TOC then follows its actual visible block.
 await page.evaluate(()=>document.getElementById('locateReading').click());await page.waitForTimeout(50);await open();
 const expected=await page.evaluate(()=>InkEngine.headingAtBlock(book.headings,viewportBlock()).title);assert.equal(await selected().textContent(),expected);assert.ok(!expected.includes('第55章'));
 assert.deepEqual(await page.evaluate(()=>calls),[]);await back();
 // Group starts inherit the preceding heading from another rendered group.
 await page.evaluate(()=>loadDocument('# 全局标题\n\n'+Array.from({length:100},(_,i)=>`长正文${i}。`.repeat(40)).join('\n\n'),80));await page.waitForTimeout(50);await open();assert.equal(await selected().textContent(),'全局标题');await back();
 // Skipped heading levels still expand the actual ancestor chain, and unrelated branches collapse.
 await page.evaluate(()=>loadDocument('# 第一\n\n### 第三\n\n###### 第六\n\n'+('正文。'.repeat(300))+'\n\n# 另一个\n\n## 其他',2));await page.waitForTimeout(50);await open();assert.equal(await selected().textContent(),'第六');assert.equal(await page.locator('.toc-toggle[aria-expanded="true"]').count(),2);
 await page.locator('.toc-toggle').first().click();assert.equal(await selected().isVisible(),false);await back();await open();assert.equal(await selected().isVisible(),true);
 await page.evaluate(()=>document.body.classList.add('dark'));fs.mkdirSync('build/screenshots',{recursive:true});await page.screenshot({path:'build/screenshots/toc-current-heading.png'});await back();
 for(const source of ['', '没有标题。']){await page.evaluate(source=>loadDocument(source),source);await page.waitForTimeout(30);await open();assert.equal(await selected().count(),0);await back();}
 assert.deepEqual(errors,[]);
 console.log('TOC browser regression passed: H1-H6, chapter 55, synchronous viewport, lazy inherited headings, ancestor-only expansion, selected row visibility, Back/body stability, independent speech and explicit locator, empty/no-heading documents.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
