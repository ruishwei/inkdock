const {test}=require('node:test');const assert=require('node:assert/strict');
const {classify}=require('../app/src/main/assets/gestures.js');
const base={dx:0,dy:0,duration:200,startX:100,width:390};
test('finger down shows and finger up hides tools',()=>{assert.equal(classify({...base,dy:60}),'show');assert.equal(classify({...base,dy:-60}),'hide');});
test('right swipe opens TOC away from both edge-back zones',()=>{assert.equal(classify({...base,dx:100,dy:10}),'toc');for(const x of [0,20,370,389])assert.equal(classify({...base,startX:x,dx:100}),null);});
test('ignores selection, multitouch, excluded targets and long press',()=>{for(const extra of [{selected:true},{multi:true},{blocked:true},{duration:800}])assert.equal(classify({...base,dx:100,...extra}),null);});
test('ignores taps, ambiguous diagonal and left swipes',()=>{for(const extra of [{dx:8,dy:3},{dx:80,dy:70},{dx:-100,dy:0}])assert.equal(classify({...base,...extra}),null);});
