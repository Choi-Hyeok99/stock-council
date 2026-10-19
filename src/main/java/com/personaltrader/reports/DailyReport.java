package com.personaltrader.reports;

import com.personaltrader.analysis.BotMeetingRepository;
import com.personaltrader.education.InvestmentKnowledge;
import com.personaltrader.education.LearningReadinessService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record DailyReport(String version, LocalDate reportDate, String slot, String title, Instant scheduledAt,
                          Instant generatedAt, Instant windowStart, String timing, String marketPhase,
                          Account account, Performance today, List<Period> periods, Broker broker,
                          BotMeetingRepository.Entry meeting, LearningReadinessService.Readiness learning,
                          InvestmentKnowledge.Curriculum curriculum, String curriculumHash,
                          List<String> notes, List<String> nextActions,
                          com.personaltrader.insights.AccountInsightsService.View insights,
                          com.personaltrader.research.DisclosureImpactService.View disclosureImpacts) {
    public record Account(BigDecimal cash, BigDecimal realizedNetPnl, BigDecimal cumulativeCosts, int holdings, boolean automationEnabled) {}
    public record Performance(long closedTrades, long wins, BigDecimal netPnl, BigDecimal commission,
                              BigDecimal transactionTax, BigDecimal slippage) {}
    public record Period(String label, int days, long closedTrades, BigDecimal netPnl) {}
    public record Broker(String state, Instant attemptedAt, Instant receivedAt, BigDecimal totalAssets,
                         BigDecimal deposit, BigDecimal stockValue, BigDecimal unrealizedPnl, boolean fresh) {}
}
