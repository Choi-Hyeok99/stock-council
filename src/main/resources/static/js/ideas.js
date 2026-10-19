'use strict';
(() => {
  let board = null, filter = 'ALL', loading = false;
  const el = id => document.getElementById(id);
  const node = (tag, cls, text) => { const item = document.createElement(tag); if (cls) item.className = cls; if (text !== undefined) item.textContent = text; return item; };
  const number = value => value === null || value === undefined ? '—' : new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 0}).format(Number(value));
  const money = value => value === null || value === undefined ? '시세 대기' : number(value) + '원';
  const percent = value => value === null || value === undefined || !Number.isFinite(Number(value)) ? '수익률 대기' : (Number(value)>0?'+':'') + Number(value).toLocaleString('ko-KR',{minimumFractionDigits:2,maximumFractionDigits:2}) + '%';
  const time = value => { if (!value) return '시각 미확인'; const date = new Date(value); return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('ko-KR', {timeZone:'Asia/Seoul',month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false}).format(date) + ' KST' : '시각 미확인'; };
  const label = state => ({FALLING_BUY_WATCH:'하락 분할 매수 검토',UPTREND_WATCH:'상승 흐름 관찰',REBOUND_WATCH:'반등 관찰',DOWNSIDE_RISK:'하락 위험',WAIT:'확인 대기'}[state] || state || '대기');
  function article(item) {
    const row = node('div', 'article'), link = node('a', '', item.title);
    try { const url = new URL(item.url); if (url.protocol !== 'https:' || !['www.hankyung.com','dart.fss.or.kr'].includes(url.hostname)) return row; link.href = url.href; } catch (_) { return row; }
    link.target = '_blank'; link.rel = 'noopener noreferrer';
    row.append(link, node('p', '', item.source + ' · ' + (item.publishedAt ? time(item.publishedAt) : (item.publishedDate || '발행일 미확인') + ' · 발행 시각 미확인') + ' · 발견 ' + time(item.discoveredAt)));
    return row;
  }
  function chart(signal) {
    const wrapper = node('div'), points = (signal.chart || []).filter(p => Number.isFinite(Number(p.close)) && Number(p.close) > 0);
    if (points.length < 2) { wrapper.append(node('p','small','확정 일봉 차트 대기')); return wrapper; }
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg'); svg.classList.add('chart'); svg.setAttribute('viewBox','0 0 320 92'); svg.setAttribute('role','img'); svg.setAttribute('aria-label',signal.name + ' 실제 확정 일봉 종가 차트');
    const values = points.map(p => Number(p.close)), ma = Number(signal.average20);
    const min = Math.min(...values, ma), max = Math.max(...values, ma), span = max - min || 1, y = v => 80 - ((v - min) / span) * 66;
    const line = document.createElementNS(svg.namespaceURI,'polyline'); line.classList.add('chart-line'); line.setAttribute('points', values.map((value,i) => (5 + i * 310 / (values.length-1)).toFixed(1) + ',' + y(value).toFixed(1)).join(' '));
    const average = document.createElementNS(svg.namespaceURI,'line'); average.classList.add('chart-ma'); ['x1','x2','y1','y2'].forEach((key,i) => average.setAttribute(key, [5,315,y(ma),y(ma)][i]));
    svg.append(average,line); wrapper.append(svg);
    const caption = node('div','chart-label'); caption.append(node('span','',points[0].date),node('span','','확정 종가 · 점선 20일 평균'),node('span','',points[points.length-1].date)); wrapper.append(caption); return wrapper;
  }
  function card(signal, expanded) {
    const item = node('article','idea-card'), head = node('div','idea-head'), company = node('div'); item.dataset.code = signal.code;
    company.append(node('h3','',signal.name),node('div','ticker',signal.code + ' · ' + signal.market));
    const badge = node('span','badge ' + ({UPTREND_WATCH:'up',REBOUND_WATCH:'rebound',DOWNSIDE_RISK:'risk'}[signal.state] || ''),signal.label); head.append(company,badge); item.append(head);
    const score = node('div','score-row'); score.append(node('strong','',String(signal.score)),node('span','','/ 100 · 근거 우선순위')); item.append(score,chart(signal));
    item.append(node('p','lead-reason',signal.explanation?.summary || (signal.reasons || [])[0] || '차트/사건 근거를 더 확인해요.'));
    const metrics = node('div','metrics'); metrics.append(node('span','','5일 ' + Number(signal.change5).toFixed(1) + '%'),node('span','','시장 대비 ' + (signal.relativeStrength == null ? '미확인' : Number(signal.relativeStrength).toFixed(1)) + '%p'),node('span','','RSI ' + Number(signal.rsi14).toFixed(0))); item.append(metrics);
    const details = node('details'); details.open = expanded; details.append(node('summary','','선정 근거 · 반대 근거 · 원문 ' + (signal.evidence || []).length + '개'));
    details.append(window.DecisionExplanation.research(signal,'idea-' + signal.code)); item.append(details); return item;
  }
  function renderIdeas() {
    const panels = new Map([...el('ideas').querySelectorAll('details[data-panel]')].map(p => [p.dataset.panel,p.open]));
    const expanded = new Set([...el('ideas').querySelectorAll('article')].filter(a => a.querySelector('details')?.open).map(a => a.dataset.code));
    const report = board?.ideas?.entry?.report, regular = report?.signals || [], risks = report?.riskSignals || [];
    const all = [...new Map([...regular,...risks].map(item => [item.code,item])).values()];
    const shown = filter === 'ALL' ? regular : all.filter(item => item.state === filter); el('ideas').replaceChildren();
    if (!shown.length) { const empty = node('div','empty'); empty.append(node('span','','◌'),node('h3','',report ? '이 분류에 확인된 기업이 없어요' : '실제 데이터를 모으고 있어요'),node('p','',report ? '조건을 채우려고 없는 추천을 만들지 않아요.' : '첫 차트 연구에는 몇 분이 걸릴 수 있어요.')); el('ideas').append(empty); }
    shown.forEach(signal => el('ideas').append(card(signal,expanded.has(signal.code))));
    el('ideas').querySelectorAll('details[data-panel]').forEach(p => { if (panels.has(p.dataset.panel)) p.open = panels.get(p.dataset.panel); });
    el('idea-count').textContent = String(regular.length); el('coverage').textContent = report ? report.researchedCount + '개 연구' : '첫 연구 진행 중';
  }
  function render(data) {
    board = data;
    el('runtime-environment').textContent='실제 시장 데이터 · 기업 연구 전용';
    el('session-label').textContent = data.session.label;
    const cal = data.session.calendar;
    el('calendar-details').textContent = data.session.coverage + ' · 달력 조회 ' + time(data.session.calendarFetchedAt) + (data.session.calendarIssue ? ' · ' + data.session.calendarIssue : '') + (cal ? ' · 다음 갱신 ' + time(cal.nextRefreshAt) + ' · 이번 달 휴일: ' + (cal.closedDays || []).filter(d => d.reason !== '주말' && d.date >= data.session.date).map(d => d.date + ' ' + d.reason).join(' / ') + ' · ' + cal.policy : '');
    el('source-label').textContent = data.information.collectionHealthy ? '실제 뉴스·공시 피드 수집 중' : '수집 상태 확인 필요';
    el('research-cadence').textContent = data.ideas.cadence;
    el('updated').textContent = '연구 ' + time(data.ideas.lastComputedAt) + ' · 차트 기준일은 기업별로 표시해요.';
    el('indices').replaceChildren(); (data.indices.indices || []).forEach(index => { const row = node('div','market-item'); row.append(node('span','',index.name),node('strong','',index.quote?.value === undefined ? '대기' : Number(index.quote.value).toLocaleString('ko-KR',{maximumFractionDigits:2})),node('p','', (index.quote?.businessDate || '기준일 미확인') + ' · ' + index.label)); el('indices').append(row); });
    renderIdeas();
    el('changes').replaceChildren(); const changes = data.ideas.changes || [];
    if (!changes.length) el('changes').append(node('p','small','아직 저장된 관심 목록 변경이 없어요.'));
    changes.slice(0,8).forEach(change => { const row = node('div','change'), detail = node('div'); row.append(node('span','',{ADDED:'새 관심',UPDATED:'판단 변경',REMOVED:'관심 제외'}[change.kind] || change.kind)); detail.append(node('strong','',change.name),node('p','',change.reason),node('p','small',time(change.occurredAt))); row.append(detail); el('changes').append(row); });
    el('context-news').replaceChildren(); (data.information.latest || []).slice(0,6).forEach(news => el('context-news').append(article(news)));
    if (!(data.information.latest || []).length) el('context-news').append(node('p','small','현재 확인된 최신 기사/공시를 기다리고 있어요.'));
    el('sources').replaceChildren(); (data.information.sources || []).forEach(source => { const row = node('div','source'); row.append(node('strong','',source.source + ' · ' + ({READY:'조회 성공',PARTIAL:'일부 조회',ERROR:'조회 실패',UNCONFIGURED:'설정 대기'}[source.state] || source.state)),node('p','small',source.coverage),node('p','small','성공 ' + time(source.lastSuccessAt) + ' · 이번 피드 ' + source.itemCount + '건' + (source.issue ? ' · ' + source.issue : ''))); el('sources').append(row); });
    el('discord-status').textContent = 'Discord · ' + data.discord.readiness + ' · 관심 기업 핵심 변화만 보고';
    const issue = data.ideas.issue || (!data.ideas.current && data.ideas.entry ? '이전 연구 기록이에요. 후보/차트 재확인 중이며 현재 추천으로 쓰기 전에 기준 시각을 확인해 주세요.' : '');
    el('load-issue').textContent = issue; el('load-issue').hidden = !issue;
  }
  async function refresh() {
    if (loading || document.hidden) return; loading = true;
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(),10000);
    try { const response = await fetch('/api/ideas',{credentials:'same-origin',cache:'no-store',signal:controller.signal});
      if (response.status === 401) { el('load-issue').replaceChildren(node('span','','로그인 세션이 만료됐어요. ')); const link = node('a','','다시 로그인'); link.href='/login'; el('load-issue').append(link); el('load-issue').hidden=false; return; }
      if (!response.ok || !(response.headers.get('Content-Type') || '').includes('application/json')) throw new Error(); render(await response.json());
    } catch (_) { el('load-issue').textContent = '연결 확인이 필요해요. 마지막 표시값을 새 데이터로 쓰지 마세요.'; el('load-issue').hidden=false; }
    finally { clearTimeout(timeout); loading=false; }
  }
  document.querySelector('.filters').addEventListener('click',event => { const button = event.target.closest('button[data-filter]'); if (!button) return; filter=button.dataset.filter; document.querySelectorAll('.filters button').forEach(b => b.classList.toggle('active',b===button)); renderIdeas(); });
  document.addEventListener('visibilitychange',() => {if (!document.hidden) refresh();}); refresh(); setInterval(refresh,15000);
})();
