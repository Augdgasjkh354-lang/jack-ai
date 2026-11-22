// 动画出片用的时钟（make_animation）：页面里的时间不走真实时钟，由 App 一帧帧拨。
// 拨到 t 秒时：CSS 动画、过渡、Web Animations 都停在 t；SVG 动画停在 t；requestAnimationFrame 排着的回调按 t 跑一次；
// Date.now、performance.now 返回 t；页面自己写了 render(t) 就调它。这样渲染多慢，成片的节奏都和写的一样。
(function () {
  var T = 0, base = Date.now(), queue = [], ids = 0;
    performance.now = function () { return T * 1000; };
  Date.now = function () { return base + T * 1000; };
  // 第一次拨时间之前照常走（fill.js 填内容时要等真的画面帧）；拨过以后回调排队，每拨一帧跑一次
  var realRaf = window.requestAnimationFrame.bind(window), realCancel = window.cancelAnimationFrame.bind(window), armed = false;
  window.requestAnimationFrame = function (cb) { if (!armed) return realRaf(cb); queue.push([++ids, cb]); return -ids; };
  window.cancelAnimationFrame = function (id) { if (id > 0) realCancel(id); else queue = queue.filter(function (q) { return q[0] !== -id; }); };
  // 小工具：K.p(t, 起, 止) 这段里走到几成（0～1）；K.ease.out(x) 等缓动；K.lerp(a, b, x)；K.clamp
  var ease = {
    linear: function (x) { return x; },
    in: function (x) { return x * x * x; },
    out: function (x) { return 1 - Math.pow(1 - x, 3); },
    inOut: function (x) { return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; },
    back: function (x) { var c = 1.70158; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); },
    elastic: function (x) { return x === 0 || x === 1 ? x : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * (2 * Math.PI) / 3) + 1; },
  };
  window.K = {
    ease: ease,
    clamp: function (x, a, b) { return Math.min(b === undefined ? 1 : b, Math.max(a === undefined ? 0 : a, x)); },
    p: function (t, a, b) { return b <= a ? (t >= a ? 1 : 0) : Math.min(1, Math.max(0, (t - a) / (b - a))); },
    lerp: function (a, b, x) { return a + (b - a) * x; },
  };
  window.__kaviSeek = function (t) {
    T = t; armed = true;
    var err = '';
    try {
      document.getAnimations().forEach(function (a) { try { a.pause(); a.currentTime = t * 1000; } catch (e) {} });
    } catch (e) {}
    try { document.querySelectorAll('svg').forEach(function (s) { if (s.pauseAnimations) { s.pauseAnimations(); s.setCurrentTime(t); } }); } catch (e) {}
    var q = queue; queue = [];
    q.forEach(function (it) { try { it[1](t * 1000); } catch (e) { err = err || String(e); } });
    if (typeof window.render === 'function') { try { window.render(t); } catch (e) { err = err || String(e); } }
    // 读一下布局，逼浏览器把这一帧的样式算完
    void document.body.offsetHeight;
    return err;
  };
  window.addEventListener('error', function (e) { window.__kaviErr = window.__kaviErr || (e.message + ' @' + (e.lineno || '')); });
})();
