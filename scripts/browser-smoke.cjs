const {chromium}=require('playwright-core');const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||chromium.executablePath(),headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const outbound=[];page.on('request',r=>{if(/^https?:/.test(r.url()))outbound.push(r.url());});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
 fs.mkdirSync('build/screenshots',{recursive:true});
 const sample=fs.readFileSync('app/src/main/assets/sample.md','utf8');
 await page.evaluate(text=>window.loadDocument(text),sample);
 assert.equal(await page.locator('.katex').count(),4);
 assert.equal(await page.locator('#toc details').count(),7);
 assert.ok(await page.locator('#content table').count());
 await page.screenshot({path:'build/screenshots/01-formulas.png',fullPage:true});
 await page.click('#tocButton');await page.screenshot({path:'build/screenshots/02-toc.png'});await page.click('#tocButton');
 await page.click('#theme');await page.screenshot({path:'build/screenshots/03-night.png',fullPage:true});assert.equal(await page.locator('body.dark').count(),1);
 await page.click('#larger');assert.equal(await page.locator('#content').evaluate(e=>e.style.fontSize),'22px');
 await page.evaluate(()=>window.loadDocument(Array.from({length:101},(_,i)=>`段落 ${i}`).join('\n\n')));
 assert.equal(await page.locator('#content section').count(),40);await page.click('#next');assert.match(await page.locator('#status').innerText(),/2 \/ 3/);
 await page.evaluate(()=>window.loadDocument('',0));assert.equal(await page.locator('#next').isDisabled(),true);assert.equal(await page.locator('#status').innerText(),'0 段');
 await page.evaluate(()=>window.loadDocument('<div id="next" style="position:fixed;inset:0">evil</div>\n\n<script>window.PWNED=true</script>\n\n$$x^2$$'));
 assert.equal(await page.locator('#next').count(),1);assert.equal(await page.evaluate(()=>!!window.PWNED),false);
 assert.equal(await page.locator('#content .katex').count(),1);
 assert.deepEqual(outbound,[]);assert.deepEqual(errors,[]);
 console.log('Browser smoke passed: math, TOC, table, theme, font, paging, empty import, HTML isolation, no network.');
 } finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
