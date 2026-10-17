const {chromium}=require('playwright-core');const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||chromium.executablePath(),headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const outbound=[];page.on('request',r=>{if(/^https?:/.test(r.url()))outbound.push(r.url());});
 await page.addInitScript(()=>{window.savedVoice=null;window.Native={ready(){},saveProgress(){},getVoiceSettings(){window.setVoiceSettings({engine:'测试离线引擎',current:'zh-local',language:'中文（中国）',voices:[{name:'zh-local',language:'中文（中国）'}],rate:1});},saveVoiceSettings(name,rate,preview){window.savedVoice={name,rate,preview};},stopSpeaking(){}};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
 fs.mkdirSync('build/screenshots',{recursive:true});
 const sample=fs.readFileSync('app/src/main/assets/sample.md','utf8');
 await page.evaluate(text=>window.loadDocument(text),sample);
 assert.equal(await page.locator('.katex').count(),4);
 assert.equal(await page.locator('#toc details').count(),7);
 assert.ok(await page.locator('#content table').count());
 await page.screenshot({path:'build/screenshots/01-formulas.png',fullPage:true});
 await page.click('#tocButton');await page.screenshot({path:'build/screenshots/02-toc.png'});await page.click('#closeToc');
 await page.click('#theme');await page.screenshot({path:'build/screenshots/03-night.png',fullPage:true});assert.equal(await page.locator('body.dark').count(),1);
 await page.click('#larger');assert.equal(await page.locator('#content').evaluate(e=>e.style.fontSize),'22px');
 await page.evaluate(()=>window.loadDocument(Array.from({length:101},(_,i)=>`段落 ${i}`).join('\n\n')));
 assert.equal(await page.locator('#content section').count(),40);await page.click('#next');assert.match(await page.locator('#status').innerText(),/2 \/ 3/);
 await page.evaluate(()=>window.loadDocument('',0));assert.equal(await page.locator('#next').isDisabled(),true);assert.equal(await page.locator('#status').innerText(),'0 段');
 await page.evaluate(()=>window.loadDocument('<div id="next" style="position:fixed;inset:0">evil</div>\n\n<script>window.PWNED=true</script>\n\n$$x^2$$'));
 assert.equal(await page.locator('#next').count(),1);assert.equal(await page.evaluate(()=>!!window.PWNED),false);
 assert.equal(await page.locator('#content .katex').count(),1);
 // Synthetic touch dispatch checks the real event handlers without hijacking scrolling.
 const swipe=async(selector,x,y,dx,dy)=>page.locator(selector).first().evaluate((el,p)=>{const make=(a,b)=>new Touch({identifier:1,target:el,clientX:a,clientY:b});const t=make(p.x,p.y);el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,touches:[t],changedTouches:[t]}));const end=make(p.x+p.dx,p.y+p.dy);el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,touches:[],changedTouches:[end]}));},{x,y,dx,dy});
 await page.evaluate(text=>window.loadDocument(text),sample);
 await swipe('#content p',100,350,0,-70);assert.equal(await page.locator('#showTools').isVisible(),true);
 await swipe('#content p',100,250,0,70);assert.equal(await page.locator('#showTools').isVisible(),false);
 await swipe('#content p',100,350,0,-70);await page.click('#showTools');assert.equal(await page.locator('#toolbar').getAttribute('aria-hidden'),'false');
 await swipe('#content p',100,250,120,0);assert.equal(await page.locator('#tocPanel').isVisible(),true);await page.click('#closeToc');
 for(const selector of ['#content pre','#content table','#content .katex','#content .katex-display']){await swipe(selector,100,250,120,0);assert.equal(await page.locator('#tocPanel').isVisible(),false);}
 await swipe('#content p',10,250,120,0);assert.equal(await page.locator('#tocPanel').isVisible(),false);
 await page.locator('#content p').first().evaluate(el=>{const r=document.createRange();r.selectNodeContents(el);const s=window.getSelection();s.removeAllRanges();s.addRange(r);});
 await swipe('#content p',100,250,120,0);assert.equal(await page.locator('#tocPanel').isVisible(),false);await page.evaluate(()=>window.getSelection().removeAllRanges());
 const bounds=await page.locator('#toolbar').boundingBox();assert.ok(Math.abs(bounds.y+bounds.height-844)<2);assert.ok(bounds.y>650);
 assert.equal(await page.locator('html').evaluate(el=>getComputedStyle(el).scrollbarWidth),'none');
 await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:'build/screenshots/04-bottom-toolbar.png'});
 await page.click('#voiceSettings');assert.match(await page.locator('#voiceEngine').innerText(),/测试离线引擎/);
 await page.locator('#voiceRate').evaluate(el=>{el.value='0.85';el.dispatchEvent(new Event('input'));});await page.click('#previewVoice');
 assert.deepEqual(await page.evaluate(()=>window.savedVoice),{name:'zh-local',rate:0.85,preview:true});await page.screenshot({path:'build/screenshots/05-voice-settings.png'});
 await page.evaluate(()=>window.setVoiceSettings({engine:'测试',voices:[],rate:1}));assert.equal(await page.locator('#previewVoice').isDisabled(),true);await page.click('#closeVoice');
 assert.deepEqual(outbound,[]);assert.deepEqual(errors,[]);
 console.log('Browser smoke passed: math, TOC, table, theme, font, paging, empty import, HTML isolation, no network, touch gestures, bottom toolbar and TTS controls (mock bridge only).');
 } finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
