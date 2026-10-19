(() => {
  'use strict';
  const label=document.getElementById('market-session-label'),detail=document.getElementById('market-session-detail'),updated=document.getElementById('market-session-updated');
  if(!label||!detail||!updated)return;
  const formatter=new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
  const stamp=value=>formatter.format(new Date(value))+' KST';
  let busy=false,expired=false,lastSuccess=0;
  function unavailable(message){label.dataset.phase='UNAVAILABLE';label.textContent='현재 장 상태 확인 대기';detail.textContent=message;updated.textContent=lastSuccess?'마지막 화면 확인 '+stamp(lastSuccess):'저장 달력·서버 시각 조회 대기';}
  async function refresh(){
    if(busy||expired||document.hidden)return;busy=true;
    try{
      const response=await fetch('/api/market-session',{credentials:'same-origin',cache:'no-store',redirect:'error',headers:{Accept:'application/json'},signal:AbortSignal.timeout(5000)});
      if(response.status===401){expired=true;unavailable('로그인 후 장 상태를 다시 확인해 주세요.');return;}
      if(!response.ok||!(response.headers.get('content-type')||'').includes('application/json'))throw new Error();
      const state=await response.json(),checked=Date.parse(state.checkedAt);
      if(!Number.isFinite(checked)||Math.abs(Date.now()-checked)>15000||!['OPEN','BEFORE_OPEN','AFTER_CLOSE','HOLIDAY','CHECK_REQUIRED'].includes(state.phase))throw new Error();
      label.dataset.phase=state.phase;label.textContent=state.label;detail.textContent=state.detail;
      updated.textContent='상태 확인 '+stamp(checked)+' · 달력 '+(state.calendarVerified?'오늘 확인':'확인 필요')+(state.calendarFetchedAt?' ('+stamp(state.calendarFetchedAt)+')':'');
      lastSuccess=Date.now();
    }catch(_){unavailable('서버의 최신 응답을 받지 못했어요. 이전 장 상태를 현재 상태로 사용하지 않아요.');}
    finally{busy=false;}
  }
  document.addEventListener('visibilitychange',()=>{if(!document.hidden){unavailable('최신 장 상태를 다시 확인하고 있어요.');refresh();}});
  setInterval(()=>{if(!expired&&lastSuccess&&Date.now()-lastSuccess>15000)unavailable('상태 갱신이 지연됐어요. 최신 응답을 기다리고 있어요.');},1000);
  refresh();setInterval(refresh,5000);
})();
