package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

public record RankedStock(String code, String name, BigDecimal price, BigDecimal volume, BigDecimal turnover) {
    public static List<RankedStock> parse(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("순위 데이터 없음");
        Map<String, RankedStock> unique = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String code = row.path("mksc_shrn_iscd").asText();
            String name = row.path("hts_kor_isnm").asText();
            if (!code.matches("[0-9]{6}") || name.isBlank()) throw new IllegalArgumentException("순위 종목 식별 오류");
            RankedStock stock = new RankedStock(code, name, new BigDecimal(row.path("stck_prpr").asText()),
                    new BigDecimal(row.path("acml_vol").asText()), new BigDecimal(row.path("acml_tr_pbmn").asText()));
            if (stock.price.signum() <= 0 || stock.volume.signum() < 0 || stock.turnover.signum() < 0)
                throw new IllegalArgumentException("순위 가격·거래량 오류");
            if (unique.putIfAbsent(code, stock) != null) throw new IllegalArgumentException("순위 중복 종목");
        }
        return unique.values().stream().sorted(Comparator.comparing(RankedStock::turnover).reversed()
                .thenComparing(RankedStock::code)).toList();
    }
}
