package com.personaltrader.education;

import com.personaltrader.orders.LegacyPortfolioHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service
public class LearningReadinessService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LegacyPortfolioHistory execution;
    public LearningReadinessService(JdbcTemplate jdbc, Clock clock, LegacyPortfolioHistory execution) {
        this.jdbc = jdbc; this.clock = clock; this.execution = execution;
    }

    @Transactional(readOnly = true)
    public Readiness current() { return at(clock.instant()); }

    public Readiness at(Instant cutoff) {
        var time = cutoff.atOffset(ZoneOffset.UTC);
        long quotes = jdbc.queryForObject("SELECT COUNT(*) FROM market_data_sample WHERE state = 'FRESH' AND received_at <= ?", Long.class, time);
        long observationDays = jdbc.queryForObject("SELECT COUNT(DISTINCT (trade_at AT TIME ZONE 'Asia/Seoul')::date) "
                + "FROM market_data_sample WHERE state = 'FRESH' AND received_at <= ?", Long.class, time);
        long bars = jdbc.queryForObject("SELECT COUNT(*) FROM daily_market_snapshot WHERE asset_kind = 'STOCK' AND received_at <= ?", Long.class, time);
        long historyDays = jdbc.queryForObject("SELECT COUNT(DISTINCT business_date) FROM daily_market_snapshot WHERE asset_kind = 'STOCK' AND received_at <= ?", Long.class, time);
        long instruments = jdbc.queryForObject("SELECT COUNT(DISTINCT instrument_code) FROM daily_market_snapshot WHERE asset_kind = 'STOCK' AND received_at <= ?", Long.class, time);
        long meetings = jdbc.queryForObject("SELECT COUNT(*) FROM bot_meeting WHERE occurred_at <= ?", Long.class, time);
        long closed = execution.closedTradeCount(cutoff);
        // A complete, broker-confirmed round trip has a cost-estimated outcome. Partial sell observations are not independent trades.
        long labeled = closed;
        String environment = execution.environment().name().equals("LIVE") ? "실전" : "모의";
        return new Readiness("DATA_COLLECTION", cutoff, quotes, observationDays, bars, historyDays, instruments,
                meetings, closed, labeled, false, false, List.of(
                "일봉 행 수에는 같은 날짜의 반복 조회가 포함돼요. 독립적인 학습 사례 수가 아니에요.",
                "수집한 과거 일봉 기간과 실제 장중 관찰 일수는 달라요. 당시 전체 종목 선택·공시를 재현할 자료는 부족해요.",
                "종료 거래와 손익 표본은 현재 " + environment + " 환경·계좌의 증권사 확인 체결만 집계해요. 과거 내부 모의·합성 체결은 섞지 않아요.",
                "부분 매도 기록 여러 건을 학습 사례 여러 개로 세지 않아요. 매수 체결 수량을 모두 매도한 왕복 거래만 종료 사례로 세며, 비용과 보상은 계좌 요율에 따른 추정치예요.",
                closed == 0 ? "완전히 종료된 증권사 확인 거래가 없어 비용 후 보상 표본이 없어요." : "거래 수만으로 학습 가능 여부를 확정하지 않아요. 독립 표본·시장 국면·비용·입력 시각 검토가 필요해요.",
                "뉴스·거래일·계좌 비용 검증과 충분한 shadow 제안·행동 결과 기록이 필요해요.",
                "강화학습 환경과 학습/검증 구간은 미구현이에요. 모델 훈련과 운영 정책 자동 변경은 꺼져 있어요."
        ));
    }
    public record Readiness(String state, Instant asOf, long freshQuoteSamples, long observationDays,
                            long stockHistoryRows, long stockHistoryDays, long historyInstruments,
                            long meetingCount, long closedTrades, long labeledTrades,
                            boolean trainingEnabled, boolean autoPromotionEnabled, List<String> limitations) {}
}
