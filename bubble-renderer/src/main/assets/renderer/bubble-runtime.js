(function () {
  const root = document.getElementById('bubble-root');
  const pipeline = window.UniVCPContentPipeline
    ? window.UniVCPContentPipeline.createContentPipeline()
    : null;
  const PIPELINE_MODES = window.UniVCPContentPipeline
    ? window.UniVCPContentPipeline.PIPELINE_MODES
    : { FULL_RENDER: 'full-render', STREAM_FAST: 'stream-fast' };

  let currentPayload = null;
  let resizeObserver = null;
  let renderSeq = 0;
  let lastReportedHeight = 0;
  const previewCleanups = new Map();
  const loadedScripts = new Map();

  function report(type, payload) {
    try {
      if (!currentPayload || !window.UniVCPAndroid) return;
      if (type === 'height') {
        window.UniVCPAndroid.reportHeight(currentPayload.id, payload);
      } else if (type === 'status') {
        window.UniVCPAndroid.reportStatus(currentPayload.id, String(payload || ''));
      } else if (type === 'error') {
        window.UniVCPAndroid.reportError(currentPayload.id, String(payload || 'Unknown render error'));
      } else if (type === 'input') {
        window.UniVCPAndroid.sendInput(currentPayload.id, String(payload || ''));
      }
    } catch (_error) {
      // The Android bridge may disappear during WebView release.
    }
  }

  function updateHeight() {
    const rootRect = root.getBoundingClientRect ? root.getBoundingClientRect() : null;
    const bodyRect = document.body && document.body.getBoundingClientRect
      ? document.body.getBoundingClientRect()
      : null;
    const height = Math.ceil(Math.max(
      document.documentElement.scrollHeight,
      document.body.scrollHeight,
      root.scrollHeight,
      root.offsetHeight || 0,
      rootRect ? rootRect.height : 0,
      bodyRect ? bodyRect.height : 0,
      80
    )) + 2;
    if (Math.abs(height - lastReportedHeight) < 4) return;
    lastReportedHeight = height;
    report('height', height);
  }

  function scheduleHeightReports() {
    updateHeight();
    requestAnimationFrame(updateHeight);
    requestAnimationFrame(() => requestAnimationFrame(updateHeight));
    setTimeout(updateHeight, 80);
    setTimeout(updateHeight, 250);
    setTimeout(updateHeight, 700);
    setTimeout(updateHeight, 1400);
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(updateHeight).catch(() => {});
    }
    root.querySelectorAll('img, video, canvas, svg').forEach((node) => {
      node.addEventListener('load', updateHeight, { once: true });
      node.addEventListener('error', updateHeight, { once: true });
    });
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

  function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') {
      return window.CSS.escape(String(value || ''));
    }
    return String(value || '').replace(/["\\]/g, '\\$&');
  }

  function decodeHtmlEntities(text) {
    const textarea = document.createElement('textarea');
    textarea.innerHTML = String(text || '');
    return textarea.value;
  }

  function loadScriptOnce(src, globalName) {
    if (globalName && window[globalName]) return Promise.resolve();
    if (loadedScripts.has(src)) return loadedScripts.get(src);
    const promise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error(`Failed to load ${src}`));
      document.head.appendChild(script);
    });
    loadedScripts.set(src, promise);
    return promise;
  }

  function sanitize(html) {
    if (!window.DOMPurify) return html;
    const cleaned = DOMPurify.sanitize(html, {
      USE_PROFILES: { html: true, svg: true, svgFilters: true, mathMl: true },
      ADD_TAGS: [
        'math', 'mi', 'mn', 'mo', 'msup', 'msub', 'mfrac', 'annotation',
        'semantics', 'mrow', 'msqrt'
      ],
      ADD_ATTR: [
        'target', 'style', 'class', 'id', 'role', 'aria-label', 'aria-hidden',
        'data-send', 'data-action', 'data-label', 'viewBox', 'xmlns',
        'fill', 'stroke', 'stroke-width', 'd', 'cx', 'cy', 'r', 'x', 'y',
        'x1', 'x2', 'y1', 'y2', 'points', 'preserveAspectRatio',
        'transform', 'width', 'height'
      ],
      FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'link', 'meta', 'base'],
      FORBID_ATTR: ['srcdoc', 'formaction'],
      ALLOW_DATA_ATTR: true
    });

    const template = document.createElement('template');
    template.innerHTML = cleaned;
    template.content.querySelectorAll('*').forEach((node) => {
      Array.from(node.attributes).forEach((attr) => {
        if (/^on/i.test(attr.name)) {
          node.removeAttribute(attr.name);
        }
      });
    });
    return template.innerHTML;
  }

  function scopeCss(cssText, scopeId) {
    if (window.UniVCPScopedCss && typeof window.UniVCPScopedCss.scopeCss === 'function') {
      return window.UniVCPScopedCss.scopeCss(cssText, scopeId);
    }
    return String(cssText || '').replace(/([^{}@][^{}]*)\{([^{}]*)\}/g, (_match, selector, body) => {
      const scoped = selector.split(',')
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
    const content = String(html || '').replace(/<style\b[^>]*>([\s\S]*?)<\/style>/gi, (_match, css) => {
      styles.push(css);
      return '';
    });
    return {
      content,
      styleText: styles.map((css) => scopeCss(css, scopeId)).join('\n')
    };
  }

  function isFullHtmlDocument(text) {
    const trimmed = String(text || '').trim();
    return /^<!doctype\s+html\b/i.test(trimmed) || /^<html\b/i.test(trimmed) || /<\/html>\s*$/i.test(trimmed);
  }

  function maybeLooksLikeHtml(text) {
    const trimmed = String(text || '').trim();
    if (!trimmed.startsWith('<')) return false;
    return /<\/?[a-z][\s\S]*>/i.test(trimmed);
  }

  function normalizeLanguage(language) {
    return String(language || '').trim().toLowerCase();
  }

  function sourceLanguage(payload) {
    return normalizeLanguage(payload.sourceLanguage || payload.language || '');
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
        const language = normalizeLanguage(lang);
        if (window.hljs && language && hljs.getLanguage(language)) {
          return hljs.highlight(code, { language, ignoreIllegals: true }).value;
        }
        return escapeHtml(code);
      }
    });
    return marked.parse(String(text || ''));
  }

  function buildCodeBlock(source, language) {
    const lang = normalizeLanguage(language) || 'html';
    return `<pre><code class="language-${escapeHtml(lang)}">${escapeHtml(source)}</code></pre>`;
  }

  function restoreProtocolBlocks(html, state) {
    if (window.UniVCPContentPipeline && typeof window.UniVCPContentPipeline.restoreProtocolBlocks === 'function') {
      return window.UniVCPContentPipeline.restoreProtocolBlocks(html, state && state.protocolPlaceholders);
    }
    return html;
  }

  function processContent(rawContent, isStreaming) {
    if (!pipeline) {
      return {
        text: String(rawContent || ''),
        state: { protocolPlaceholders: new Map() },
        meta: { stepsApplied: [] }
      };
    }
    return pipeline.process(String(rawContent || ''), {
      mode: isStreaming ? PIPELINE_MODES.STREAM_FAST : PIPELINE_MODES.FULL_RENDER
    });
  }

  function renderToHtml(payload) {
    const processed = processContent(payload.rawContent || '', payload.isStreaming);
    const text = processed.text;
    const language = sourceLanguage(payload);
    let html;

    if (payload.renderMode === 'CODE_PREVIEW') {
      html = buildCodeBlock(text, language || 'html');
    } else if (payload.renderMode === 'THREE') {
      html = buildCodeBlock(text, 'threejs');
    } else if (!payload.isStreaming && isFullHtmlDocument(text)) {
      html = buildCodeBlock(text, language || 'html');
    } else if (payload.renderMode === 'RICH_HTML' || maybeLooksLikeHtml(text)) {
      html = text;
    } else {
      html = renderMarkdown(text);
    }

    return restoreProtocolBlocks(html, processed.state);
  }

  function cleanupPreviews() {
    previewCleanups.forEach((cleanup) => {
      try { cleanup(); } catch (_error) {}
    });
    previewCleanups.clear();
  }

  function getCodeLanguage(codeNode) {
    const className = codeNode.className || '';
    const match = className.match(/\blanguage-([\w-]+)/i);
    return normalizeLanguage(match ? match[1] : '');
  }

  function bridgeScript(frameId) {
    return `
<script>
(function(){
  var frameId = ${JSON.stringify(frameId)};
  var lastHeight = 0;
  function postStatus(status, message) {
    try { parent.postMessage({ type: 'univcp-preview-status', frameId: frameId, status: status, message: message || '' }, '*'); } catch (e) {}
  }
  function measure() {
    var body = document.body || document.documentElement;
    return Math.ceil(Math.max(
      document.documentElement ? document.documentElement.scrollHeight : 0,
      body ? body.scrollHeight : 0,
      body ? body.offsetHeight : 0,
      160
    ));
  }
  function postResize() {
    var height = measure();
    if (Math.abs(height - lastHeight) < 2) return;
    lastHeight = height;
    try { parent.postMessage({ type: 'univcp-preview-resize', frameId: frameId, height: height }, '*'); } catch (e) {}
  }
  window.addEventListener('error', function(event) {
    postStatus('error', event.message || 'Preview error');
    setTimeout(postResize, 0);
  });
  window.addEventListener('unhandledrejection', function(event) {
    var reason = event.reason && (event.reason.stack || event.reason.message) || event.reason;
    postStatus('error', String(reason || 'Preview promise rejection'));
    setTimeout(postResize, 0);
  });
  if (window.ResizeObserver && document.body) {
    new ResizeObserver(postResize).observe(document.body);
  }
  requestAnimationFrame(function(){ postResize(); postStatus('ready', 'ready'); });
  setTimeout(postResize, 250);
  setTimeout(postResize, 1000);
})();
<\/script>`;
  }

  function injectBridgeIntoHtmlDocument(source, frameId) {
    const instrumentation = bridgeScript(frameId);
    const doc = String(source || '');
    if (/<\/body>/i.test(doc)) {
      return doc.replace(/<\/body>/i, `${instrumentation}</body>`);
    }
    return `${doc}${instrumentation}`;
  }

  function buildHtmlPreviewDocument(source, language, frameId) {
    const lang = normalizeLanguage(language);
    if (lang === 'svg' || /^\s*<svg[\s>]/i.test(source)) {
      return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;min-height:100%;background:transparent}body{display:flex;align-items:center;justify-content:center;padding:12px;box-sizing:border-box}svg{max-width:100%;height:auto}</style>
</head>
<body>
${source}
${bridgeScript(frameId)}
</body>
</html>`;
    }

    if (isFullHtmlDocument(source)) {
      return injectBridgeIntoHtmlDocument(source, frameId);
    }

    return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;min-height:100%;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;background:transparent;color:#111827}body{padding:12px;box-sizing:border-box}img,svg,canvas,video{max-width:100%;height:auto}</style>
</head>
<body>
${source}
${bridgeScript(frameId)}
</body>
</html>`;
  }

  function buildThreePreviewDocument(source, frameId) {
    const safeSource = String(source || '').replace(/<\/script/gi, '<\\/script');
    return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;width:100%;min-height:100%;overflow:hidden;background:#0f172a;color:#e2e8f0}
#mount{width:100%;height:100%;min-height:320px}
.status{padding:14px;font:13px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre-wrap}
canvas{display:block;max-width:100%}
</style>
</head>
<body>
<div id="mount"><div class="status">Loading Three.js preview...</div></div>
<script src="vendor/three.min.js"><\/script>
<script>
(function(){
  var mount = document.getElementById('mount');
  function escapeText(value){return String(value || '').replace(/[&<>]/g,function(ch){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[ch];});}
  function clearStatus(){Array.prototype.slice.call(mount.querySelectorAll('.status')).forEach(function(node){node.remove();});}
  if (!window.THREE) throw new Error('THREE failed to load.');
  var OriginalRenderer = THREE.WebGLRenderer;
  THREE.WebGLRenderer = function() {
    var renderer = new (Function.prototype.bind.apply(OriginalRenderer, [null].concat(Array.prototype.slice.call(arguments))))();
    if (renderer.domElement && !renderer.domElement.isConnected) {
      clearStatus();
      mount.appendChild(renderer.domElement);
    }
    var originalSetSize = renderer.setSize.bind(renderer);
    renderer.setSize = function(width, height, updateStyle) {
      var nextWidth = Math.min(width || mount.clientWidth || 640, mount.clientWidth || 640);
      var nextHeight = Math.min(height || 360, 720);
      return originalSetSize(nextWidth, nextHeight, updateStyle);
    };
    return renderer;
  };
  THREE.WebGLRenderer.prototype = OriginalRenderer.prototype;
  try {
${safeSource}
    if (!mount.querySelector('canvas')) {
      var canvas = document.querySelector('canvas');
      if (canvas && !mount.contains(canvas)) {
        clearStatus();
        mount.appendChild(canvas);
      }
    }
  } catch (error) {
    mount.innerHTML = '<pre class="status">' + escapeText(error && (error.stack || error.message) || error) + '</pre>';
    throw error;
  }
})();
<\/script>
${bridgeScript(frameId)}
</body>
</html>`;
  }

  function createPreviewContainer(preElement, options) {
    if (!preElement || preElement.closest('.uvcp-preview-container')) return;

    const codeNode = preElement.querySelector('code');
    const source = codeNode ? codeNode.textContent || '' : preElement.textContent || '';
    const frameId = `uvcp-preview-${Math.random().toString(36).slice(2)}`;
    const container = document.createElement('div');
    container.className = 'uvcp-preview-container';
    container.dataset.frameId = frameId;

    const toolbar = document.createElement('div');
    toolbar.className = 'uvcp-preview-toolbar';

    const status = document.createElement('div');
    status.className = 'uvcp-preview-status';
    status.hidden = true;

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'uvcp-preview-toggle';
    toggle.textContent = options.openLabel || 'Run';

    toolbar.appendChild(toggle);
    preElement.parentNode.insertBefore(container, preElement);
    container.appendChild(toolbar);
    container.appendChild(preElement);
    container.appendChild(status);

    let frame = null;
    let showingPreview = false;
    let timeout = null;

    function setStatus(nextStatus, message) {
      container.dataset.previewStatus = nextStatus;
      status.hidden = !message || nextStatus === 'ready';
      status.textContent = message || '';
      report('status', `preview:${nextStatus}`);
      scheduleHeightReports();
    }

    function destroyFrame() {
      if (timeout) {
        clearTimeout(timeout);
        timeout = null;
      }
      if (frame) {
        try {
          frame.srcdoc = '';
          frame.src = 'about:blank';
          frame.contentWindow && frame.contentWindow.stop && frame.contentWindow.stop();
        } catch (_error) {}
        frame.remove();
        frame = null;
      }
    }

    function showSource() {
      showingPreview = false;
      toggle.textContent = options.openLabel || 'Run';
      preElement.hidden = false;
      destroyFrame();
      setStatus('source', '');
    }

    function showPreview() {
      showingPreview = true;
      toggle.textContent = options.closeLabel || 'Source';
      preElement.hidden = true;
      setStatus('loading', options.loadingLabel || 'Loading preview...');

      frame = document.createElement('iframe');
      frame.className = 'uvcp-preview-frame';
      frame.dataset.frameId = frameId;
      frame.sandbox = options.sandbox || 'allow-scripts allow-modals';
      frame.style.height = `${options.fallbackHeight || 220}px`;
      frame.srcdoc = options.buildSrcdoc(source, frameId);
      container.appendChild(frame);

      timeout = setTimeout(() => {
        if (showingPreview) {
          setStatus('error', 'Preview did not report ready state.');
        }
      }, 4000);
      updateHeight();
    }

    toggle.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (showingPreview) {
        showSource();
      } else {
        showPreview();
      }
    });

    previewCleanups.set(frameId, destroyFrame);
  }

  function setupCodePreviews() {
    root.querySelectorAll('pre code').forEach((codeNode) => {
      const pre = codeNode.closest('pre');
      if (!pre || pre.dataset.uvcpPreviewReady === 'true') return;

      const language = getCodeLanguage(codeNode);
      const source = codeNode.textContent || '';
      if (['html', 'svg'].includes(language) || (language === '' && isFullHtmlDocument(source))) {
        pre.dataset.uvcpPreviewReady = 'true';
        createPreviewContainer(pre, {
          openLabel: 'Run',
          closeLabel: 'Source',
          fallbackHeight: language === 'svg' ? 260 : 220,
          buildSrcdoc: (content, frameId) => buildHtmlPreviewDocument(content, language || 'html', frameId)
        });
      } else if (['js', 'javascript', 'threejs'].includes(language) && /\bTHREE\./.test(source)) {
        pre.dataset.uvcpPreviewReady = 'true';
        createPreviewContainer(pre, {
          openLabel: 'Preview',
          closeLabel: 'Source',
          fallbackHeight: 360,
          buildSrcdoc: (content, frameId) => buildThreePreviewDocument(content, frameId)
        });
      }
    });
  }

  function bindInteractiveButtons() {
    root.querySelectorAll('button').forEach((button) => {
      if (button.dataset.uvcpBridgeBound === 'true') return;
      if (button.closest('.uvcp-preview-container')) return;
      button.dataset.uvcpBridgeBound = 'true';
      if (!button.className) button.classList.add('uvcp-ai-button');
      button.type = 'button';

      button.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        const text = (button.dataset.send || button.dataset.input || button.value || button.textContent || '').trim();
        if (!text) {
          report('error', 'Button has no input text.');
          return;
        }
        report('input', text.slice(0, 1000));
        button.dataset.sent = 'true';
      });
    });
  }

  async function renderMermaidBlocks() {
    const mermaidBlocks = Array.from(root.querySelectorAll('code.language-mermaid, code.language-flowchart, code.language-graph'));
    if (mermaidBlocks.length > 0) {
      await loadScriptOnce('vendor/mermaid.min.js', 'mermaid');
    }
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

  async function enhanceContent() {
    if (!window.renderMathInElement && /\$[^$\n]+\$|\\\(|\\\[|\$\$/.test(root.textContent || '')) {
      await loadScriptOnce('vendor/katex.min.js', 'katex');
      await loadScriptOnce('vendor/auto-render.min.js', 'renderMathInElement');
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

    await renderMermaidBlocks();

    const highlightTargets = Array.from(root.querySelectorAll('pre code')).filter((node) => {
      if (node.closest('.uvcp-protocol-block')) return false;
      return /\blanguage-[A-Za-z0-9_-]+\b/.test(node.className || '');
    });
    if (highlightTargets.length > 0 && !window.hljs) {
      await loadScriptOnce('vendor/highlight.min.js', 'hljs');
    }

    if (window.hljs) {
      highlightTargets.forEach((node) => {
        try { hljs.highlightElement(node); } catch (_error) {}
      });
    }

    setupCodePreviews();
    bindInteractiveButtons();
  }

  async function renderPayload(payload) {
    const seq = ++renderSeq;
    currentPayload = payload;
    lastReportedHeight = 0;
    try {
      report('status', payload.isStreaming ? 'streaming' : 'rendering');
      applyTheme(payload.theme);
      cleanupPreviews();

      const scopeId = `bubble-${String(payload.id || 'current').replace(/[^A-Za-z0-9_-]/g, '-')}`;
      root.id = scopeId;
      root.className = 'uvcp-bubble';
      root.setAttribute('aria-live', 'polite');

      const html = renderToHtml(payload);
      const extracted = extractAndScopeStyles(html, scopeId);
      const safeContent = sanitize(extracted.content);
      const nextHtml = `${extracted.styleText ? `<style>${extracted.styleText}</style>` : ''}${safeContent}`;

      if (window.morphdom && root.dataset.rendered === 'true' && payload.isStreaming) {
        morphdom(root, `<div id="${scopeId}" class="uvcp-bubble" aria-live="polite">${nextHtml}</div>`, {
          onBeforeElUpdated: function (fromEl) {
            if (fromEl.tagName === 'IFRAME') return false;
            if (fromEl === document.activeElement) return false;
            return true;
          }
        });
      } else {
        root.innerHTML = nextHtml || '<span></span>';
        root.dataset.rendered = 'true';
      }

      if (seq !== renderSeq) return;
      if (!payload.isStreaming) {
        await enhanceContent();
      }

      scheduleHeightReports();
      report('status', payload.isStreaming ? 'streaming' : 'ready');
    } catch (error) {
      root.innerHTML = `<div class="uvcp-error">Render failed:\n\n${escapeHtml(error && (error.stack || error.message) || error)}</div>`;
      updateHeight();
      report('error', error && (error.message || error.stack) || error);
    }
  }

  function dispose() {
    cleanupPreviews();
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
    root.innerHTML = '';
  }

  window.addEventListener('message', (event) => {
    const data = event.data || {};
    if (!data || typeof data !== 'object') return;
    if (data.type === 'univcp-preview-resize') {
      const frame = root.querySelector(`iframe[data-frame-id="${cssEscape(data.frameId)}"]`);
      if (frame) {
        frame.style.height = `${Math.max(160, Number(data.height) || 220)}px`;
        updateHeight();
      }
    } else if (data.type === 'univcp-preview-status') {
      const container = root.querySelector(`.uvcp-preview-container[data-frame-id="${cssEscape(data.frameId)}"]`);
      if (container) {
        const status = container.querySelector('.uvcp-preview-status');
        if (status) {
          status.hidden = !data.message || data.status === 'ready';
          status.textContent = data.message || '';
        }
        container.dataset.previewStatus = data.status || 'ready';
        report('status', `preview:${data.status || 'ready'}`);
        updateHeight();
      }
    }
  });

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

  updateHeight();
})();
