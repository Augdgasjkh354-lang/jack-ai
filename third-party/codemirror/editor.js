// Kavi 代码编辑器：CodeMirror 6 打成一个文件，放在 App 的 assets/editor/ 里，由 WebView 打开（见 ui/CodeEditorScreen.kt）。
// 和 App 之间只走 Bridge（JS → Kotlin）和 window.kv*（Kotlin → JS），文字一律 base64，免得转义出错。
import { EditorView, basicSetup } from 'codemirror';
import { EditorState, Compartment } from '@codemirror/state';
import { keymap } from '@codemirror/view';
import { indentWithTab, undo, redo, undoDepth, redoDepth } from '@codemirror/commands';
import { openSearchPanel, closeSearchPanel, searchPanelOpen } from '@codemirror/search';
import { StreamLanguage, LanguageSupport } from '@codemirror/language';
import { oneDark } from '@codemirror/theme-one-dark';
import { unifiedMergeView } from '@codemirror/merge';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { java } from '@codemirror/lang-java';
import { cpp } from '@codemirror/lang-cpp';
import { sql } from '@codemirror/lang-sql';
import { xml } from '@codemirror/lang-xml';
import { rust } from '@codemirror/lang-rust';
import { go } from '@codemirror/lang-go';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { yaml } from '@codemirror/legacy-modes/mode/yaml';
import { toml } from '@codemirror/legacy-modes/mode/toml';
import { kotlin } from '@codemirror/legacy-modes/mode/clike';

function langFor(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  switch (ext) {
    case 'js': case 'mjs': case 'cjs': return javascript();
    case 'jsx': return javascript({ jsx: true });
    case 'ts': return javascript({ typescript: true });
    case 'tsx': return javascript({ typescript: true, jsx: true });
    case 'py': return python();
    case 'html': case 'htm': case 'vue': return html();
    case 'css': case 'scss': case 'less': return css();
    case 'json': return json();
    case 'md': case 'markdown': return markdown();
    case 'java': return java();
    case 'c': case 'h': case 'cpp': case 'cc': case 'hpp': return cpp();
    case 'sql': return sql();
    case 'xml': case 'svg': return xml();
    case 'rs': return rust();
    case 'go': return go();
    case 'sh': case 'bash': case 'zsh': return new LanguageSupport(StreamLanguage.define(shell));
    case 'yaml': case 'yml': return new LanguageSupport(StreamLanguage.define(yaml));
    case 'toml': return new LanguageSupport(StreamLanguage.define(toml));
    case 'kt': case 'kts': return new LanguageSupport(StreamLanguage.define(kotlin));
    default: return [];
  }
}

const dec = new TextDecoder(), enc = new TextEncoder();
const fromB64 = s => { const b = atob(s); const a = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) a[i] = b.charCodeAt(i); return dec.decode(a); };
const toB64 = t => { const a = enc.encode(t); let s = ''; for (let i = 0; i < a.length; i += 0x8000) s += String.fromCharCode.apply(null, a.subarray(i, i + 0x8000)); return btoa(s); };

const theme = new Compartment(), lang = new Compartment(), wrap = new Compartment(), diff = new Compartment(), ro = new Compartment();
// 界面上的字换成中文（查找替换面板、对比的按钮、折叠提示）
const zh = EditorState.phrases.of({
  'Accept': '保留', 'Reject': '退回', 'Find': '查找', 'Replace': '替换', 'next': '下一个', 'previous': '上一个', 'all': '全部',
  'match case': '区分大小写', 'regexp': '正则', 'by word': '整词', 'replace': '替换', 'replace all': '全部替换', 'close': '关闭',
  'current match': '当前', 'on line': '在第', 'Go to line': '跳到行', 'go': '跳', 'Folded lines': '折叠的行', 'Unfolded lines': '展开的行',
  'to': '到', 'folded code': '折叠的代码', 'unfold': '展开', 'Fold line': '折叠', 'Unfold line': '展开',
});
let saved = '';
let view = null;

const light = EditorView.theme({ '&': { backgroundColor: '#FFFFFF' }, '.cm-gutters': { backgroundColor: '#F6F7F9', border: 'none' } }, { dark: false });
const base = EditorView.theme({
  '&': { height: '100%', fontSize: '13px' },
  '.cm-scroller': { fontFamily: 'ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace', lineHeight: '1.5' },
  '.cm-content': { paddingBottom: '40vh' },
});

function report() {
  if (!view) return;
  const text = view.state.doc.toString();
  try { Bridge.state(text !== saved, undoDepth(view.state) > 0, redoDepth(view.state) > 0, view.state.doc.lines); } catch (e) {}
}

window.kvOpen = (b64, name, dark, wrapOn, readOnly) => {
  const text = fromB64(b64);
  saved = text;
  const state = EditorState.create({
    doc: text,
    extensions: [
      basicSetup,
      keymap.of([indentWithTab]),
      base,
      theme.of(dark ? oneDark : light),
      lang.of(langFor(name)),
      wrap.of(wrapOn ? EditorView.lineWrapping : []),
      diff.of([]),
      ro.of(EditorState.readOnly.of(!!readOnly)),
      zh,
      EditorView.updateListener.of(u => { if (u.docChanged) report(); }),
    ],
  });
  if (view) view.setState(state); else view = new EditorView({ state, parent: document.getElementById('editor') });
  report();
};
window.kvSave = () => { if (!view) return; const t = view.state.doc.toString(); try { Bridge.save(toB64(t)); } catch (e) {} };
window.kvSaved = () => { if (view) { saved = view.state.doc.toString(); report(); } };
window.kvUndo = () => { if (view) { undo(view); view.focus(); } };
window.kvRedo = () => { if (view) { redo(view); view.focus(); } };
window.kvSearch = () => { if (!view) return; if (searchPanelOpen(view.state)) closeSearchPanel(view); else openSearchPanel(view); };
window.kvWrap = on => { if (view) view.dispatch({ effects: wrap.reconfigure(on ? EditorView.lineWrapping : []) }); };
window.kvTheme = dark => { if (view) view.dispatch({ effects: theme.reconfigure(dark ? oneDark : light) }); };
window.kvFont = px => { document.documentElement.style.setProperty('--kv-font', px + 'px'); if (view) { view.dom.style.fontSize = px + 'px'; view.requestMeasure(); } };
// 和旧版本比：改动直接标在正文里（删掉的红、加上的绿），可以一段段退回
window.kvDiff = b64 => {
  if (!view) return;
  view.dispatch({ effects: diff.reconfigure(b64 == null ? [] : unifiedMergeView({ original: fromB64(b64), mergeControls: true, highlightChanges: true })) });
};
window.kvGoto = line => {
  if (!view) return;
  const l = view.state.doc.line(Math.max(1, Math.min(line, view.state.doc.lines)));
  view.dispatch({ selection: { anchor: l.from }, scrollIntoView: true }); view.focus();
};
try { Bridge.ready(); } catch (e) {}
