package com.personaltrader.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.personaltrader.research.KrxTradingCalendarClient.*;

/** Persisted official monthly calendar, refreshed each KST day. GET views have no network effects. */
@Service
public class TradingCalendarService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final KrxTradingCalendarClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public TradingCalendarService(KrxTradingCalendarClient client, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
                                  PlatformTransactionManager manager) {
        this.client = client; this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
        tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    public synchronized void refreshDue() {
        ZonedDateTime now = clock.instant().atZone(SEOUL);
        if (now.toLocalTime().isBefore(LocalTime.of(6, 30))) return;
        LocalDate date = now.toLocalDate();
        int claimed = jdbc.update("""
            INSERT INTO market_calendar_refresh(refresh_date,attempted_at,attempts,state)
            VALUES (?,?,1,'IN_PROGRESS') ON CONFLICT (refresh_date) DO UPDATE
            SET attempted_at=EXCLUDED.attempted_at,completed_at=NULL,attempts=market_calendar_refresh.attempts+1,
                state='IN_PROGRESS',issue=NULL
            WHERE market_calendar_refresh.state <> 'OK' AND market_calendar_refresh.attempts < 3
              AND market_calendar_refresh.attempted_at <= ?
            """, date, utc(now.toInstant()), utc(now.toInstant().minus(Duration.ofMinutes(15))));
        if (claimed == 0) return;
        try {
            CalendarMonth received = client.fetch(YearMonth.from(date));
            Instant completed = clock.instant();
            if (!completed.atZone(SEOUL).toLocalDate().equals(date)) throw new IllegalStateException("달력 조회 날짜 변경");
            String days = mapper.writeValueAsString(received.days());
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO market_calendar_snapshot(basis_month,fetched_at,source,content_hash,days) VALUES (?,?,?,?,?::jsonb)",
                    received.month().toString(), utc(completed), SOURCE, received.contentHash(), days);
                jdbc.update("UPDATE market_calendar_refresh SET state='OK',completed_at=?,issue=NULL WHERE refresh_date=?",
                    utc(completed), date);
            });
        } catch (Exception failure) {
            jdbc.update("UPDATE market_calendar_refresh SET state='ERROR',completed_at=?,issue=? WHERE refresh_date=?",
                utc(clock.instant()), "공식 달력 조회·날짜 검증 실패 · 신규 매수 대기", date);
        }
    }

    public View view() {
        Instant now = clock.instant(); LocalDate today = now.atZone(SEOUL).toLocalDate();
        List<Snapshot> stored = jdbc.query("SELECT basis_month,fetched_at,source,days FROM market_calendar_snapshot ORDER BY fetched_at DESC,id DESC LIMIT 1",
            (rs, row) -> {
                try {
                    var days = mapper.readValue(rs.getString(4), new TypeReference<List<Day>>() {});
                    return new Snapshot(YearMonth.parse(rs.getString(1)), rs.getTimestamp(2).toInstant(), rs.getString(3), days);
                } catch (Exception failure) { throw new IllegalStateException("저장 달력 검증 실패"); }
            });
        List<Refresh> attempts = jdbc.query("SELECT attempted_at,attempts,state,issue FROM market_calendar_refresh WHERE refresh_date=?",
            (rs, row) -> new Refresh(rs.getTimestamp(1).toInstant(), rs.getInt(2), rs.getString(3), rs.getString(4)), today);
        Snapshot snapshot = stored.isEmpty() ? null : stored.getFirst();
        Refresh attempt = attempts.isEmpty() ? null : attempts.getFirst();
        Day day = snapshot == null ? null : snapshot.days().stream().filter(d -> d.date().equals(today)).findFirst().orElse(null);
        boolean validated = snapshot != null && SOURCE.equals(snapshot.source()) && snapshot.month().equals(YearMonth.from(today))
            && snapshot.days().size() == snapshot.month().lengthOfMonth() && !snapshot.fetchedAt().isAfter(now)
            && snapshot.fetchedAt().atZone(SEOUL).toLocalDate().equals(today)
            && attempt != null && attempt.state().equals("OK") && day != null && day.scheduleReviewed();
        String state = validated ? day.closed() ? "CLOSED" : "OPEN_DAY" : snapshot == null ? "WAITING" : "UNVERIFIED";
        String issue = attempt == null ? null : attempt.issue();
        if (day != null && !day.scheduleReviewed()) issue = "특수장/미분류 일정: " + day.reason() + " · 시간표 확인 전 신규 매수 대기";
        if (!validated && issue == null) issue = "오늘 공식 달력 갱신 확인 전 · 신규 매수 대기";
        Instant next = attempt != null && !attempt.state().equals("OK") && attempt.attempts() < 3
            ? attempt.attemptedAt().plus(Duration.ofMinutes(15))
            : today.plusDays(attempt == null ? 0 : 1).atTime(6, 30).atZone(SEOUL).toInstant();
        return new View(state, validated, day != null && day.closed(), day == null ? null : day.reason(),
            SOURCE, snapshot == null ? null : snapshot.fetchedAt(), snapshot == null ? null : snapshot.month().atDay(1),
            snapshot == null ? null : snapshot.month().atEndOfMonth(), snapshot == null ? List.of() : snapshot.days().stream().filter(Day::closed).toList(),
            issue, next, "KIND 현물주식 월별 거래 달력 · 06:30 이후 하루 1회 성공 조회, 실패 시 15분 간격 최대 3회 · 연간 전체/특수 개장 시간표 아님");
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private record Snapshot(YearMonth month, Instant fetchedAt, String source, List<Day> days) {}
    private record Refresh(Instant attemptedAt, int attempts, String state, String issue) {}
    public record View(String state, boolean verifiedToday, boolean closed, String reason, String source, Instant fetchedAt,
                       LocalDate coveredFrom, LocalDate coveredThrough, List<Day> closedDays, String issue, Instant nextRefreshAt, String policy) {}
}
