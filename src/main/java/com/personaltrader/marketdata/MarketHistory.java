package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Only completed sessions enter averages. Today's forming candle is always excluded. */
public record MarketHistory(LocalDate basisDate, BigDecimal lastClose, BigDecimal average5,
                            BigDecimal average20, BigDecimal averageTurnover20, int completedSessions,
                            String turnoverUnit, Instant receivedAt, @JsonIgnore List<CompletedBar> bars) {
    public static MarketHistory parse(JsonNode rows, Instant now, boolean index) {
        if (!rows.isArray()) throw new IllegalArgumentException("일봉 응답 형식 오류");
        LocalDate today = now.atZone(TimedMarketQuote.SEOUL).toLocalDate();
        TreeMap<LocalDate, CompletedBar> days = new TreeMap<>();
        for (JsonNode row : rows) {
            // Some KIS responses pad the array with empty records.
            if (row.path("stck_bsop_date").asText().isBlank()) {
                if (row.isObject() && !row.isEmpty() && row.properties().stream()
                        .allMatch(entry -> entry.getValue().asText().isBlank())) continue;
                throw new IllegalArgumentException("일봉 기준일 누락");
            }
            LocalDate date = LocalDate.parse(row.path("stck_bsop_date").asText(), DateTimeFormatter.BASIC_ISO_DATE);
            if (date.isAfter(today)) throw new IllegalArgumentException("미래 기준일 일봉");
            if (!date.isBefore(today)) continue;
            BigDecimal close = new BigDecimal(row.path(index ? "bstp_nmix_prpr" : "stck_clpr").asText());
            BigDecimal open = new BigDecimal(row.path(index ? "bstp_nmix_oprc" : "stck_oprc").asText());
            BigDecimal high = new BigDecimal(row.path(index ? "bstp_nmix_hgpr" : "stck_hgpr").asText());
            BigDecimal low = new BigDecimal(row.path(index ? "bstp_nmix_lwpr" : "stck_lwpr").asText());
            BigDecimal volume = new BigDecimal(row.path("acml_vol").asText());
            BigDecimal turnover = new BigDecimal(row.path("acml_tr_pbmn").asText());
            if (close.signum() <= 0 || low.signum() <= 0 || low.compareTo(high) > 0 || open.compareTo(low) < 0
                    || open.compareTo(high) > 0 || close.compareTo(low) < 0 || close.compareTo(high) > 0
                    || volume.signum() < 0 || turnover.signum() < 0)
                throw new IllegalArgumentException("일봉 가격·거래량 오류");
            if (days.putIfAbsent(date, new CompletedBar(date, open, high, low, close, volume, turnover)) != null)
                throw new IllegalArgumentException("중복 일봉 기준일");
        }
        if (days.size() < 20) throw new IllegalArgumentException("확정 일봉 20개 미만");
        if (days.lastKey().isBefore(today.minusDays(7))) throw new IllegalArgumentException("일봉 기준일이 오래됐습니다.");
        List<CompletedBar> latest20 = new ArrayList<>(days.values()).subList(days.size() - 20, days.size());
        return new MarketHistory(days.lastKey(), days.lastEntry().getValue().close(),
                mean(latest20.subList(15, 20), false), mean(latest20, false), mean(latest20, true), days.size(),
                index ? "INDEX_PROVIDER_UNIT_UNVERIFIED" : "KRW", now, List.copyOf(days.values()));
    }
    private static BigDecimal mean(List<CompletedBar> days, boolean turnover) {
        return days.stream().map(day -> turnover ? day.turnover() : day.close())
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(days.size()), 2, RoundingMode.HALF_UP);
    }
    public boolean uptrend() { return lastClose.compareTo(average20) > 0 && average5.compareTo(average20) > 0; }
    public record CompletedBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                               BigDecimal close, BigDecimal volume, BigDecimal turnover) {}
}
