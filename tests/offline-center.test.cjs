const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('app/src/main/assets/offline-center.js', 'utf8');
function setup(native) {
  const events = []; const status = {textContent: ''}; let click;
  const button = {addEventListener(type, fn) { assert.equal(type, 'click'); click = fn; }};
  const context = {window: {Native: native ? {openOfflineVoiceCenter() {events.push('native');}} : undefined}, closePanels() {events.push('close');}, document: {getElementById(id) {return id === 'offlineVoiceCenter' ? button : status;}}};
  vm.runInNewContext(code, context); return {events, status, click};
}
test('offline setup closes Web panels before opening native dialog; repeats remain usable', () => {
  const ui = setup(true); ui.click(); ui.click();
  assert.deepEqual(ui.events, ['close', 'native', 'close', 'native']);
});
test('browser preview explains that installation is unavailable without claiming a model was installed', () => {
  const ui = setup(false); ui.click(); assert.match(ui.status.textContent, /不下载或安装/); assert.deepEqual(ui.events, []);
});
test('offline setup uses a separate packaged script and fixed external destinations', () => {
  const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
  assert.match(html, /id="offlineVoiceCenter"/); assert.match(html, /src="offline-center.js"/);
  const catalog = fs.readFileSync('app/src/main/java/org/inkdock/reader/OfflineVoiceCatalog.kt', 'utf8');
  const urls = [...catalog.matchAll(/https:\/\/[^"\s]+/g)].map(x => new URL(x[0]));
  assert.equal(urls.length, 4); assert.ok(urls.every(u => !u.search && ['github.com', 'k2-fsa.github.io', 'huggingface.co'].includes(u.hostname)));
});
