(() => {
  'use strict';
  const el=id=>document.getElementById(id), fmt=new Intl.NumberFormat('ko-KR',{maximumFractionDigits:0});
  const node=(tag,cls,text)=>{const n=document.createElement(tag);n.className=cls||'';if(text!=null)n.textContent=text;return n;};
  const cents=x=>x==null?'확인 대기':Number(x)===0?'0센트':Number(x)*100<1?'1센트 미만':fmt.format(Math.ceil(Number(x)*100))+'센트';
  const time=x=>new Intl.DateTimeFormat('ko-KR',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false,timeZone:'Asia/Seoul'}).format(new Date(x))+' KST';
  const role={ECONOMY:'경제',POLICY:'정책',NEWS:'뉴스',INDUSTRY:'산업',SCIENCE:'기술',FINANCE:'재무',MARKET:'시장',RISK:'위험',REPORT:'종합 요약'};
  const phase={INDEPENDENT:'독립 의견',DEBATE:'질문·답변',FINAL:'최종 의견',SUMMARY:'종합 요약'};
  const state={RESERVED:'호출 예약 중',SENT:'응답 대기',SETTLED:'사용량 확인',NOT_SENT:'전송 안 됨',REJECTED:'요청 거절',UNKNOWN:'결과 미확정 · 예약 유지',ARCHIVED:'보관됨 · 예약 비용 유지'};
  let loading=false,lastSuccess=0;
  function error(message){el('cost-error').textContent=message;el('cost-error').hidden=!message;}
  function render(d){
    lastSuccess=Date.now();
    el('cost-mode').textContent=d.configured?'AI 키 등록됨':'AI 키 등록 대기 · 아직 호출하지 않아요';
    el('cost-checked').textContent='조회 '+time(d.checkedAt)+' · 화면을 보고 있으면 15초마다 갱신';
    el('cost-spent').textContent=cents(d.monthly.costUsd);el('cost-remaining').textContent=cents(d.remainingUsd);
    el('cost-limit').textContent='$'+fmt.format(Number(d.ceilingUsd))+' · '+cents(d.ceilingUsd);
    el('cost-reserved').textContent=cents(d.reservedUsd);
    const ratio=Number(d.ceilingUsd)>0?Number(d.committedUsd)/Number(d.ceilingUsd)*100:100;
    el('cost-progress').value=Math.min(100,Math.max(0,ratio));
    el('cost-budget-note').textContent='사용·예약 '+cents(d.committedUsd)+' · 한도의 '+fmt.format(Math.ceil(ratio))+'% · '+d.month.slice(0,7)+' UTC 월 기준 · 초기화 '+time(d.resetsAt);
    el('cost-budget-note').className='small'+(ratio>=80?' budget-alert':'');
    el('cost-calculation').textContent=d.calculationNote;
    el('cost-today').textContent=cents(d.daily.costUsd);el('cost-calls').textContent=fmt.format(d.daily.submittedCalls)+'회';
    el('cost-tokens').textContent=fmt.format(d.monthly.inputTokens+d.monthly.outputTokens)+'토큰';
    el('cost-count-note').textContent='오늘 '+d.today+' KST · 이번 달 전송 '+fmt.format(d.monthly.submittedCalls)+'회 / 사용량 확인 '+fmt.format(d.monthly.measuredCalls)+'회 · 처리 중·미확정 '+fmt.format(d.monthly.unresolvedCalls)+'회. 캐시 '+fmt.format(d.monthly.cachedTokens)+'토큰은 입력 토큰에 포함돼요.';
    el('provider-note').textContent=d.providerNote;
    const models=el('cost-models');models.replaceChildren();
    for(const m of d.models){const c=node('article','panel model-card');c.append(node('h3','',m.model),node('strong','',cents(m.costUsd)),node('p','small','전송 '+fmt.format(m.submittedCalls)+'회 · 사용량 확인 '+fmt.format(m.measuredCalls)+'회'),node('p','small','입력 '+fmt.format(m.inputTokens)+' / 출력 '+fmt.format(m.outputTokens)+'토큰'));models.append(c);}
    if(!d.models.length)models.append(node('p','empty-cost','아직 AI 호출 기록이 없어요. 첫 응답부터 사용량과 계산 비용이 쌓여요.'));
    const days=el('cost-days');days.replaceChildren();for(const x of d.days){const r=node('div','day-row');r.append(node('span','',x.day+' · 전송 '+fmt.format(x.submittedCalls)+'회'),node('strong','',cents(x.costUsd)));days.append(r);}
    if(!d.days.length)days.append(node('p','empty-cost','아직 날짜별 호출 기록이 없어요.'));
    const recent=el('cost-recent');recent.replaceChildren();for(const x of d.recent){const r=node('article','call-row');r.append(node('h3','',(role[x.role]||x.role)+' · '+(phase[x.phase]||x.phase)),node('p','',time(x.at)+' · '+x.model),node('p','',state[x.state]||x.state));
      const pending=['RESERVED','SENT','UNKNOWN','ARCHIVED'].includes(x.state);r.append(node('p','',(pending?'예약액 ':x.costUsd==null?'사용량 비용 확인 대기':'사용량 비용 ')+(pending?cents(x.reservedUsd):x.costUsd==null?'':cents(x.costUsd))));
      if(x.inputTokens!=null&&x.outputTokens!=null)r.append(node('p','', '입력 '+fmt.format(x.inputTokens)+' / 출력 '+fmt.format(x.outputTokens)+'토큰'));recent.append(r);}
    if(!d.recent.length)recent.append(node('p','empty-cost','저장된 호출이 아직 없어요.'));
    error('');
  }
  async function refresh(){
    if(loading||document.hidden)return;loading=true;el('cost-refresh').disabled=true;
    const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),12000);
    try{const r=await fetch('/api/ai-costs',{credentials:'same-origin',cache:'no-store',signal:abort.signal});if(r.status===401){error('로그인이 필요해요. 로그인 후 다시 열어주세요.');return;}if(!r.ok)throw new Error();const d=await r.json();if(Date.now()-new Date(d.checkedAt).getTime()>45000)throw new Error();render(d);}
    catch(_){error('새 비용 기록을 가져오지 못했어요. 남아 있는 숫자는 마지막 확인값이에요.');}
    finally{clearTimeout(timer);loading=false;el('cost-refresh').disabled=false;}
  }
  el('cost-refresh').addEventListener('click',refresh);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
  setInterval(()=>{if(!document.hidden){if(lastSuccess&&Date.now()-lastSuccess>45000)error('마지막 확인 이후 45초가 지났어요. 최신 비용 확인 대기 중이에요.');refresh();}},15000);refresh();
})();
