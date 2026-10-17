const {test}=require('node:test');const assert=require('node:assert/strict');
const {isTap}=require('../app/src/main/assets/gestures.js');
test('deliberate stationary tap is accepted',()=>assert.equal(isTap({distance:2,duration:120,sinceScroll:800}),true));
test('all swipes, long holds and post-scroll taps are rejected',()=>{for(const args of [{distance:80},{duration:500},{sinceScroll:100}])assert.equal(isTap(args),false);});
test('selection, controls and multitouch are rejected',()=>{for(const args of [{selected:true},{blocked:true},{multi:true}])assert.equal(isTap(args),false);});
test('movement threshold and scroll cooldown are bounded',()=>{assert.equal(isTap({distance:10,duration:350,sinceScroll:450}),true);assert.equal(isTap({distance:11}),false);assert.equal(isTap({sinceScroll:449}),false);});
