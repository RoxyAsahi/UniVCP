(function () {
  const root = document.getElementById('bubble-root');
  let currentPayload = null;
  let resizeObserver = null;

  function report(type, payload) {
    try {
      if (!currentPayload || !window.UniVCPAndroid) return;
      if (type === 'height') {
        window.UniVCPAndroid.reportHeight(currentPayload.id, payload);
      } else if (type === 'status') {
        window.UniVCPAndroid.reportStatus(currentPayload.id, String(payload || ''));
      } else if (type === 'error') {
        window.UniVCPAndroid.reportError(currentPayload.id, String(payload || 'Unknown render error'));
      }
    } catch (_error) {
      // The Android bridge may disappear during WebView release.
    }
  }

  function updateHeight() {
    const height = Math.ceil(Math.max(
      document.documentElement.scrollHeight,
      document.body.scrollHeight,
      root.scrollHeight,
      80
    ));
    report('height', height);
  }

  function applyTheme(theme) {
    if (!theme) return;
    const style = document.documentElement.style;
    style.setProperty('--uvcp-bg', theme.background || '#ffffff');
    style.setProperty('--uvcp-fg', theme.onBackground || '#1f2328');
    style.setProperty('--uvcp-surface', theme.surface || '#f6f8fa');
    style.setProperty('--uvcp-on-surface', theme.onSurface || '#1f2328');
    style.setProperty('--uvcp-primary', theme.primary || '#2563eb');
    style.setProperty('--uvcp-outline', theme.outline || '#d0d7de');
    document.documentElement.dataset.theme = theme.dark ? 'dark' : 'light';
  }

  function escapeHtml(text) {
    return String(text || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function splitTopLevelSelectors(selectorText) {
    const result = [];
    let current = '';
    let depth = 0;
    let quote = null;
    for (let i = 0; i < selectorText.length; i += 1) {
      const ch = selectorText[i];
      if (quote) {
        current += ch;
        if (ch === '\\') {
          current += selectorText[++i] || '';
        } else if (ch === quote) {
          quote = null;
        }
        continue;
      }
      if (ch === '"' || ch === "'") {
        quote = ch;
        current += ch;
        continue;
      }
      if (ch === '(' || ch === '[') depth += 1;
      if (ch === ')' || ch === ']') depth = Math.max(0, depth - 1);
      if (ch === ',' && depth === 0) {
        result.push(current.trim());
        current = '';
      } else {
        current += ch;
      }
    }
    if (current.trim()) result.push(current.trim());
    return result;
  }

  function scopeCss(cssText, scopeId) {
    const css = String(cssText || '').replace(/\/\*[\s\S]*?\*\//g, '');
    return css.replace(/([^{}@][^{}]*)\{([^{}]*)\}/g, function (_match, selector, body) {
      const scoped = splitTopLevelSelectors(selector)
        .map((item) => {
          const trimmed = item.replace(/^(html|body|:root)\b/i, '').trim();
          if (!trimmed || trimmed === '*') return `#${scopeId} *`;
          if (trimmed.startsWith(':')) return `#${scopeId}${trimmed}`;
          return `#${scopeId} ${trimmed}`;
        })
        .join(', ');
      return scoped ? `${scoped} { ${body.trim()} }` : '';
    });
  }

  function extractAndScopeStyles(html, scopeId) {
    const styles = [];
    const content = String(html || '').replace(/<style\b[^>]*>([\s\S]*?)<\/style>/gi, function (_match, css) {
      styles.push(css);
      return '';
    });
    return {
      content,
      styleText: styles.map((css) => scopeCss(css, scopeId)).join('\n')
    };
  }

  function sanitize(html) {
    if (!window.DOMPurify) return html;
    return DOMPurify.sanitize(html, {
      ADD_TAGS: ['math', 'mi', 'mn', 'mo', 'msup', 'msub', 'mfrac', 'annotation'],
      ADD_ATTR: ['target', 'style', 'class', 'id', 'data-*', 'viewBox', 'xmlns'],
      FORBID_TAGS: ['script', 'iframe', 'object', 'embed'],
      ALLOW_DATA_ATTR: true
    });
  }

  function maybeLooksLikeHtml(text) {
    return /<\/?[a-z][\s\S]*>/i.test(String(text || '').trim());
  }

  function renderMarkdown(text) {
    if (!window.marked) {
      return `<pre>${escapeHtml(text)}</pre>`;
    }
    marked.setOptions({
      gfm: true,
      breaks: false,
      mangle: false,
      headerIds: false,
      highlight: function (code, lang) {
        if (window.hljs && lang && hljs.getLanguage(lang)) {
          return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
        }
        return escapeHtml(code);
      }
    });
    return marked.parse(String(text || ''));
  }

  function buildHtmlPreview(payload) {
    const source = payload.rawContent || '';
    if (!payload.allowScript) {
      return sanitize(source);
    }
    const html = payload.language === 'svg'
      ? `<!doctype html><html><body style="margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;">${source}</body></html>`
      : source;
    return `<iframe class="uvcp-preview-frame" sandbox="allow-scripts" srcdoc="${escapeHtml(html)}"></iframe>`;
  }

  function buildThreePreview(payload) {
    if (!payload.allowScript) {
      return '<div class="uvcp-status">Three.js preview is script-gated. Enable interactive preview to run this bubble.</div>';
    }
    const code = payload.rawContent || '';
    const html = `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#0f172a;color:#e2e8f0}#mount{width:100%;height:100%;min-height:220px}.status{padding:14px;font:13px/1.5 monospace}</style>
</head>
<body>
<div id="mount"><div class="status">Loading Three.js preview...</div></div>
<script src="vendor/three.min.js"><\/script>
<script>
const mount=document.getElementById('mount');
function clearStatus(){mount.querySelectorAll('.status').forEach(x=>x.remove())}
const OriginalRenderer=THREE.WebGLRenderer;
THREE.WebGLRenderer=function(...args){
  const renderer=new OriginalRenderer(...args);
  if(renderer.domElement && !renderer.domElement.isConnected){clearStatus();mount.appendChild(renderer.domElement);}
  const originalSetSize=renderer.setSize.bind(renderer);
  renderer.setSize=function(w,h,style){return originalSetSize(Math.min(w||mount.clientWidth||640,mount.clientWidth||640),Math.min(h||360,720),style)}
  return renderer;
};
THREE.WebGLRenderer.prototype=OriginalRenderer.prototype;
try { ${code} } catch (error) { mount.innerHTML='<pre class="status">'+String(error && (error.stack||error.message)||error).replace(/[&<>]/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[ch]))+'</pre>'; }
<\/script>
</body>
</html>`;
    return `<iframe class="uvcp-preview-frame" sandbox="allow-scripts" srcdoc="${escapeHtml(html)}"></iframe>`;
  }

  async function enhanceContent() {
    if (window.hljs) {
      root.querySelectorAll('pre code').forEach((node) => {
        try { hljs.highlightElement(node); } catch (_error) {}
      });
    }

    if (window.renderMathInElement) {
      try {
        renderMathInElement(root, {
          delimiters: [
            { left: '$$', right: '$$', display: true },
            { left: '\\[', right: '\\]', display: true },
            { left: '\\(', right: '\\)', display: false },
            { left: '$', right: '$', display: false }
          ],
          throwOnError: false
        });
      } catch (error) {
        console.warn('KaTeX render failed', error);
      }
    }

    const mermaidBlocks = Array.from(root.querySelectorAll('code.language-mermaid, code.language-flowchart, code.language-graph'));
    mermaidBlocks.forEach((codeNode, index) => {
      const pre = codeNode.closest('pre');
      const div = document.createElement('div');
      div.className = 'mermaid';
      div.textContent = codeNode.textContent.replace(/[\u2013\u2014\u2015]/g, '--');
      div.id = `mermaid-${Date.now()}-${index}`;
      if (pre) pre.replaceWith(div);
    });
    if (window.mermaid && root.querySelector('.mermaid')) {
      try {
        mermaid.initialize({
          startOnLoad: false,
          theme: currentPayload && currentPayload.theme && currentPayload.theme.dark ? 'dark' : 'default',
          securityLevel: 'strict'
        });
        await mermaid.run({ nodes: Array.from(root.querySelectorAll('.mermaid')) });
      } catch (error) {
        root.querySelectorAll('.mermaid').forEach((node) => {
          const original = node.textContent;
          node.innerHTML = `<div class="uvcp-error">Mermaid render failed: ${escapeHtml(error.message || error)}</div><pre>${escapeHtml(original)}</pre>`;
        });
      }
    }
  }

  async function renderPayload(payload) {
    currentPayload = payload;
    try {
      report('status', payload.isStreaming ? 'streaming' : 'rendering');
      applyTheme(payload.theme);
      const scopeId = `bubble-${String(payload.id || 'current').replace(/[^A-Za-z0-9_-]/g, '-')}`;
      root.id = scopeId;
      root.className = 'uvcp-bubble';

      let html;
      if (payload.renderMode === 'CODE_PREVIEW') {
        html = buildHtmlPreview(payload);
      } else if (payload.renderMode === 'THREE') {
        html = buildThreePreview(payload);
      } else if (payload.renderMode === 'RICH_HTML' || maybeLooksLikeHtml(payload.rawContent)) {
        html = payload.rawContent || '';
      } else {
        html = renderMarkdown(payload.rawContent || '');
      }

      const extracted = extractAndScopeStyles(html, scopeId);
      const safeContent = sanitize(extracted.content);
      const nextHtml = `${extracted.styleText ? `<style>${extracted.styleText}</style>` : ''}${safeContent}`;

      if (window.morphdom && root.dataset.rendered === 'true' && payload.isStreaming) {
        morphdom(root, `<div id="${scopeId}" class="uvcp-bubble">${nextHtml}</div>`, {
          onBeforeElUpdated: function (fromEl, toEl) {
            if (fromEl.tagName === 'IFRAME') return false;
            if (fromEl === document.activeElement) return false;
            return true;
          }
        });
      } else {
        root.innerHTML = nextHtml || '<span></span>';
        root.dataset.rendered = 'true';
      }

      await enhanceContent();
      updateHeight();
      setTimeout(updateHeight, 80);
      setTimeout(updateHeight, 450);
      report('status', payload.isStreaming ? 'streaming' : 'ready');
    } catch (error) {
      root.innerHTML = `<div class="uvcp-error">Render failed:\n\n${escapeHtml(error && (error.stack || error.message) || error)}</div>`;
      updateHeight();
      report('error', error && (error.message || error.stack) || error);
    }
  }

  function dispose() {
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
    root.innerHTML = '';
  }

  window.addEventListener('error', (event) => {
    report('error', event.error && (event.error.stack || event.error.message) || event.message);
  });

  window.addEventListener('unhandledrejection', (event) => {
    report('error', event.reason && (event.reason.stack || event.reason.message) || event.reason);
  });

  if (window.ResizeObserver) {
    resizeObserver = new ResizeObserver(updateHeight);
    resizeObserver.observe(document.body);
    resizeObserver.observe(root);
  }

  window.UniVCPRenderer = {
    renderPayload,
    dispose
  };

  report('status', 'ready');
  updateHeight();
})();
