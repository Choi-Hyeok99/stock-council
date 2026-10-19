(()=>{'use strict';
const $=id=>document.getElementById(id),time=v=>v?new Date(v).toLocaleString('ko-KR',{timeZone:'Asia/Seoul',hour12:false})+' KST':'미확인';
const label={DISABLED:'자동 실행 없음',MANUAL_ONLY:'사용자 직접 확인',PAUSED:'보류',DATA_REVIEW:'자료 검토 중',UNKNOWN:'확인 대기',EVIDENCE_WAIT:'근거 준비 중',TITLE_ONLY:'제목 관찰',REVIEW_REQUIRED:'원문 재검토 필요',TRIGGERED:'조건 도달',NOT_TRIGGERED:'조건 미도달'};
let loading=false,lastSuccess=0,historyLoading=false;
function node(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=String(text);return n;}
function note(parent,text){parent.append(node('p','small',text));}
function companies(rows,prefix){return rows.map(c=>{
 const card=node('article','review-card');card.append(node('span','pill muted',c.held?'보유 · 매수 당시 근거 보존':'관찰 · 기업 검토 대기'),node('h3','',c.name+' · '+c.code));
 note(card,`최종 검증 확정 ${c.verifiedCorePillars} / ${c.requiredCorePillars} · 자료 연결 범위는 아래 표시 · ${label[c.state]||c.state}`);
 for(const p of c.pillars){const row=node('div','pillar-row');row.append(node('strong','',`${p.label} · ${label[p.state]||p.state}`));note(row,p.reason);card.append(row);}
 const d=node('details','');d.dataset.key=prefix+c.code;d.append(node('summary','','보유 논거·점검 범위'));note(d,c.holdReason);
 if(c.held){note(d,`처음 매수 #${c.entryOrderId} · 당시 이유: ${c.originalEntryReason||'미저장'}`);const risk=node('div','risk-note');note(risk,'아래는 점검 시각 관찰이에요. 실제 매도는 주문 실행 관문과 체결 확인을 별도로 거쳐요.');
  for(const r of c.legacyRiskChecks){const p=node('p','small',`${r.label}: ${label[r.state]||r.state}${r.observed!=null||r.threshold!=null?' · 관측 '+(r.observed??'미확인')+' / 기준 '+(r.threshold??'미확인'):''}${r.priceAt?' · 가격 '+time(r.priceAt):''}${r.dailyBasisDate?' · 일봉 '+r.dailyBasisDate:''}`);p.dataset.state=r.state;risk.append(p);}d.append(risk);}
 card.append(d);
 if(c.financials){const f=node('a','',`재무자료 ${c.financials.annualCollected}/${c.financials.annualRequired}개 · 숫자와 근거 보기 →`);f.href='/financials#company-'+encodeURIComponent(c.code);card.append(f);}
 const e=node('details','');e.dataset.key=prefix+'news-'+c.code;e.append(node('summary','',`연결 기사·공시 제목 ${c.evidence.length}건`));
 note(e,'최근30일 제한 조회에서 선정한 최대6개 제목이에요. 사업·재무 분석의 증거를 대신하지 않아요.');
 const list=node('ul','');for(const item of c.evidence){const li=node('li',''),a=node('a','',item.source+' · '+item.title);try{const u=new URL(item.url);if(u.protocol==='https:'&&['dart.fss.or.kr','www.hankyung.com'].includes(u.hostname)&&!u.username&&!u.password&&!u.port){a.href=u.href;a.target='_blank';a.rel='noopener noreferrer';}}catch{}li.append(a);note(li,`게시 ${item.publishedAt?time(item.publishedAt):item.publishedDate||'미확인'} · 최초 수집 ${time(item.discoveredAt)}`);list.append(li);}e.append(list);card.append(e);return card;
});}
function render(v){
 $('mandate').textContent='기업 연구 전용 · 자동매매 OFF · 직접 모의 매도는 사용자 확인';$('freshness').textContent=v.current?'최근 점검 기록':'현재 점검 대기';
 $('load-issue').hidden=!v.issue;if(v.issue)$('load-issue').textContent=v.issue;
 if(!v.latest){$('updated').textContent='첫 점검 대기';return;}const r=v.latest.report;
 $('updated').textContent=`점검 ${time(r.generatedAt)} · ${r.version} · 기록 #${v.latest.id}`;
 $('roles').replaceChildren(...r.roles.map(b=>{const n=node('article','review-card');n.append(node('h3','',b.name),node('span','pill muted',label[b.state]||b.state),node('p','',b.summary));note(n,b.detail);return n;}));
 const open=new Set([...$('companies').querySelectorAll('details[open]')].map(d=>d.dataset.key));const cards=companies(r.companies,'live-');$('companies').replaceChildren(...cards);for(const d of $('companies').querySelectorAll('details'))d.open=open.has(d.dataset.key);
 if(!cards.length)$('companies').append(node('p','small','현재 확인된 보유·관찰 기업이 없어요. 없는 후보를 채우지 않아요.'));
 $('company-count').textContent=r.companies.length;$('next-step').textContent=r.nextStep;$('dart-state').textContent=r.dartApiConfigured?'OpenDART 키 등록됨 · 실제 재무 수집·수치 관찰은 재무 화면에서 확인':'OpenDART 인증키 미등록 · 재무·사업·가치·경영 검토 대기';$('scope').textContent=r.scope;
 const currentIds=[...$('history').querySelectorAll('button')].map(b=>b.dataset.id).join(',');if(currentIds!==v.history.map(h=>String(h.id)).join(','))$('history').replaceChildren(...v.history.map(h=>{const b=node('button','',time(h.generatedAt));b.type='button';b.dataset.id=h.id;b.addEventListener('click',()=>history(h.id));return b;}));
}
async function data(path){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);try{const resp=await fetch(path,{cache:'no-store',credentials:'same-origin',signal:controller.signal,headers:{Accept:'application/json'}});if(!resp.ok||!resp.headers.get('content-type')?.includes('application/json'))throw new Error(resp.status===401||resp.status===403?'로그인이 만료됐어요. 다시 로그인해 주세요.':'점검 기록을 가져오지 못했어요.');return await resp.json();}catch(e){if(e.name==='AbortError')throw new Error('점검 조회가 지연됐어요.');throw e;}finally{clearTimeout(timer);}}
async function refresh(){if(loading||document.hidden)return;loading=true;try{render(await data('/api/philosophy'));lastSuccess=Date.now();}catch(e){$('load-issue').hidden=false;$('load-issue').textContent=e.message+' · 마지막 화면은 보관된 정보예요.';$('freshness').textContent='최신 상태 미확인';}finally{loading=false;}}
async function history(id){if(historyLoading)return;historyLoading=true;try{const e=await data('/api/philosophy/'+encodeURIComponent(id));$('history-status').textContent=`과거 기록 #${e.id} · ${time(e.generatedAt)} · 당시 교육 ${e.report.curriculumVersion} · 현재 판단 아님`;$('historical').replaceChildren(...companies(e.report.companies,'history-'));}catch(e){$('history-status').textContent=e.message;}finally{historyLoading=false;}}
setInterval(()=>{if(lastSuccess&&Date.now()-lastSuccess>45000)$('freshness').textContent='화면 갱신 필요';refresh();},15000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});refresh();
})();
