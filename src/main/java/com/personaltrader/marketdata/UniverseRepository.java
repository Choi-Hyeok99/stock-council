package com.personaltrader.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Repository
public class UniverseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KisMarketDataClient client;
    public UniverseRepository(JdbcTemplate jdbc, ObjectMapper mapper, KisMarketDataClient client) {
        this.jdbc = jdbc; this.mapper = mapper; this.client = client;
    }
    public List<String> heldCodes(){
        return jdbc.queryForList("SELECT DISTINCT stock_code FROM execution_position ORDER BY stock_code LIMIT 10",String.class);
    }
    @Transactional
    public void save(UUID run, Instant started, Instant completed, Instant rankingAt, String state,
                     String detail, List<UniverseService.IndexData> indices,
                     List<UniverseService.ObservedStock> stocks, List<UniverseService.Decision> decisions) {
        try {
            jdbc.update("""
                    INSERT INTO universe_selection_run (id, rule_version, source, started_at, completed_at,
                        ranking_received_at, state, selected_count, market_data, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, run, UniverseService.RULE_VERSION, client.sourcePrefix() + "_REST_AND_KIS_MASTER", utc(started), utc(completed), utc(rankingAt), state,
                    stocks.size(), mapper.writeValueAsString(indices), detail);
            for (UniverseService.Decision decision : decisions) {
                UniverseService.ObservedStock metrics = stocks.stream()
                        .filter(stock -> stock.code().equals(decision.code())).findFirst().orElse(null);
                jdbc.update("""
                        INSERT INTO universe_selection_record (run_id, stock_code, stock_name, decision, reason, metrics)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """, run, decision.code(), decision.name(), decision.state(), decision.reason(),
                        metrics == null ? null : mapper.writeValueAsString(metrics));
            }
            for (UniverseService.IndexData index : indices) saveHistory(run, "INDEX", index.code(), index.history());
            for (UniverseService.ObservedStock stock : stocks) saveHistory(run, "STOCK", stock.code(), stock.history());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("종목 선정 기록 변환 실패"); }
    }
    private void saveHistory(UUID run, String kind, String code, MarketHistory history) {
        jdbc.batchUpdate("""
                INSERT INTO daily_market_snapshot (run_id, asset_kind, instrument_code, business_date,
                    open_price, high_price, low_price, close_price, volume, turnover,
                    turnover_unit, adjusted_price, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, history.bars(), 100, (statement, bar) -> {
            statement.setObject(1, run); statement.setString(2, kind); statement.setString(3, code);
            statement.setObject(4, bar.date()); statement.setBigDecimal(5, bar.open());
            statement.setBigDecimal(6, bar.high()); statement.setBigDecimal(7, bar.low());
            statement.setBigDecimal(8, bar.close()); statement.setBigDecimal(9, bar.volume());
            statement.setBigDecimal(10, bar.turnover()); statement.setString(11, history.turnoverUnit());
            statement.setBoolean(12, kind.equals("STOCK")); statement.setObject(13, utc(history.receivedAt()));
        });
    }
    private OffsetDateTime utc(Instant instant) { return instant == null ? null : instant.atOffset(ZoneOffset.UTC); }
}
