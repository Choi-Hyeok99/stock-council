(() => {
  'use strict';
  const source = document.getElementById('guide-source'), output = document.getElementById('guide-rendered');
  if (!source || !output) return;
  const guides = {'WORKING-MANUAL.md':'manual','ARCHITECTURE.md':'architecture','VALUE-REVIEW.md':'value','SECURITY-REVIEW.md':'security','SECURITY.md':'security','MAINTENANCE.md':'maintenance','README.md':'readme'};
  const node = (tag, text) => { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; return n; };
  function safeLink(raw) {
    if (/^https?:\/\//i.test(raw)) {
      try { const url = new URL(raw); return !url.username && !url.password && ['http:', 'https:'].includes(url.protocol) ? url.href : null; } catch (_) { return null; }
    }
    const name = raw.split('#')[0].split('/').pop();
    return guides[name] ? '/guide/' + guides[name] : null;
  }
  function inline(parent, text) {
    const pattern = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^\s)]+\))/g;
    let start = 0;
    for (const match of text.matchAll(pattern)) {
      parent.append(document.createTextNode(text.slice(start, match.index)));
      const part = match[0];
      if (part.startsWith('`')) parent.append(node('code', part.slice(1, -1)));
      else if (part.startsWith('**')) parent.append(node('strong', part.slice(2, -2)));
      else {
        const pieces = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(part), href = safeLink(pieces[2]);
        if (href) { const link = node('a', pieces[1]); link.href = href; if (href.startsWith('http')) { link.target = '_blank'; link.rel = 'noopener noreferrer'; } parent.append(link); }
        else parent.append(document.createTextNode(pieces[1] + ' (' + pieces[2] + ')'));
      }
      start = match.index + part.length;
    }
    parent.append(document.createTextNode(text.slice(start)));
  }
  const lines = source.textContent.split(/\r?\n/), fragment = document.createDocumentFragment(), toc = document.getElementById('guide-toc-links');
  let paragraph = [], list = null, headingCount = 0;
  const flush = () => { if (paragraph.length) { const p = node('p'); inline(p, paragraph.join(' ')); fragment.append(p); paragraph = []; } list = null; };
  const tableCells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(x => x.trim());
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^\s*```/.test(line)) {
      flush(); const code = [];
      while (++i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i]);
      const pre = node('pre'); pre.append(node('code', code.join('\n'))); fragment.append(pre); continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      flush(); const h = node('h' + heading[1].length); inline(h, heading[2]); h.id = 'document-section-' + (++headingCount); fragment.append(h);
      if (heading[1].length <= 2) { const link = node('a', h.textContent); link.href = '#' + h.id; toc.append(link); }
      continue;
    }
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1])) {
      flush(); const wrapper = node('div'); wrapper.className = 'guide-table-wrap'; const table = node('table'), head = node('thead'), headRow = node('tr');
      tableCells(line).forEach(value => { const cell = node('th'); cell.scope = 'col'; inline(cell, value); headRow.append(cell); }); head.append(headRow); table.append(head); i++;
      const body = node('tbody');
      while (i + 1 < lines.length && lines[i + 1].includes('|') && lines[i + 1].trim()) { const row = node('tr'); tableCells(lines[++i]).forEach(value => { const cell = node('td'); inline(cell, value); row.append(cell); }); body.append(row); }
      table.append(body); wrapper.append(table); fragment.append(wrapper); continue;
    }
    const item = /^\s*(?:([-*])|\d+[.])\s+(.+)$/.exec(line);
    if (item) {
      const type = item[1] ? 'UL' : 'OL';
      if (paragraph.length) flush();
      if (!list || list.tagName !== type) { list = node(type.toLowerCase()); fragment.append(list); }
      const li = node('li'); inline(li, item[2]); list.append(li); continue;
    }
    if (!line.trim()) { flush(); continue; }
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) { flush(); fragment.append(node('hr')); continue; }
    if (/^>\s?/.test(line)) { flush(); const quote = node('blockquote'); inline(quote, line.replace(/^>\s?/, '')); fragment.append(quote); continue; }
    list = null; paragraph.push(line.trim());
  }
  flush(); output.replaceChildren(fragment); output.hidden = false; source.remove();
  const tocPanel = document.getElementById('guide-toc'); tocPanel.hidden = !toc.children.length; tocPanel.open = window.matchMedia('(min-width: 761px)').matches;
})();
