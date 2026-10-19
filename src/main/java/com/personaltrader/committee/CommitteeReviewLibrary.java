package com.personaltrader.committee;

import com.personaltrader.research.ResearchWorkspace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Account-scoped saved reviews. No provider, enqueue, or order dependency is invoked. */
@Service
public class CommitteeReviewLibrary {
    private final JdbcTemplate jdbc;private final ResearchWorkspace workspace;private final Clock clock;
    public CommitteeReviewLibrary(JdbcTemplate jdbc,ResearchWorkspace workspace,Clock clock){this.jdbc=jdbc;this.workspace=workspace;this.clock=clock;}
    public Inbox view(){
        String scope=workspace.id();if(scope==null)return new Inbox(clock.instant(),List.of(),0,0,0,0,0);
        var rows=jdbc.query("""
            SELECT j.brief_id,b.stock_code,b.report->>'name' AS name,b.agenda,j.state,j.created_at,j.updated_at,
              j.prompt_version,r.first_seen_at,s.summary->>'summary' AS summary,s.summary->>'conclusion' AS conclusion,
              s.id IS NOT NULL AS has_summary,
              (SELECT count(*) FROM committee_opinion o WHERE o.brief_id=j.brief_id AND o.phase='INDEPENDENT') AS independent,
              (SELECT count(*) FROM committee_opinion o WHERE o.brief_id=j.brief_id AND o.phase='DEBATE') AS debate,
              (SELECT count(*) FROM committee_opinion o WHERE o.brief_id=j.brief_id AND o.phase='FINAL') AS finals
            FROM committee_ai_job j JOIN committee_brief b ON b.id=j.brief_id AND b.scope_id=j.scope_id
            LEFT JOIN committee_summary s ON s.brief_id=j.brief_id
            LEFT JOIN committee_result_receipt r ON r.brief_id=j.brief_id AND r.scope_id=j.scope_id
            WHERE j.scope_id=? AND NOT EXISTS(SELECT 1 FROM committee_ai_call c JOIN committee_archived_call a ON a.call_id=c.id WHERE c.job_id=j.id) ORDER BY j.created_at DESC,j.id DESC
            """,(rs,n)->{
                String state=rs.getString("state");boolean complete="COMPLETE".equals(state)&&rs.getBoolean("has_summary");
                var seen=rs.getObject("first_seen_at",OffsetDateTime.class);int total=CommitteeContract.usesDebate(rs.getString("prompt_version"))?25:17;
                int done=rs.getInt("independent")+rs.getInt("finals")+(total==25?rs.getInt("debate"):0)+(rs.getBoolean("has_summary")?1:0);
                return new SavedReview(rs.getLong("brief_id"),rs.getString("stock_code"),rs.getString("name"),rs.getString("agenda"),state,
                    rs.getObject("created_at",OffsetDateTime.class).toInstant(),rs.getObject("updated_at",OffsetDateTime.class).toInstant(),
                    Math.min(done,total),total,complete,complete&&seen==null,seen==null?null:seen.toInstant(),rs.getString("summary"),rs.getString("conclusion"));
            },scope);
        return new Inbox(clock.instant(),rows,(int)rows.stream().filter(SavedReview::active).count(),(int)rows.stream().filter(SavedReview::complete).count(),
            (int)rows.stream().filter(SavedReview::unread).count(),(int)rows.stream().filter(r->!r.complete()&&!r.active()&&!"CLOSED".equals(r.state())).count(),(int)rows.stream().filter(r->"CLOSED".equals(r.state())).count());
    }
    public void acknowledge(long brief){
        String scope=workspace.id();if(scope==null)throw new IllegalArgumentException("기록 확인 필요");
        // One statement scopes ownership and requires the actual completed summary; retries are idempotent.
        jdbc.update("""
            INSERT INTO committee_result_receipt(scope_id,brief_id,first_seen_at)
            SELECT j.scope_id,j.brief_id,? FROM committee_ai_job j JOIN committee_summary s ON s.brief_id=j.brief_id
            JOIN committee_brief b ON b.id=j.brief_id AND b.scope_id=j.scope_id
            WHERE j.scope_id=? AND j.brief_id=? AND j.state='COMPLETE'
            ON CONFLICT DO NOTHING
            """,clock.instant().atOffset(ZoneOffset.UTC),scope,brief);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_result_receipt WHERE scope_id=? AND brief_id=?)",Boolean.class,scope,brief)))
            throw new IllegalArgumentException("완료된 검토만 확인 표시할 수 있어요.");
    }
    public record SavedReview(long briefId,String code,String name,String agenda,String state,Instant createdAt,Instant updatedAt,
        int doneSteps,int totalSteps,boolean complete,boolean unread,Instant seenAt,String summary,String conclusion){
        public boolean active(){return Set.of("QUEUED","RUNNING","FINALIZING").contains(state);}
    }
    public record Inbox(Instant checkedAt,List<SavedReview> reviews,int activeCount,int completedCount,int unreadCount,int pausedCount,int closedCount){}
}
