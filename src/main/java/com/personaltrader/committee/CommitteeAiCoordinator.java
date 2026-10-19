package com.personaltrader.committee;

import com.fasterxml.jackson.databind.*;
import com.personaltrader.research.ResearchWorkspace;
import com.personaltrader.research.PublicInformationService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One persisted meeting at a time. Independent evidence, directed questions and answers, final ballots, one summary. No broker tools. */
@Service
public class CommitteeAiCoordinator {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    private final CommitteeAiGateway gateway;private final CommitteeAiLedger ledger;private final ResearchWorkspace workspace;
    private final CommitteeConversationService conversations;private final TransactionTemplate tx;
    private final AtomicBoolean advancing=new AtomicBoolean();private volatile Thread worker;private volatile boolean stopping;
    private volatile String runtimeIssue;
    private final CommitteeRequestPolicy requests;
    @org.springframework.beans.factory.annotation.Autowired private CommitteeReviewControlService controls;
    @org.springframework.beans.factory.annotation.Autowired private CommitteeWorkerState workState;
    @org.springframework.beans.factory.annotation.Autowired private CommitteeNewReviewConsent newReviewConsent;
    public CommitteeAiCoordinator(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,CommitteeAiGateway gateway,CommitteeAiLedger ledger,
        ResearchWorkspace workspace,CommitteeConversationService conversations,PlatformTransactionManager transactions,CommitteeRequestPolicy requests){
        this.requests=requests;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.gateway=gateway;this.ledger=ledger;this.workspace=workspace;this.conversations=conversations;tx=new TransactionTemplate(transactions);
    }
    @PostConstruct public void recoverStoppedReviews(){controls.recoverInterrupted();}
    @Scheduled(fixedDelay=15000,initialDelay=65000)
    public void tick(){
        if(stopping||!advancing.compareAndSet(false,true))return;
        worker=Thread.startVirtualThread(()->{try{ledger.expireAbandoned();if(!gateway.available())return;
            advance();
        }catch(Exception failure){runtimeIssue="AI 진행 상태 확인 필요 · 자동 재전송하지 않아요.";}finally{workState.clear();advancing.set(false);}});
    }
    @PreDestroy public void stop(){stopping=true;Thread active=worker;if(active!=null){active.interrupt();try{active.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    public InvestmentCommitteeService.Entry referenceFor(InvestmentCommitteeService.Entry entry){
        if(entry==null)return null;if(!InvestmentCommitteeService.VERSION.equals(entry.report().version()))return entry;String hash=packHash(entry);
        var ids=jdbc.queryForList("SELECT brief_id FROM committee_ai_job WHERE scope_id=? AND pack_hash=? AND prompt_version=? ORDER BY id DESC LIMIT 1",Long.class,workspace.id(),hash,CommitteeContract.PROMPT_VERSION);
        return ids.isEmpty()?entry:entry(ids.getFirst());
    }
    public Queued enqueue(long id){return enqueue(id,null);}
    public Queued enqueue(long id,CommitteeNewReviewConsent.Confirmation confirmation){
        return tx.execute(status->{jdbc.execute("SELECT pg_advisory_xact_lock(73491821)");
            // The selected brief's old-cost acknowledgement is separate from starting/retrying an old job.
            if(requests.unknownBlocked(id))newReviewConsent.authorize(id,confirmation);
            return enqueueConfirmed(id);
        });
    }
    private Queued enqueueConfirmed(long id){
        if(!gateway.available())throw new RequestRejected("NOT_CONFIGURED","먼저 로컬 API 키와 월 한도를 설정해 주세요.");
        if(ledger.view(workspace.id()).remainingUsd().signum()<=0)throw new RequestRejected("MONTHLY_BUDGET_LIMIT","이번 달 남은 AI 호출 예산이 없어요. 기존 비용과 예약액은 유지해요.");
        if(requests.unknownBlocked(id))throw new RequestRejected("UNKNOWN_CALLS",unknownIssue());
        var entry=entry(id);
        if(!InvestmentCommitteeService.VERSION.equals(entry.report().version()))throw new RequestRejected("STALE_EVIDENCE","이전 자료 전달 기준의 기록이에요. 이 기업의 최신 자료를 선택해 주세요.");
        if(!financialReady(entry))throw new RequestRejected("FINANCIAL_WAIT","이 후보의 연간 재무 수집 후 최신 기록을 선택해 주세요.");
        Job job=queue(entry);if(job==null)throw new RequestRejected("DAILY_LIMIT_OR_BUSY","하루 회의 수 또는 현재 회의 상태를 확인해 주세요.");
        if(Set.of("FAILED","BUDGET_WAIT","UNKNOWN","STALE","PAUSED","CLOSED").contains(job.state()))
            throw new RequestRejected("REVIEW_CONTROL_REQUIRED","검토 내역에서 이어서 진행·최신 자료로 처음부터·검토 종료 중 선택해 주세요.");
        if("COMPLETE".equals(job.state()))return new Queued(job.brief(),job.state(),"같은 자료의 완료된 검토를 열었어요. 새 API 호출 없이 재사용해요.");
        requests.record(job.id(),clock.instant());runtimeIssue=null;
        return new Queued(job.brief(),job.state(),"직접 요청한 AI 검토 1회를 예약했어요. 주문은 실행하지 않아요.");
    }
    private Job queue(InvestmentCommitteeService.Entry entry){
        if(entry.generatedAt().isAfter(clock.instant())||Duration.between(entry.generatedAt(),clock.instant()).compareTo(Duration.ofHours(24))>0)
            throw new RequestRejected("STALE_EVIDENCE","새 자료를 수집한 뒤 검토해 주세요.");
        String scope=workspace.id(),pack=pack(entry),hash=PublicInformationService.hash(pack);LocalDate day=clock.instant().atZone(CommitteeAiLedger.SEOUL).toLocalDate();
        if(pack.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new RequestRejected("EVIDENCE_TOO_LARGE","자료 크기 확인 필요");
        return tx.execute(status->{
            jdbc.execute("SELECT pg_advisory_xact_lock(73491821)");
            var reused=jobs("WHERE scope_id=? AND pack_hash=? AND prompt_version=? ORDER BY id DESC LIMIT 1",scope,hash,CommitteeContract.PROMPT_VERSION);
            if(!reused.isEmpty())return reused.getFirst();
            int today=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM committee_ai_job WHERE scope_id=? AND meeting_day=?",Integer.class,scope,day));
            boolean extra=ledger.extraReviewAuthorized(scope,entry.id(),day);
            if(today>=daily(day)+(extra?1:0))return null;
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_job WHERE state IN ('QUEUED','RUNNING','FINALIZING'))",Boolean.class)))return null;
            var ids=jdbc.queryForList("INSERT INTO committee_ai_job(scope_id,brief_id,pack_hash,prompt_version,pack,meeting_day,auto_slot,state,created_at,updated_at) VALUES (?,?,?,?,?::jsonb,?,?,'QUEUED',?,?) ON CONFLICT DO NOTHING RETURNING id",Long.class,scope,entry.id(),hash,CommitteeContract.PROMPT_VERSION,pack,day,null,utc(),utc());
            if(ids.isEmpty())return null;
            if(extra&&today>=daily(day))event(entry.id(),"USER_EXTRA_REVIEW","사용자 요청으로 지정 기업·당일 모의 검토 1회 추가 · 월 예산/주문 보류 유지");
            event(entry.id(),"AI_QUEUED","8명 실제 AI 검토 예약 · 주문 없음");return jobs("WHERE id=?",ids.getFirst()).getFirst();
        });
    }
    private void advance(){
        String scope=workspace.id();if(scope==null)return;
        var pending=jobs("WHERE scope_id=? AND state IN ('QUEUED','RUNNING','FINALIZING') ORDER BY id LIMIT 1",scope);if(pending.isEmpty())return;
        Job job=pending.getFirst();
        workState.begin(job.id());
        if(!requests.requested(job.id())){fail(job,"STALE","사용자 직접 요청 기록 없음 · 자동 호출 차단 · 최신 자료에서 직접 요청해 주세요.");return;}
        if(requests.unknownBlocked(job.brief())){runtimeIssue=unknownIssue();return;}
        if(!CommitteeContract.PROMPT_VERSION.equals(job.version())){fail(job,"STALE","이전 분석 기준의 회의 · 새 자료로 검토 필요");return;}var entry=entry(job.brief());
        if(Duration.between(controls.authorizedAt(job.id(),job.created()),clock.instant()).compareTo(Duration.ofHours(2))>0){fail(job,"STALE","진행 허용 시간이 지났어요. 저장된 의견을 확인하고 직접 이어서 진행하거나 최신 자료로 새 검토해 주세요.");return;}
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_call WHERE job_id=? AND state IN ('RESERVED','SENT'))",Boolean.class,job.id())))return;
        var convo=conversations.read(entry);if(!convo.issues().isEmpty()){fail(job,"FAILED","저장된 의견 검증 확인 필요");return;}
        List<CommitteeContract.Opinion> independent=opinions(job.brief(),"INDEPENDENT"),debate=opinions(job.brief(),"DEBATE"),finals=opinions(job.brief(),"FINAL");
        String phase=independent.size()<8?"INDEPENDENT":debate.size()<8?"DEBATE":finals.size()<8?"FINAL":"SUMMARY";
        if("SUMMARY".equals(phase)&&!CommitteeContract.tally(finals,entry.fingerprint(),ids(entry),clock.instant()).complete()){fail(job,"FAILED","유효한 8명 최종 의견 확인 필요");return;}
        if("SUMMARY".equals(phase)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_summary WHERE brief_id=? AND input_fingerprint=? AND prompt_version=?)",Boolean.class,entry.id(),entry.fingerprint(),job.version()))){
            jdbc.update("UPDATE committee_ai_job SET state='COMPLETE',issue=NULL,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING','FINALIZING')",utc(),job.id());return;
        }
        var role="SUMMARY".equals(phase)?null:CommitteeContract.ROLES.stream().filter(r->("INDEPENDENT".equals(phase)?independent:"DEBATE".equals(phase)?debate:finals).stream().noneMatch(o->o.roleKey().equals(r.key()))).findFirst().orElseThrow();
        String input=job.pack();
        if(role!=null)input+="\n내 역할의 조사 메모 (관측값·한계·검토 질문, 결론 아님)\n"+json(CommitteeResearchDossier.forRole(entry.report(),role.key()));
        if("DEBATE".equals(phase)){
            input+="\n8명의 실제 독립 주장·수치·가정·위험 (동조 의무 없음)\n"+json(claims(independent));
            input+="\n당신에게 보낸 실제 질문 원본\n"+json(dialogue(independent.stream().filter(o->o.discussion()!=null&&role.key().equals(o.discussion().questionTo())).toList()));
            // Every respondent sees the same independent round, avoiding sequential answer conformity.
        }
        if("FINAL".equals(phase)){
            input+="\n내 초기 의견과 검증할 가정\n"+json(claims(independent.stream().filter(o->role.key().equals(o.roleKey())).toList()));
            input+="\n8명의 실제 답변·반론과 관측 근거\n"+json(dialogue(debate));
        }
        if("SUMMARY".equals(phase))input+="\n실제 최종 의견·근거와 서버 집계\n"+json(dialogue(finals))+"\n"+json(CommitteeContract.tally(finals,entry.fingerprint(),ids(entry),clock.instant()));
        String instructions=CommitteeResearchPrompts.forPhase(entry,role,phase);
        input=CommitteeEvidenceIds.aliasSerialized(input,entry.report(),mapper);
        CommitteeAiGateway.Prepared prepared;
        try{prepared=gateway.prepare(role==null?"REPORT":role.key(),phase,instructions,input,CommitteeEvidenceIds.allowed(entry.report()));}
        catch(IllegalArgumentException invalid){fail(job,"FAILED","분석 자료·토론 입력 크기 확인 필요 · 유료 예약·전송 전 중단했어요.");return;}
        // Recheck immediately before reserving any paid call; never retry an unknown result.
        if(!requests.requested(job.id())||requests.unknownBlocked(job.brief())){runtimeIssue=unknownIssue();return;}
        Long call=ledger.reserve(job.id(),role==null?"REPORT":role.key(),phase,prepared.model(),prepared.hash(),prepared.reservedUsd());if(call==null)return;
        if(!Objects.equals(scope,workspace.id())){ledger.notSent(call);fail(job,"FAILED","계좌 변경 중 · AI 검토 보류");return;}
        var result=gateway.submit(call,prepared);if(!result.ok()){fail(job,"DELIVERY_UNKNOWN".equals(result.outcome())?"UNKNOWN":"FAILED",result.issue());return;}
        try{
            Instant at=clock.instant();
            if(role==null){var summary=parseSummary(result.output(),entry,finals);tx.executeWithoutResult(status->{
                jdbc.update("INSERT INTO committee_summary(brief_id,generated_at,model,input_fingerprint,prompt_version,summary,input_tokens,output_tokens,cost_usd) VALUES (?,?,?,?,?,?::jsonb,?,?,?)",entry.id(),at.atOffset(ZoneOffset.UTC),result.model(),entry.fingerprint(),CommitteeContract.PROMPT_VERSION,json(summary),result.inputTokens(),result.outputTokens(),result.costUsd());
                jdbc.update("UPDATE committee_ai_job SET state='COMPLETE',issue=NULL,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING','FINALIZING')",at.atOffset(ZoneOffset.UTC),job.id());event(entry.id(),"AI_SUMMARY","실제 8명 최종 의견과 종합 요약 저장 · 주문 승인 연결 전");});
            }else{
                var opinion=parseOpinion(result.output(),role,phase,entry,at,independent);
                tx.executeWithoutResult(status->{jdbc.update("INSERT INTO committee_opinion(brief_id,role_key,phase,generated_at,provider,model,prompt_version,input_fingerprint,opinion,input_tokens,output_tokens,cost_usd) VALUES (?,?,?,?,'openai',?,?,?,?::jsonb,?,?,?)",entry.id(),opinion.roleKey(),phase,at.atOffset(ZoneOffset.UTC),result.model(),CommitteeContract.PROMPT_VERSION,entry.fingerprint(),json(opinion),result.inputTokens(),result.outputTokens(),result.costUsd());
                    jdbc.update("UPDATE committee_ai_job SET updated_at=? WHERE id=?",at.atOffset(ZoneOffset.UTC),job.id());event(entry.id(),"AI_OPINION",role.name()+" · 실제 "+("FINAL".equals(phase)?"최종":"DEBATE".equals(phase)?"질문 답변·반론":"독립")+" 의견 저장 · 주문 없음");});
            }
            runtimeIssue=null;
        }catch(ResponseFormatException invalid){fail(job,"FAILED","응답 형식 확인 필요 ("+invalid.getMessage()+") · 원문 미노출·비용 기록 유지");}
        catch(Exception invalid){fail(job,"FAILED","응답 형식·출처/변경 근거 검증 대기 · 사용량 비용은 기록했어요.");}
    }
    private CommitteeContract.Opinion parseOpinion(JsonNode n,CommitteeContract.Role role,String phase,InvestmentCommitteeService.Entry e,Instant at,List<CommitteeContract.Opinion> originals){
        String vote=text(n,"vote",16),summary=text(n,"summary",300),changed=text(n,"changedReason",300);if(!Set.of("APPROVE","REJECT","ABSTAIN").contains(vote)||summary.isBlank())throw new IllegalArgumentException();
        List<String> sources=sourceIds(n,e);if(vote.equals("APPROVE")&&sources.isEmpty())throw new IllegalArgumentException();
        if("FINAL".equals(phase)){var before=originals.stream().filter(o->o.roleKey().equals(role.key())).findFirst().orElseThrow();if(!before.vote().equals(vote)&&changed.isBlank())throw new IllegalArgumentException();}
        var d=n.path("discussion");
        var discussion=new CommitteeContract.Discussion(text(d,"questionTo",32),text(d,"question",160),text(d,"replyTo",32),text(d,"reply",200),text(d,"counterpoint",160),text(d,"changeCondition",160));
        if(!CommitteeContract.validDiscussion(discussion,role.key(),phase))throw new ResponseFormatException("DISCUSSION_PHASE");
        if("DEBATE".equals(phase)&&originals.stream().noneMatch(o->discussion.replyTo().equals(o.roleKey())&&o.discussion()!=null
                &&role.key().equals(o.discussion().questionTo())&&!o.discussion().question().isBlank()))throw new ResponseFormatException("QUESTION_REFERENCE");
        return new CommitteeContract.Opinion(role.key(),phase,vote,summary,strings(n,"facts",3,180),strings(n,"inferences",3,180),strings(n,"risks",3,180),sources,changed,e.fingerprint(),CommitteeContract.PROMPT_VERSION,at,discussion);
    }
    private Summary parseSummary(JsonNode n,InvestmentCommitteeService.Entry e,List<CommitteeContract.Opinion> finals){
        String conclusion=text(n,"conclusion",32),summary=text(n,"summary",500);if(summary.isBlank()||!Set.of("REVIEW_BUY","REVIEW_SELL","KEEP_WATCH","NO_ACTION").contains(conclusion))throw new IllegalArgumentException();
        boolean recommends=conclusion.startsWith("REVIEW_");if(recommends&&!CommitteeContract.tally(finals,e.fingerprint(),ids(e),clock.instant()).thresholdMet())throw new IllegalArgumentException();
        if(conclusion.equals("REVIEW_BUY")&&!e.report().agenda().equals("BUY_REVIEW")||conclusion.equals("REVIEW_SELL")&&!e.report().agenda().equals("SELL_REVIEW"))throw new IllegalArgumentException();
        List<String> sources=sourceIds(n,e);if(recommends&&sources.isEmpty())throw new IllegalArgumentException();
        if(!Set.of("CONDITIONAL_INTEREST","UNATTRACTIVE","DATA_GAP","MIXED").contains(text(n,"assessment",32))
            ||text(n,"bullCase",180).isBlank()||text(n,"bearCase",180).isBlank()||text(n,"reconsiderWhen",180).isBlank())throw new ResponseFormatException("SYNTHESIS");
        return new Summary(conclusion,summary,strings(n,"reasons",3,240),strings(n,"cautions",3,240),sources,text(n,"assessment",32),text(n,"bullCase",180),text(n,"bearCase",180),text(n,"reconsiderWhen",180));
    }
    private static List<Map<String,Object>> dialogue(List<CommitteeContract.Opinion> opinions){
        return opinions.stream().map(o->{Map<String,Object> m=new LinkedHashMap<>();m.put("role",o.roleKey());m.put("vote",o.vote());if(!"DEBATE".equals(o.phase()))m.put("claim",o.summary());
            m.put("facts",o.facts().stream().limit(1).toList());m.put("evidenceIds",o.evidenceIds());var d=o.discussion();Map<String,Object> talk=new LinkedHashMap<>();
            if(d!=null){if("INDEPENDENT".equals(o.phase())){talk.put("questionTo",d.questionTo());talk.put("question",d.question());}
                else{talk.put("replyTo",d.replyTo());talk.put("reply",d.reply());talk.put("counterpoint",d.counterpoint());}
                talk.put("changeCondition",d.changeCondition());}
            m.put("discussion",talk);return m;}).toList();
    }
    private static List<Map<String,Object>> claims(List<CommitteeContract.Opinion> opinions){
        return opinions.stream().map(o->Map.<String,Object>of("role",o.roleKey(),"vote",o.vote(),"claim",o.summary(),"facts",o.facts().stream().limit(1).toList(),"assumptions",o.inferences().stream().limit(1).toList(),"risks",o.risks().stream().limit(1).toList(),"evidenceIds",o.evidenceIds())).toList();
    }
    static String text(JsonNode node,String key,int max){JsonNode value=node.get(key);if(value==null||!value.isTextual()||value.asText().length()>max||value.asText().matches("(?s).*https?://.*"))throw new ResponseFormatException("TEXT_"+key);return value.asText();}
    static List<String> strings(JsonNode node,String key,int max,int length){JsonNode value=node.get(key);if(value==null||!value.isArray()||value.size()>max)throw new ResponseFormatException("LIST_"+key);List<String> result=new ArrayList<>();for(JsonNode v:value){if(!v.isTextual()||v.asText().length()>length||v.asText().matches("(?s).*https?://.*"))throw new ResponseFormatException("ITEM_"+key);result.add(v.asText());}return List.copyOf(result);}
    private static final class ResponseFormatException extends IllegalArgumentException {
        private ResponseFormatException(String label){super(label);}
    }
    private List<String> sourceIds(JsonNode n,InvestmentCommitteeService.Entry e){
        var sources=strings(n,"evidenceIds",6,200).stream().map(id->CommitteeEvidenceIds.canonical(id,e.report())).distinct().toList();
        if(!ids(e).containsAll(sources))throw new IllegalArgumentException();return sources;
    }
    private static Set<String> ids(InvestmentCommitteeService.Entry e){return new HashSet<>(e.report().evidence().stream().map(InvestmentCommitteeService.Evidence::id).toList());}
    private List<CommitteeContract.Opinion> opinions(long brief,String phase){return jdbc.query("SELECT opinion FROM committee_opinion WHERE brief_id=? AND phase=? ORDER BY id",(rs,n)->{try{return mapper.readValue(rs.getString(1),CommitteeContract.Opinion.class);}catch(Exception fail){throw new IllegalStateException("저장 의견 확인 필요");}},brief,phase);}
    private String packHash(InvestmentCommitteeService.Entry entry){return PublicInformationService.hash(pack(entry));}
    private String pack(InvestmentCommitteeService.Entry e){return CommitteeEvidencePack.encode(e.report(),mapper);}
    public Status status(InvestmentCommitteeService.Entry entry){
        String scope=workspace.id();var rows=entry==null?List.<Job>of():jobs("WHERE scope_id=? AND brief_id=?",scope,entry.id());Job job=rows.isEmpty()?null:rows.getFirst();
        int independent=job==null?0:Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM committee_opinion WHERE brief_id=? AND phase='INDEPENDENT'",Integer.class,entry.id()));
        int debate=job==null?0:Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM committee_opinion WHERE brief_id=? AND phase='DEBATE'",Integer.class,entry.id()));
        int finals=job==null?0:Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM committee_opinion WHERE brief_id=? AND phase='FINAL'",Integer.class,entry.id()));
        boolean verified=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_call c JOIN committee_ai_job j ON j.id=c.job_id WHERE j.scope_id=? AND c.state='SETTLED' AND c.outcome='completed')",Boolean.class,scope));
        Summary summary=entry==null?null:jdbc.query("SELECT summary,input_fingerprint,prompt_version FROM committee_summary WHERE brief_id=?",(rs,n)->{
            try{if(!rs.getString(2).equals(entry.fingerprint())||!rs.getString(3).equals(job==null?CommitteeContract.PROMPT_VERSION:job.version()))return null;return mapper.readValue(rs.getString(1),Summary.class);}catch(Exception fail){return null;}},entry.id()).stream().filter(Objects::nonNull).findFirst().orElse(null);
        var active=jdbc.queryForList("SELECT brief_id FROM committee_ai_job WHERE scope_id=? AND NOT EXISTS(SELECT 1 FROM committee_ai_call c JOIN committee_archived_call a ON a.call_id=c.id WHERE c.job_id=committee_ai_job.id) ORDER BY CASE WHEN state IN ('QUEUED','RUNNING','FINALIZING') THEN 0 ELSE 1 END,id DESC LIMIT 1",Long.class,scope);
        var budget=ledger.view(scope);
        String inputIssue=job==null&&entry!=null&&!financialReady(entry)?"이 후보의 연간 재무 수집 대기 · 최신 기록에서 공식 수치가 준비된 뒤 AI를 호출해요.":job==null&&budget.todayMeetings()>=budget.dailyLimit()?"오늘 회의 한도 "+budget.todayMeetings()+"/"+budget.dailyLimit()+"회 사용 · 한국 날짜가 바뀌면 새 회의를 요청할 수 있어요. 유료 검토는 직접 요청할 때만 시작해요.":null;
        boolean unknownBlocked=requests.unknownBlocked(entry==null?-1:entry.id());
        var consent=newReviewConsent.view(entry==null?null:entry.id());
        String blocker=unknownBlocked?(consent.canConfirm()?"종료된 검토에 응답·비용 미확정 "+consent.calls().size()+"건이 남아 있어요. 기존 예약액을 보존하며 아래 버튼에서 추가 비용을 확인한 뒤 새 검토를 시작할 수 있어요.":unknownIssue()):null;
        return new Status(gateway.available(),verified,job==null?null:job.id(),job==null?"NOT_QUEUED":job.state(),independent,finals,job==null?null:job.updated(),job!=null&&job.issue()!=null?job.issue():inputIssue!=null?inputIssue:blocker!=null?blocker:job!=null&&Set.of("QUEUED","RUNNING","FINALIZING").contains(job.state())?runtimeIssue:null,budget,summary,
            "Luna 6역할 + mini 재무/위험2역할·요약 · reasoning none · 유료 검색/도구 없음 · 기업 분석 전용",active.isEmpty()?null:active.getFirst(),debate,job==null||CommitteeContract.usesDebate(job.version())?25:17,unknownBlocked&&!consent.canConfirm(),false,job==null?null:controls.options(entry.id()),consent);
    }
    private String unknownIssue(){
        var rows=jdbc.queryForList("SELECT j.brief_id,b.report->>'name' AS name FROM committee_ai_call c JOIN committee_ai_job j ON j.id=c.job_id JOIN committee_brief b ON b.id=j.brief_id WHERE c.state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id) ORDER BY c.id LIMIT 1");
        if(rows.isEmpty())return "미확정 호출 상태 확인 필요 · 예약 비용 유지";
        var row=rows.getFirst();return String.valueOf(row.get("name"))+" 검토 #"+row.get("brief_id")+"의 응답·비용 확인 대기 · 예약 비용 유지 · 추가 검토 보류. 이전 요청은 재전송하지 않아요.";
    }
    public static final class RequestRejected extends IllegalArgumentException {
        private final String code;
        public RequestRejected(String code,String message){super(message);this.code=code;}
        public String code(){return code;}
    }
    private static boolean financialReady(InvestmentCommitteeService.Entry e){return e!=null&&e.report().financialCurrent()
        &&e.report().financialEvidence()!=null&&e.report().financialEvidence().annualEvidenceReady();}
    private InvestmentCommitteeService.Entry entry(long id){String scope=workspace.id();return jdbc.query("SELECT id,generated_at,fingerprint,report FROM committee_brief WHERE id=? AND scope_id=?",(rs,n)->{try{return new InvestmentCommitteeService.Entry(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getString(3),mapper.readValue(rs.getString(4),InvestmentCommitteeService.Brief.class));}catch(Exception f){throw new IllegalArgumentException("기록 확인 필요");}},id,scope).stream().findFirst().orElseThrow(()->new IllegalArgumentException("현재 계좌의 기록 확인 필요"));}
    private List<Job> jobs(String where,Object...args){return jdbc.query("SELECT id,brief_id,pack,state,issue,created_at,updated_at,prompt_version FROM committee_ai_job "+where,(rs,n)->new Job(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class).toInstant(),rs.getObject(7,OffsetDateTime.class).toInstant(),rs.getString(8)),args);}
    private void fail(Job job,String state,String issue){if(jdbc.update("UPDATE committee_ai_job SET state=?,issue=?,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING','FINALIZING')",state,issue,utc(),job.id())==1){event(job.brief(),"AI_PAUSED",issue);runtimeIssue=issue;}}
    private void event(long brief,String type,String detail){jdbc.update("INSERT INTO committee_event(brief_id,occurred_at,event_type,detail) VALUES (?,?,?,?)",brief,utc(),type,detail);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception f){throw new IllegalArgumentException("AI 자료 형식 확인 필요");}}
    private OffsetDateTime utc(){return clock.instant().atOffset(ZoneOffset.UTC);}
    private static int daily(LocalDate day){return day.getDayOfWeek()==DayOfWeek.SATURDAY||day.getDayOfWeek()==DayOfWeek.SUNDAY?4:10;}
    private record Job(long id,long brief,String pack,String state,String issue,Instant created,Instant updated,String version){}
    public record Queued(long briefId,String state,String message){}
    public record Summary(String conclusion,String summary,List<String> reasons,List<String> cautions,List<String> evidenceIds,
        String assessment,String bullCase,String bearCase,String reconsiderWhen){}
    public record Status(boolean configured,boolean responseVerified,Long jobId,String state,int independentCount,int finalCount,Instant updatedAt,String issue,CommitteeAiLedger.Budget budget,Summary summary,String modelPlan,Long latestMeetingBriefId,int debateCount,int totalSteps,boolean requestBlocked,boolean automaticReviewsEnabled,CommitteeReviewControlService.Options controls,CommitteeNewReviewConsent.View newReviewConsent){}
}
