package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;

public record TimedMarketQuote(String code, BigDecimal price, Instant tradeAt,
                              Instant barAt, Instant receivedAt) {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final long MAX_AGE_SECONDS = 120;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    public static TimedMarketQuote parse(String code, JsonNode trades, JsonNode bars, Instant receivedAt) {
        try {
            if (!trades.isArray() || trades.isEmpty() || !bars.isArray() || bars.isEmpty()) {
                throw new IllegalArgumentException("체결·분봉 데이터가 없습니다.");
            }
            JsonNode latestBar = null;
            LocalDateTime barTime = null;
            for (JsonNode bar : bars) {
                LocalDateTime value = LocalDateTime.of(
                        LocalDate.parse(bar.path("stck_bsop_date").asText(), DateTimeFormatter.BASIC_ISO_DATE),
                        LocalTime.parse(bar.path("stck_cntg_hour").asText(), TIME));
                if (barTime == null || value.isAfter(barTime)) { barTime = value; latestBar = bar; }
            }
            JsonNode latestTrade = null;
            LocalTime tradeTime = null;
            for (JsonNode trade : trades) {
                LocalTime value = LocalTime.parse(trade.path("stck_cntg_hour").asText(), TIME);
                if (tradeTime == null || value.isAfter(tradeTime)) { tradeTime = value; latestTrade = trade; }
            }
            BigDecimal price = new BigDecimal(latestTrade.path("stck_prpr").asText());
            BigDecimal barPrice = new BigDecimal(latestBar.path("stck_prpr").asText());
            if (price.signum() <= 0 || barPrice.signum() <= 0) throw new IllegalArgumentException();
            // The trade endpoint contains time only. Its date is corroborated by the same symbol's minute bars.
            TimedMarketQuote quote = new TimedMarketQuote(code, price,
                    barTime.toLocalDate().atTime(tradeTime).atZone(SEOUL).toInstant(),
                    barTime.atZone(SEOUL).toInstant(), receivedAt);
            String problem = quote.freshnessProblem(receivedAt);
            if (problem != null) throw new IllegalArgumentException(problem);
            return quote;
        } catch (java.time.DateTimeException | NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException("가격 또는 기준 날짜·시각이 올바르지 않습니다.");
        }
    }

    public static boolean regularSession(Instant now) {
        ZonedDateTime local = now.atZone(SEOUL);
        return local.getDayOfWeek() != DayOfWeek.SATURDAY && local.getDayOfWeek() != DayOfWeek.SUNDAY
                && !local.toLocalTime().isBefore(LocalTime.of(9, 0))
                && local.toLocalTime().isBefore(LocalTime.of(15, 30));
    }

    public String freshnessProblem(Instant now) {
        if (!regularSession(now)) return "정규장 수집 시간 밖입니다. 체결을 허용하지 않습니다.";
        LocalDate today = now.atZone(SEOUL).toLocalDate();
        if (!tradeAt.atZone(SEOUL).toLocalDate().equals(today)
                || !barAt.atZone(SEOUL).toLocalDate().equals(today)) return "이전 기준일 시세입니다.";
        if (!regularSession(tradeAt) || !regularSession(barAt)) return "장외 시각의 시세입니다.";
        for (Instant time : new Instant[]{tradeAt, barAt, receivedAt}) {
            if (time.isAfter(now)) return "미래 시각의 시세입니다.";
            if (Duration.between(time, now).compareTo(Duration.ofSeconds(MAX_AGE_SECONDS)) > 0)
                return "시세가 120초를 초과했습니다.";
        }
        return null;
    }
}
