package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.research.PublicInformationService;
import com.personaltrader.research.ResearchWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Only a confirmed owner POST changes a review's lifecycle. Reads never grant paid authority. */
@Service
public class CommitteeReviewControlService {
    private static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING","FINALIZING");
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    private final ResearchWorkspace workspace;private final CommitteeAiGateway gateway;private final CommitteeAiLedger ledger;
    private final CommitteeRequestPolicy requests;private final CommitteeConversationService conversations;private final CommitteeWorkerState worker;
    private final TransactionTemplate tx;private final boolean allowUnknownRestart;
    public CommitteeReviewControlService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,ResearchWorkspace workspace,
        CommitteeAiGateway gateway,CommitteeAiLedger ledger,CommitteeRequestPolicy requests,CommitteeConversationService conversations,
        CommitteeWorkerState worker,PlatformTransactionManager transactions,
        @Value("${APP_ALLOW_UNCONFIRMED_REVIEW_RESTART:false}") boolean allowUnknownRestart){
        this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.workspace=workspace;this.gateway=gateway;this.ledger=ledger;
        this.requests=requests;this.conversations=conversations;this.worker=worker;this.tx=new TransactionTemplate(transactions);this.allowUnknownRestart=allowUnknownRestart;
    }
    public Options options(long brief){return options(load(brief,false));}
    private Options options(Row row){
        var budget=ledger.view(workspace.id());
        int inFlight=count("SELECT count(*) FROM committee_ai_call WHERE job_id=? AND state IN ('RESERVED','SENT')",row.job());
        int unknown=count("SELECT count(*) FROM committee_ai_call WHERE job_id=? AND state='UNKNOWN'",row.job());
        int done=count("SELECT (SELECT count(*) FROM committee_opinion WHERE brief_id=?)+(SELECT count(*) FROM committee_summary WHERE brief_id=?)",row.entry().id(),row.entry().id());
        BigDecimal settled=jdbc.queryForObject("SELECT coalesce(sum(cost_usd),0) FROM committee_ai_call WHERE job_id=?",BigDecimal.class,row.job());
        BigDecimal reserved=jdbc.queryForObject("SELECT coalesce(sum(reserved_usd),0) FROM committee_ai_call WHERE job_id=? AND state IN ('RESERVED','SENT','UNKNOWN')",BigDecimal.class,row.job());
        var children=jdbc.queryForList("SELECT brief_id FROM committee_ai_job WHERE restart_of_job_id=?",Long.class,row.job());
        Long child=children.isEmpty()?null:children.getFirst();
        var latest=latest(row.entry());
        String common=!gateway.available()?"로컬 API 키·활성화 설정 확인이 필요해요.":
            inFlight>0||worker.busy(row.job())?"전송된 호출의 결과를 저장 중이에요. 잠시 후 선택해 주세요.":
            Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_job WHERE id<>? AND state IN ('QUEUED','RUNNING','FINALIZING'))",Boolean.class,row.job()))?"다른 검토가 진행 중이에요. 완료하거나 일시중지한 뒤 선택해 주세요.":
            budget.remainingUsd().signum()<=0?"이번 달 남은 호출 예산이 없어요.":null;
        String resume=ACTIVE.contains(row.state())?"이미 진행 중인 검토예요.":"COMPLETE".equals(row.state())?"이미 완료됐어요.":
            "CLOSED".equals(row.state())?"종료한 기록은 이어서 진행하지 않아요. 새 검토를 선택해 주세요.":common;
        if(resume==null&&(!CommitteeContract.PROMPT_VERSION.equals(row.version())||!fresh(row.entry())))resume="당시 자료가 오래됐거나 분석 기준이 바뀌었어요. 최신 자료로 처음부터 검토해 주세요.";
        if(resume==null&&!financialReady(row.entry()))resume="당시 공식 재무 입력을 확인할 수 없어요. 최신 자료로 새 검토해 주세요.";
        if(resume==null&&!requests.requested(row.job()))resume="원래 사용자 요청 기록이 없어 이어서 진행할 수 없어요.";
        if(resume==null&&(unknown>0||requests.unknownBlocked(briefId(row))))resume="응답·비용 미확정 호출은 재전송하지 않아요. 비용을 보존하고 최신 자료로 새 검토할 수 있는지 확인해 주세요.";
        if(resume==null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_call WHERE job_id=? AND attempt>=3 AND NOT EXISTS(SELECT 1 FROM committee_opinion o WHERE o.brief_id=? AND o.phase=committee_ai_call.phase AND o.role_key=committee_ai_call.role_key))",Boolean.class,row.job(),briefId(row))))resume="해당 단계의 재요청 한도에 도달했어요. 최신 자료로 새 검토해 주세요.";
        if(resume==null&&!conversations.read(row.entry()).issues().isEmpty())resume="저장된 의견 검증이 필요해요. 기존 기록을 보존하고 새 검토를 선택해 주세요.";
        String restart=ACTIVE.contains(row.state())?"일시중지한 뒤 처음부터 검토할 수 있어요.":"COMPLETE".equals(row.state())?"완료한 결과는 그대로 보관해요. 새 기업 화면에서 최신 자료를 확인해 주세요.":child!=null?"이미 만든 새 검토를 열어 주세요.":common;
        if(restart==null&&(latest==null||!fresh(latest)||!financialReady(latest)))restart="이 기업의 최신 공식 재무와 연구 자료를 수집 중이에요.";
        if(restart==null&&budget.todayMeetings()>=budget.dailyLimit())restart="오늘 새 검토 한도를 사용했어요. 이어서 진행은 새 회의로 세지 않아요.";
        var unknownIds=unknownIds();
        boolean ack=!unknownIds.isEmpty();
        if(restart==null&&ack&&(!allowUnknownRestart||!unknownsBelongTo(row,unknownIds)))restart="미확정 호출의 비용 확인 전에는 새 유료 검토가 차단돼요. 기존 예약 비용은 유지해요.";
        BigDecimal unknownReserved=jdbc.queryForObject("SELECT coalesce(sum(reserved_usd),0) FROM committee_ai_call c WHERE state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id)",BigDecimal.class);
        return new Options(row.updated(),ACTIVE.contains(row.state()),resume==null,resume,restart==null,restart,
            !Set.of("CLOSED","COMPLETE").contains(row.state()),done,CommitteeContract.usesDebate(row.version())?25:17,
            inFlight,unknown,settled,reserved,row.entry().generatedAt(),latest==null?null:latest.id(),latest==null?null:latest.generatedAt(),child,ack,unknownIds,unknownReserved);
    }
    public CommitteeAiCoordinator.Queued act(long brief,Decision decision){
        if(decision==null||!decision.confirmed())throw reject("CONFIRM_REQUIRED","검토와 실행할 선택을 직접 확인해 주세요.");
        UUID key;try{key=UUID.fromString(decision.clientKey());if(!key.toString().equalsIgnoreCase(decision.clientKey()))throw new IllegalArgumentException();}catch(Exception e){throw reject("REQUEST_KEY_REQUIRED","요청 식별자를 확인하고 다시 선택해 주세요.");}
        if(!Set.of("PAUSE","RESUME","RESTART","CLOSE").contains(Objects.toString(decision.action(),"")))throw reject("INVALID_ACTION","선택할 검토 동작을 확인해 주세요.");
        return tx.execute(status->{
            jdbc.execute("SELECT pg_advisory_xact_lock(73491821)");Row row=load(brief,true);
            var replay=jdbc.queryForList("SELECT job_id,action,coalesce(target_job_id,job_id) AS target FROM committee_job_action WHERE client_key=?",key);
            if(!replay.isEmpty()){
                var saved=replay.getFirst();if(((Number)saved.get("job_id")).longValue()!=row.job()||!decision.action().equals(saved.get("action")))throw reject("REQUEST_KEY_CONFLICT","이 요청은 다른 선택에 사용됐어요. 상태를 새로 확인해 주세요.");
                return response(((Number)saved.get("target")).longValue(),"이미 저장한 선택이에요. 중복 유료 검토는 만들지 않았어요.");
            }
            // A second click on the parent can only reopen its one existing restart child.
            var children=jdbc.queryForList("SELECT id FROM committee_ai_job WHERE restart_of_job_id=?",Long.class,row.job());
            if("RESTART".equals(decision.action())&&!children.isEmpty())return response(children.getFirst(),"이미 만든 새 검토를 열었어요. 추가 예약 없음.");
            if(!Objects.equals(row.updated(),decision.expectedUpdatedAt()))throw reject("STATE_CHANGED","검토 상태가 바뀌었어요. 최신 상태를 확인한 뒤 다시 선택해 주세요.");
            Options o=options(row);long target=row.job();boolean acknowledged=false;
            switch(decision.action()){
                case "PAUSE"->{if(!o.canPause())throw reject("NOT_ACTIVE","진행 중인 검토만 일시중지할 수 있어요.");setState(row,"PAUSED","사용자 일시중지 · 전송된 호출 결과는 보존하고 추가 호출은 하지 않아요.");}
                case "CLOSE"->{if(!o.canClose())throw reject("ALREADY_CLOSED","이미 완료하거나 종료한 기록이에요.");setState(row,"CLOSED","사용자 검토 종료 · 대화·사용량·미확정 예약 비용 보존");}
                case "RESUME"->{if(!o.canResume())throw reject("RESUME_BLOCKED",o.resumeReason());setState(row,"QUEUED",null);}
                case "RESTART"->{
                    if(!o.canRestart())throw reject("RESTART_BLOCKED",o.restartReason());
                    if(!Objects.equals(o.restartEvidenceBriefId(),decision.evidenceBriefId()))throw reject("EVIDENCE_CHANGED","최신 자료가 바뀌었어요. 자료 시각을 다시 확인해 주세요.");
                    if(o.requiresUnknownAcknowledgement()&&(!decision.acknowledgeUnknownCosts()||!new HashSet<>(o.unknownCallIds()).equals(new HashSet<>(decision.unknownCallIds()==null?List.of():decision.unknownCallIds()))))throw reject("UNKNOWN_COST_CONFIRM_REQUIRED","미확정 예약 비용과 새 검토의 추가 비용을 직접 확인해 주세요.");
                    acknowledged=o.requiresUnknownAcknowledgement();target=restart(row,loadEntry(decision.evidenceBriefId()),key,o.unknownCallIds(),acknowledged);
                    setState(row,"CLOSED","사용자 요청으로 새 검토 생성 · 이전 대화·비용 보존");
                }
                default->throw new IllegalStateException();
            }
            jdbc.update("INSERT INTO committee_job_action(job_id,action,client_key,requested_at,target_job_id,unknown_cost_acknowledged) VALUES (?,?,?,?,?,?)",row.job(),decision.action(),key,utc(),target==row.job()?null:target,acknowledged);
            if("RESUME".equals(decision.action()))requests.record(row.job(),clock.instant());
            event(brief,"USER_"+decision.action(),switch(decision.action()){
                case "RESUME"->"사용자 이어서 진행 확인 · 완료한 의견/확정 비용 보존 · 남은 단계만 추가 비용 가능";
                case "RESTART"->"사용자 최신 자료로 처음부터 검토 확인 · 이전 기록 보존 · 새 유료 검토 별도 생성";
                case "PAUSE"->"사용자 일시중지 · 이미 전송된 호출은 결과 저장 후 멈춤";
                default->"사용자 검토 종료 · 기록과 비용은 삭제하지 않음";
            });
            return response(target,switch(decision.action()){
                case "RESUME"->"저장된 의견 다음 단계부터 이어서 진행해요. 추가 비용이 발생할 수 있어요.";
                case "RESTART"->"최신 자료로 별도의 새 검토를 만들었어요. 이전 대화와 비용은 그대로 보관해요.";
                case "PAUSE"->"일시중지했어요. 이미 전송한 호출 1건은 결과 저장 후 멈춰요.";
                default->"검토를 종료했어요. 대화·사용 비용·미확정 예약 비용은 남겨뒀어요.";
            });
        });
    }
    private long restart(Row source,InvestmentCommitteeService.Entry evidence,UUID key,List<Long> unknown,boolean acknowledged){
        if(!evidence.report().code().equals(source.entry().report().code())||!evidence.report().agenda().equals(source.entry().report().agenda())||!fresh(evidence)||!financialReady(evidence))throw reject("EVIDENCE_CHANGED","해당 기업의 최신 자료를 다시 확인해 주세요.");
        String scope=workspace.id(),fingerprint=PublicInformationService.hash(evidence.fingerprint()+":owner-restart:"+key),pack=CommitteeEvidencePack.encode(evidence.report(),mapper);
        if(pack.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw reject("EVIDENCE_TOO_LARGE","연구 입력 크기를 확인해 주세요.");
        Long brief=jdbc.queryForObject("INSERT INTO committee_brief(scope_id,stock_code,agenda,generated_at,fingerprint,report) SELECT scope_id,stock_code,agenda,generated_at,?,report FROM committee_brief WHERE id=? AND scope_id=? RETURNING id",Long.class,fingerprint,evidence.id(),scope);
        Long job=jdbc.queryForObject("INSERT INTO committee_ai_job(scope_id,brief_id,pack_hash,prompt_version,pack,meeting_day,state,created_at,updated_at,restart_of_job_id) VALUES (?,?,?,?,?::jsonb,?,'QUEUED',?,?,?) RETURNING id",Long.class,scope,brief,PublicInformationService.hash(pack),CommitteeContract.PROMPT_VERSION,pack,clock.instant().atZone(CommitteeAiLedger.SEOUL).toLocalDate(),utc(),utc(),source.job());
        if(acknowledged)for(long call:unknown)jdbc.update("INSERT INTO committee_unknown_review_authorization(brief_id,unknown_call_id,authorized_at,reason) VALUES (?,?,?,?)",brief,call,utc(),"소유자 새 검토 확인 · 이전 미확정 예약 유지 · 원래 호출 재전송 없음");
        requests.record(job,clock.instant());event(brief,"USER_RESTART_SOURCE","이전 검토 #"+briefId(source)+"에서 사용자 새 검토 · 근거 원본 #"+evidence.id()+"의 시각 유지 · 주문 없음");return job;
    }
    public Instant authorizedAt(long job,Instant fallback){
        var times=jdbc.queryForList("SELECT requested_at FROM committee_job_action WHERE job_id=? AND action='RESUME' ORDER BY id DESC LIMIT 1",OffsetDateTime.class,job);
        return times.isEmpty()?fallback:times.getFirst().toInstant();
    }
    public void recoverInterrupted(){
        // Startup has no owner requests yet. A stopped server must not silently continue paid reviews.
        jdbc.update("UPDATE committee_ai_job SET state='PAUSED',issue=?,updated_at=? WHERE state IN ('QUEUED','RUNNING','FINALIZING')","서버 중단 후 사용자 선택 대기 · 저장된 의견과 예약 비용 유지",utc());
    }
    private Row load(long brief,boolean lock){
        var entries=jdbc.query("SELECT j.id,j.state,j.updated_at,j.prompt_version FROM committee_ai_job j JOIN committee_brief b ON b.id=j.brief_id AND b.scope_id=j.scope_id WHERE j.brief_id=? AND j.scope_id=?"+(lock?" FOR UPDATE OF j":""),(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class).toInstant(),rs.getString(4)},brief,workspace.id());
        if(entries.isEmpty())throw reject("REVIEW_NOT_FOUND","현재 소유자의 저장된 검토를 선택해 주세요.");var x=entries.getFirst();return new Row((Long)x[0],(String)x[1],(Instant)x[2],(String)x[3],loadEntry(brief));
    }
    private InvestmentCommitteeService.Entry loadEntry(Long brief){
        if(brief==null)throw reject("EVIDENCE_REQUIRED","최신 근거를 선택해 주세요.");
        return jdbc.query("SELECT id,generated_at,fingerprint,report FROM committee_brief WHERE id=? AND scope_id=?",(rs,n)->{try{return new InvestmentCommitteeService.Entry(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getString(3),mapper.readValue(rs.getString(4),InvestmentCommitteeService.Brief.class));}catch(Exception e){throw reject("EVIDENCE_INVALID","저장된 근거 확인이 필요해요.");}},brief,workspace.id()).stream().findFirst().orElseThrow(()->reject("EVIDENCE_REQUIRED","최신 근거를 선택해 주세요."));
    }
    private InvestmentCommitteeService.Entry latest(InvestmentCommitteeService.Entry source){var ids=jdbc.queryForList("SELECT id FROM committee_brief WHERE scope_id=? AND stock_code=? AND agenda=? ORDER BY id DESC LIMIT 1",Long.class,workspace.id(),source.report().code(),source.report().agenda());return ids.isEmpty()?null:loadEntry(ids.getFirst());}
    private boolean fresh(InvestmentCommitteeService.Entry e){return InvestmentCommitteeService.VERSION.equals(e.report().version())&&!e.generatedAt().isAfter(clock.instant())&&Duration.between(e.generatedAt(),clock.instant()).compareTo(Duration.ofHours(24))<=0;}
    private boolean financialReady(InvestmentCommitteeService.Entry e){return e.report().financialCurrent()&&e.report().financialEvidence()!=null&&e.report().financialEvidence().annualEvidenceReady();}
    private List<Long> unknownIds(){return jdbc.queryForList("SELECT id FROM committee_ai_call c WHERE state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id) ORDER BY id",Long.class);}
    private boolean unknownsBelongTo(Row row,List<Long> ids){
        Set<Long> relevant=new HashSet<>(jdbc.queryForList("SELECT id FROM committee_ai_call c WHERE job_id=? AND state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id)",Long.class,row.job()));
        relevant.addAll(jdbc.queryForList("SELECT unknown_call_id FROM committee_unknown_review_authorization WHERE brief_id=?",Long.class,briefId(row)));
        return !ids.isEmpty()&&relevant.containsAll(ids);
    }
    private int count(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,Integer.class,args));}
    private void setState(Row row,String state,String issue){jdbc.update("UPDATE committee_ai_job SET state=?,issue=?,updated_at=? WHERE id=?",state,issue,utc(),row.job());}
    private CommitteeAiCoordinator.Queued response(long job,String message){return jdbc.query("SELECT brief_id,state FROM committee_ai_job WHERE id=? AND scope_id=?",(rs,n)->new CommitteeAiCoordinator.Queued(rs.getLong(1),rs.getString(2),message),job,workspace.id()).stream().findFirst().orElseThrow(()->reject("REVIEW_NOT_FOUND","검토 결과 확인 필요"));}
    private void event(long brief,String type,String detail){jdbc.update("INSERT INTO committee_event(brief_id,occurred_at,event_type,detail) VALUES (?,?,?,?)",brief,utc(),type,detail);}
    private OffsetDateTime utc(){return clock.instant().atOffset(ZoneOffset.UTC);}
    private static long briefId(Row row){return row.entry().id();}
    private static CommitteeAiCoordinator.RequestRejected reject(String code,String message){return new CommitteeAiCoordinator.RequestRejected(code,message);}
    private record Row(long job,String state,Instant updated,String version,InvestmentCommitteeService.Entry entry){}
    public record Decision(String action,boolean confirmed,String clientKey,Instant expectedUpdatedAt,Long evidenceBriefId,boolean acknowledgeUnknownCosts,List<Long> unknownCallIds){}
    public record Options(Instant expectedUpdatedAt,boolean canPause,boolean canResume,String resumeReason,boolean canRestart,String restartReason,boolean canClose,
        int savedSteps,int totalSteps,int inFlightCalls,int unknownCalls,BigDecimal settledUsd,BigDecimal reservedUsd,Instant evidenceAt,Long restartEvidenceBriefId,Instant restartEvidenceAt,Long restartedBriefId,boolean requiresUnknownAcknowledgement,List<Long> unknownCallIds,BigDecimal unknownReservedUsd){}
}
