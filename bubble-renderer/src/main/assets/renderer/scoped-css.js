(function () {
  function stripCssComments(cssString) {
    if (typeof cssString !== 'string' || !cssString) return '';

    let result = '';
    let quote = null;
    for (let index = 0; index < cssString.length; index += 1) {
      const char = cssString[index];
      const nextChar = cssString[index + 1];

      if (quote) {
        result += char;
        if (char === '\\' && index + 1 < cssString.length) {
          result += cssString[index + 1];
          index += 1;
          continue;
        }
        if (char === quote) quote = null;
        continue;
      }

      if (char === '"' || char === "'") {
        quote = char;
        result += char;
        continue;
      }

      if (char === '/' && nextChar === '*') {
        index += 2;
        while (index < cssString.length && !(cssString[index] === '*' && cssString[index + 1] === '/')) {
          index += 1;
        }
        index += 1;
        continue;
      }

      result += char;
    }
    return result;
  }

  function splitTopLevel(input, separatorChar) {
    const items = [];
    let current = '';
    let quote = null;
    let bracketDepth = 0;
    let parenDepth = 0;

    for (let index = 0; index < input.length; index += 1) {
      const char = input[index];

      if (quote) {
        current += char;
        if (char === '\\' && index + 1 < input.length) {
          current += input[index + 1];
          index += 1;
          continue;
        }
        if (char === quote) quote = null;
        continue;
      }

      if (char === '"' || char === "'") {
        quote = char;
        current += char;
        continue;
      }
      if (char === '[') {
        bracketDepth += 1;
        current += char;
        continue;
      }
      if (char === ']') {
        bracketDepth = Math.max(0, bracketDepth - 1);
        current += char;
        continue;
      }
      if (char === '(') {
        parenDepth += 1;
        current += char;
        continue;
      }
      if (char === ')') {
        parenDepth = Math.max(0, parenDepth - 1);
        current += char;
        continue;
      }

      if (char === separatorChar && bracketDepth === 0 && parenDepth === 0) {
        items.push(current);
        current = '';
        continue;
      }
      current += char;
    }

    items.push(current);
    return items;
  }

  function findMatchingBrace(cssText, openBraceIndex) {
    let depth = 1;
    let quote = null;
    let bracketDepth = 0;
    let parenDepth = 0;

    for (let index = openBraceIndex + 1; index < cssText.length; index += 1) {
      const char = cssText[index];
      const nextChar = cssText[index + 1];

      if (quote) {
        if (char === '\\' && index + 1 < cssText.length) {
          index += 1;
          continue;
        }
        if (char === quote) quote = null;
        continue;
      }

      if (char === '"' || char === "'") {
        quote = char;
        continue;
      }
      if (char === '/' && nextChar === '*') {
        index += 2;
        while (index < cssText.length && !(cssText[index] === '*' && cssText[index + 1] === '/')) {
          index += 1;
        }
        index += 1;
        continue;
      }
      if (char === '[') {
        bracketDepth += 1;
        continue;
      }
      if (char === ']') {
        bracketDepth = Math.max(0, bracketDepth - 1);
        continue;
      }
      if (char === '(') {
        parenDepth += 1;
        continue;
      }
      if (char === ')') {
        parenDepth = Math.max(0, parenDepth - 1);
        continue;
      }
      if (bracketDepth > 0 || parenDepth > 0) continue;

      if (char === '{') {
        depth += 1;
      } else if (char === '}') {
        depth -= 1;
        if (depth === 0) return index;
      }
    }

    return cssText.length - 1;
  }

  function splitSelectorSegments(selector) {
    const segments = [];
    let current = '';
    let pendingCombinator = null;
    let quote = null;
    let bracketDepth = 0;
    let parenDepth = 0;

    function flushCurrent() {
      const trimmed = current.trim();
      if (!trimmed) {
        current = '';
        return;
      }
      segments.push({
        combinator: segments.length === 0 ? null : (pendingCombinator || ' '),
        compound: trimmed
      });
      current = '';
      pendingCombinator = null;
    }

    for (let index = 0; index < selector.length; index += 1) {
      const char = selector[index];

      if (quote) {
        current += char;
        if (char === '\\' && index + 1 < selector.length) {
          current += selector[index + 1];
          index += 1;
          continue;
        }
        if (char === quote) quote = null;
        continue;
      }

      if (char === '"' || char === "'") {
        quote = char;
        current += char;
        continue;
      }
      if (char === '[') {
        bracketDepth += 1;
        current += char;
        continue;
      }
      if (char === ']') {
        bracketDepth = Math.max(0, bracketDepth - 1);
        current += char;
        continue;
      }
      if (char === '(') {
        parenDepth += 1;
        current += char;
        continue;
      }
      if (char === ')') {
        parenDepth = Math.max(0, parenDepth - 1);
        current += char;
        continue;
      }

      if (bracketDepth === 0 && parenDepth === 0) {
        if (/\s/.test(char)) {
          flushCurrent();
          let lookahead = index;
          while (lookahead + 1 < selector.length && /\s/.test(selector[lookahead + 1])) {
            lookahead += 1;
          }
          const nextChar = selector[lookahead + 1];
          if (segments.length > 0 && nextChar && !['>', '+', '~'].includes(nextChar)) {
            pendingCombinator = ' ';
          }
          index = lookahead;
          continue;
        }
        if (char === '>' || char === '+' || char === '~') {
          flushCurrent();
          pendingCombinator = char;
          while (index + 1 < selector.length && /\s/.test(selector[index + 1])) {
            index += 1;
          }
          continue;
        }
      }

      current += char;
    }

    flushCurrent();
    return segments;
  }

  const ROOT_PREFIX_REGEX = /^(?::root|html|body)(?=$|[#.:\[])/i;
  const BLOCKED_BLOCK_AT_RULES = new Set([
    '@font-face',
    '@page',
    '@property',
    '@counter-style',
    '@font-feature-values',
    '@import'
  ]);
  const PASSTHROUGH_BLOCK_AT_RULES = new Set([
    '@keyframes',
    '@-webkit-keyframes',
    '@-moz-keyframes',
    '@-o-keyframes'
  ]);

  function normalizeCompound(compound) {
    const trimmed = String(compound || '').trim();
    if (!trimmed || trimmed === '*') {
      return { compound: trimmed, attachToRoot: false };
    }

    let normalized = trimmed;
    let previous = null;
    let attachToRoot = false;
    while (normalized && normalized !== previous) {
      previous = normalized;
      if (ROOT_PREFIX_REGEX.test(normalized)) attachToRoot = true;
      normalized = normalized.replace(ROOT_PREFIX_REGEX, '').trim();
    }

    return { compound: normalized, attachToRoot };
  }

  function rebuildSelector(segments) {
    return segments.reduce((result, segment, index) => {
      if (index === 0 || !segment.combinator) return `${result}${segment.compound}`;
      if (segment.combinator === ' ') return `${result} ${segment.compound}`;
      return `${result} ${segment.combinator} ${segment.compound}`;
    }, '');
  }

  function scopeSelector(selector, scopeId) {
    const trimmed = String(selector || '').trim();
    if (!trimmed) return `#${scopeId}`;

    const normalizedSegments = splitSelectorSegments(trimmed)
      .map((segment) => ({ ...segment, ...normalizeCompound(segment.compound) }))
      .filter((segment) => segment.compound);

    if (normalizedSegments.length === 0) return `#${scopeId}`;
    const normalizedSelector = rebuildSelector(normalizedSegments);
    if (!normalizedSelector || normalizedSelector === '*') return `#${scopeId} *`;
    if (normalizedSegments[0] && normalizedSegments[0].attachToRoot && /^[#.:\[]/.test(normalizedSelector)) {
      return `#${scopeId}${normalizedSelector}`;
    }
    if (normalizedSelector.startsWith(':')) return `#${scopeId}${normalizedSelector}`;
    return `#${scopeId} ${normalizedSelector}`;
  }

  function scopeStyleRule(prelude, body, scopeId) {
    const scopedSelectors = splitTopLevel(prelude, ',')
      .map((selector) => scopeSelector(selector, scopeId))
      .filter(Boolean)
      .join(', ');

    return scopedSelectors ? `${scopedSelectors} { ${body.trim()} }` : '';
  }

  function scopeNestedCss(cssString, scopeId) {
    const cssText = stripCssComments(cssString);
    const statements = [];
    let index = 0;

    while (index < cssText.length) {
      while (index < cssText.length && /\s/.test(cssText[index])) index += 1;
      if (index >= cssText.length) break;

      let prelude = '';
      let quote = null;
      let bracketDepth = 0;
      let parenDepth = 0;
      let cursor = index;

      for (; cursor < cssText.length; cursor += 1) {
        const char = cssText[cursor];
        const nextChar = cssText[cursor + 1];

        if (quote) {
          prelude += char;
          if (char === '\\' && cursor + 1 < cssText.length) {
            prelude += cssText[cursor + 1];
            cursor += 1;
            continue;
          }
          if (char === quote) quote = null;
          continue;
        }

        if (char === '"' || char === "'") {
          quote = char;
          prelude += char;
          continue;
        }
        if (char === '/' && nextChar === '*') {
          cursor += 2;
          while (cursor < cssText.length && !(cssText[cursor] === '*' && cssText[cursor + 1] === '/')) {
            cursor += 1;
          }
          cursor += 1;
          continue;
        }
        if (char === '[') {
          bracketDepth += 1;
          prelude += char;
          continue;
        }
        if (char === ']') {
          bracketDepth = Math.max(0, bracketDepth - 1);
          prelude += char;
          continue;
        }
        if (char === '(') {
          parenDepth += 1;
          prelude += char;
          continue;
        }
        if (char === ')') {
          parenDepth = Math.max(0, parenDepth - 1);
          prelude += char;
          continue;
        }
        if (bracketDepth === 0 && parenDepth === 0 && (char === '{' || char === ';')) break;
        prelude += char;
      }

      const trimmedPrelude = prelude.trim();
      if (!trimmedPrelude) {
        index = cursor + 1;
        continue;
      }
      if (cursor >= cssText.length) break;
      if (cssText[cursor] === ';') {
        index = cursor + 1;
        continue;
      }

      const blockStart = cursor;
      const blockEnd = findMatchingBrace(cssText, blockStart);
      const body = cssText.slice(blockStart + 1, blockEnd);
      const atRuleName = trimmedPrelude.startsWith('@')
        ? (trimmedPrelude.match(/^@[a-z-]+/i)?.[0]?.toLowerCase() || '')
        : '';

      let nextStatement = '';
      if (!atRuleName) {
        nextStatement = scopeStyleRule(trimmedPrelude, body, scopeId);
      } else if (BLOCKED_BLOCK_AT_RULES.has(atRuleName)) {
        nextStatement = '';
      } else if (PASSTHROUGH_BLOCK_AT_RULES.has(atRuleName)) {
        nextStatement = `${trimmedPrelude} { ${body.trim()} }`;
      } else {
        const scopedBody = scopeNestedCss(body, scopeId);
        nextStatement = scopedBody ? `${trimmedPrelude} { ${scopedBody} }` : '';
      }

      if (nextStatement) statements.push(nextStatement);
      index = blockEnd + 1;
    }

    return statements.join('\n');
  }

  function scopeCss(cssString, scopeId) {
    if (!scopeId) throw new Error('scopeId is required to scope CSS.');
    return scopeNestedCss(cssString, scopeId);
  }

  window.UniVCPScopedCss = { scopeCss };
})();
