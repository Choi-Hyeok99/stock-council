package com.personaltrader.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only index observations. Provider timestamps are never replaced with fetch timestamps. */
@Service
public class MarketIndexCollector {
    private static final Map<String, String> NAMES = Map.of("0001", "KOSPI", "1001", "KOSDAQ");
    private final KisMarketDataClient client;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long intervalMs;
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private volatile Instant cycleStartedAt, cycleCompletedAt;
    private volatile boolean collecting;
    private LocalDate closeCollectedDate;

    public MarketIndexCollector(KisMarketDataClient client, JdbcTemplate jdbc, Clock clock,
                                @Value("${kis.index-interval-ms:30000}") long intervalMs) {
        if (intervalMs < 30000) throw new IllegalArgumentException("지수 조회 간격은 최소 30초");
        this.client = client; this.jdbc = jdbc; this.clock = clock; this.intervalMs = intervalMs;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public synchronized void collect() {
        if (!client.configured()) return;
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(TimedMarketQuote.SEOUL);
        boolean weekday = local.getDayOfWeek() != DayOfWeek.SATURDAY && local.getDayOfWeek() != DayOfWeek.SUNDAY;
        LocalTime time = local.toLocalTime();
        boolean window = weekday && !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(15, 45));
        boolean closing = window && !time.isBefore(LocalTime.of(15, 40)) && !local.toLocalDate().equals(closeCollectedDate);
        if (cycleStartedAt != null && !window) return;
        if (cycleStartedAt != null && !closing && Duration.between(cycleStartedAt, now).toMillis() < intervalMs) return;
        cycleStartedAt = now; cycleCompletedAt = null; collecting = true;
        try {
            for (String code : List.of("0001", "1001")) collectOne(code);
            if (closing) closeCollectedDate = local.toLocalDate();
        } finally { cycleCompletedAt = clock.instant(); collecting = false; }
    }

    private void collectOne(String code) {
        Instant attempt = clock.instant();
        MarketIndexQuote quote = null;
        String note = client.domainLabel() + " · 날짜·시각이 포함된 지수 분봉 조회";
        String error = null;
        try { quote = client.indexMinutes(code); }
        catch (RuntimeException ignored) {
            // Either environment can lose its minute endpoint. Date-only observations are never trade-fresh.
            note = client.domainLabel() + " · 분봉 조회·시각 검증 실패 → 지수 일봉 조회 · 지수 기준 시각 미확인";
            try { quote = client.indexLatestDay(code); }
            catch (RuntimeException failure) { error = "지수 분봉·일봉 조회 또는 값·날짜 검증 실패"; }
        }
        Result previous = results.get(code);
        try {
            Long sampleId = jdbc.queryForObject("""
                    INSERT INTO market_index_sample (index_code, attempted_at, received_at, business_date,
                        basis_at, index_value, previous_close, basis_kind, source, state, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, code, utc(attempt), quote == null ? null : utc(quote.receivedAt()),
                    quote == null ? null : quote.businessDate(), quote == null ? null : utc(quote.basisAt()),
                    quote == null ? null : quote.value(), quote == null ? null : quote.previousClose(),
                    quote == null ? null : quote.basisKind(), quote == null ? null : quote.source(),
                    quote == null ? "ERROR" : quote.basisKind(), error == null ? note : error);
            if (quote != null) checkpoint(sampleId, quote);
        } catch (RuntimeException ignored) { error = "지수 또는 장 시작·마감 기록 저장 실패"; }
        results.put(code, new Result(quote == null && previous != null ? previous.quote() : quote, attempt, note, error));
    }

    private void checkpoint(Long id, MarketIndexQuote quote) {
        ZonedDateTime local = quote.receivedAt().atZone(TimedMarketQuote.SEOUL);
        if (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY
                || !quote.businessDate().equals(local.toLocalDate())) return;
        LocalTime time = local.toLocalTime();
        LocalTime basis = quote.basisAt() == null ? null : quote.basisAt().atZone(TimedMarketQuote.SEOUL).toLocalTime();
        String phase = null;
        if (basis != null && !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(9, 5))
                && !basis.isBefore(LocalTime.of(9, 0)) && !basis.isAfter(LocalTime.of(9, 5))) phase = "OPEN";
        if (!time.isBefore(LocalTime.of(15, 40)) && (basis == null || !basis.isBefore(LocalTime.of(15, 30)))) phase = "CLOSE";
        if (phase == null) return;
        jdbc.update("""
                INSERT INTO market_index_checkpoint (report_date, index_code, phase, recorded_at, basis_kind, sample_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (report_date, index_code, phase) DO NOTHING
                """, local.toLocalDate(), quote.code(), phase, utc(quote.receivedAt()), quote.basisKind(), id);
    }

    public Snapshot snapshot() {
        Instant now = clock.instant();
        List<IndexView> indices = List.of(view("0001", now), view("1001", now));
        LocalDate today = now.atZone(TimedMarketQuote.SEOUL).toLocalDate();
        List<Checkpoint> checkpoints = jdbc.query("""
                SELECT c.index_code, c.phase, c.recorded_at, s.index_value, s.business_date,
                       s.basis_at, s.received_at, s.basis_kind, s.source
                FROM market_index_checkpoint c JOIN market_index_sample s ON s.id = c.sample_id
                WHERE c.report_date = ? ORDER BY c.phase DESC, c.index_code
                """, (rs, row) -> new Checkpoint(rs.getString(1), NAMES.get(rs.getString(1)), rs.getString(2),
                rs.getTimestamp(3).toInstant(), rs.getBigDecimal(4), rs.getObject(5, LocalDate.class),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(),
                rs.getString(8), rs.getString(9)), today);
        return new Snapshot(indices, checkpoints, today, intervalMs / 1000, cycleStartedAt, cycleCompletedAt,
                nextDue(now), collecting, "장중 목표 " + intervalMs / 1000 + "초 · 09:00–09:05 시작 관측 / 15:40 이후 마감 관측 저장 · 거래일 확인 상태는 투자 레이더/운영 화면에 별도 표시");
    }

    private IndexView view(String code, Instant now) {
        Result result = results.get(code);
        MarketIndexQuote quote = result == null ? null : result.quote();
        boolean fresh = quote != null && result.error() == null && quote.fresh(now);
        String state, label, detail;
        if (!client.configured()) { state = "UNCONFIGURED"; label = "키 설정 필요"; detail = "KIS 연결 설정 대기"; }
        else if (result != null && result.error() != null) { state = "ERROR"; label = "조회·저장 실패"; detail = result.error() + " · 표시 값은 참고용"; }
        else if (quote == null) { state = "WAITING"; label = "지수 조회 대기"; detail = "실제 KIS 응답 대기"; }
        else if (!quote.businessDate().equals(now.atZone(TimedMarketQuote.SEOUL).toLocalDate())) {
            state = "PREVIOUS_SESSION"; label = "과거 거래일 값"; detail = "제공된 기준일 " + quote.businessDate() + " · 당일 지수로 사용하지 않습니다.";
        } else if (fresh) { state = "FRESH"; label = "시각 검증 통과"; detail = result.note(); }
        else if (quote.basisAt() == null) { state = "DATE_ONLY"; label = "당일 값 · 기준 시각 미확인"; detail = result.note(); }
        else if (TimedMarketQuote.regularSession(now)) { state = "STALE"; label = "갱신 지연"; detail = "120초 초과 · 시장 판단 사용 불가"; }
        else { state = "CLOSED"; label = "당일 장외 참고 값"; detail = "정규장 밖 · 실제 기준 시각을 확인해 주세요."; }
        return new IndexView(code, NAMES.get(code), quote, state, label, detail, result == null ? null : result.attempt(), fresh,
                quote == null || quote.basisAt() == null ? null : Math.max(0, Duration.between(quote.basisAt(), now).getSeconds()),
                quote == null ? null : Math.max(0, Duration.between(quote.receivedAt(), now).getSeconds()));
    }
    private Instant nextDue(Instant now) {
        ZonedDateTime local = now.atZone(TimedMarketQuote.SEOUL);
        if (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY
                || local.toLocalTime().isBefore(LocalTime.of(9, 0)) || local.toLocalTime().isAfter(LocalTime.of(15, 45))) return null;
        return cycleStartedAt == null ? now : cycleStartedAt.plusMillis(intervalMs);
    }
    private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private record Result(MarketIndexQuote quote, Instant attempt, String note, String error) {}
    public record IndexView(String code, String name, MarketIndexQuote quote, String state, String label,
                            String detail, Instant lastAttemptAt, boolean fresh, Long ageSeconds, Long receivedAgeSeconds) {}
    public record Checkpoint(String code, String name, String phase, Instant recordedAt, BigDecimal value,
                             LocalDate businessDate, Instant basisAt, Instant receivedAt, String basisKind, String source) {}
    public record Snapshot(List<IndexView> indices, List<Checkpoint> checkpoints, LocalDate reportDate,
                           long targetIntervalSeconds, Instant cycleStartedAt, Instant cycleCompletedAt,
                           Instant nextDueAt, boolean collecting, String policy) {}
}
