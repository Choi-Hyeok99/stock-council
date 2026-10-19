package com.personaltrader.committee;

import com.personaltrader.research.ResearchWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** A per-brief owner acknowledgement of CLOSED unknown calls; never clears costs or resends calls. */
@Service
public class CommitteeNewReviewConsent {
    private final JdbcTemplate jdbc;private final Clock clock;private final ResearchWorkspace workspace;
    private final CommitteeRequestPolicy requests;private final boolean enabled;
    public CommitteeNewReviewConsent(JdbcTemplate jdbc,Clock clock,ResearchWorkspace workspace,CommitteeRequestPolicy requests,
        @Value("${APP_ALLOW_UNCONFIRMED_REVIEW_RESTART:false}") boolean enabled){
        this.jdbc=jdbc;this.clock=clock;this.workspace=workspace;this.requests=requests;this.enabled=enabled;
    }
    public View view(Long brief){
        if(brief==null||!requests.unknownBlocked(brief))return new View(false,false,null,List.of(),BigDecimal.ZERO);
        String scope=workspace.id();
        var rows=jdbc.query("""
            SELECT c.id,j.brief_id,j.scope_id,j.state,b.report->>'name' AS name,c.reserved_usd,
              EXISTS(SELECT 1 FROM committee_manual_request m WHERE m.job_id=j.id) AS owner_requested
            FROM committee_ai_call c JOIN committee_ai_job j ON j.id=c.job_id JOIN committee_brief b ON b.id=j.brief_id
            WHERE c.state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id) ORDER BY c.id
            """,(rs,n)->new Uncertain(rs.getLong("id"),rs.getLong("brief_id"),rs.getString("scope_id"),rs.getString("state"),
                rs.getString("name"),rs.getBigDecimal("reserved_usd"),rs.getBoolean("owner_requested")));
        String reason=!eligible(enabled,scope,rows)?"종료·소유자 요청·현재 작업공간 조건이 확인된 미확정 호출만 비용 확인 후 새 검토를 허용해요.":null;
        if(reason==null&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_brief WHERE id=? AND scope_id=?)",Boolean.class,brief,scope)))reason="현재 작업공간의 새 기업 자료를 선택해 주세요.";
        if(reason==null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_job WHERE brief_id=? OR state IN ('QUEUED','RUNNING','FINALIZING'))",Boolean.class,brief)))reason="이미 시작한 검토나 진행 중인 검토가 있어요. 검토 내역에서 현재 상태를 확인해 주세요.";
        var visible=rows.stream().filter(r->Objects.equals(scope,r.scope())).map(r->new Pending(r.id(),r.brief(),r.company(),r.reserved())).toList();
        BigDecimal sum=visible.stream().map(Pending::reservedUsd).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new View(true,reason==null,reason,visible,sum);
    }
    /** Called only inside the coordinator's locked owner-confirmed enqueue transaction. */
    public void authorize(long brief,Confirmation confirmation){
        View v=view(brief);if(!v.required())return;
        if(!v.canConfirm())throw reject("UNKNOWN_CALLS",v.reason());
        var expected=v.calls().stream().map(Pending::callId).collect(java.util.stream.Collectors.toSet());
        if(!confirms(confirmation,expected))throw reject("UNKNOWN_COST_CONFIRM_REQUIRED","종료된 검토의 미확정 예약 비용과 새 검토의 추가 비용을 버튼에서 직접 확인해 주세요.");
        for(var call:v.calls())jdbc.update("""
            INSERT INTO committee_unknown_review_authorization(brief_id,unknown_call_id,authorized_at,reason)
            VALUES (?,?,?,?) ON CONFLICT DO NOTHING
            """,brief,call.callId(),clock.instant().atOffset(ZoneOffset.UTC),"소유자 새 기업 검토 직접 확인 · 종료된 미확정 예약 비용 유지 · 원래 호출 재전송 없음");
    }
    static boolean eligible(boolean enabled,String scope,List<Uncertain> calls){
        return enabled&&scope!=null&&!calls.isEmpty()&&calls.size()<=128&&calls.stream().allMatch(c->Objects.equals(scope,c.scope())&&"CLOSED".equals(c.state())&&c.ownerRequested());
    }
    static boolean confirms(Confirmation c,Set<Long> expected){
        return c!=null&&c.acknowledged()&&c.callIds()!=null&&c.callIds().size()<=128&&c.callIds().stream().noneMatch(Objects::isNull)
            &&c.callIds().size()==new HashSet<>(c.callIds()).size()&&new HashSet<>(c.callIds()).equals(expected);
    }
    private static CommitteeAiCoordinator.RequestRejected reject(String code,String message){return new CommitteeAiCoordinator.RequestRejected(code,message);}
    record Uncertain(long id,long brief,String scope,String state,String company,BigDecimal reserved,boolean ownerRequested){}
    public record Pending(long callId,long briefId,String company,BigDecimal reservedUsd){}
    public record View(boolean required,boolean canConfirm,String reason,List<Pending> calls,BigDecimal reservedUsd){}
    public record Confirmation(boolean acknowledged,List<Long> callIds){}
}
