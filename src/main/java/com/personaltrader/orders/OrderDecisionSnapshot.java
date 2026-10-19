package com.personaltrader.orders;

import com.personaltrader.marketdata.TimedMarketQuote;
import com.personaltrader.marketdata.MarketIndexQuote;
import com.personaltrader.research.InvestmentSignalEngine.Signal;
import com.personaltrader.research.InvestmentSignalEngine.NumericCheck;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Whitelisted immutable evidence. Never carries account IDs, broker receipts or credentials. */
public record OrderDecisionSnapshot(String version,String provenance,Instant capturedAt,Instant orderAt,
        String environment,String side,String summary,Signal research,TimedMarketQuote stockQuote,
        MarketIndexQuote marketQuote,BigDecimal marketAverage20,List<NumericCheck>executionChecks,
        Allocation allocation,List<String>counterArguments,List<String>limitations,ExitDecision exitDecision,AdditionDecision additionDecision,com.personaltrader.research.PaperAllocationService.Decision allocationEvidence,com.personaltrader.research.PaperEntryReviewService.Review entryReview) {
    public static final String VERSION="ORDER_DECISION_V6";
    public OrderDecisionSnapshot(String version,String provenance,Instant capturedAt,Instant orderAt,
            String environment,String side,String summary,Signal research,TimedMarketQuote stockQuote,
            MarketIndexQuote marketQuote,BigDecimal marketAverage20,List<NumericCheck>executionChecks,
            Allocation allocation,List<String>counterArguments,List<String>limitations,ExitDecision exitDecision){
        this(version,provenance,capturedAt,orderAt,environment,side,summary,research,stockQuote,marketQuote,
            marketAverage20,executionChecks,allocation,counterArguments,limitations,exitDecision,null,null,null);
    }
    public record AdditionDecision(long entryOrderId,int previousQuantity,BigDecimal previousAveragePrice,
        BigDecimal previousCommittedCapital,BigDecimal cumulativeTarget,BigDecimal combinedPrincipal,
        BigDecimal combinedEstimatedLoss,BigDecimal combinedCommittedCapital,Instant researchCompletedAt,
        Instant previousBuyAt,Instant nextEarliestBuyAt,String policy){}
    public record QuantityLimit(String key,String label,int maximumQuantity,BigDecimal moneyCap,String formula){}
    public record Allocation(BigDecimal totalBuyBudget,String budgetRule,int requestedQuantity,int submittedQuantity,
        BigDecimal limitPrice,BigDecimal grossAmount,BigDecimal estimatedEntryCommission,BigDecimal estimatedEntryTotal,
        BigDecimal commissionRate,BigDecimal sellTaxRate,BigDecimal assumedLossRate,BigDecimal assumedStopPrice,
        BigDecimal expectedLoss,BigDecimal allowedLoss,List<QuantityLimit>quantityLimits,String bindingConstraint){}
    public record ExitCheck(String key,String label,String observed,String threshold,Boolean triggered){}
    public record ExitPosition(String name,int quantity,BigDecimal averagePrice,BigDecimal entryCosts,
        Instant openedAt,long entryOrderId,String entryReason){}
    public record ExitEconomics(int requestedQuantity,int submittedQuantity,int sellableQuantity,BigDecimal orderPrice,
        BigDecimal matchedEntryAmount,BigDecimal allocatedEntryCosts,BigDecimal sellAmount,BigDecimal sellCommission,
        BigDecimal sellTax,BigDecimal netProceeds,BigDecimal netPnl){}
    public record ExitDecision(String trigger,String triggerLabel,String policy,Instant evaluatedAt,
        TimedMarketQuote triggerQuote,ExitPosition position,BigDecimal stopPrice,BigDecimal average20,
        LocalDate historyBasisDate,Instant historyReceivedAt,int confirmedTradingDays,
        List<ExitCheck>conditionChecks,ExitEconomics economics,String lossExplanation){}
}
