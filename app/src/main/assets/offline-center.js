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
