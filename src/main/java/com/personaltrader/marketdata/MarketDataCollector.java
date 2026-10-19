package com.personaltrader.marketdata;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;

@Service
public class MarketDataCollector {
    public static final String PROBE_CODE = "005930";
    private final KisMarketDataClient client;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private volatile CollectionResult result = new CollectionResult(null, null, null);

    public MarketDataCollector(KisMarketDataClient client, Clock clock, JdbcTemplate jdbc) {
        this.client = client; this.clock = clock; this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${kis.probe-interval-ms:120000}", initialDelay = 3000)
    public synchronized void collect() {
        if (!client.configured() || !TimedMarketQuote.regularSession(clock.instant())) return;
        Instant attempt = clock.instant();
        try {
            TimedMarketQuote quote = client.collect(PROBE_CODE);
            result = new CollectionResult(quote, attempt, null);
        } catch (RuntimeException exception) {
            result = new CollectionResult(result.quote(), attempt, "시세 조회 또는 날짜·시각 검증에 실패했습니다.");
        }
        Snapshot snapshot = snapshot();
        try {
            // Failed attempts carry no quote values, avoiding duplicate old prices in the research dataset.
            boolean success = snapshot.fresh();
            jdbc.update("""
                    INSERT INTO market_data_sample (stock_code, source, state, price, trade_at, bar_at,
                                                    received_at, attempted_at, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, PROBE_CODE, client.sourcePrefix() + "_REST", snapshot.state(), success ? snapshot.price() : null,
                    success ? OffsetDateTime.ofInstant(snapshot.tradeAt(), ZoneOffset.UTC) : null,
                    success ? OffsetDateTime.ofInstant(snapshot.barAt(), ZoneOffset.UTC) : null,
                    success ? OffsetDateTime.ofInstant(snapshot.receivedAt(), ZoneOffset.UTC) : null,
                    OffsetDateTime.ofInstant(attempt, ZoneOffset.UTC), snapshot.detail());
        } catch (RuntimeException exception) {
            result = new CollectionResult(result.quote(), attempt, "수집 기록 저장에 실패했습니다.");
        }
    }

    public Snapshot snapshot() {
        CollectionResult current = result;
        Instant now = clock.instant();
        TimedMarketQuote quote = current.quote();
        String state;
        String label;
        String detail;
        if (!client.configured()) {
            state = "UNCONFIGURED"; label = "키 설정 필요"; detail = "로컬 KIS 키 설정을 확인해 주세요.";
        } else if (!TimedMarketQuote.regularSession(now)) {
            state = "CLOSED"; label = "수집 시간 밖"; detail = "평일 09:00–15:30에 수집합니다. 마지막 가격은 참고용입니다.";
        } else if (current.error() != null) {
            state = "ERROR"; label = "조회 실패"; detail = current.error() + " 마지막 성공 가격은 체결에 사용할 수 없습니다.";
        } else if (quote == null) {
            state = "WAITING"; label = "첫 시세 대기"; detail = "연결 확인용 종목 · 관찰 목록 전체 시세와 지수는 각각 별도 수집합니다.";
        } else if (quote.freshnessProblem(now) != null) {
            state = "STALE"; label = "시세 사용 불가"; detail = quote.freshnessProblem(now);
        } else {
            state = "FRESH"; label = "시각 검증 통과"; detail = client.domainLabel() + " 응답 · 체결 시각 + 같은 종목 분봉 기준일 확인";
        }
        return new Snapshot(state, label, detail, PROBE_CODE, "삼성전자 · 조회 확인용",
                quote == null ? null : quote.price(), quote == null ? null : quote.tradeAt(),
                quote == null ? null : quote.barAt(), quote == null ? null : quote.receivedAt(),
                current.attempt(), quote == null ? null : Math.max(0, Duration.between(quote.tradeAt(), now).getSeconds()),
                "FRESH".equals(state), false,
                "자동 종목 선정·시세 관찰 단계 · 뉴스·거래일·비용 검증 전 자동 체결 대기");
    }

    private record CollectionResult(TimedMarketQuote quote, Instant attempt, String error) {}
    public record Snapshot(String state, String label, String detail, String code, String name,
                           BigDecimal price, Instant tradeAt, Instant barAt, Instant receivedAt,
                           Instant lastAttemptAt, Long ageSeconds, boolean fresh, boolean executionAllowed,
                           String executionReason) {}
}
