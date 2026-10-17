const {chromium}=require('playwright-core');const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||chromium.executablePath(),headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const errors=[],outbound=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))outbound.push(r.url());});
 await page.addInitScript(()=>{window.savedVoice=null;window.requests=[];window.nativePanelOpen=false;window.Native={ready(){},panelState(open){window.nativePanelOpen=open;},saveProgress(){},speak(text,requestId){window.requests.push({text,requestId});window.onSpeechStatus({requestId,phase:'queued',message:'等待引擎'});window.onSpeechStatus({requestId,phase:'started',message:'引擎报告开始'});},openDocument(){window.importPanelState=window.nativePanelOpen;},getVoiceSettings(){window.setVoiceSettings({engine:'测试离线引擎',current:'zh-local',language:'中文（中国）',voices:[{name:'zh-local',language:'中文（中国）'}],rate:1});},saveVoiceSettings(name,rate,preview){window.savedVoice={name,rate,preview};},stopSpeaking(){const r=window.requests.at(-1);if(r)window.onSpeechStatus({requestId:r.requestId,phase:'stopped',message:'已停止'});}};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));fs.mkdirSync('build/screenshots',{recursive:true});
 await page.click('#moreButton');await page.click('#open');
 assert.equal(await page.locator('#morePanel').isVisible(),false);
 assert.equal(await page.evaluate(()=>window.importPanelState),false);
 const sample=fs.readFileSync('app/src/main/assets/sample.md','utf8');
 await page.evaluate(text=>window.loadDocument(text),sample);await page.waitForTimeout(700);
 assert.equal(await page.locator('.katex').count(),4);assert.equal(await page.locator('.toc-title').count(),7);assert.ok(await page.locator('#content table').count());
 assert.equal(await page.locator('#toolbar > button').count(),3);assert.equal(await page.locator('#showTools').count(),0);
 const bounds=await page.locator('#toolbar').boundingBox();assert.ok(bounds.height<=68);assert.ok(Math.abs(bounds.y+bounds.height-844)<2);
 for(const button of await page.locator('#toolbar > button').all()){const b=await button.boundingBox();assert.ok(b.width>=44&&b.height>=44);assert.ok(await button.getAttribute('aria-label'));}
 await page.screenshot({path:'build/screenshots/01-formulas.png',fullPage:true});await page.screenshot({path:'build/screenshots/04-bottom-toolbar.png'});
 await page.click('#tocButton');assert.doesNotMatch(await page.locator('#toc').innerText(),/阅读此节/);
 const arrow=page.locator('.toc-toggle').first();await arrow.click();assert.equal(await arrow.getAttribute('aria-expanded'),'false');assert.equal(await page.locator('#tocPanel').isVisible(),true);await arrow.click();
 await page.screenshot({path:'build/screenshots/02-toc.png'});await page.locator('.toc-title').first().click();assert.equal(await page.locator('#tocPanel').isVisible(),false);
 await page.click('#moreButton');await page.click('#theme');assert.equal(await page.locator('body.dark').count(),1);assert.equal(await page.locator('#morePanel').isVisible(),false);assert.equal(await page.evaluate(()=>window.nativePanelOpen),false);await page.click('#moreButton');await page.click('#larger');assert.equal(await page.locator('#content').evaluate(e=>e.style.fontSize),'22px');assert.equal(await page.locator('#morePanel').isVisible(),false);assert.equal(await page.evaluate(()=>window.nativePanelOpen),false);await page.screenshot({path:'build/screenshots/03-night.png',fullPage:true});
 await page.click('#speak');assert.equal(await page.locator('#speak').getAttribute('aria-label'),'停止朗读');assert.equal(await page.locator('#stopIcon').isVisible(),true);assert.equal(await page.locator('#playIcon').isVisible(),false);await page.click('#speak');assert.match(await page.locator('#speak').getAttribute('aria-label'),/连续朗读/);
 await page.click('#moreButton');await page.click('#voiceSettings');assert.match(await page.locator('#voiceEngine').innerText(),/测试离线引擎/);
 await page.locator('#voiceRate').evaluate(el=>{el.value='0.85';el.dispatchEvent(new Event('input'));});await page.click('#previewVoice');assert.deepEqual(await page.evaluate(()=>window.savedVoice),{name:'zh-local',rate:0.85,preview:true});await page.screenshot({path:'build/screenshots/05-voice-settings.png'});
 await page.evaluate(()=>window.setVoiceSettings({engine:'测试',voices:[],rate:1}));assert.equal(await page.locator('#previewVoice').isDisabled(),true);await page.click('#closeVoice');
 // Panel Back handshake, continuous reading, selection, error and stale callback regression.
 for(const id of ['tocPanel','morePanel','voicePanel']){await page.evaluate(id=>{if(id==='tocPanel')document.getElementById('tocButton').click();else{document.getElementById('moreButton').click();if(id==='voicePanel')document.getElementById('voiceSettings').click();}},id);assert.equal(await page.evaluate(()=>window.nativePanelOpen),true);assert.equal(await page.evaluate(()=>window.handleNativeBack()),true);assert.equal(await page.evaluate(()=>window.nativePanelOpen),false);}
 assert.equal(await page.evaluate(()=>window.handleNativeBack()),false);
 await page.evaluate(()=>{window.loadDocument('# 标题\n\n第一段合成正文。\n\n## 第二标题\n\n第二段合成正文。');window.requests=[];});
 await page.click('#speak');assert.equal(await page.evaluate(()=>window.requests[0].text),'第一段合成正文。');assert.equal(await page.locator('.speaking').count(),1);
 const firstId=await page.evaluate(()=>window.requests[0].requestId);
 await page.evaluate(id=>window.onSpeechStatus({requestId:id,phase:'done',message:'完成'}),firstId);await page.waitForTimeout(200);assert.equal(await page.evaluate(()=>window.requests[1].text),'第二段合成正文。');
 await page.evaluate(id=>window.onSpeechStatus({requestId:id,phase:'done',message:'迟到回调'}),firstId);await page.waitForTimeout(200);assert.equal(await page.evaluate(()=>window.requests.length),2);
 await page.evaluate(()=>window.onSpeechStatus({requestId:window.requests.at(-1).requestId,phase:'error',errorCode:-5,message:'合成失败，请检查离线语音包'}));assert.match(await page.locator('#playbackStatus').innerText(),/合成失败/);assert.equal(await page.locator('.speaking').count(),0);assert.notEqual(await page.locator('#speak').getAttribute('aria-label'),'停止朗读');
 await page.locator('#content p').first().evaluate(el=>{const r=document.createRange();r.selectNodeContents(el);const selection=window.getSelection();selection.removeAllRanges();selection.addRange(r);});await page.click('#speak');
 assert.equal(await page.evaluate(()=>window.requests.at(-1).text),'第一段合成正文。');
 const count=await page.evaluate(()=>window.requests.length);await page.evaluate(()=>window.onSpeechStatus({requestId:window.requests.at(-1).requestId,phase:'done',message:'完成'}));await page.waitForTimeout(200);assert.equal(await page.evaluate(()=>window.requests.length),count);assert.match(await page.locator('#playbackStatus').innerText(),/选中文字朗读完成/);
 await page.evaluate(()=>window.getSelection().removeAllRanges());await page.evaluate(text=>window.loadDocument(text),sample);
 // Exercise actual pointer handlers: taps toggle, movement/scroll/selection never do.
 const pointer=async(selector,dx=0,dy=0)=>{await page.locator(selector).first().evaluate((el,p)=>{const opts={bubbles:true,pointerId:1,pointerType:'touch',isPrimary:true,button:0,clientX:100,clientY:250};el.dispatchEvent(new PointerEvent('pointerdown',opts));el.dispatchEvent(new PointerEvent('pointermove',{...opts,clientX:100+p.dx,clientY:250+p.dy}));el.dispatchEvent(new PointerEvent('pointerup',{...opts,clientX:100+p.dx,clientY:250+p.dy}));},{dx,dy});};
 const hidden=()=>page.locator('#toolbar').evaluate(el=>el.classList.contains('is-hidden'));
 await page.waitForTimeout(700);await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),true);
 await page.waitForTimeout(400);await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),false);
 for(const move of [[0,90],[0,-90],[120,0],[-120,0]]){await page.waitForTimeout(400);await pointer('#content p',...move);await page.waitForTimeout(400);assert.equal(await hidden(),false);assert.equal(await page.locator('#tocPanel').isVisible(),false);}
 for(const selector of ['#content pre','#content table','#content .katex','#content .katex-display']){await page.waitForTimeout(400);await pointer(selector);await page.waitForTimeout(400);assert.equal(await hidden(),false);}
 await page.locator('#content p').first().evaluate(el=>{const r=document.createRange();r.selectNodeContents(el);const s=window.getSelection();s.removeAllRanges();s.addRange(r);});await page.waitForTimeout(400);await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),false);await page.evaluate(()=>window.getSelection().removeAllRanges());
 await page.waitForTimeout(500);await page.evaluate(()=>window.dispatchEvent(new Event('scroll')));await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),false);
 await page.waitForTimeout(500);await pointer('#content p');await page.waitForTimeout(100);await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),false);
 await page.waitForTimeout(500);await pointer('#content p');await page.click('#moreButton');await page.waitForTimeout(400);assert.equal(await hidden(),false);await pointer('#content p');await page.waitForTimeout(400);assert.equal(await hidden(),false);await page.click('#closeMore');
 assert.equal(await page.locator('html').evaluate(el=>getComputedStyle(el).scrollbarWidth),'none');
 await page.evaluate(()=>window.loadDocument(Array.from({length:101},(_,i)=>`段落 ${i}`).join('\n\n')));assert.equal(await page.locator('#content section').count(),40);await page.click('#next');assert.match(await page.locator('#status').innerText(),/2 \/ 3/);
 await page.evaluate(()=>window.loadDocument('',0));assert.equal(await page.locator('#next').isDisabled(),true);assert.equal(await page.locator('#status').innerText(),'0 段');
 await page.evaluate(()=>window.loadDocument('<div id="next" style="position:fixed;inset:0">evil</div>\n\n<script>window.PWNED=true</script>\n\n$$x^2$$'));assert.equal(await page.locator('#next').count(),1);assert.equal(await page.evaluate(()=>!!window.PWNED),false);assert.equal(await page.locator('#content .katex').count(),1);
 assert.deepEqual(outbound,[]);assert.deepEqual(errors,[]);
 console.log('Browser smoke passed: offline reading, compact controls, tap guards, no swipe actions, TOC arrow/title, play/stop and TTS mock. Native system bars/TTS require device checks.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
