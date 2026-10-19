(() => {
 'use strict';
 const el=id=>document.getElementById(id),node=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=text;return n;};
 const fmt=new Intl.NumberFormat('ko-KR',{maximumFractionDigits:0}),money=x=>x==null?'확인 대기':fmt.format(Number(x))+'원',signed=x=>x==null?'확인 대기':(Number(x)>0?'+':'')+money(x),pct=x=>x==null?'확인 대기':(Number(x)>0?'+':'')+Number(x).toFixed(2)+'%',rate=x=>x==null?'미확인':new Intl.NumberFormat('ko-KR',{maximumFractionDigits:5}).format(Number(x)*100)+'%';
 const time=x=>x?new Intl.DateTimeFormat('ko-KR',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false,timeZone:'Asia/Seoul'}).format(new Date(x))+' KST':'시세 확인 대기';
 let view=null,posting=false,loading=false,prepared=null,attempted=false,uncertain=false,deadline=0,balanceDeadline=0,sendStarted=0,measuring=false;const quantities=new Map();const requestedCode=new URLSearchParams(location.search).get('code');let first=true;
 function error(text){el('sale-error').textContent=text;el('sale-error').hidden=!text;}
 function controls(){const remaining=Math.max(0,Math.floor((deadline-performance.now())/1000)),balanceReady=performance.now()<balanceDeadline;const loss=Number(prepared?.quote?.economics?.netPnl)<0;
  el('sale-submit').disabled=posting||measuring||attempted||uncertain||!prepared?.intentId||remaining<1||!balanceReady||!el('sale-confirmed').checked||(loss&&!el('sale-loss-confirmed').checked);
  el('sale-recalculate').disabled=posting||attempted||uncertain;el('sale-close').disabled=posting;
  if(prepared)el('sale-expiry').textContent=prepared.intentId?(remaining>0?'확인 유효시간 '+remaining+'초 · '+(balanceReady?'15초 이내 잔고 대조 확인됨':'잔고 대조 갱신 대기 · 최신 금액을 다시 확인할 수 있어요.'):'확인이 만료됐어요. 최신 금액을 다시 확인해 주세요.'):'지금은 계산만 가능해요. 아래 전송 보류 사유를 확인해 주세요.';
  el('sale-measure').disabled=posting||measuring;
  if(sendStarted&&posting){const ms=Math.round(performance.now()-sendStarted);el('sale-send-clock').textContent='응답 대기 '+fmt.format(ms)+'ms'+(ms>=3000?' · 재전송하지 않고 접수 결과를 기다려요.':' · 전송 준비/증권사 응답 확인 중');}
  document.querySelectorAll('.sale-open').forEach(b=>b.disabled=posting||measuring||uncertain);
 }
 async function request(path,body){const started=performance.now();const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);try{
  const headers={'Accept':'application/json'};if(body){headers['Content-Type']='application/json';headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-value"]').content;}
  const r=await fetch(path,{method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,headers,credentials:'same-origin',cache:'no-store',signal:controller.signal});
  if(r.status===401)throw new Error('로그인이 필요해요. 로그인 후 다시 확인해 주세요.');const data=await r.json();if(!r.ok){const e=new Error(data.message||'계좌·시세·수량을 다시 확인해 주세요.');e.notSubmitted=data.notSubmitted===true;throw e;}
  if(data&&typeof data==='object'&&!Array.isArray(data))data._timing={roundTripMs:Math.round(performance.now()-started),serverMs:r.headers.get('X-Order-Server-Ms'),dispatchMs:r.headers.get('X-Order-Dispatch-Ms')};return data;
 }finally{clearTimeout(timer);}}
 function render(data){view=data;balanceDeadline=data.reconciledAt?performance.now()+Math.max(0,15000-(Date.parse(data.checkedAt)-Date.parse(data.reconciledAt))):0;el('sale-environment').textContent=data.environment==='PAPER'?'모의계좌 · 직접 확인 후 매도':'실전 환경 · 직접 매도 연결 전';el('sale-updated').textContent='조회 '+time(data.checkedAt)+' · 15초마다 저장 상태 갱신';el('sale-cost-note').textContent=data.costNote;
  const container=el('sale-positions');if(container.contains(document.activeElement))return;container.replaceChildren();
  if(!data.positions.length){container.append(node('p','small','현재 원장에 보유한 종목이 없어요.'));return;}
  for(const p of data.positions){const card=node('article','sale-card');card.id='sale-'+p.code;const e=p.economics;
   card.append(node('h2','',p.name),node('p','small',p.heldQuantity+'주 보유 · 증권사 매도 가능 '+(p.sellableQuantity==null?'확인 대기':p.sellableQuantity+'주')),node('span','sale-badge',p.suggestion),node('p','small',p.reason));
   card.append(node('span','sale-label','전량 매도 시 예상 입금액'),node('strong','sale-amount',money(e?.netProceeds)),node('span','sale-label','전량 매도 시 비용 후 손익'),node('strong','sale-amount '+(Number(e?.netPnl)<0?'sale-negative':'sale-positive'),signed(e?.netPnl)+' · '+pct(e?.netReturnPercent)));
   card.append(node('p','small','기준 지정가 '+money(p.limitPrice)+' · '+time(p.quoteAt)+(p.quoteFresh?' · 검증 시세':' · 이전 실제 시세 / 계산 참고')));
   if(p.blockers.length){const list=node('ul','sale-blockers');p.blockers.forEach(x=>list.append(node('li','',x)));card.append(list);}
   const form=node('form',''),label=node('label','','매도할 수량'),row=node('div','sale-quantity'),input=node('input','');input.type='number';input.min='1';input.max=String(p.heldQuantity);input.step='1';input.inputMode='numeric';input.required=true;input.id='quantity-'+p.code;input.value=String(Math.min(p.heldQuantity,quantities.get(p.code)||p.suggestedQuantity));label.htmlFor=input.id;input.addEventListener('input',()=>quantities.set(p.code,Number(input.value)));
   row.append(input,node('span','','주'));for(const [text,value] of [['절반',Math.max(1,Math.floor(p.heldQuantity/2))],['전량',p.heldQuantity]]){const b=node('button','',text);b.type='button';b.addEventListener('click',()=>{input.value=String(value);quantities.set(p.code,value);});row.append(b);}
   const open=node('button','sale-open','이 수량의 금액 확인');open.type='submit';form.append(label,row,open);form.addEventListener('submit',event=>{event.preventDefault();if(form.reportValidity())prepare(p.code,Number(input.value));});card.append(form);container.append(card);
  }
  controls();if(first&&requestedCode){el('sale-'+requestedCode)?.scrollIntoView({block:'start',behavior:'smooth'});}first=false;
 }
 async function refresh(){if(loading||posting||document.hidden)return;loading=true;try{render(await request('/api/manual-sales'));if(!uncertain)error('');}catch(e){error(e.message||'연결을 확인해 주세요. 마지막 금액을 최신으로 판단하지 마세요.');}finally{loading=false;}}
 function showPrepared(p){prepared=p;attempted=false;const q=p.quote,e=q.economics;el('sale-dialog-title').textContent=q.name+' 매도 확인';el('sale-order-description').textContent=q.quantity+'주 × 지정가 '+money(q.limitPrice)+' · 이 가격 이상에서 체결을 요청하는 모의 주문이에요.';
  el('sale-net-proceeds').textContent=money(e?.netProceeds);el('sale-net-pnl').textContent=signed(e?.netPnl);el('sale-net-pnl').className=Number(e?.netPnl)<0?'sale-negative':'sale-positive';el('sale-net-percent').textContent=pct(e?.netReturnPercent);
  const detail=el('sale-breakdown');detail.replaceChildren();for(const [label,value] of [['매도 총액',money(e?.grossProceeds)],['매도 수수료 · '+rate(q.commissionRate),money(e?.sellCommission)],['거래세 · '+rate(q.taxRate),money(e?.transactionTax)],['매도 후 예상 입금액',money(e?.netProceeds)],['선택 수량의 매수 원금',money(e?.principal)],['이미 낸 매수 수수료 · 수량 배분',money(e?.allocatedBuyCommission)],['매수·매도 총 거래비용',money(e?.totalTradingCosts)],['모든 매매비용 후 예상 손익',signed(e?.netPnl)]]){const row=node('div','');row.append(node('dt','',label),node('dd','',value));detail.append(row);}
  el('sale-basis').textContent='시세 '+time(q.quoteAt)+' · 확인 금액은 예상이며 실제 체결·정산을 보장하지 않아요.';el('sale-blockers').replaceChildren(...q.blockers.map(x=>node('li','',x)));
  el('sale-confirmed').checked=false;el('sale-loss-confirmed').checked=false;el('sale-loss-row').hidden=!(Number(e?.netPnl)<0);el('sale-feedback').textContent='';if(!el('sale-dialog').open)el('sale-dialog').showModal();controls();
 }
 async function prepare(code,quantity){if(posting||uncertain)return;posting=true;el('sale-result').textContent='증권사 주문·잔고를 대조하고 금액을 준비하고 있어요. 아직 주문하지 않아요.';el('sale-result').hidden=false;controls();const started=performance.now();try{const p=await request('/api/manual-sales/prepare',{code,quantity});balanceDeadline=p.reconciledAt?performance.now()+Math.max(0,15000-(Date.parse(p.quote.checkedAt)-Date.parse(p.reconciledAt))-(performance.now()-started)):0;deadline=p.expiresAt?performance.now()+Math.max(0,Math.min(60000,Date.parse(p.expiresAt)-Date.parse(p.quote.checkedAt)-(performance.now()-started))):0;showPrepared(p);}catch(e){error(e.message||'금액 확인에 실패했어요. 주문은 전송하지 않았어요.');}finally{posting=false;controls();}}
 async function confirm(){if(el('sale-submit').disabled||!prepared)return;const id=prepared.intentId;posting=true;attempted=true;sendStarted=performance.now();el('sale-feedback').textContent='확인한 매도 요청의 응답을 기다리고 있어요.';controls();try{
  const r=await request('/api/manual-sales/confirm',{intentId:id,confirmed:el('sale-confirmed').checked,lossAcknowledged:el('sale-loss-confirmed').checked});
  const state=r.notSubmitted?'전송 전 중단 · 주문 전송 없음':({SUBMITTED:'증권사 접수 · 체결 확인 대기',PARTIALLY_FILLED:'일부 체결 · 잔량 확인 필요',FILLED:'체결 확인',REJECTED:'접수 거절',UNKNOWN:'접수 결과 미확정',SUBMITTING:'전송 상태 확인 필요'})[r.status]||'주문 상태 확인 필요';
  const t=r._timing;el('sale-send-clock').textContent='화면 왕복 '+fmt.format(t?.roundTripMs||0)+'ms · 서버 '+(t?.serverMs==null?'미측정':fmt.format(Number(t.serverMs))+'ms')+' · 전송 시작 '+(t?.dispatchMs==null?'전송 기록 없음':fmt.format(Number(t.dispatchMs))+'ms')+' · 체결 완료 시간과 달라요.';
  if(r.notSubmitted){prepared={...prepared,intentId:null};deadline=0;attempted=false;}
  el('sale-feedback').textContent=state+' · 주문 #'+r.orderId+' · '+r.message;el('sale-result').textContent=state+' · 주문 #'+r.orderId+' · 보유·주문 화면에서 최종 결과를 확인해 주세요.';el('sale-result').hidden=false;
 }catch(e){
  if(e.notSubmitted){prepared={...prepared,intentId:null};deadline=0;attempted=false;el('sale-feedback').textContent=e.message;el('sale-send-clock').textContent='전송 없음 · 최신 금액 확인 후 직접 다시 요청할 수 있어요.';}
  else{uncertain=true;el('sale-feedback').textContent=(e.message||'전송 결과 확인이 필요해요.')+' 새 주문을 만들지 말고 보유·주문 화면에서 먼저 확인해 주세요.';error('매도 요청 결과를 먼저 확인해 주세요. 자동 재전송하지 않아요.');}
 }finally{posting=false;sendStarted=0;controls();refresh();loadTimings();}}
 async function loadTimings(){if(document.hidden)return;try{const rows=await request('/api/manual-sales/timings'),box=el('sale-speed-history');box.replaceChildren();
  if(!rows.length){box.append(node('p','small','아직 단계별 측정 기록이 없어요. 위 버튼은 조회·대조만 측정해요. 실제 주문 전송 시간은 직접 매도한 뒤 기록돼요.'));return;}
  for(const r of rows.slice(0,6)){const item=node('p','small');const kind=({MANUAL_CONFIRM:'직접 매도',MANUAL_PREPARE:'금액 준비',RECONCILE_READ:'조회·대조만'})[r.operation]||'처리';
   item.textContent=time(r.measuredAt)+' · '+kind+' · 서버 '+fmt.format(r.totalMs)+'ms · '+(r.dispatchMs==null?'전송 없음':('전송 시작 '+fmt.format(r.dispatchMs)+'ms'))+' · '+(({READY:'조회 완료',NOT_SENT:'전송 안 함',SUBMITTED:'접수',UNKNOWN:'접수 미확정',REJECTED:'중단/거절',FILLED:'체결 확인'})[r.outcome]||r.outcome);box.append(item);}
 }catch(_){el('sale-speed-feedback').textContent='처리 시간 기록 연결 확인 필요';}}
 el('sale-measure').addEventListener('click',async()=>{if(posting||measuring)return;measuring=true;controls();el('sale-speed-feedback').textContent='조회·대조 시간을 측정 중이에요. 매도 주문은 보내지 않아요.';
  try{const r=await request('/api/manual-sales/measure',{});el('sale-speed-feedback').textContent=r.message+' 화면 왕복 '+fmt.format(r._timing.roundTripMs)+'ms / 서버 '+fmt.format(Number(r._timing.serverMs))+'ms';}
  catch(e){el('sale-speed-feedback').textContent=e.message;}finally{measuring=false;controls();loadTimings();refresh();}});
 el('sale-close').addEventListener('click',()=>{if(!posting)el('sale-dialog').close();});el('sale-dialog').addEventListener('cancel',event=>{if(posting)event.preventDefault();});
 el('sale-confirmed').addEventListener('change',controls);el('sale-loss-confirmed').addEventListener('change',controls);el('sale-submit').addEventListener('click',confirm);el('sale-recalculate').addEventListener('click',()=>{if(prepared&&!attempted)prepare(prepared.quote.code,prepared.quote.quantity);});
 document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});refresh();loadTimings();setInterval(refresh,5000);setInterval(loadTimings,15000);setInterval(controls,500);
})();
