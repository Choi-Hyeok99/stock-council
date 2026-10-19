package com.personaltrader.analysis;

import java.math.BigDecimal;

public record RiskAssessment(
        boolean pass,
        int quantity,
        BigDecimal orderValue,
        BigDecimal estimatedLoss,
        BigDecimal maxAllowedLoss,
        String reason
) {
}
