package com.personaltrader.committee;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Read-only app usage estimates. Does not fetch billing, reserve calls or start reviews. */
@Service
public class CommitteeCostDashboard {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OpenAiCredentials credentials;
    public CommitteeCostDashboard(JdbcTemplate jdbc, Clock clock, OpenAiCredentials credentials) {
        this.jdbc=jdbc; this.clock=clock; this.credentials=credentials;
    }
    private static final String TOTALS="""
        SELECT COUNT(*) AS reservations,
          COUNT(*) FILTER(WHERE submitted_at IS NOT NULL) AS submitted,
          COUNT(*) FILTER(WHERE state='SETTLED') AS measured,
          COUNT(*) FILTER(WHERE state IN ('RESERVED','SENT','UNKNOWN') AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=committee_ai_call.id)) AS unresolved,
          COALESCE(SUM(cost_usd),0) AS cost,
          COALESCE(SUM(input_tokens),0) AS input,
          COALESCE(SUM(output_tokens),0) AS output,
          COALESCE(SUM(cached_tokens),0) AS cached
        FROM committee_ai_call
        """;
    private Totals totals(String where,Object... args) {
        return jdbc.queryForObject(TOTALS+where,(r,n)->new Totals(r.getLong("reservations"),r.getLong("submitted"),
            r.getLong("measured"),r.getLong("unresolved"),r.getBigDecimal("cost"),r.getLong("input"),
            r.getLong("output"),r.getLong("cached")),args);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View view() {
        Instant now=clock.instant(); LocalDate month=now.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate today=now.atZone(CommitteeAiLedger.SEOUL).toLocalDate();
        var amounts=jdbc.queryForList("SELECT committed_usd FROM committee_ai_budget_month WHERE month_start=?",BigDecimal.class,month);
        BigDecimal committed=amounts.isEmpty()?BigDecimal.ZERO:amounts.getFirst();
        Totals monthly=totals(" WHERE month_start=?",month);
        Totals daily=totals(" WHERE created_at>=? AND created_at<?",today.atStartOfDay(CommitteeAiLedger.SEOUL).toOffsetDateTime(),
            today.plusDays(1).atStartOfDay(CommitteeAiLedger.SEOUL).toOffsetDateTime());
        var models=jdbc.query("""
            SELECT model,COUNT(*) FILTER(WHERE submitted_at IS NOT NULL),COUNT(*) FILTER(WHERE state='SETTLED'),
              COALESCE(SUM(cost_usd),0),COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0)
            FROM committee_ai_call WHERE month_start=? GROUP BY model ORDER BY SUM(cost_usd) DESC NULLS LAST,model
            """,(r,n)->new ModelUsage(r.getString(1),r.getLong(2),r.getLong(3),r.getBigDecimal(4),r.getLong(5),r.getLong(6)),month);
        var days=jdbc.query("""
            SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,COALESCE(SUM(cost_usd),0),
              COUNT(*) FILTER(WHERE submitted_at IS NOT NULL)
            FROM committee_ai_call WHERE month_start=?
            GROUP BY day ORDER BY day DESC LIMIT 14
            """,(r,n)->new DayUsage(r.getObject(1,LocalDate.class),r.getBigDecimal(2),r.getLong(3)),month);
        var recent=jdbc.query("""
            SELECT created_at,model,role_key,phase,CASE WHEN EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=committee_ai_call.id) THEN 'ARCHIVED' ELSE state END,cost_usd,reserved_usd,input_tokens,output_tokens
            FROM committee_ai_call WHERE month_start=? ORDER BY id DESC LIMIT 20
            """,(r,n)->new Call(r.getObject(1,OffsetDateTime.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                r.getBigDecimal(6),r.getBigDecimal(7),(Integer)r.getObject(8),(Integer)r.getObject(9)),month);
        return new View(now,credentials.available(),month,month.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC),
            today,CommitteeAiLedger.CEILING,committed,committed.subtract(monthly.costUsd()).max(BigDecimal.ZERO),
            CommitteeAiLedger.CEILING.subtract(committed).max(BigDecimal.ZERO),monthly,daily,models,days,recent,
            "ADMIN_KEY_REQUIRED",null,null,
            "이 앱이 저장한 호출의 토큰 사용량 × 설정 단가로 계산한 비용이에요. OpenAI 청구액·충전 잔액과는 달라요. 실패한 답변도 확인된 토큰 비용은 포함하며, 미확정 호출은 예약액을 남겨둬요.",
            "OpenAI 공식 비용 조회에는 별도 Admin API 키가 필요해요. 현재 공식 청구액과 크레딧 잔액은 자동 조회하지 않아요.");
    }
    public record Totals(long reservations,long submittedCalls,long measuredCalls,long unresolvedCalls,BigDecimal costUsd,
        long inputTokens,long outputTokens,long cachedTokens) {}
    public record ModelUsage(String model,long submittedCalls,long measuredCalls,BigDecimal costUsd,long inputTokens,long outputTokens) {}
    public record DayUsage(LocalDate day,BigDecimal costUsd,long submittedCalls) {}
    public record Call(OffsetDateTime at,String model,String role,String phase,String state,BigDecimal costUsd,
        BigDecimal reservedUsd,Integer inputTokens,Integer outputTokens) {}
    public record View(Instant checkedAt,boolean configured,LocalDate month,Instant resetsAt,LocalDate today,
        BigDecimal ceilingUsd,BigDecimal committedUsd,BigDecimal reservedUsd,BigDecimal remainingUsd,Totals monthly,Totals daily,
        List<ModelUsage> models,List<DayUsage> days,List<Call> recent,String providerStatus,BigDecimal providerCostUsd,
        BigDecimal providerCreditBalanceUsd,String calculationNote,String providerNote) {}
}
