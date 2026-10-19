package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Latest provider observation, separate from completed-day strategy averages. */
public record MarketIndexQuote(String code, BigDecimal value, BigDecimal previousClose, BigDecimal change,
                               BigDecimal changeRate, LocalDate businessDate, Instant basisAt,
                               Instant receivedAt, String basisKind, String source) {
    public static MarketIndexQuote minutes(String code, JsonNode response, Instant receivedAt) {
        return minutes(code, response, receivedAt, "KIS_VTS_INDEX_MINUTE");
    }
    public static MarketIndexQuote minutes(String code, JsonNode response, Instant receivedAt, String source) {
        JsonNode rows = response.path("output2");
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("지수 분봉 없음");
        JsonNode latest = null; LocalDateTime latestTime = null;
        for (JsonNode row : rows) {
            if (blank(row)) continue;
            LocalDate date = date(row.path("stck_bsop_date").asText());
            String hour = row.path("stck_cntg_hour").asText();
            if (!hour.matches("[0-9]{6}")) throw new IllegalArgumentException("지수 시각 누락");
            LocalTime time = LocalTime.of(Integer.parseInt(hour.substring(0, 2)), Integer.parseInt(hour.substring(2, 4)), Integer.parseInt(hour.substring(4, 6)));
            LocalDateTime candidate = date.atTime(time);
            if (candidate.atZone(TimedMarketQuote.SEOUL).toInstant().isAfter(receivedAt)) throw new IllegalArgumentException("미래 지수 시각");
            if (time.isBefore(LocalTime.of(9, 0)) || time.isAfter(LocalTime.of(15, 30))) continue;
            positive(row.path("bstp_nmix_prpr"));
            if (latestTime == null || candidate.isAfter(latestTime)) { latestTime = candidate; latest = row; }
        }
        if (latest == null) throw new IllegalArgumentException("정규장 지수 분봉 없음");
        return make(code, positive(latest.path("bstp_nmix_prpr")), optionalPositive(response.path("output1").path("prdy_nmix")),
                latestTime.toLocalDate(), latestTime.atZone(TimedMarketQuote.SEOUL).toInstant(), receivedAt,
                "DATED_MINUTE", source);
    }

    public static MarketIndexQuote daily(String code, JsonNode response, Instant receivedAt) {
        return daily(code, response, receivedAt, "KIS_VTS_INDEX_DAILY");
    }
    public static MarketIndexQuote daily(String code, JsonNode response, Instant receivedAt, String source) {
        JsonNode rows = response.path("output2");
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("지수 날짜 없음");
        LocalDate today = receivedAt.atZone(TimedMarketQuote.SEOUL).toLocalDate();
        JsonNode latest = null; LocalDate latestDate = null;
        for (JsonNode row : rows) {
            if (blank(row)) continue;
            LocalDate candidate = date(row.path("stck_bsop_date").asText());
            if (candidate.isAfter(today)) throw new IllegalArgumentException("미래 지수 날짜");
            positive(row.path("bstp_nmix_prpr"));
            if (latestDate == null || candidate.isAfter(latestDate)) { latestDate = candidate; latest = row; }
        }
        if (latest == null) throw new IllegalArgumentException("지수 일봉 없음");
        return make(code, positive(latest.path("bstp_nmix_prpr")), optionalPositive(response.path("output1").path("prdy_nmix")),
                latestDate, null, receivedAt, "DATE_ONLY", source);
    }
    private static MarketIndexQuote make(String code, BigDecimal value, BigDecimal previous, LocalDate date,
                                          Instant basis, Instant received, String kind, String source) {
        BigDecimal change = previous == null ? null : value.subtract(previous);
        BigDecimal rate = previous == null ? null : change.multiply(new BigDecimal("100")).divide(previous, 2, RoundingMode.HALF_UP);
        return new MarketIndexQuote(code, value, previous, change, rate, date, basis, received, kind, source);
    }
    private static boolean blank(JsonNode row) {
        return row.isObject() && !row.isEmpty() && row.properties().stream().allMatch(entry -> entry.getValue().asText().isBlank());
    }
    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("지수 날짜 누락");
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static BigDecimal positive(JsonNode node) {
        String text = node.asText();
        if (!text.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) throw new IllegalArgumentException("지수 값 형식 오류");
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0) throw new IllegalArgumentException("지수 값 오류");
        return value;
    }
    private static BigDecimal optionalPositive(JsonNode node) {
        try { return positive(node); } catch (IllegalArgumentException ignored) { return null; }
    }
    public boolean fresh(Instant now) {
        return TimedMarketQuote.regularSession(now) && basisAt != null
                && businessDate.equals(now.atZone(TimedMarketQuote.SEOUL).toLocalDate())
                && !basisAt.isAfter(now) && !receivedAt.isAfter(now)
                && Duration.between(basisAt, now).getSeconds() <= 120 && Duration.between(receivedAt, now).getSeconds() <= 120;
    }
}
