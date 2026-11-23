// Kavi 终端页：xterm.js 和 App 之间的桥。思路照 Mika 的 assets/terminal/main.js（Apache-2.0），按 Kavi 改了配色和接口名。
(function () {
  'use strict';

  const term = new Terminal({
    cursorBlink: true,
    cursorStyle: 'bar',
    scrollback: 5000,
    fontFamily: 'ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace',
    fontSize: 13,
    lineHeight: 1.15,
    allowProposedApi: true,
    theme: {
      background: '#111318', foreground: '#E6E6E9', cursor: '#8AB4F8', selectionBackground: '#33405A',
      black: '#1C1E24', red: '#E06C75', green: '#98C379', yellow: '#E5C07B',
      blue: '#61AFEF', magenta: '#C678DD', cyan: '#56B6C2', white: '#D7DAE0',
      brightBlack: '#5C6370', brightRed: '#FF7B86', brightGreen: '#B5E890', brightYellow: '#FFD68A',
      brightBlue: '#7CC4FF', brightMagenta: '#DE97F0', brightCyan: '#73D0DB', brightWhite: '#FFFFFF'
    }
  });

  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('terminal'));

  // 不让终端里的程序改系统剪贴板，也不让 OSC 8 变成能点的链接
  try {
    term.parser.registerOscHandler(52, () => true);
    term.parser.registerOscHandler(8, () => true);
  } catch (e) {}

  const enc = new TextEncoder();
  function toB64(bytes) { let s = ''; for (const b of bytes) s += String.fromCharCode(b); return btoa(s); }
  function fromB64(s) { const bin = atob(s); const a = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i); return a; }

  // 按键行上的 Ctrl / Alt：按下后下一个字带上它
  let ctrl = false, alt = false;
  function send(d) {
    if (ctrl && d.length === 1) {
      const c = d.toUpperCase().charCodeAt(0);
      if (c >= 64 && c <= 95) d = String.fromCharCode(c - 64);
      else if (d === ' ') d = '\u0000';
      ctrl = false; try { Bridge.mods(false, alt); } catch (e) {}
    }
    if (alt) { d = '\u001b' + d; alt = false; try { Bridge.mods(ctrl, false); } catch (e) {} }
    try { Bridge.write(toB64(enc.encode(d))); } catch (e) {}
  }
  term.onData(send);
  term.onResize(({ cols, rows }) => { try { Bridge.resize(cols, rows); } catch (e) {} });

  let fitTimer = 0;
  function fitNow() {
    if (fitTimer) return;
    fitTimer = setTimeout(() => { fitTimer = 0; try { fit.fit(); } catch (e) {} }, 30);
  }
  window.addEventListener('resize', fitNow);
  if (window.ResizeObserver) new ResizeObserver(fitNow).observe(document.getElementById('terminal'));

  setTimeout(() => {
    try { fit.fit(); } catch (e) {}
    try { Bridge.ready(term.cols, term.rows); } catch (e) {}
  }, 50);

  window.kvWrite = s => { try { term.write(fromB64(s)); } catch (e) {} };
  window.kvFocus = () => { try { term.focus(); } catch (e) {} };
  window.kvKey = k => { send(k); try { term.focus(); } catch (e) {} };
  window.kvMods = (c, a) => { ctrl = c; alt = a; };
  window.kvPaste = s => { try { term.paste(new TextDecoder().decode(fromB64(s))); } catch (e) {} };
  window.kvFont = n => { try { term.options.fontSize = n; fit.fit(); } catch (e) {} };
  window.kvCopyAll = () => {
    try {
      const sel = term.getSelection();
      if (sel) { Bridge.copy(sel); term.clearSelection(); return; }
      term.selectAll(); const text = term.getSelection().replace(/\s+$/, ''); term.clearSelection();
      if (text) Bridge.copy(text);
    } catch (e) {}
  };

  document.addEventListener('pointerdown', () => { try { term.focus(); } catch (e) {} }, true);
})();
