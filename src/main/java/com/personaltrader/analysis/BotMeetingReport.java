package com.personaltrader.analysis;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record BotMeetingReport(String ruleVersion, String mode, String phase, String conclusion, String summary,
                               Instant selectionAt, int observedCount, int freshQuoteCount,
                               BigDecimal internalCapital, BigDecimal botBudget, boolean automationEnabled,
                               String brokerState, BigDecimal brokerAssets, Instant brokerReceivedAt,
                               String costProfile, List<String> blockers,
                               List<Speaker> speakers, List<StockReview> stocks,
                               com.personaltrader.education.KnowledgeReview.Review education,
                               List<com.personaltrader.marketdata.MarketIndexCollector.IndexView> marketIndices) {
    public record Speaker(String name, String state, String summary, String detail) {}
    public record StockReview(String code, String name, BigDecimal price, String priceLabel,
                              LocalDate dailyBasisDate, BigDecimal average5, BigDecimal average20,
                              Instant tradeAt, Instant receivedAt, boolean fresh,
                              String marketVote, String stockVote, String riskVote, String newsVote,
                              int quantity, BigDecimal estimatedLoss, String decision, String reason,
                              com.personaltrader.marketdata.UniverseService.SelectionRationale selectionRationale) {}
}
