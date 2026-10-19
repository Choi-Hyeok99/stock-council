package com.personaltrader.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-only result of confirmed fills, separate from immutable order-intent evidence. */
public record OrderTradeResult(String side,boolean reconciled,int filledQuantity,BigDecimal averageFillPrice,
        BigDecimal filledAmount,BigDecimal matchedEntryAmount,BigDecimal allocatedEntryCosts,
        BigDecimal commission,BigDecimal tax,BigDecimal netCash,BigDecimal pricePnl,BigDecimal netPnl,
        Instant firstConfirmedAt,Instant lastConfirmedAt,Long entryOrderId,String entryReason,
        OrderDecisionSnapshot entryDecision,String accountingScope,List<EntryBuy> entryBuys) {
    public record EntryBuy(long orderId,int filledQuantity,BigDecimal filledAmount,BigDecimal commission,
        Instant orderedAt,String reason,OrderDecisionSnapshot decision){}
}
