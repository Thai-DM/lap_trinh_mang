/* Prepare one context on the actual game canvas. A failed GPU must never reach TeaVM's GL constructor. */
(function () {
  'use strict';
  let started = false;
  let failed = false;
  window.smashprofsGraphicsLost = false;
  const graphics = {mode: 'pending', attempts: 0};
  window.smashprofsGraphics = graphics;

  window.showFailure = function (reason, code) {
    if (failed) return;
    failed = true;
    document.getElementById('loading').hidden = true;
    document.getElementById('failure').hidden = false;
    document.getElementById('failure').dataset.code = code || 'game-error';
    document.getElementById('failure-message').textContent = String(reason);
    document.getElementById('graphics-help').hidden = code !== 'webgl-unavailable' && code !== 'webgl-lost';
  };
  window.retryCompatibleGraphics = function () {
    const url = new URL(location.href);
    url.searchParams.set('graphics', 'compat');
    url.searchParams.delete('effects');
    location.assign(url.href);
  };
  window.startSmashProfs = function () {
    if (started || failed) return;
    const canvas = document.getElementById('canvas');
    const original = canvas.getContext.bind(canvas);
    const compatible = new URLSearchParams(location.search).get('graphics') === 'compat';
    const modes = compatible ? ['low-power', 'default'] : ['default', 'low-power'];
    let context = null;
    for (const mode of modes) {
      for (const kind of ['webgl', 'experimental-webgl']) {
        graphics.attempts++;
        try {
          context = original(kind, {alpha: false, antialias: false, stencil: false, premultipliedAlpha: false,
            preserveDrawingBuffer: false, failIfMajorPerformanceCaveat: false, powerPreference: mode});
        } catch (_) { context = null; }
        if (context && !context.isContextLost()) {
          graphics.mode = mode;
          break;
        }
        context = null;
      }
      if (context) break;
    }
    if (!context) {
      graphics.mode = 'unavailable';
      window.showFailure('Trình duyệt chưa tạo được WebGL để vẽ trò chơi. Hãy bật tăng tốc đồ họa, khởi động lại trình duyệt và thử lại.', 'webgl-unavailable');
      return;
    }
    // The pinned backend asks for webgl again. Reuse this exact context, including the legacy-name fallback.
    canvas.getContext = function (kind, attributes) {
      return kind === 'webgl' || kind === 'experimental-webgl' ? context : original(kind, attributes);
    };
    canvas.addEventListener('webglcontextlost', function (event) {
      event.preventDefault();window.smashprofsGraphicsLost = true;
      window.showFailure('Trình duyệt vừa mất kết nối đồ họa. Đóng bớt tab game rồi tải lại. Hồ sơ và điểm đã lưu vẫn được giữ.', 'webgl-lost');
    });
    if (typeof window.main !== 'function') { window.showFailure('Không tải được game.js. Hãy tải lại trang.');return; }
    started = true;
    try { window.main(); } catch (error) { window.showFailure(error.message || error); }
  };
  addEventListener('error', event => { if (event.message) window.showFailure(event.message); });
  addEventListener('unhandledrejection', event => window.showFailure(event.reason));
  addEventListener('load', window.startSmashProfs);
}());
