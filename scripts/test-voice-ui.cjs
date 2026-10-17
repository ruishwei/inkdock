const {chromium}=require('playwright-core');
const assert=require('node:assert/strict');
const path=require('node:path');
const fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||chromium.executablePath(),headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:412,height:915}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
   window.calls=[];
   window.Native={ready(){},panelState(){},stopSpeaking(){calls.push(['stop']);},saveProgress(){},getVoiceSettings(){},
    selectVoiceEngine(name){calls.push(['engine',name]);},setSpeechSource(source){calls.push(['source',source]);},
    configureCloudVoice(source){calls.push(['config',source]);},previewCloudVoice(){calls.push(['preview']);},
    saveVoiceSettings(...args){calls.push(['voice',...args]);}};
  });
  await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/index.html'));
  const state={engine:'Synthetic engine A',enginePackage:'test.a',engines:[{name:'test.a',label:'A'},{name:'test.b',label:'B'}],voices:[{name:'zh-test',language:'中文'}],current:'zh-test',rate:1,source:'offline'};
  await page.evaluate(s=>{window.loadDocument('# Test\n\nSynthetic test paragraph.',0,20,false);window.setVoiceSettings(s);document.getElementById('moreButton').click();document.getElementById('voiceSettings').click();},state);
  assert.equal(await page.locator('#offlineControls').isVisible(),true);
  assert.equal(await page.locator('#cloudControls').isVisible(),false);
  fs.mkdirSync('build/screenshots',{recursive:true});
  await page.screenshot({path:'build/screenshots/07-engine-selection.png'});
  await page.selectOption('#engineChoice','test.b');await page.click('#switchEngine');
  assert.deepEqual(await page.evaluate(()=>calls.slice(-2)),[['stop'],['engine','test.b']]);
  await page.evaluate(s=>window.setVoiceSettings({...s,loading:true,voices:[]}),state);
  assert.equal(await page.locator('#previewVoice').isDisabled(),true);
  await page.evaluate(s=>window.setVoiceSettings({...s,source:'QWEN'}),state);
  assert.equal(await page.locator('#offlineControls').isVisible(),false);
  await page.screenshot({path:'build/screenshots/08-cloud-voice-experimental.png'});
  await page.click('#configureCloud');assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['config','QWEN']);
  await page.click('#previewCloud');assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['preview']);
  await page.click('#cloudStop');assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['stop']);
  await page.selectOption('#speechSource','offline');assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['source','offline']);
  await page.evaluate(()=>window.handleNativeBack());assert.equal(await page.locator('#voicePanel').isVisible(),false);
  assert.deepEqual(errors,[]);
  console.log('PASS: offline/cloud UI, engine switch stop ordering, pending init, config/preview/stop routing, Back panel dismissal. No API calls or real audio.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
