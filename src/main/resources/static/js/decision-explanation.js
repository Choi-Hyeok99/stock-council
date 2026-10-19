(() => {
  'use strict';
  const n = (tag, cls, text) => { const x = document.createElement(tag); if (cls) x.className = cls; if (text != null) x.textContent = String(text); return x; };
  const numeric = value => value == null || value === '' || !Number.isFinite(Number(value)) ? null : Number(value);
  const num = (value, unit = '') => numeric(value) == null ? '미확인' : new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 2}).format(Number(value)) + unit;
  const money = value => num(value, '원');
  const time = value => { const d = value ? new Date(value) : null; return !d || !Number.isFinite(d.getTime()) ? '미저장' : new Intl.DateTimeFormat('ko-KR', {timeZone: 'Asia/Seoul',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(d) + ' KST'; };
  const states = {FEED_UNAVAILABLE:'핵심 피드 확인 필요',RISK_TITLE:'위험 키워드 제목 발견',CORRECTION:'정정·철회 발견',TITLE_CATALYST:'촉매 가능성 제목',NO_LINKED_NEWS:'연결된 최근 뉴스 없음',NO_CATALYST:'확인된 호재 제목 없음'};
  function lines(title, values, cls = '') {
    const box = n('section','decision-section ' + cls); box.append(n('h4','',title));
    const list = n('ul'); (values || []).forEach(v => list.append(n('li','',v))); box.append(list); return box;
  }
  function fold(title, key) { const box = n('details','decision-fold'); box.dataset.panel = key; box.append(n('summary','',title)); return box; }
  function metrics(items) {
    const box = n('dl','decision-metrics'); items.forEach(([label,value]) => { const row = n('div'); row.append(n('dt','',label),n('dd','',value)); box.append(row); }); return box;
  }
  function checks(items) {
    const list = n('div','decision-checks'); (items || []).forEach(c => {
      const row = n('div','decision-check ' + (c.pass ? 'pass' : 'wait')), copy = n('div');
      copy.append(n('strong','',c.label),n('p','',c.observed),n('p','decision-muted','조건: ' + c.threshold));
      row.append(n('span','decision-check-state',c.pass ? '통과' : '대기'),copy); list.append(row);
    }); return list;
  }
  function articles(items, legacy) {
    const list = n('div','decision-articles');
    (items || []).forEach(item => {
      const evidence = legacy ? item : item.evidence; if (!evidence) return;
      const row = n('article','decision-article');
      row.append(n('strong','',legacy ? '당시 연결된 제목 · 분류 상세 미저장' : item.explanation));
      const title = n('a','',evidence.title);
      try { const url = new URL(evidence.url); if (url.protocol === 'https:' && ['www.hankyung.com','dart.fss.or.kr'].includes(url.hostname)) { title.href = url.href; title.target = '_blank'; title.rel = 'noopener noreferrer'; } } catch (_) { /* Display as text without an untrusted URL. */ }
      row.append(title,n('p','decision-muted',(evidence.source || '출처 미확인') + ' · 발행 ' + (evidence.publishedAt ? time(evidence.publishedAt) : (evidence.publishedDate || '미확인') + ' (시각 미확인)') + ' · 발견 ' + time(evidence.discoveredAt)),n('p','decision-muted','기업 연결 기준: ' + (evidence.matchBasis || '미저장'))); list.append(row);
    }); return list;
  }
  function research(signal, key, reference = false) {
    const root = n('div','decision-explanation'), e = signal?.explanation;
    if (!signal) { root.append(n('p','decision-muted','상세 연구 입력이 저장되지 않았어요. 현재 자료로 과거 근거를 재구성하지 않아요.')); return root; }
    root.append(n('p','decision-summary',e?.summary || '당시 저장된 가격·선정 이유를 참고해요. 전체 점수 계산과 실행 관문은 미저장이에요.'),n('p','decision-muted',(reference ? '매수 전 참고 기록 · ' : '현재 연구 · ') + '확정 일봉 ' + signal.basisDate + ' · 조회 ' + time(signal.chartReceivedAt)));
    root.append(metrics([['확정 종가',money(signal.lastClose)],['5일 / 20일 평균',money(signal.average5) + ' / ' + money(signal.average20)],['최근 5거래일',num(signal.change5,'%')],['KOSPI 대비 상대 강도',num(signal.relativeStrength,'%p')],['단순 14구간 RSI',num(signal.rsi14)],['평균 TR / 종가',num(signal.atrPercent,'%')]]));
    if (e) {
      root.append(n('p','decision-muted',e.returnIntervals + '거래일 주식 ' + num(e.stockReturn,'%') + ' − 같은 기간 KOSPI ' + num(e.benchmarkReturn,'%') + ' = 상대 강도 ' + num(signal.relativeStrength,'%p') + '. 평균선은 확정 일봉으로 계산해요.'),n('p','decision-muted','확정일 거래량 / 직전 ' + e.volumeWindow + '일 평균: ' + num(e.volumeRatio,'배') + ' · 종가/20일 평균 이격 ' + num(e.extensionPercent,'%') + '. 거래량은 유동성 참고이며 상승 증거로 가산하지 않아요.'));
      const entry = fold('매수 관문 · ' + (e.entryChecks || []).filter(c => c.pass).length + '/' + (e.entryChecks || []).length + ' 통과',key + '-gates'); entry.append(checks(e.entryChecks)); root.append(entry);
      const score = fold('점수 계산 · ' + signal.score + '/100 (상승 확률 아님)',key + '-score');
      (e.scoreBreakdown || []).forEach(c => { const row = n('div','decision-score-item'), head = n('div'); head.append(n('strong','',c.label),n('span','',(c.points > 0 ? '+' : '') + c.points + '점')); row.append(head,n('p','',c.observed),n('p','decision-muted',c.rule)); score.append(row); });
      score.append(n('p','decision-muted','항목 합계 ' + e.rawScore + '점 → 0~100 범위 적용 ' + signal.score + '점. 기본값·가중치·절단값은 검증 전 정책이에요.')); root.append(score);
      const news = e.news, newsPanel = fold('뉴스·공시 · ' + (states[news?.state] || '미확인') + ' · 가점 ' + (news?.scoreContribution || 0) + '점',key + '-news');
      newsPanel.append(n('p','', '연결 ' + (news?.linkedCount || 0) + '건 · 촉매 가능성 ' + (news?.catalystCount || 0) + '건 · 일반 공시 ' + (news?.routineCount || 0) + '건'),n('p','decision-muted',news?.scope));
      if (!news?.scoreContribution) newsPanel.append(n('p','','제목상 호재 가점이 없어요. 관찰 우선순위 점수이며 AI의 투자 결론이 아니에요.'));
      newsPanel.append(articles(news?.items,false)); root.append(newsPanel);
    } else {
      const news = fold('당시 기사·공시 참고 · ' + (signal.evidence || []).length + '건',key + '-news'); news.append(n('p','decision-muted','당시 제목 분류·가점 계산 상세는 저장되지 않았어요. 제목을 새 정책으로 재분류해 매수 이유처럼 표시하지 않아요.'),articles(signal.evidence,true)); root.append(news);
    }
    const argumentsPanel = fold('찬성 근거와 반대 근거',key + '-arguments');
    argumentsPanel.append(lines('관찰 근거',signal.reasons),lines('반대 근거·확인하지 못한 것',signal.risks,'decision-cautions'),n('p','decision-muted',signal.invalidation)); root.append(argumentsPanel);
    if (e) root.append(n('p','decision-muted',e.limitations.join(' · ')));
    return root;
  }
  window.DecisionExplanation = Object.freeze({research});
})();
