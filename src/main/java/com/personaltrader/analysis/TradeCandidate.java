package com.personaltrader.analysis;

import java.math.BigDecimal;

public record TradeCandidate(
        String code,
        String name,
        BigDecimal lastPrice,
        BigDecimal fiveDayAverage,
        BigDecimal twentyDayAverage,
        boolean marketPass,
        boolean stockPass,
        boolean riskPass,
        int suggestedQuantity,
        BigDecimal estimatedLoss,
        String reason
) {
    public boolean eligible() {
        return marketPass && stockPass && riskPass;
    }
}
