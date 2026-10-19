(() => {
  'use strict';
  const el=id=>document.getElementById(id),make=(tag,text)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;return n;};
  const library=document.body.dataset.reviewLibrary==='true',params=new URLSearchParams(location.search);
  let tab=['active','completed','paused','closed'].includes(params.get('tab'))?params.get('tab'):null;
  let selected=params.get('id')||'',inbox=null,loading=false,marking=false,controlling=false,renderedId=null,renderedState='',next=0,selectSignature='',lastSuccess=0;
  const active=r=>['QUEUED','RUNNING','FINALIZING'].includes(r.state);
  const stamp=x=>x?new Intl.DateTimeFormat('ko-KR',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false,timeZone:'Asia/Seoul'}).format(new Date(x)):'';
  const state=x=>({QUEUED:'예약',RUNNING:'토론 중',FINALIZING:'요약 중',COMPLETE:'완료',FAILED:'보류',UNKNOWN:'응답 확인 필요',BUDGET_WAIT:'예산 대기',STALE:'중단',PAUSED:'일시중지',CLOSED:'검토 종료'})[x]||'확인 필요';
  function receiptControls(){
    const row=inbox?.reviews.find(r=>String(r.briefId)===selected),button=el('saved-review-seen');
    button.hidden=!row?.complete;
    button.disabled=marking||controlling||!row?.unread||renderedId!==Number(selected)||renderedState!=='COMPLETE'||Date.now()-lastSuccess>30000;
    button.textContent=row?.unread?'이 결과 확인했어요':'확인한 결과예요';
  }
  function choose(id){
    if(marking||controlling)return;
    selected=id==null?'':String(id);renderedId=null;renderedState='';el('saved-review-feedback').textContent='';
    const q=new URLSearchParams({tab:tab||'completed'});if(selected)q.set('id',selected);
    history.replaceState(null,'','/committee/reviews?'+q);
    document.dispatchEvent(new CustomEvent('committee-library-select',{detail:{id:selected?Number(selected):null}}));
    render();
  }
  function render(){
    if(!inbox)return;
    el('reviews-active-count').textContent=inbox.activeCount+'건';
    el('reviews-completed-count').textContent=inbox.completedCount+'건';
    el('reviews-unread-count').textContent='미확인 '+inbox.unreadCount+'건';
    document.querySelectorAll('[data-review-unread]').forEach(n=>n.textContent='· 미확인 '+inbox.unreadCount+'건');
    el('reviews-paused-count').textContent=inbox.pausedCount+'건';
    el('reviews-closed-count').textContent=(inbox.closedCount||0)+'건';
    el('review-inbox-status').textContent='완료 '+inbox.completedCount+'건 · 확인 전 '+inbox.unreadCount+'건 · '+stamp(inbox.checkedAt)+' KST';
    const running=inbox.reviews.filter(active);
    el('review-running-summary').textContent=running.length?running.map(r=>r.name+' · '+state(r.state)+' · '+Math.floor(r.doneSteps*100/r.totalSteps)+'%').join(' / ')+' · 다른 기업을 봐도 이 검토는 계속 진행돼요.':'진행 중인 검토가 없어요. 새 유료 검토는 기업 선택 후 직접 시작해야 해요.';
    if(!library)return;
    if(!tab){const specified=inbox.reviews.find(r=>String(r.briefId)===selected);tab=specified?(active(specified)?'active':specified.complete?'completed':specified.state==='CLOSED'?'closed':'paused'):inbox.activeCount?'active':'completed';}
    for(const key of ['active','completed','paused','closed'])el('reviews-'+key).setAttribute('aria-current',key===tab?'page':'false');
    let rows=inbox.reviews.filter(r=>tab==='completed'?r.complete:tab==='active'?active(r):tab==='closed'?r.state==='CLOSED':!r.complete&&!active(r)&&r.state!=='CLOSED');
    // Keep an open meeting visible when it finishes; never replace its conversation with another company.
    const current=inbox.reviews.find(r=>String(r.briefId)===selected);
    if(tab==='active'&&current&&!rows.some(r=>r.briefId===current.briefId))rows=[current,...rows];
    if(!selected&&rows.length){const first=tab==='completed'?rows.find(r=>r.unread)||rows[0]:rows[0];choose(first.briefId);return;}
    const selector=el('saved-review-select');
    el('saved-review-label').textContent=tab==='completed'?'검토완료 기업 선택':tab==='active'?'진행 중인 검토 선택':tab==='closed'?'종료한 검토 선택':'보류·중단된 검토 선택';
    const signature=JSON.stringify([tab,selected,rows.map(r=>[r.briefId,r.state,r.unread,r.doneSteps])]);
    if(signature!==selectSignature&&document.activeElement!==selector){
      selector.replaceChildren();
      const groups=tab==='completed'?[['미확인 결과',rows.filter(r=>r.unread)],['확인한 결과',rows.filter(r=>!r.unread)]]:[[tab==='active'?'진행·방금 완료된 검토':tab==='closed'?'종료한 검토':'보류·중단된 검토',rows]];
      for(const [label,items] of groups){if(!items.length)continue;const group=make('optgroup');group.label=label;
        for(const r of items){const o=make('option',(r.unread?'● ':'')+r.name+' · '+(r.agenda==='SELL_REVIEW'?'과거 매도 검토':'기업 분석')+' · '+stamp(r.createdAt)+' · '+state(r.state)+(active(r)?' '+Math.floor(r.doneSteps*100/r.totalSteps)+'%':''));o.value=String(r.briefId);group.append(o);}selector.append(group);}
      if(!rows.length){const o=make('option',tab==='completed'?'아직 완료된 검토가 없어요':tab==='active'?'현재 진행 중인 검토가 없어요':tab==='closed'?'종료한 검토가 없어요':'보류·중단된 검토가 없어요');o.value='';selector.append(o);}
      selector.value=selected;selector.disabled=!rows.length||marking||controlling;selectSignature=signature;
    }
    const chosen=inbox.reviews.find(r=>String(r.briefId)===selected);
    el('saved-review-note').textContent=chosen?(chosen.complete?(chosen.unread?'아래 대화와 결과를 보고 ‘이 결과 확인했어요’를 누르면 미확인 개수에서 빠져요. 매수·매도 승인은 아니에요.':'확인한 결과예요. 저장된 대화와 요약을 다시 볼 수 있어요.'):chosen.state==='CLOSED'?'종료한 검토예요. 대화와 비용은 보관되어 있고 새 검토를 만들면 별도 기록으로 이어져요.':active(chosen)?'실제 저장된 발언과 진행 상황이에요. 완료되면 이 화면에서 결과를 이어서 볼 수 있어요.':'아래에서 이어서 진행·최신 자료로 처음부터·검토 종료를 선택할 수 있어요. 사용할 수 없는 선택은 이유를 함께 표시해요.'):'목록에서 기업을 고르면 당시 대화와 결과를 불러와요. 목록 조회에는 AI 비용이 들지 않아요.';
    const source=el('saved-review-source');source.hidden=!chosen;if(chosen)source.href='/committee?code='+encodeURIComponent(chosen.code)+'&agenda='+encodeURIComponent(chosen.agenda);
    receiptControls();
  }
  async function refresh(){
    if(loading||document.hidden)return;loading=true;const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const r=await fetch('/api/committee/reviews',{credentials:'same-origin',cache:'no-store',signal:controller.signal});if(!r.ok)throw new Error();inbox=await r.json();lastSuccess=Date.now();el('review-inbox-error').hidden=true;render();}
    catch(_){el('review-inbox-error').hidden=false;el('review-inbox-error').textContent='검토 목록 연결 확인 필요 · 마지막 표시값이며 새 회의나 재호출은 시작하지 않아요.';}
    finally{clearTimeout(timer);loading=false;next=Date.now()+(inbox?.activeCount?5000:15000);receiptControls();}
  }
  async function markSeen(){
    const row=inbox?.reviews.find(r=>String(r.briefId)===selected);
    if(marking||!row?.complete||!row.unread||renderedId!==row.briefId||renderedState!=='COMPLETE'||document.hidden)return;
    marking=true;receiptControls();el('saved-review-select').disabled=true;
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const headers={};headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-value"]').content;
      const r=await fetch('/api/committee/reviews/'+row.briefId+'/seen',{method:'POST',credentials:'same-origin',headers,signal:controller.signal});if(!r.ok)throw new Error();const result=await r.json();el('saved-review-feedback').textContent=result.message;
    }catch(_){el('saved-review-feedback').textContent='확인 표시 결과를 받지 못했어요. 목록을 새로 확인한 뒤 다시 눌러도 중복 처리되지 않아요.';}
    finally{clearTimeout(timer);marking=false;selectSignature='';await refresh();el('saved-review-select').disabled=!inbox?.reviews.length;receiptControls();}
  }
  if(library){
    el('saved-review-browser').hidden=false;document.querySelector('.library-back').hidden=false;
    el('saved-review-select').addEventListener('change',()=>choose(el('saved-review-select').value||null));
    el('saved-review-select').addEventListener('blur',()=>render());el('saved-review-seen').addEventListener('click',markSeen);
    document.addEventListener('committee-review-rendered',e=>{renderedId=e.detail.id;renderedState=e.detail.state;receiptControls();});
    document.getElementById('committee-empty').hidden=true;
  }else{
    for(const node of [el('reviews-active'),el('reviews-completed'),el('reviews-paused'),el('reviews-closed'),...document.querySelectorAll('[data-review-link]')]){node.target='_blank';node.rel='noopener noreferrer';}
  }
  document.addEventListener('committee-control-busy',e=>{controlling=!!e.detail?.busy;if(library)el('saved-review-select').disabled=marking||controlling||!inbox?.reviews.length;});
  document.addEventListener('committee-control-updated',()=>refresh());
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
  refresh();setInterval(()=>{if(Date.now()>=next)refresh();},5000);setInterval(receiptControls,3000);
})();
