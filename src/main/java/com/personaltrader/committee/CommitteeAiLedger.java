package com.personaltrader.committee;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.time.*;
import java.util.*;

/** Durable, project-wide monthly reservations. Unknown sends retain their full reservation. */
@Service
public class CommitteeAiLedger {
    public static final BigDecimal CEILING=new BigDecimal("10.00");
    public static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;private final Clock clock;private final TransactionTemplate tx;
    public CommitteeAiLedger(JdbcTemplate jdbc,Clock clock,PlatformTransactionManager transactions){this.jdbc=jdbc;this.clock=clock;tx=new TransactionTemplate(transactions);}
    public Long reserve(long job,String role,String phase,String model,String hash,BigDecimal amount){
        return tx.execute(status->{
            var state=jdbc.queryForList("SELECT j.state FROM committee_ai_job j WHERE j.id=? AND EXISTS(SELECT 1 FROM committee_manual_request r WHERE r.job_id=j.id) AND NOT EXISTS(SELECT 1 FROM committee_ai_call u WHERE u.state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call retired WHERE retired.call_id=u.id) AND NOT EXISTS(SELECT 1 FROM committee_unknown_review_authorization a WHERE a.brief_id=j.brief_id AND a.unknown_call_id=u.id)) FOR UPDATE OF j",String.class,job);
            if(state.isEmpty()||!Set.of("QUEUED","RUNNING","FINALIZING").contains(state.getFirst()))return null;
            var previous=jdbc.query("SELECT attempt,state,created_at FROM committee_ai_call WHERE job_id=? AND role_key=? AND phase=? ORDER BY attempt DESC LIMIT 1",(rs,n)->new Previous(rs.getInt(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class)),job,role,phase);
            int attempt=previous.isEmpty()?1:previous.getFirst().attempt()+1;
            if(!previous.isEmpty()&&(attempt>3||!Set.of("SETTLED","REJECTED","NOT_SENT").contains(previous.getFirst().state())))return null;
            if(!previous.isEmpty()&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_job_action WHERE job_id=? AND action='RESUME' AND requested_at>?)",Boolean.class,job,previous.getFirst().created()))){
                jdbc.update("UPDATE committee_ai_job SET state='FAILED',issue=?,updated_at=? WHERE id=?","이전 호출 기록 확인 필요 · 사용자 재요청 전 자동 재전송 없음",utc(),job);return null;
            }
            LocalDate month=month();jdbc.update("INSERT INTO committee_ai_budget_month(month_start) VALUES (?) ON CONFLICT DO NOTHING",month);
            BigDecimal used=jdbc.queryForObject("SELECT committed_usd FROM committee_ai_budget_month WHERE month_start=? FOR UPDATE",BigDecimal.class,month);
            if(amount==null||amount.signum()<=0||used.add(amount).compareTo(CEILING)>0){
                jdbc.update("UPDATE committee_ai_job SET state='BUDGET_WAIT',issue=?,updated_at=? WHERE id=?","월 $10 잔여 예산 부족 · 추가 호출 보류",utc(),job);return null;
            }
            Long id=jdbc.queryForObject("INSERT INTO committee_ai_call(job_id,role_key,phase,model,request_hash,attempt,month_start,reserved_usd,state,created_at) VALUES (?,?,?,?,?,?,?,?,'RESERVED',?) RETURNING id",Long.class,job,role,phase,model,hash,attempt,month,amount,utc());
            jdbc.update("UPDATE committee_ai_budget_month SET committed_usd=committed_usd+? WHERE month_start=?",amount,month);
            jdbc.update("UPDATE committee_ai_job SET state=?,updated_at=? WHERE id=?","SUMMARY".equals(phase)?"FINALIZING":"RUNNING",utc(),job);return id;
        });
    }
    /** One successful claim only; a SENT request cannot be submitted a second time. */
    public boolean claimSend(long id,String hash){return jdbc.update("UPDATE committee_ai_call c SET state='SENT',submitted_at=? WHERE c.id=? AND c.state='RESERVED' AND c.request_hash=? AND EXISTS(SELECT 1 FROM committee_ai_job j JOIN committee_manual_request r ON r.job_id=j.id WHERE j.id=c.job_id AND j.state IN ('QUEUED','RUNNING','FINALIZING') AND NOT EXISTS(SELECT 1 FROM committee_ai_call u WHERE u.state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call retired WHERE retired.call_id=u.id) AND NOT EXISTS(SELECT 1 FROM committee_unknown_review_authorization a WHERE a.brief_id=j.brief_id AND a.unknown_call_id=u.id)))",utc(),id,hash)==1;}
    public void settle(long id,int in,int out,int cached,int writes,BigDecimal cost,String outcome){
        finish(id,"SETTLED",in,out,cached,writes,cost,outcome);
    }
    public void notSent(long id){finish(id,"NOT_SENT",null,null,null,null,BigDecimal.ZERO,"CREDENTIALS_WAIT");}
    public void releaseUnclaimed(long id){tx.executeWithoutResult(status->{
        var states=jdbc.queryForList("SELECT state FROM committee_ai_call WHERE id=? FOR UPDATE",String.class,id);
        if(!states.isEmpty()&&"RESERVED".equals(states.getFirst()))notSent(id);
    });}
    public void rejected(long id,String outcome){finish(id,"REJECTED",null,null,null,null,BigDecimal.ZERO,outcome);}
    public void unknown(long id){finish(id,"UNKNOWN",null,null,null,null,null,"DELIVERY_UNKNOWN");}
    private void finish(long id,String state,Integer in,Integer out,Integer cached,Integer writes,BigDecimal cost,String outcome){
        tx.executeWithoutResult(status->{
            var rows=jdbc.query("SELECT month_start,reserved_usd,state FROM committee_ai_call WHERE id=? FOR UPDATE",(rs,n)->new Reservation(rs.getObject(1,LocalDate.class),rs.getBigDecimal(2),rs.getString(3)),id);
            if(rows.isEmpty()||!Set.of("RESERVED","SENT").contains(rows.getFirst().state()))return;
            var row=rows.getFirst();
            if("NOT_SENT".equals(state)&&!"RESERVED".equals(row.state()))throw new IllegalStateException("전송 상태 확인 필요");
            BigDecimal charge=cost==null?row.amount():cost;
            jdbc.update("UPDATE committee_ai_budget_month SET committed_usd=committed_usd-?+? WHERE month_start=?",row.amount(),charge,row.month());
            jdbc.update("UPDATE committee_ai_call SET state=?,input_tokens=?,output_tokens=?,cached_tokens=?,cache_write_tokens=?,cost_usd=?,outcome=?,settled_at=? WHERE id=?",state,in,out,cached,writes,cost,outcome,utc(),id);
        });
    }
    public Budget view(String scope){
        LocalDate month=month(),day=clock.instant().atZone(SEOUL).toLocalDate();
        var totals=jdbc.queryForList("SELECT committed_usd FROM committee_ai_budget_month WHERE month_start=?",BigDecimal.class,month);
        BigDecimal used=totals.isEmpty()?BigDecimal.ZERO:totals.getFirst();
        BigDecimal settled=jdbc.queryForObject("SELECT COALESCE(SUM(cost_usd),0) FROM committee_ai_call WHERE month_start=?",BigDecimal.class,month);
        Long unknown=jdbc.queryForObject("SELECT COUNT(*) FROM committee_ai_call c WHERE month_start=? AND state IN ('RESERVED','SENT','UNKNOWN') AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id)",Long.class,month);
        BigDecimal archived=jdbc.queryForObject("SELECT COALESCE(SUM(c.reserved_usd),0) FROM committee_ai_call c JOIN committee_archived_call a ON a.call_id=c.id WHERE c.month_start=? AND c.state='UNKNOWN'",BigDecimal.class,month);
        int meetings=scope==null?0:Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM committee_ai_job WHERE scope_id=? AND meeting_day=?",Integer.class,scope,day));
        int daily=day.getDayOfWeek()==DayOfWeek.SATURDAY||day.getDayOfWeek()==DayOfWeek.SUNDAY?4:10;
        if(scope!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_extra_review_authorization WHERE scope_id=? AND meeting_day=?)",Boolean.class,scope,day)))daily++;
        return new Budget(month,CEILING,settled,used,CEILING.subtract(used).max(BigDecimal.ZERO),unknown==null?0:unknown,archived,meetings,daily,
            "앱 호출 전 예산 예약·실제 토큰 정산. 전송/사용량 미확정은 예약액 유지. 공급자 프로젝트의 월$10 hard limit은 사용자 설정이며 API로 검증하지 않았어요. 환율·세금·다른 앱 청구액은 제외예요.");
    }
    public boolean extraReviewAuthorized(String scope,long brief,LocalDate day){
        return scope!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_extra_review_authorization WHERE scope_id=? AND brief_id=? AND meeting_day=? AND authorized_at<=?)",
            Boolean.class,scope,brief,day,utc()));
    }
    public void expireAbandoned(){
        var calls=jdbc.query("SELECT id,job_id,state FROM committee_ai_call WHERE state IN ('RESERVED','SENT') AND created_at<?",(rs,n)->new Abandoned(rs.getLong(1),rs.getLong(2),rs.getString(3)),clock.instant().minusSeconds(180).atOffset(ZoneOffset.UTC));
        for(var c:calls){if("RESERVED".equals(c.state()))releaseUnclaimed(c.id());else unknown(c.id());
            var states=jdbc.queryForList("SELECT state FROM committee_ai_call WHERE id=?",String.class,c.id());
            if(!states.isEmpty()&&Set.of("UNKNOWN","NOT_SENT").contains(states.getFirst()))jdbc.update("UPDATE committee_ai_job SET state=?,issue=?,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING','FINALIZING')","UNKNOWN".equals(states.getFirst())?"UNKNOWN":"FAILED","중단된 호출 확인 필요 · 자동 재전송 없음",utc(),c.job());
        }
    }
    private LocalDate month(){return clock.instant().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);}
    private OffsetDateTime utc(){return clock.instant().atOffset(ZoneOffset.UTC);}
    private record Reservation(LocalDate month,BigDecimal amount,String state){}
    private record Abandoned(long id,long job,String state){}
    private record Previous(int attempt,String state,OffsetDateTime created){}
    public record Budget(LocalDate month,BigDecimal ceilingUsd,BigDecimal settledUsd,BigDecimal committedUsd,BigDecimal remainingUsd,
        long unsettledCalls,BigDecimal archivedReservedUsd,int todayMeetings,int dailyLimit,String explanation){}
}
