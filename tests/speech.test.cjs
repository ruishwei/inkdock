const {test}=require('node:test');const assert=require('node:assert/strict');
const {splitText,splitOfflineText}=require('../app/src/main/assets/speech.js');
test('empty and whitespace selections produce no requests',()=>{assert.deepEqual(splitText(' \n '),[]);assert.deepEqual(splitText(null),[]);});
test('long Chinese text is split without truncation',()=>{const text='这是合成朗读测试。'.repeat(1000);const parts=splitText(text);assert.ok(parts.length>1);assert.equal(parts.join(''),text);assert.ok(parts.every(p=>p.length<=2800));});
test('surrogate pairs are never cut in half',()=>{const text='😀'.repeat(3000);const parts=splitText(text,99);assert.equal(parts.join(''),text);assert.ok(parts.every(p=>!/[\uD800-\uDBFF]$/.test(p)&&!/[\uDC00-\uDFFF]/.test(p[0])));});

test('cloud cap splits safely without dropping long synthetic text',()=>{const text='云端合成验证😀。'.repeat(200);const parts=splitText(text,600);assert.equal(parts.join(''),text);assert.ok(parts.every(p=>p.length<=600));});

function assertOfflineBounds(text,firstChunk=true){
 const parts=splitOfflineText(text,firstChunk);
 assert.equal(parts.join(''),text);
 let first=firstChunk;
 for(const part of parts){
  assert.ok(part.length>0&&part.length<=(first?120:360));
  assert.ok(!/[\uD800-\uDBFF]$/.test(part)&&!/[\uDC00-\uDFFF]/.test(part[0]));
  if(part.trim())first=false;
 }
 return parts;
}
test('offline starts with one complete Chinese sentence, then bounded sentence groups',()=>{
 const text='第一句很短。  第二句也短！\n'+('接着读下一组句子；'.repeat(100));
 const parts=assertOfflineBounds(text);
 assert.equal(parts[0],'第一句很短。  ');
 assert.ok(parts.slice(1).some(part=>part.length>120));
 assert.ok(parts.every(part=>/[。！？；]\s*$/.test(part)));
});
test('offline recognizes English sentence punctuation and closing quotes',()=>{
 for(const first of ['A short sentence.  ','Is it ready?!\n','She said, "Ready!"  ','She answered “Yes.”\n','第一句说：“你好！”  ']){
  const text=first+'The next sentence follows. '.repeat(40);
  assert.equal(assertOfflineBounds(text)[0],first);
 }
});
test('offline does not treat decimal points or in-word periods as sentence ends',()=>{
 const first='Version 3.14 at example.com is ready. ';
 assert.equal(assertOfflineBounds(first+'A second sentence.')[0],first);
});
test('offline hard caps unpunctuated text and preserves all text and whitespace',()=>{
 for(const text of ['一'.repeat(2000),'a'.repeat(2000),'首句没有句号\n\n第二行\t'+('测试\t 空格  '.repeat(200)),
  ' '.repeat(250)+'text'+' '.repeat(1000)+'tail\n', '头'.repeat(120)+' '+('后'.repeat(800))])assertOfflineBounds(text);
});
test('offline preserves surrogate pairs at first and subsequent hard caps',()=>{
 for(const text of ['一'.repeat(119)+'😀'+'二'.repeat(1000), '一'.repeat(120)+'二'.repeat(359)+'😀'+'三'.repeat(600), '😀'.repeat(1200)])assertOfflineBounds(text);
});
test('offline subsequent paragraphs use moderate groups rather than a second short lead',()=>{
 const text='这段仍在继续。'.repeat(100);
 const parts=assertOfflineBounds(text,false);
 assert.ok(parts[0].length>120);
});
test('offline handles absent and whitespace-only input without a request',()=>{
 for(const text of ['',null,undefined,' \r\n\t '])assert.deepEqual(splitOfflineText(text),[]);
});
