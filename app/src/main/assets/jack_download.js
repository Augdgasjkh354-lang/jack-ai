// Kavi 批量下载、存视频用的脚本：列出页面上的图片、附件链接，找出网页里的视频和音频。
// 每次调用前注入，定义在 window.__jackDl 上。
(function () {
  if (window.__jackDl) return;

  var FILE_EXT = /\.(pdf|docx?|xlsx?|pptx?|csv|txt|md|rtf|odt|ods|odp|epub|mobi|azw3|zip|rar|7z|tar|gz|tgz|bz2|xz|apk|exe|msi|dmg|iso|mp3|m4a|flac|wav|ogg|aac|mp4|mov|mkv|webm|avi|psd|ai|dwg|stl|torrent|json|xml|srt|ass)$/i;
  var IMG_EXT = /\.(jpe?g|png|gif|webp|avif|bmp|heic)(\?|#|$)/i;
  // 懒加载图片把真地址藏在这些属性里
  var LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-url', 'data-actualsrc', 'data-ks-lazyload',
    'data-echo', 'data-original-src', 'data-croporisrc', 'data-hi-res-src', 'data-zoom-image', 'data-large', 'data-big', 'file'];

  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }

  /** srcset 里最大的那张。 */
  function biggest(s) {
    var best = '', bw = 0;
    (s || '').split(',').forEach(function (p) {
      var t = p.trim().split(/\s+/);
      if (!t[0]) return;
      var w = parseFloat(t[1]) || 1;
      if (w >= bw) { bw = w; best = t[0]; }
    });
    return best;
  }

  function images() {
    var out = [], seen = {};
    function add(u, name) {
      u = abs(u);
      if (!u || seen[u] || !/^(https?:|data:image|blob:)/.test(u)) return;
      // 很小的 data: 图多半是占位图
      if (/^data:/.test(u) && u.length < 3000) return;
      seen[u] = 1;
      out.push({ url: u, name: clean(name).slice(0, 40) });
    }
    document.querySelectorAll('img').forEach(function (img) {
      var lazy = '';
      for (var i = 0; i < LAZY.length; i++) {
        var v = img.getAttribute(LAZY[i]);
        if (v && !/^data:/.test(v)) { lazy = v; break; }
      }
      var w = img.naturalWidth, h = img.naturalHeight;
      // 图标、头像、表情这类小图不要（还没加载出来的懒加载图先留着）
      if (w && h && (w < 150 || h < 150) && !lazy) return;
      add(biggest(img.getAttribute('data-srcset')) || lazy || biggest(img.getAttribute('srcset')) || img.currentSrc || img.src, img.alt || img.title);
    });
    document.querySelectorAll('picture source[srcset]').forEach(function (s) { add(biggest(s.getAttribute('srcset')), ''); });
    // 点开看大图的链接
    document.querySelectorAll('a[href]').forEach(function (a) {
      if (IMG_EXT.test(a.href) && a.querySelector('img')) add(a.href, a.title);
    });
    var og = document.querySelector('meta[property="og:image"]');
    if (og && og.content) add(og.content, '');
    return out;
  }

  function files() {
    var out = [], seen = {};
    document.querySelectorAll('a[href]').forEach(function (a) {
      var u = abs(a.getAttribute('href'));
      if (!u || seen[u] || !/^(https?:|data:|blob:)/.test(u)) return;
      var path = u.split('#')[0].split('?')[0];
      if (!a.hasAttribute('download') && !FILE_EXT.test(path)) return;
      seen[u] = 1;
      out.push({ url: u, name: clean(a.getAttribute('download') || a.textContent || a.title).slice(0, 80) });
    });
    return out;
  }

  function collect(kind, filter, max) {
    var list = kind === 'images' ? images() : kind === 'files' ? files() : files().concat(images());
    if (filter) {
      var f = filter.toLowerCase();
      list = list.filter(function (x) { return (x.url + ' ' + x.name).toLowerCase().indexOf(f) >= 0; });
    }
    return JSON.stringify({ total: list.length, items: list.slice(0, max) });
  }

  function media() {
    var els = [];
    document.querySelectorAll('video,audio').forEach(function (v) {
      var srcs = [v.currentSrc, v.src];
      v.querySelectorAll('source').forEach(function (s) { srcs.push(s.src); });
      var r = v.getBoundingClientRect();
      els.push({
        tag: v.tagName.toLowerCase(), srcs: srcs.filter(function (s, i, a) { return s && a.indexOf(s) === i; }),
        duration: isFinite(v.duration) ? Math.round(v.duration) : 0, area: Math.round(r.width * r.height),
        playing: !v.paused, height: v.videoHeight || 0,
      });
    });
    var pi = null;
    try { if (window.__playinfo__) pi = JSON.stringify(window.__playinfo__); } catch (e) {}
    return JSON.stringify({ media: els, playinfo: pi, title: document.title });
  }

  window.__jackDl = { collect: collect, media: media };
})();
