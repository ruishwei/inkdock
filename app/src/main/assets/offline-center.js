'use strict';
(() => {
  const button = document.getElementById('offlineVoiceCenter');
  if (!button) return;
  button.addEventListener('click', () => {
    if (window.Native && typeof window.Native.openOfflineVoiceCenter === 'function') {
      if (typeof cancelReading === 'function') cancelReading();
      if (typeof closePanels === 'function') closePanels();
      window.Native.openOfflineVoiceCenter();
    } else {
      document.getElementById('voiceStatus').textContent = '安装与配置中心仅在 Android 应用内可用；此浏览器预览不下载或安装语音包。';
    }
  });
})();

document.getElementById("kokoroSetup")?.addEventListener("click", () => { if(window.Native?.openKokoroSetup) { closePanels(); window.Native.openKokoroSetup(); } else { document.getElementById("voiceStatus").textContent="Kokoro内置下载仅在包含原生库的Android实验构建可用。"; } });
