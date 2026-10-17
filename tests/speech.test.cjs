const {test}=require('node:test');const assert=require('node:assert/strict');
const {splitText}=require('../app/src/main/assets/speech.js');
test('empty and whitespace selections produce no requests',()=>{assert.deepEqual(splitText(' \n '),[]);assert.deepEqual(splitText(null),[]);});
test('long Chinese text is split without truncation',()=>{const text='这是合成朗读测试。'.repeat(1000);const parts=splitText(text);assert.ok(parts.length>1);assert.equal(parts.join(''),text);assert.ok(parts.every(p=>p.length<=2800));});
test('surrogate pairs are never cut in half',()=>{const text='😀'.repeat(3000);const parts=splitText(text,99);assert.equal(parts.join(''),text);assert.ok(parts.every(p=>!/[\uD800-\uDBFF]$/.test(p)&&!/[\uDC00-\uDFFF]/.test(p[0])));});
