(() => {
  'use strict';
  const el=id=>document.getElementById(id), make=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=text;return n;};
  const fmt=new Intl.NumberFormat('ko-KR',{maximumFractionDigits:0});
  const money=x=>x==null?'확인 대기':fmt.format(Number(x))+'원';
  const rounded=x=>{const v=Math.sign(Number(x))*Math.round(Math.abs(Number(x)));return Object.is(v,-0)?0:v;};
  const exact=x=>x==null?'확인 대기':fmt.format(rounded(x))+'원';
  const signed=x=>x==null?'확인 대기':(rounded(x)>0?'+':'')+exact(x);
  const percent=x=>x==null?'확인 대기':(Number(x)>0?'+':'')+Number(x).toFixed(2)+'%';
  const rate=x=>x==null?'요율 미확인':new Intl.NumberFormat('ko-KR',{maximumFractionDigits:4}).format(Number(x)*100)+'%';
  const time=x=>x?new Intl.DateTimeFormat('ko-KR',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false,timeZone:'Asia/Seoul'}).format(new Date(x))+' KST':'시각 미확인';
  const labels={WATCH:'관심 유지',DEFER:'검토 보류',REJECT:'관심 제외'};
  const agenda=a=>a==='SELL_REVIEW'?'과거 매도 검토':'기업 분석';
  const libraryMode=document.body.dataset.reviewLibrary==='true';
  const pagePath=libraryMode?'/committee/reviews':'/committee';
  const params=new URLSearchParams(location.search);let selected=params.get('id')||'',code=/^[0-9]{6}$/.test(params.get('code')||'')?params.get('code'):'',action=params.get('agenda')==='SELL_REVIEW'?'SELL_REVIEW':'BUY_REVIEW',view=null,loading=false,posting=false,lastSuccess=0;
  let requestKey='',requestSignature='',quantityOverride='';
  let candidatePage=0,candidateSignature='';
  let chatMode='ALL',chatRole='ALL',chatSignature='',nextRefreshAt=0;
  let coverageSignature='',evidenceSignature='';
  const shortRole={ECONOMY:'경제',POLICY:'정책',NEWS:'뉴스',INDUSTRY:'산업',SCIENCE:'기술',FINANCE:'재무',MARKET:'시장',RISK:'위험'};
  const voteLabel=v=>({APPROVE:'찬성',REJECT:'반대',ABSTAIN:'기권'})[v]||'대기';
  const aiState=x=>({NOT_QUEUED:'검토 요청 대기',QUEUED:'검토 예약됨',RUNNING:'의견 작성 중',FINALIZING:'종합 요약 중',COMPLETE:'검토 완료',FAILED:'검토 보류',UNKNOWN:'호출 결과 확인 필요',BUDGET_WAIT:'월 예산 대기',STALE:'새 자료 필요',PAUSED:'일시중지 · 내 선택 대기',CLOSED:'검토 종료 · 기록 보관'})[x]||'상태 확인 중';
  const cents=x=>Number(x)>0?Math.ceil(Number(x)*100):0;
  const isFresh=()=>view&&Date.now()-lastSuccess<45000&&Date.now()-new Date(view.checkedAt).getTime()<45000;
  function controls(){document.querySelectorAll('[data-candidate-code]').forEach(b=>b.disabled=posting);document.querySelectorAll('[data-review]').forEach(b=>b.disabled=posting||!isFresh()||!view?.entry);
    const fresh=isFresh();el('ai-progress-display').dataset.stale=String(!fresh);el('ai-progress-freshness').textContent=view?(fresh?'최근 동기화 '+time(view.checkedAt)+' · 검토 중 5초마다 갱신':'연결 확인 필요 · 마지막 확인된 진행률이에요.'):'상태 불러오는 중';
    const a=view?.aiStatus;el('ai-analyze').disabled=libraryMode||posting||!isFresh()||!view?.entry||!a?.configured||a?.requestBlocked||!view?.entry?.report?.financialCurrent||!view?.entry?.report?.financialEvidence?.annualEvidenceReady||(a?.state==='NOT_QUEUED'&&(a?.budget?.todayMeetings>=a?.budget?.dailyLimit||Number(a?.budget?.remainingUsd)<=0))||['QUEUED','RUNNING','FINALIZING','COMPLETE','UNKNOWN','STALE'].includes(a?.state);
    el('ai-analyze').hidden=libraryMode||a?.state!=='NOT_QUEUED';
    el('committee-company').disabled=posting;el('committee-history').disabled=posting;
    const flags={PAUSE:'canPause',RESUME:'canResume',RESTART:'canRestart',CLOSE:'canClose'};
    document.querySelectorAll('[data-review-control]').forEach(b=>b.disabled=posting||!fresh||!a?.controls?.[flags[b.dataset.reviewControl]]);
  }
  function renderAiProgress(ai){
    const count=x=>Number.isInteger(Number(x))?Math.max(0,Math.min(8,Number(x))):0;
    const independent=count(ai?.independentCount),finals=count(ai?.finalCount),debate=count(ai?.debateCount),total=ai?.totalSteps===17?17:25,withDebate=total===25;
    const complete=ai?.state==='COMPLETE'&&independent===8&&finals===8&&(!withDebate||debate===8)&&!!ai?.summary;
    const done=independent+finals+(withDebate?debate:0)+(ai?.summary?1:0),percent=Math.floor(done*100/total);
    const paused=['FAILED','UNKNOWN','BUDGET_WAIT','STALE','PAUSED','CLOSED'].includes(ai?.state);
    let stage=aiState(ai?.state);
    if(complete)stage='검토 완료 · 요약을 확인해 주세요';
    else if(ai?.state==='RUNNING')stage=independent<8?'전문가들이 독립 의견과 질문을 작성하고 있어요':withDebate&&debate<8?'상대 질문에 답하고 반론을 주고받고 있어요':finals<8?'반론을 검토하고 최종 투표 중이에요':'종합 요약을 준비하고 있어요';
    else if(ai?.state==='FINALIZING')stage='최종 의견을 모아 종합 요약 중이에요';
    else if(ai?.state==='COMPLETE')stage='완료 자료 확인 필요';
    el('ai-progress-display').dataset.state=paused?'PAUSED':complete?'COMPLETE':ai?.state||'NOT_QUEUED';
    el('ai-progress-percent').textContent=percent+'%';el('ai-progress-stage').textContent=stage;
    el('ai-stage-debate').hidden=!withDebate;el('ai-progress-bar').max=total;el('ai-progress-bar').value=done;el('ai-progress-bar').setAttribute('aria-valuetext',percent+'% · '+done+'/'+total+'개 작업 완료 · '+stage);
    for(const [id,text,finished,active] of [
      ['ai-stage-independent','독립 의견 '+independent+'/8',independent===8,ai?.state==='RUNNING'&&independent<8],
      ['ai-stage-debate','질문·답변 '+debate+'/8',debate===8,ai?.state==='RUNNING'&&independent===8&&debate<8],
      ['ai-stage-final','최종 투표 '+finals+'/8',finals===8,ai?.state==='RUNNING'&&independent===8&&(!withDebate||debate===8)&&finals<8],
      ['ai-stage-summary','종합 요약 '+(complete?'완료':ai?.state==='FINALIZING'?'작성 중':'대기'),complete,ai?.state==='FINALIZING']]){
      el(id).textContent=text;el(id).dataset.stage=finished?'done':active&&!paused?'active':'waiting';
    }
  }
  function renderReviewControls(data){
    const o=data.aiStatus?.controls,panel=el('review-control-panel'),state=data.aiStatus?.state;
    panel.hidden=!o||state==='COMPLETE';if(!o)return;
    if(panel.dataset.brief!==String(data.entry.id)){el('review-control-feedback').textContent='';panel.dataset.brief=String(data.entry.id);}
    const usd=x=>Number(x)>0&&Number(x)<.0001?'$0.0001 미만':new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',minimumFractionDigits:2,maximumFractionDigits:4}).format(Number(x||0));
    el('review-control-title').textContent=o.canPause?'진행 중인 검토 관리':state==='CLOSED'?'종료한 검토 · 기록 보관':'이 검토를 어떻게 할까요?';
    el('review-control-summary').textContent='저장된 결과 '+o.savedSteps+'/'+o.totalSteps+'단계 · 사용 비용 '+usd(o.settledUsd)+' · 처리 중·미확정 예약 '+usd(o.reservedUsd)+' · 당시 자료 '+time(o.evidenceAt)+(o.inFlightCalls?' · 결과 확인 중 '+o.inFlightCalls+'건':'');
    for(const b of document.querySelectorAll('[data-review-control]')){
      const action=b.dataset.reviewControl;
      b.hidden=action==='PAUSE'?!o.canPause:action==='RESUME'?o.canPause||state==='CLOSED':action==='RESTART'?o.canPause||!!o.restartedBriefId:!o.canClose;
    }
    el('review-resume-reason').textContent=!o.canPause&&state!=='CLOSED'&&o.resumeReason?'이어서 진행: '+o.resumeReason:'';
    el('review-restart-reason').textContent=!o.canPause&&o.restartReason?'새 검토: '+o.restartReason:'';
    const link=el('review-restarted-link');link.hidden=!o.restartedBriefId;
    if(o.restartedBriefId)link.href='/committee/reviews?id='+encodeURIComponent(o.restartedBriefId);
  }
  async function controlReview(action){
    const o=view?.aiStatus?.controls,flag={PAUSE:'canPause',RESUME:'canResume',RESTART:'canRestart',CLOSE:'canClose'}[action];
    if(posting||!isFresh()||!view?.entry||!o?.[flag])return;
    const id=view.entry.id,name=view.entry.report.name,extra=o.requiresUnknownAcknowledgement?'\n미확정 '+o.unknownCallIds.length+'건의 예약 $'+Number(o.unknownReservedUsd||0).toFixed(4)+'는 그대로 남고 새 검토 비용이 추가돼요. 이전 호출 자체는 재전송하지 않아요.':'';
    const messages={PAUSE:'일시중지할까요?\n이미 전송된 호출은 결과·비용을 저장한 뒤 멈추고 다음 호출은 하지 않아요.',
      RESUME:'이어서 진행할까요?\n저장한 '+o.savedSteps+'/'+o.totalSteps+'단계를 유지하고 남은 단계부터 진행해요. 답변을 저장하지 못한 단계는 다시 요청해 추가 비용이 생길 수 있어요. 월 $10 한도 적용.',
      RESTART:'최신 자료로 처음부터 검토할까요?\n새 근거 작성 시각: '+time(o.restartEvidenceAt)+'\n이전 대화와 비용은 남기고 새 검토에서 최대 25회 API 호출 비용이 발생해요. 월 $10과 오늘 회의 한도를 적용해요.'+extra,
      CLOSE:'이 검토를 종료할까요?\n추가 호출은 멈추고 기존 대화·사용 비용·미확정 예약 비용은 보관해요. 이미 전송된 호출은 결과 저장 후 종료해요.'};
    if(!window.confirm(name+' · '+messages[action]))return;
    if(!isFresh()||view?.entry?.id!==id){el('review-control-feedback').textContent='화면이 오래됐어요. 갱신 후 다시 선택해 주세요.';refresh();return;}
    const storageKey='committee-control:'+id+':'+action+':'+o.expectedUpdatedAt;let key=crypto.randomUUID();
    try{key=sessionStorage.getItem(storageKey)||key;sessionStorage.setItem(storageKey,key);}catch(_){}
    posting=true;controls();document.dispatchEvent(new CustomEvent('committee-control-busy',{detail:{busy:true}}));
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const headers={'Content-Type':'application/json'};headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-value"]').content;
      const response=await fetch('/api/committee/'+id+'/control',{method:'POST',credentials:'same-origin',cache:'no-store',headers,
        body:JSON.stringify({action,confirmed:true,clientKey:key,expectedUpdatedAt:o.expectedUpdatedAt,evidenceBriefId:o.restartEvidenceBriefId,acknowledgeUnknownCosts:action==='RESTART'&&o.requiresUnknownAcknowledgement,unknownCallIds:o.unknownCallIds||[]}),signal:controller.signal});
      const result=await response.json();el('review-control-feedback').textContent=result.message||'현재 검토 상태를 다시 확인해 주세요.';
      if(response.ok&&Number.isSafeInteger(result.briefId)&&result.briefId>0){
        const tab=result.state==='CLOSED'?'closed':['QUEUED','RUNNING','FINALIZING'].includes(result.state)?'active':result.state==='COMPLETE'?'completed':'paused';
        location.assign('/committee/reviews?'+new URLSearchParams({tab,id:String(result.briefId)}));
      }
    }catch(_){el('review-control-feedback').textContent='결과를 받지 못했어요. 자동 재요청하지 않아요. 상태를 새로 확인한 뒤 같은 선택을 다시 누르면 요청 식별자로 중복 처리를 막아요.';}
    finally{clearTimeout(timer);posting=false;document.dispatchEvent(new CustomEvent('committee-control-busy',{detail:{busy:false}}));document.dispatchEvent(new CustomEvent('committee-control-updated'));refresh();controls();}
  }
  function warning(message){el('load-issue').textContent=message;el('load-issue').hidden=!message;controls();}
  function list(id,items,empty){el(id).replaceChildren(...(items?.length?items:[empty]).map(x=>make('li','',x)));}
  function link(url,text){const a=make('a','',text);try{const u=new URL(url);if(!['https:','http:'].includes(u.protocol)||u.username||u.password)throw new Error();a.href=u.href;a.target='_blank';a.rel='noopener noreferrer';}catch(_){a.removeAttribute('href');}return a;}
  const accessLabel=e=>({'TITLE_ONLY':'제목만 수집','PUBLISHER_SUMMARY':'발행처 요약 수집','PUBLISHER_EXCERPT':'공개 기사 본문 일부','OFFICIAL_EXCERPT':'공식 본문 일부 발췌','VALUATION_ASSUMPTIONS':'가정 기반 가치 연구','OFFICIAL_LINK':'공식 링크 · 본문 검증 별도','FINANCIAL_VALUES':'공시 재무 수치','MARKET_VALUES':'시장 수치'}[e.accessLevel]||'확인 범위 미정');
  const sourceDate=e=>e.publishedAt?time(e.publishedAt):(e.publishedDate||'발행일 미확인');
  function evidenceSource(e,opened=new Set()){
    const n=make('article','evidence');n.append(link(e.url,e.title));
    const meta=make('div','evidence-meta');meta.append(make('span','evidence-access',accessLabel(e)),make('span','',e.source+' · '+sourceDate(e)));n.append(meta);
    if(e.articleExcerpt){const d=make('details','evidence-summary');d.dataset.evidence=e.id;d.open=opened.has(e.id);
      d.append(make('summary','','공개 기사 내용 읽기'),make('p','publisher-summary',e.articleExcerpt),make('p','small','본문 수집 '+time(e.articleFetchedAt)+' · '+(e.articleTruncated?'일부 발췌 · 생략된 내용 있음':'공개 본문에서 발췌')+' · 원문과 다른 출처의 확인도 함께 필요해요.'));n.append(d);}
    if(e.publisherSummary){
      const d=make('details','evidence-summary');d.dataset.evidence=e.id;d.open=opened.has(e.id);
      d.append(make('summary','','발행처 요약 읽기'),make('p','publisher-summary',e.publisherSummary),make('p','small',(e.summaryTruncated?'제공된 요약 일부 · 생략된 내용 있음':'발행처가 RSS에 제공한 요약')+' · 기사 전체 본문을 읽은 결과가 아니에요.'));
      n.append(d);
    }else if(e.accessLevel==='TITLE_ONLY')n.append(make('p','small','제목과 링크만 있어요. 기사 내용이나 기업 영향을 확인한 근거로 쓰지 않아요.'));
    n.append(make('p','small',e.relevance==='MARKET_CONTEXT_ONLY'?'시장 맥락 · 이 기업에 미치는 영향은 별도 확인':e.relevance==='ISSUANCE_TITLE'?'상품·자금 발행 자료 · 실적 호재 여부는 별도 확인':'기업과 연결된 자료'),make('p','small','수집 '+time(e.receivedAt)));return n;
  }
  function renderEvidence(data){
    const sources=data.entry.report.evidence||[],signature=JSON.stringify([data.entry.id,sources]);
    if(signature===evidenceSignature)return;evidenceSignature=signature;
    const box=el('committee-evidence'),sameBrief=box.dataset.brief===String(data.entry.id),opened=new Set(sameBrief?[...box.querySelectorAll('details[open]')].map(d=>d.dataset.evidence):[]);
    box.dataset.brief=String(data.entry.id);box.replaceChildren(...sources.map(e=>evidenceSource(e,opened)));
    if(!sources.length)box.append(make('p','small','연결된 원문 자료가 아직 없어요.'));
  }
  function renderConversation(data){
    const c=data.conversation,entry=data.entry;
    if(!c||!entry)return;
    const signature=JSON.stringify([entry.report.code,entry.report.agenda,entry.id,chatMode,chatRole,c]);
    if(signature===chatSignature)return;
    const thread=el('chat-thread'),nearBottom=thread.scrollHeight-thread.scrollTop-thread.clientHeight<45,oldTop=thread.scrollTop;
    const opened=new Set([...thread.querySelectorAll('details[open]')].map(x=>x.dataset.message));
    const newTopic=thread.dataset.topic!==entry.report.code+':'+entry.report.agenda||thread.dataset.filter!==chatMode+':'+chatRole;
    thread.dataset.filter=chatMode+':'+chatRole;
    thread.dataset.topic=entry.report.code+':'+entry.report.agenda;chatSignature=signature;
    el('chat-status').textContent=c.messages.length?'실제 의견 '+c.messages.length+'개 · '+aiState(data.aiStatus?.state)+' · 기록 #'+entry.id:'아직 실제 AI 발언이 없어요 · '+aiState(data.aiStatus?.state);
    const filters=el('chat-role-filter'),filterLeft=filters.scrollLeft,focusedRole=filters.contains(document.activeElement)?document.activeElement.dataset.role:null;filters.replaceChildren();
    for(const key of ['ALL',...data.roles.map(x=>x.key)]){const b=make('button','',key==='ALL'?'전체':shortRole[key]||key);b.type='button';b.dataset.role=key;b.setAttribute('aria-pressed',String(chatRole===key));b.addEventListener('click',()=>{chatRole=key;renderConversation(view);});filters.append(b);}
    if(focusedRole)[...filters.children].find(b=>b.dataset.role===focusedRole)?.focus({preventScroll:true});filters.scrollLeft=filterLeft;
    document.querySelectorAll('[data-chat-mode]').forEach(b=>b.setAttribute('aria-pressed',String(chatMode===b.dataset.chatMode)));
    thread.replaceChildren();
    const messages=c.messages.filter(m=>(chatMode==='ALL'||m.phase===chatMode)&&(chatRole==='ALL'||m.roleKey===chatRole));
    if(!messages.length){const n=make('div','chat-empty');n.append(make('span','chat-empty-icon','···'),make('strong','',c.messages.length?'선택한 범위의 발언이 아직 없어요':'봇들이 대화할 자리예요'),make('p','',c.messages.length?'전체 대화나 다른 분야를 선택해보세요.':data.aiStatus?.configured?'실제 AI 검토가 시작되면 독립 의견부터 차례로 보여줘요. 위에서 검토 상태를 확인할 수 있어요.':'로컬 API 키와 월 한도를 설정하면 실제 자료로 AI 검토가 시작돼요.'));thread.append(n);}
    for(const m of messages){
      const row=make('article','chat-message role-'+m.roleKey.toLowerCase()+(m.phase==='FINAL'?' chat-final':''));
      const avatar=make('span','chat-avatar',shortRole[m.roleKey]?.slice(0,1)||'봇');avatar.setAttribute('aria-hidden','true');
      const content=make('div','chat-content'),meta=make('div','chat-meta');meta.append(make('strong','',m.roleName),make('span','',m.phase==='FINAL'?'최종 의견':m.phase==='DEBATE'?'질문 답변·반론':'독립 의견·질문'),make('span','chat-vote vote-'+m.vote.toLowerCase(),voteLabel(m.vote)));
      const bubble=make('div','chat-bubble'),d=m.discussion;
      if(d?.replyTo){const question=c.messages.find(x=>x.phase==='INDEPENDENT'&&x.roleKey===d.replyTo)?.discussion?.question;
        if(question)bubble.append(make('blockquote','chat-question',(shortRole[d.replyTo]||d.replyTo)+'의 질문 · '+question));}
      bubble.append(make('p','chat-brief',m.phase==='DEBATE'&&d?.reply?d.reply:m.summary));
      if(d?.question)bubble.append(make('p','chat-question','→ '+(shortRole[d.questionTo]||d.questionTo)+'에게 · '+d.question));
      const more=make('details','chat-message-details');more.dataset.message=String(m.id);more.open=opened.has(String(m.id));more.append(make('summary','','설명·근거 펼치기'),make('p','chat-full-summary',m.summary));
      for(const [label,items] of [['확인한 사실',m.facts],['해석',m.inferences],['반대 근거·위험',m.risks]])if(items.length){more.append(make('h4','',label));const ul=make('ul','reason-list');items.forEach(x=>ul.append(make('li','',x)));more.append(ul);}
      if(d?.counterpoint)more.append(make('h4','','강한 반론 · 수용/반박'),make('p','',d.counterpoint));
      if(d?.changeCondition)more.append(make('h4','','판단을 바꿀 조건'),make('p','',d.changeCondition));
      if(m.changedReason)more.append(make('h4','','반론·최종 판단 설명'),make('p','',m.changedReason));
      if(m.evidenceIds.length){more.append(make('h4','','인용한 자료'));for(const id of m.evidenceIds){const source=entry.report.evidence.find(x=>x.id===id);if(source)more.append(link(source.url,source.title));}}
      bubble.append(more);content.append(meta,bubble,make('span','chat-time',time(m.at)));row.append(avatar,content);thread.append(row);
    }
    const finals=el('chat-conclusions'),openedFinal=new Set([...finals.querySelectorAll('details[open]')].map(x=>x.dataset.role));finals.replaceChildren();
    for(const f of c.conclusions){const n=make('article','chat-conclusion');n.append(make('strong','',shortRole[f.roleKey]||f.roleName),make('span','chat-vote'+(f.vote?' vote-'+f.vote.toLowerCase():''),voteLabel(f.vote)));
      if(f.summary){n.append(make('p','conclusion-brief',f.summary));const d=make('details','');d.dataset.role=f.roleKey;d.open=openedFinal.has(f.roleKey);d.append(make('summary','','전체 결론'),make('p','',f.summary));n.append(d);}else n.append(make('p','conclusion-brief','최종 의견 대기'));finals.append(n);}
    const tally=c.tally;el('chat-vote-summary').textContent=tally.complete?'찬성 '+tally.approve+' · 반대 '+tally.reject+' · 기권 '+tally.abstain:'최종 의견 '+tally.received+'/8';
    el('chat-warning').hidden=!c.issues.length;el('chat-warning').textContent=c.issues.join(' ');
    if(newTopic||nearBottom)thread.scrollTop=thread.scrollHeight;else thread.scrollTop=oldTop;
  }
  function renderQuantitative(data){
    const box=el('quantitative-facts');box.replaceChildren();
    if(!data.quantitativeFacts?.length){box.append(make('p','small','이 기록의 비교 가능한 연간 재무 입력을 확인 중이에요. 과거 분석은 당시 자료를 그대로 표시해요.'));return;}
    for(const f of data.quantitativeFacts){const tile=make('article','quant-fact');tile.append(make('h3','',f.label),make('strong','quant-number',f.value==null?'미계산':f.unit==='KRW'?money(f.value):new Intl.NumberFormat('ko-KR',{maximumFractionDigits:2}).format(Number(f.value))+f.unit),make('p','small',f.period+' · '+f.fs));const more=make('details','');more.append(make('summary','','계산·출처'),make('p','',f.formula),make('p','small',f.limitation));for(const id of f.evidenceIds){const e=data.entry.report.evidence.find(x=>x.id===id);if(e)more.append(link(e.url,e.title));}tile.append(more);box.append(tile);}
  }
  function renderCoverage(data){
    const r=data.entry.report,box=el('evidence-coverage'),passages=el('business-passages');
    const dossiers=data.researchDossiers||[],signature=JSON.stringify([data.entry.id,data.coverage,dossiers,r.businessEvidence,r.evidence]);
    if(signature===coverageSignature)return;coverageSignature=signature;
    const sameBrief=box.dataset.brief===String(data.entry.id),opened=new Set(sameBrief?[...box.querySelectorAll('details[open]')].map(x=>x.dataset.role):[]);box.dataset.brief=String(data.entry.id);box.replaceChildren();
    const old=!r.businessEvidence,dossierByRole=new Map(dossiers.map(d=>[d.roleKey,d])),sources=new Map((r.evidence||[]).map(e=>[e.id,e]));
    box.classList.toggle('with-research-dossiers',dossiers.length>0);
    el('coverage-note').textContent=dossiers.length?'분야별로 관측값·출처·상대에게 물을 질문을 묶었어요. 아래는 회의에 전달하는 준비 자료이고, 실제 발언과 결론은 대화방에서 확인해요.':old?'이 회의는 본문 전달을 보완하기 전의 기록이에요. 아래는 당시 입력 범위이며 새 기업 선택에서 최신 자료를 볼 수 있어요.':'이 기록은 분야별 조사 질문을 보강하기 전의 자료예요. 당시 입력과 실제 대화를 보존했어요. 새 기업의 최신 자료에서 보강된 준비 내용을 볼 수 있어요.';
    const summary=el('research-material-summary');summary.replaceChildren();
    if(dossiers.length){const findings=dossiers.flatMap(d=>d.findings||[]),referenced=new Set(findings.flatMap(f=>f.evidenceIds||[]));
      for(const text of ['분야 '+dossiers.length+'개','연결 출처 '+referenced.size+'개','토론 준비 질문 '+dossiers.reduce((n,d)=>n+(d.researchQuestions||[]).length,0)+'개'])summary.append(make('span','research-material-count',text));}
    summary.hidden=!dossiers.length;
    for(const c of data.coverage||[]){const card=make('article','coverage-card');card.dataset.state=c.state;
      const dossier=dossierByRole.get(c.key),head=make('div','coverage-card-heading');head.append(make('h3','',c.name),make('span','coverage-status',({DATA_AVAILABLE:'수치 자료 있음',PARTIAL:'일부 근거 있음',NO_DIRECT_EVIDENCE:'직접 근거 부족'})[c.state]||'확인 중'));card.append(head);
      if(dossier?.findings?.length){const first=dossier.findings[0];card.append(make('strong','research-finding-label',first.label),make('p','research-preview',first.observation));}
      else card.append(make('p','research-preview',c.task));
      const d=make('details','research-role-detail');d.dataset.role=c.key;d.open=opened.has(c.key);d.append(make('summary','',dossier?'관측값·출처·토론 질문 보기':'평가 항목·수집 범위 보기'),make('p','small',c.task));
      if(dossier){
        for(const f of dossier.findings||[]){const finding=make('section','research-finding');finding.append(make('h4','',f.label),make('p','',f.observation));
          if(f.caveat)finding.append(make('p','research-caveat',f.caveat));
          const refs=[...new Set(f.evidenceIds||[])].map(id=>sources.get(id)).filter(Boolean);
          if(refs.length){const refsBox=make('div','research-source-links');for(const e of refs)refsBox.append(link(e.url,e.title),make('span','',accessLabel(e)+' · '+sourceDate(e)));finding.append(refsBox);}
          d.append(finding);
        }
        if(dossier.researchQuestions?.length){d.append(make('h4','','회의에서 반문할 질문'));const questions=make('ul','research-questions');for(const q of dossier.researchQuestions){const li=make('li','');li.append(make('span','research-question-role',(shortRole[q.toRole]||q.toRole)+'에게'),make('p','',q.question));questions.append(li);}d.append(questions);}
      }
      for(const [title,items] of [['전달한 자료',c.available],['해석할 때 확인할 점',dossier?.limitations],['수집 범위·남은 공백',c.gaps]]){if(!items?.length)continue;d.append(make('h4','',title));const ul=make('ul','reason-list');for(const text of items)ul.append(make('li','',text));d.append(ul);}
      card.append(d);box.append(card);
    }
    passages.replaceChildren();
    for(const p of r.businessEvidence?.passages||[]){const n=make('article','business-passage');n.append(make('h3','',p.period+' · '+p.title),make('p','',p.text),make('p','small',p.truncated?'일부 발췌 · 생략된 내용 있음':'해당 구간 발췌 · 문서 전체 검토 아님'),link(p.sourceUrl,'공식 공시 원문 ↗'));passages.append(n);}
    for(const gap of r.businessEvidence?.gaps||[])passages.append(make('p','small',gap));
    if(!r.businessEvidence?.passages?.length)passages.append(make('p','small','이 기록에는 공식 본문 발췌가 없어요. 기업에 해당 사업이나 현금 창출이 없다는 의미는 아니에요.'));
  }
  function selectCompany(nextCode,nextAgenda='BUY_REVIEW'){
    if(posting)return;code=nextCode;action=nextAgenda;selected='';quantityOverride='';lastSuccess=0;controls();
    el('review-note').value='';el('review-feedback').textContent='';el('ai-feedback').textContent='';
    const index=(view?.candidates||[]).findIndex(x=>x.code===code);if(index>=0)candidatePage=Math.floor(index/3);
    history.replaceState(null,'','/committee?'+new URLSearchParams({code,agenda:action}));if(view)renderCandidates(view);refresh();
  }
  function renderCandidates(data){
    const candidates=data.candidates||[],pages=Math.max(1,Math.ceil(candidates.length/3));candidatePage=Math.min(candidatePage,pages-1);
    const active=code||data.entry?.report?.code,signature=JSON.stringify([candidates,candidatePage,active]);if(signature===candidateSignature)return;candidateSignature=signature;
    el('candidate-page').textContent=(candidatePage+1)+' / '+pages;el('candidate-prev').disabled=candidatePage===0;el('candidate-next').disabled=candidatePage>=pages-1;
    el('candidate-note').textContent=candidates.length?'총 '+candidates.length+'개 기업 · 진행 중인 회의와 재무 준비 후보부터 표시 · 자료 기준 '+time(data.checkedAt):'최근 저장된 기업 자료를 수집 중이에요.';
    const grid=el('candidate-grid');grid.replaceChildren();
    if(!candidates.length)grid.append(make('p','small','후보가 수집되면 최대 3개씩 표시해요.'));
    for(const c of candidates.slice(candidatePage*3,candidatePage*3+3)){
      const card=make('article','candidate-card');card.dataset.selected=String(c.code===active);
      const head=make('div','candidate-head');head.append(make('h3','',c.name),make('span','candidate-status',aiState(c.aiState)));card.append(head);
      card.append(make('p','small',c.code+' · 확정 종가 '+money(c.referenceClose)+' · '+(c.priceDate||'기준일 미확인')));
      const ratios=make('div','candidate-ratios');const multiple=x=>x==null?'미계산':new Intl.NumberFormat('ko-KR',{maximumFractionDigits:2}).format(Number(x))+'배';
      for(const [label,value] of [['참고 PER',c.per],['참고 PBR',c.pbr],['참고 PSR',c.psr]]){const cell=make('div','');cell.append(make('span','',label),make('strong','',multiple(value)));ratios.append(cell);}card.append(ratios);
      card.append(make('p','candidate-basis',c.reason));const detail=make('details','candidate-risk');detail.append(make('summary','','확인할 위험'),make('p','',c.caution));card.append(detail);
      card.append(make('p','small',(c.annualReady?'연간 재무 준비':'연간 재무 수집 중')+' · '+(c.researchCurrent?'연구 작성 당시 최신':'연구 갱신 대기')+(c.savedOpinions?' · 실제 발언 '+c.savedOpinions+'/'+c.expectedOpinions:'')));
      const choose=make('button','candidate-select',c.code===active?'선택한 기업 · 상세 보기':'이 기업 선택');choose.type='button';choose.dataset.candidateCode=c.code;choose.setAttribute('aria-pressed',String(c.code===active));choose.disabled=posting;
      choose.addEventListener('click',()=>selectCompany(c.code));card.append(choose);grid.append(card);
    }
  }
  function render(data){
    view=data;lastSuccess=Date.now();if(!libraryMode)renderCandidates(data);
    const ai=data.aiStatus,b=ai?.budget;
    el('ai-mode').textContent=!ai?.configured?'로컬 API 키·월 한도 등록 대기':ai.issue?'AI 진행 확인 필요':ai.responseVerified?'직접 요청 모드 · 자동 검토 꺼짐':'직접 요청 모드 · 첫 응답 대기';
    el('model-spend').textContent='월 사용량 비용 약 '+fmt.format(cents(b?.settledUsd))+'센트';
    const budgetMoney=x=>x==null?'확인 대기':Number(x)===0?'0센트':Number(x)*100<1?'1센트 미만':fmt.format(cents(x))+'센트';
    el('cost-summary-spent').textContent=budgetMoney(b?.settledUsd);el('cost-summary-left').textContent=budgetMoney(b?.remainingUsd);el('cost-summary-reserved').textContent=b?budgetMoney(Math.max(0,Number(b.committedUsd)-Number(b.settledUsd))):'확인 대기';
    el('cost-summary-note').textContent=b?b.month.slice(0,7)+' UTC 월 기준 · 앱 한도 $'+fmt.format(Number(b.ceilingUsd))+' · 보관된 미확정 비용도 잔여 예산에서 차감해요. OpenAI 청구액·잔액은 별도예요.':'월 예산 조회 대기';
    renderAiProgress(ai);renderReviewControls(data);
    const financial=data.entry?.report?.financialEvidence;
    const cashYears=(financial?.periods||[]).filter(p=>p.report==='11011'&&p.values?.OPERATING_CASH_FLOW!=null).length;
    el('ai-data-status').textContent=financial?(financial.annualEvidenceReady?'공식 연간 재무 연결 · 영업현금흐름 '+cashYears+'개 사업연도 확인 · 미래 현금은 별도 가정으로 검토해요.':'이 후보의 공식 연간 재무 수집 대기 · 데이터가 준비되기 전에는 AI 호출 비용을 쓰지 않아요.'):'이 과거 기록의 재무 전달 형식은 이전 버전이에요. 기업 선택에서 최신 자료를 확인해 주세요.';
    el('ai-progress').textContent=aiState(ai?.state)+' · 독립 의견 '+(ai?.independentCount||0)+'/8'+(ai?.totalSteps===25?' · 질문·답변 '+(ai?.debateCount||0)+'/8':'')+' · 최종 의견 '+(ai?.finalCount||0)+'/8';
    el('ai-budget').textContent='사용·예약 약 '+fmt.format(cents(b?.committedUsd))+' / 1,000센트 ($10) · 오늘 회의 '+(b?.todayMeetings||0)+'/'+(b?.dailyLimit||10)+' · 확인 중 호출 '+(b?.unsettledCalls||0)+'개 · 보관 예약 '+budgetMoney(b?.archivedReservedUsd);
    el('ai-issue').hidden=!ai?.issue;el('ai-issue').textContent=ai?.issue||'';
    el('ai-plan').textContent=ai?.modelPlan||'';el('ai-budget-note').textContent=b?.explanation||'';
    renderNewReviewConsent(ai);
    el('ai-analyze').textContent=ai?.requestBlocked?'미확정 호출 확인 대기':ai?.state==='NOT_QUEUED'&&b?.todayMeetings>=b?.dailyLimit?'오늘 회의 한도 도달 · 다음 날 검토':Number(b?.remainingUsd)<=0?'이번 달 호출 예산 부족':ai?.newReviewConsent?.required&&ai.newReviewConsent.canConfirm?'미확정 비용 확인하고 새 검토':['FAILED','BUDGET_WAIT'].includes(ai?.state)?'AI 검토 재요청 · 추가 비용 가능':'이 기업 AI 검토 시작';
    el('active-meeting-link').hidden=!ai?.latestMeetingBriefId||ai.latestMeetingBriefId===data.entry?.id;
    if(ai?.latestMeetingBriefId)el('active-meeting-link').href='/committee/reviews?id='+encodeURIComponent(ai.latestMeetingBriefId);
    el('checked-at').textContent='조회 '+time(data.checkedAt)+' · 새 상태를 15초마다 확인해요';
    el('order-mode').textContent='AI는 분석만 · 매매는 내가 직접';
    const r=data.entry?.report,company=el('committee-company');company.replaceChildren();
    for(const card of data.cards){const o=make('option','',card.name+' · '+agenda(card.agenda));o.value=card.code+':'+card.agenda;company.append(o);}
    if(r)company.value=r.code+':'+r.agenda;
    if(!data.cards.length)company.append(make('option','','실제 자료 수집 대기'));
    const history=el('committee-history');history.replaceChildren();const latest=make('option','','최신 자료');latest.value='';history.append(latest);
    for(const h of data.history){const o=make('option','','#'+h.id+' · '+time(h.generatedAt));o.value=String(h.id);history.append(o);}
    if(selected&&!data.history.some(h=>String(h.id)===selected)){const o=make('option','','지정 기록 #'+selected);o.value=selected;history.append(o);}history.value=selected;
    el('committee-empty').hidden=!!r;el('committee-content').hidden=!r;
    if(r){
      const tally=data.conversation?.tally,summary=ai?.summary;
      el('debate-synthesis').hidden=!summary?.assessment;
      el('synthesis-assessment').textContent=({CONDITIONAL_INTEREST:'조건부 관심',UNATTRACTIVE:'가격·사업 매력 부족',DATA_GAP:'핵심 자료 부족',MIXED:'찬반 혼재'})[summary?.assessment]||'';
      el('synthesis-bull').textContent=summary?.bullCase||'';el('synthesis-bear').textContent=summary?.bearCase||'';el('synthesis-condition').textContent=summary?.reconsiderWhen||'';
      el('brief-title').textContent=r.name+' · '+agenda(r.agenda);el('brief-summary').textContent=ai?.summary?.summary||(tally?.complete?(tally.thresholdMet?'투자 논거에 6명 이상 찬성했어요. 반대 근거도 함께 확인하세요.':'찬반이 모이지 않았어요. 부족한 근거와 재판단 조건을 확인하세요.'):'자료를 모았어요 · AI 토론과 최종 판단을 기다리고 있어요.');
      el('brief-state').textContent=aiState(ai?.state);el('brief-time').textContent='기록 #'+data.entry.id+' · 자료 고정 '+time(r.preparedAt)+(selected?' · 당시 기록':' · 동일 자료의 AI 검토는 재사용');
      list('brief-reasons',ai?.summary?.reasons||r.reasons,'검토 근거 수집 대기');list('brief-cautions',ai?.summary?[...ai.summary.cautions,...r.cautions]:r.cautions,'추가 검토 필요');
      const metrics=el('brief-metrics');metrics.replaceChildren();
      for(const [label,value] of [['확정 종가',money(r.referenceClose)],['가격 기준일',r.chartBasisDate||'미확인'],['분석 자료','공식 재무 + 공개 정보']]){const n=make('div','');n.append(make('span','',label),make('strong','',value));metrics.append(n);}
      el('freshness-note').textContent='차트 기준일 '+(r.chartBasisDate||'미확인')+' / 조회 '+time(r.chartReceivedAt)+' · 연구 '+(r.researchCurrent?'작성 당시 최신':'작성 당시 갱신 대기')+' · 재무 '+(r.financialCurrent?'작성 당시 최신':'작성 당시 확인 대기')+' · 상태는 작성 시점 기준이에요. '+(Date.parse(data.checkedAt)-Date.parse(r.preparedAt)>1200000?'20분 넘은 기록이므로 새 자료 확인이 필요해요. ':'')+'기준일이 다른 자료를 현재 값으로 혼동하지 마세요.';
      el('vote-result').textContent=tally?.complete?'찬성 '+tally.approve+'/8 · '+Number(tally.percent).toFixed(0)+'%': '최종 의견 '+(tally?.received||0)+'/8 · 투표 대기';
      renderQuantitative(data);
      renderCoverage(data);
      renderConversation(data);
      const multiplesBox=el('multiples-review'),mp=r.multiples;multiplesBox.replaceChildren();
      if(mp){multiplesBox.append(make('p','small',(mp.year||'미확인')+'년 · '+(mp.fs||'미확인')+' · 가격 기준 '+(mp.priceDate||'미확인')+' · '+mp.basis));
        const grid=make('div','brief-metrics');
        const ratio=x=>x==null?'계산 보류':new Intl.NumberFormat('ko-KR',{maximumFractionDigits:2}).format(Number(x))+'배';
        for(const [label,value] of [['참고 PER',ratio(mp.per)],['현재 가격 / 3년 평균이익',ratio(mp.threeYearAveragePer)],['참고 PBR',ratio(mp.pbr)],['참고 PSR',ratio(mp.psr)],['이익수익률',percent(mp.earningsYieldPercent)],['연간 매출 성장',percent(mp.revenueGrowthPercent)],['영업이익률',percent(mp.operatingMarginPercent)]]){
          const tile=make('div','');tile.append(make('span','',label),make('strong','',value));grid.append(tile);}
        multiplesBox.append(grid);const detail=make('details','');detail.append(make('summary','','계산 기준·출처·한계'));
        detail.append(make('p','small','보통주 '+(mp.commonShares==null?'미확인':fmt.format(Number(mp.commonShares))+'주')+' · 공시 기준 '+(mp.sharesAsOf||'미확인')));
        const ul=make('ul','reason-list');for(const note of mp.limitations||[])ul.append(make('li','',note));detail.append(ul);
        for(const id of mp.evidenceIds||[]){const source=r.evidence.find(x=>x.id===id);if(source?.url)detail.append(link(source.url,source.title));}multiplesBox.append(detail);
      }else multiplesBox.append(make('p','small','이 과거 기록에는 배수 비교가 없어요. 최신 자료에서 확인해 주세요.'));
      const valueBox=el('value-review'),v=r.valueReview;valueBox.replaceChildren();
      if(v){valueBox.append(make('p','small',v.horizon+' · 가격 기준 '+(v.referenceDate||'미확인')+' · 확정 종가 '+money(v.referenceClose)));
        for(const [title,text] of [['사업',v.businessFacts],['성장·경쟁력 가설',v.potentialStrength],['반대 근거',v.counterEvidence]])if(text)valueBox.append(make('h3','',title),make('p','scope-note',text));
        valueBox.append(make('p','small','가치 자료 '+v.valuationState+' · 금융업 별도 검토 '+(v.financialSectorReview?'필요':'해당 없음')));
        const row=make('div','brief-metrics');for(const [title,x] of [['보수 시나리오',v.conservativePerShare],['기준 시나리오',v.basePerShare],['낙관 시나리오',v.optimisticPerShare]])row.append(make('span','',title+' '+(x==null?'계산 보류':money(x))));valueBox.append(row);
        if(v.discountToConservativePercent!=null)valueBox.append(make('p','small','보수 가정 대비 가격 할인 '+percent(v.discountToConservativePercent)+' · 상승률 아님'));
        for(const [title,items] of [['가정과 한계',v.assumptions],['추가로 필요한 근거',v.missing],['사업 논거 무효화 조건',v.invalidation]]){valueBox.append(make('h3','',title));const ul=make('ul','reason-list');for(const text of items||[])ul.append(make('li','',text));valueBox.append(ul);}
      }else valueBox.append(make('p','small','이 과거 기록은 가치 검토 자료를 추가하기 전의 원본이에요. 최신 자료를 선택하면 새 기준을 볼 수 있어요.'));
      const opened=new Set([...el('committee-roles').querySelectorAll('details')].filter(x=>x.open).map(x=>x.dataset.role));
      el('committee-roles').replaceChildren();
      for(const role of r.roles){const card=make('article','role-card'),head=make('div','role-header'),title=make('div','');title.append(make('h3','',role.name),make('p','',role.focus));head.append(make('span','avatar',role.name.slice(0,1)),title);card.append(head,make('span','role-status','원본 준비 자료 · 실제 AI 의견은 대화방에서 확인'));
        if(role.observations.length){const ul=make('ul','observations');for(const fact of role.observations.slice(0,3))ul.append(make('li','',fact));card.append(ul);}else card.append(make('p','small','이 분야의 확인된 분석 자료가 아직 없어요.'));
        if(role.observations.length>3){const more=make('details','');more.dataset.role=role.key+'-facts';more.open=opened.has(more.dataset.role);more.append(make('summary','','전체 준비 수치 보기'));const values=make('ul','reason-list');for(const fact of role.observations.slice(3))values.append(make('li','',fact));more.append(values);card.append(more);}
        const d=make('details','');d.dataset.role=role.key;d.open=opened.has(role.key);d.append(make('summary','','추가로 필요한 근거'));const ul=make('ul','reason-list');for(const x of role.missing)ul.append(make('li','',x));d.append(ul);card.append(d);el('committee-roles').append(card);
      }
      el('approval-note').textContent='관심·보류·제외는 연구 메모예요. 자동매매는 하지 않아요. 직접 매도는 보유 화면에서 확인하세요.';
      el('review-history').replaceChildren();for(const x of data.reviews){const n=make('div','review-record');n.append(make('strong','',(labels[x.decision]||x.decision)+' · '+time(x.at)));if(x.note)n.append(make('p','',x.note));el('review-history').append(n);}
      el('download-summary').href='/committee/'+data.entry.id+'.md';
      el('committee-steps').replaceChildren();for(const step of data.steps){const n=make('li','',step.label);n.append(make('span','',step.state));el('committee-steps').append(n);}
      el('committee-events').replaceChildren();for(const x of data.events){const n=make('div','event');n.append(make('p','',x.detail),make('p','small',time(x.at)));el('committee-events').append(n);}
      const preview=['📋 '+r.name+' · '+agenda(r.agenda),aiState(ai?.state)+' / 주문 없음',ai?.summary?.summary||'실제 AI 요약 대기','','검토 이유',...(ai?.summary?.reasons||r.reasons).slice(0,3).map(x=>'• '+x),'','반대 근거',...(ai?.summary?.cautions||r.cautions).slice(0,3).map(x=>'• '+x),'','8명 상태',...data.conversation.conclusions.map(x=>'• '+x.roleName+': '+voteLabel(x.vote)),'','상세: 투자위원회 기록 #'+data.entry.id];
      el('discord-preview').textContent=preview.join('\n');
      el('foreign-news-status').textContent=data.foreignNewsStatus+' · 날짜만 있는 공시는 접수 시각을 추정하지 않아요. 시장 맥락 자료는 이 기업의 수혜 근거로 확인한 것이 아니에요.';
      el('financial-metrics').replaceChildren();if(!r.metrics.length)el('financial-metrics').append(make('p','small','이 기록에는 재무 수치가 전달되지 않았어요. 기업에 현금흐름이 없다는 뜻은 아니에요. 최신 수집 자료를 확인해 주세요.'));for(const m of r.metrics){const n=make('div','financial-row');n.append(make('span','',m.period+' · '+m.label),make('strong','',['AVAILABLE','READY'].includes(m.state)&&m.value!=null?(m.currency==='KRW'?money(m.value):fmt.format(Number(m.value))+' '+m.currency):'자료 확인 대기'));el('financial-metrics').append(n);}
      renderEvidence(data);
    }
    el('cost-options').replaceChildren();for(const o of data.costs.options){const n=make('article','cost-option'+(o.recommended?' recommended':''));n.append(make('h3','',o.model+(o.recommended?' · 선택안':'')),make('strong','','회의당 약 '+fmt.format(rounded(Number(o.meetingUsd)*100))+'센트'),make('p','','신규 $10 예산이면 약 '+Math.floor(10/Number(o.meetingUsd))+'회 · 토큰 가정 기준'),make('p','','252회 가정 시 약 $'+fmt.format(rounded(o.plannedMonthUsd))+' · 월 한도로 보장되는 횟수 아님'),link(o.source,'공식 단가'));if(o.recommended)n.append(link('https://developers.openai.com/api/docs/models/gpt-5.4-mini','mini 공식 단가'));el('cost-options').append(n);}
    el('cost-assumptions').textContent=data.costs.explanation+' 비용 계산 기준 '+data.costs.asOf+' · 요약 mini 공식 단가는 설계 문서에 있어요.';
    el('api-scope-note').textContent='선택 기록의 실제 AI 의견 '+(data.conversation?.messages.length||0)+'개 · 최종 표 '+(data.conversation?.tally.received||0)+'/8 · 공식 공시·공개 기사 발췌·발행처 요약을 범위 내 사용 · 해외 기사 전체 본문은 미연결 · 기업 연구 전용. 사용량 비용은 설정 단가 추정이며 공급자 청구서 대조는 별도예요.';
    warning(data.issue||'');controls();document.dispatchEvent(new CustomEvent('committee-review-rendered',{detail:{id:data.entry?.id,state:data.aiStatus?.state}}));
  }
  async function refresh(){if(loading||document.hidden||posting||(libraryMode&&!selected))return;loading=true;const key=selected+':'+code+':'+action+':'+quantityOverride;const q=new URLSearchParams();if(selected)q.set('id',selected);else if(code){q.set('code',code);q.set('agenda',action);}if(quantityOverride)q.set('quantity',quantityOverride);const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const response=await fetch('/api/committee?'+q,{credentials:'same-origin',cache:'no-store',signal:controller.signal});if(response.status===401){lastSuccess=0;warning('로그인이 필요해요. 로그인 후 새로고침해 주세요.');return;}if(!response.ok)throw new Error();const data=await response.json();if(key===selected+':'+code+':'+action+':'+quantityOverride)render(data);}
    catch(_){lastSuccess=0;warning('새 자료를 불러오지 못했어요. 로그인과 연결을 확인하세요. 마지막 기록을 최신 자료로 판단하지 마세요.');}finally{clearTimeout(timer);loading=false;nextRefreshAt=Date.now()+(['QUEUED','RUNNING','FINALIZING'].includes(view?.aiStatus?.state)?5000:15000);if(key!==selected+':'+code+':'+action+':'+quantityOverride)refresh();}
  }
  async function review(actionName){if(!isFresh()||posting||!view?.entry)return;const id=view.entry.id,note=el('review-note').value,signature=id+':'+actionName+':'+note;if(signature!==requestSignature){requestKey=crypto.randomUUID();requestSignature=signature;}posting=true;controls();
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const headers={'Content-Type':'application/json'};headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-value"]').content;
      const response=await fetch('/api/committee/'+id+'/review',{method:'POST',credentials:'same-origin',headers,body:JSON.stringify({action:actionName,note,clientKey:requestKey}),signal:controller.signal});if(!response.ok)throw new Error();const data=await response.json();el('review-feedback').textContent=data.message;requestSignature='';el('review-note').value='';}
    catch(_){const message='저장 결과를 확인하지 못했어요. 같은 내용으로 다시 요청하면 중복 기록을 막아요. 로그인·화면 갱신 상태도 확인해 주세요.';el('review-feedback').textContent=message;}finally{clearTimeout(timer);posting=false;refresh();controls();}
  }
  function renderNewReviewConsent(ai){
    const v=ai?.newReviewConsent,box=el('new-review-consent');box.hidden=!v?.required;if(!v?.required)return;
    const usd=x=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',minimumFractionDigits:4,maximumFractionDigits:4}).format(Number(x||0));
    el('new-review-consent-note').textContent='미확정 '+v.calls.length+'건 · 예약 '+usd(v.reservedUsd)+' 보존. '+(v.canConfirm?'아래 버튼을 누른 뒤 기존 비용과 새 검토의 추가 비용을 확인할 수 있어요.':v.reason||'호출 상태 확인이 필요해요.');
    el('new-review-consent-calls').replaceChildren(...v.calls.map(c=>{const n=make('li','');n.append(link(new URL('/committee/reviews?tab=closed&id='+encodeURIComponent(c.briefId),location.origin).href,c.company+' · 종료한 검토 #'+c.briefId),make('span','',' · 예약 '+usd(c.reservedUsd)));return n;}));
  }
  async function analyze(){
    if(libraryMode||!isFresh()||posting||!view?.entry||!view?.aiStatus?.configured||view.aiStatus.requestBlocked)return;
    const id=view.entry.id,name=view.entry.report.name,consent=view.aiStatus.newReviewConsent;
    const extra=consent?.required?'\n\n종료한 검토 미확정 '+consent.calls.length+'건의 예약 $'+Number(consent.reservedUsd).toFixed(4)+'를 유지하고 새 검토 비용을 추가하는 데 동의하나요? 이전 호출은 재전송하지 않아요.':'';
    if(!window.confirm(name+' AI 검토 1회를 시작할까요?\n전문가 의견·토론·요약 최대 25회 API 호출 비용이 발생해요. 월 $10 예산을 유지하며 주문은 실행하지 않아요.'+extra))return;
    if(!isFresh()||view?.entry?.id!==id){el('ai-feedback').textContent='화면이 갱신됐어요. 새 비용·자료 상태를 확인한 뒤 다시 눌러 주세요.';refresh();return;}
    posting=true;controls();const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
    try{const headers={'Content-Type':'application/json'};headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-value"]').content;
      const response=await fetch('/api/committee/'+id+'/analyze',{method:'POST',credentials:'same-origin',headers,body:JSON.stringify({confirmed:true,acknowledgeUnknownCosts:!!consent?.required,unknownCallIds:consent?.required?consent.calls.map(c=>c.callId):[]}),signal:controller.signal});
      const result=await response.json();
      if(!response.ok){el('ai-feedback').textContent=result.message||'요청이 거절됐어요. 화면의 검토 대기 이유를 확인해 주세요.';return;}
      el('ai-feedback').textContent=result.message;selected=String(result.briefId);quantityOverride='';history.replaceState(null,'',pagePath+'?id='+encodeURIComponent(selected));
    }catch(_){el('ai-feedback').textContent='서버 응답을 받지 못했어요. 자동 재요청하지 않아요. 새로고침해서 실제 예약 여부를 확인해 주세요.';}
    finally{clearTimeout(timer);posting=false;refresh();controls();}
  }
  document.addEventListener('committee-library-select',event=>{
    if(!libraryMode||posting)return;const id=event.detail?.id;
    if(id==null){selected='';view=null;lastSuccess=0;el('committee-content').hidden=true;el('committee-empty').hidden=true;return;}
    if(selected===String(id)&&view?.entry?.id===id)return;
    selected=String(id);code='';quantityOverride='';view=null;lastSuccess=0;el('committee-content').hidden=true;el('committee-empty').hidden=true;chatSignature='';controls();refresh();
  });
  el('candidate-prev').addEventListener('click',()=>{candidatePage=Math.max(0,candidatePage-1);if(view)renderCandidates(view);});
  el('candidate-next').addEventListener('click',()=>{candidatePage++;if(view)renderCandidates(view);});
  el('committee-company').addEventListener('change',()=>{const [nextCode,nextAgenda]=el('committee-company').value.split(':');selectCompany(nextCode,nextAgenda);});
  el('committee-history').addEventListener('change',()=>{selected=el('committee-history').value;quantityOverride='';lastSuccess=0;controls();if(view?.entry){code=view.entry.report.code;action=view.entry.report.agenda;}history.replaceState(null,'',pagePath+(selected?'?id='+encodeURIComponent(selected):''));refresh();});
  document.querySelectorAll('[data-review]').forEach(b=>b.addEventListener('click',()=>review(b.dataset.review)));
  document.querySelectorAll('[data-chat-mode]').forEach(b=>b.addEventListener('click',()=>{chatMode=b.dataset.chatMode;if(view)renderConversation(view);}));
  el('ai-analyze').addEventListener('click',analyze);
  document.querySelectorAll('[data-review-control]').forEach(b=>b.addEventListener('click',()=>controlReview(b.dataset.reviewControl)));
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});refresh();setInterval(()=>{if(Date.now()>=nextRefreshAt)refresh();},5000);setInterval(controls,3000);
})();
