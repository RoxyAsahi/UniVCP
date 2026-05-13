(function () {
  const PIPELINE_MODES = {
    FULL_RENDER: 'full-render',
    STREAM_FAST: 'stream-fast'
  };

  function escapeHtml(text) {
    return String(text || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function processStartEndMarkers(text) {
    if (typeof text !== 'string' || !text.includes('「始」')) return text;
    return text.replace(/「始」([\s\S]*?)(「末」|$)/g, (_match, content, end) => {
      return `「始」${escapeHtml(content)}${end}`;
    });
  }

  function removeIndentationFromCodeBlockMarkers(text) {
    if (typeof text !== 'string') return text;
    const lines = text.split('\n');
    let inCodeBlock = false;
    return lines.map((line) => {
      const trimmed = line.trim();
      if (trimmed.startsWith('```')) {
        inCodeBlock = !inCodeBlock;
        return trimmed;
      }
      return inCodeBlock ? line : line;
    }).join('\n');
  }

  function deIndentToolRequestBlocks(text) {
    if (typeof text !== 'string') return text;
    const lines = text.split('\n');
    let inToolBlock = false;

    return lines.map((line) => {
      const isBacktickWrapped = /`[^`]*<<<\[TOOL_REQUEST\]>>>[^`]*`/.test(line) ||
        /`[^`]*<<<\[END_TOOL_REQUEST\]>>>[^`]*`/.test(line);
      const isStart = !isBacktickWrapped && line.includes('<<<[TOOL_REQUEST]>>>');
      const isEnd = !isBacktickWrapped && line.includes('<<<[END_TOOL_REQUEST]>>>');
      const needsTrim = isStart || inToolBlock;
      const processed = needsTrim ? line.trimStart() : line;
      if (isStart) inToolBlock = true;
      if (isEnd) inToolBlock = false;
      return processed;
    }).join('\n');
  }

  function deIndentMisinterpretedCodeBlocks(text) {
    if (typeof text !== 'string') return text;
    const lines = text.split('\n');
    let inFence = false;
    const listRegex = /^\s*([-*]|\d+\.)\s+/;
    const htmlTagRegex = /^\s*<\/?(div|p|img|span|a|h[1-6]|ul|ol|li|table|tr|td|th|section|article|header|footer|nav|aside|main|figure|figcaption|blockquote|pre|code|style|button|form|input|textarea|select|label|video|audio|canvas|svg)[\s>/]/i;
    const chineseParagraphRegex = /^[\u4e00-\u9fa5]/;

    return lines.map((line) => {
      if (line.trim().startsWith('```')) {
        inFence = !inFence;
        return line.trimStart();
      }
      if (inFence) return line;

      const trimmedStartLine = line.trimStart();
      const hasIndentation = line.length > trimmedStartLine.length;
      if (hasIndentation && !listRegex.test(line)) {
        if (htmlTagRegex.test(line) || chineseParagraphRegex.test(trimmedStartLine)) {
          return trimmedStartLine;
        }
      }
      return line;
    }).join('\n');
  }

  function applyContentProcessors(text) {
    if (typeof text !== 'string') return text;
    return removeIndentationFromCodeBlockMarkers(text)
      .replace(/^(\s*```)(?![A-Za-z0-9_-]*[\r\n])(?=\S)/gm, '$1\n')
      .replace(/(^|[^\w/\\=])~(?![\s~])/g, '$1~ ')
      .replace(/^(\[(?:(?!\]:\s).)*的发言\]:\s*)+/g, '')
      .replace(/(<img[^>]+>)\s*(```)/g, '$1\n\n<!-- UniVCP-Renderer-Separator -->\n\n$2');
  }

  function normalizeAiButtons(text) {
    if (typeof text !== 'string' || !/<button\b/i.test(text)) return text;
    return text.replace(/<button\b([^>]*)>/gi, (match, attrs) => {
      if (/\bdata-send\s*=/i.test(attrs)) return match;
      const onclick = attrs.match(/\sonclick\s*=\s*(["'])([\s\S]*?)\1/i);
      if (!onclick) return match;
      const js = onclick[2] || '';
      const inputMatch = js.match(/\binput\s*\(\s*(["'`])([\s\S]*?)\1\s*\)/i) ||
        js.match(/\bsendMessage\s*\(\s*(["'`])([\s\S]*?)\1\s*\)/i);
      if (!inputMatch) return match;
      const textValue = inputMatch[2]
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
      const withoutOnclick = attrs.replace(/\sonclick\s*=\s*(["'])([\s\S]*?)\1/i, '');
      return `<button${withoutOnclick} data-send="${textValue}">`;
    });
  }

  function protectRanges(text, specs) {
    const source = String(text || '');
    const ranges = [];

    specs.forEach((spec) => {
      let index = 0;
      while (index < source.length) {
        const start = source.indexOf(spec.start, index);
        if (start < 0) break;
        const end = source.indexOf(spec.end, start + spec.start.length);
        const close = end < 0 ? source.length : end + spec.end.length;
        ranges.push({
          start,
          end: close,
          raw: source.slice(start, close),
          type: spec.type
        });
        index = close;
      }
    });

    const toolResultRegex = /\[\[(?:工具调用结果信息汇总)[\s\S]*?(?:工具调用结果结束)\]\]/g;
    let match;
    while ((match = toolResultRegex.exec(source)) !== null) {
      ranges.push({
        start: match.index,
        end: match.index + match[0].length,
        raw: match[0],
        type: 'tool-result'
      });
    }

    if (ranges.length === 0) {
      return { text: source, placeholders: new Map() };
    }

    ranges.sort((a, b) => a.start - b.start || b.end - a.end);
    const normalized = [];
    let lastEnd = -1;
    ranges.forEach((range) => {
      if (range.start >= lastEnd) {
        normalized.push(range);
        lastEnd = range.end;
      }
    });

    const placeholders = new Map();
    let result = '';
    let cursor = 0;
    normalized.forEach((range, index) => {
      const placeholder = `<!--UNIVCP_PROTOCOL_${index}-->`;
      result += source.slice(cursor, range.start) + placeholder;
      placeholders.set(placeholder, range);
      cursor = range.end;
    });
    result += source.slice(cursor);
    return { text: result, placeholders };
  }

  function restoreProtocolBlocks(html, placeholders) {
    if (!placeholders || placeholders.size === 0) return html;
    let result = String(html || '');
    placeholders.forEach((range, placeholder) => {
      const block = `<pre class="uvcp-protocol-block" data-protocol="${range.type}"><code>${escapeHtml(range.raw)}</code></pre>`;
      const escapedPlaceholder = placeholder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      result = result.replace(new RegExp(escapedPlaceholder, 'g'), block);
    });
    return result;
  }

  function createContentPipeline() {
    function createContext(inputText, options) {
      return {
        mode: options.mode || PIPELINE_MODES.FULL_RENDER,
        text: typeof inputText === 'string' ? inputText : '',
        meta: { stepsApplied: [] },
        state: {
          protocolPlaceholders: new Map()
        }
      };
    }

    function step(ctx, name, handler) {
      ctx.text = handler(ctx.text, ctx) ?? ctx.text;
      ctx.meta.stepsApplied.push(name);
      return ctx;
    }

    function protectProtocols(text, ctx) {
      const protectedResult = protectRanges(text, [
        { start: '<<<[TOOL_REQUEST]>>>', end: '<<<[END_TOOL_REQUEST]>>>', type: 'tool-request' },
        { start: '<<<DailyNoteStart>>>', end: '<<<DailyNoteEnd>>>', type: 'daily-note' },
        { start: '<<<[DESKTOP_PUSH]>>>', end: '<<<[DESKTOP_PUSH_END]>>>', type: 'desktop-push' }
      ]);
      ctx.state.protocolPlaceholders = protectedResult.placeholders;
      return protectedResult.text;
    }

    function runFullRenderPipeline(inputText, options = {}) {
      const ctx = createContext(inputText, { ...options, mode: PIPELINE_MODES.FULL_RENDER });
      step(ctx, 'protect-protocol-blocks', protectProtocols);
      step(ctx, 'escape-start-end-markers', processStartEndMarkers);
      step(ctx, 'deindent-code-blocks', deIndentMisinterpretedCodeBlocks);
      step(ctx, 'deindent-tool-request-blocks', deIndentToolRequestBlocks);
      step(ctx, 'normalize-ai-buttons', normalizeAiButtons);
      step(ctx, 'apply-common-content-processors', applyContentProcessors);
      return { text: ctx.text, meta: ctx.meta, state: ctx.state };
    }

    function runStreamFastPipeline(inputText, options = {}) {
      const ctx = createContext(inputText, { ...options, mode: PIPELINE_MODES.STREAM_FAST });
      step(ctx, 'escape-start-end-markers', processStartEndMarkers);
      step(ctx, 'deindent-code-blocks', deIndentMisinterpretedCodeBlocks);
      step(ctx, 'normalize-ai-buttons', normalizeAiButtons);
      step(ctx, 'apply-common-content-processors', applyContentProcessors);
      return { text: ctx.text, meta: ctx.meta, state: ctx.state };
    }

    function process(inputText, options = {}) {
      const mode = options.mode || PIPELINE_MODES.FULL_RENDER;
      if (mode === PIPELINE_MODES.STREAM_FAST) {
        return runStreamFastPipeline(inputText, options);
      }
      return runFullRenderPipeline(inputText, options);
    }

    return {
      process,
      runFullRenderPipeline,
      runStreamFastPipeline
    };
  }

  window.UniVCPContentPipeline = {
    PIPELINE_MODES,
    createContentPipeline,
    escapeHtml,
    restoreProtocolBlocks
  };
})();
