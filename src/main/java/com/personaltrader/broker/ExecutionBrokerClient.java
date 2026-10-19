package com.personaltrader.broker;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Broker facts only. An acknowledgement is not a fill. Account identifiers never enter these models. */
public interface ExecutionBrokerClient {
    enum Environment { PAPER, LIVE }
    enum Side { BUY, SELL }
    record OrderRequest(String code, Side side, int quantity, BigDecimal limitPrice) {}
    record Acknowledgement(String orderNumber, String organization, LocalDate date, LocalTime time) {}
    record BrokerOrder(String orderNumber, String originalOrderNumber, String organization, String code,
                       Side side, int quantity, int filledQuantity, BigDecimal filledAmount,
                       BigDecimal limitPrice, boolean canceled, boolean rejected, LocalDate date, LocalTime time) {}
    record Holding(String code, String name, int quantity, int sellableQuantity, BigDecimal averagePrice,
                   BigDecimal currentPrice) {}
    record Account(BigDecimal cash, BigDecimal totalAssets, List<Holding> holdings, Instant receivedAt) {}
    record BuyingPower(BigDecimal cash, int quantity) {}

    boolean configured(Environment environment);
    boolean liveArmed();
    /** Opaque fingerprint of environment and account; never the account identifier itself. */
    String accountKey(Environment environment);
    Account account(Environment environment);
    BuyingPower buyingPower(Environment environment, String code, BigDecimal limitPrice);
    Acknowledgement place(Environment environment, OrderRequest request);
    /** Durable, explicitly confirmed PAPER sale only. Implementations must validate the linked intent. */
    default Acknowledgement placeConfirmedPaperSale(long orderId,java.util.UUID intentId,OrderRequest request){throw new NotSubmitted();}
    Acknowledgement cancel(Environment environment, Acknowledgement original, String code, int remainingQuantity,
                           BigDecimal limitPrice);
    List<BrokerOrder> orders(Environment environment, LocalDate from, LocalDate to);

    /** Local/token/preflight failure before starting the order POST is definite non-submission. */
    final class NotSubmitted extends RuntimeException {
        public NotSubmitted() { super("주문 전송 전에 중단됐습니다. 증권사에 주문을 보내지 않았습니다."); }
    }

    /** A parsed nonzero rt_cd is a definite broker rejection, never a timeout or malformed success. */
    final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code) { super("증권사에서 요청을 거절했습니다."); this.code = code; }
        public String code() { return code; }
    }
}
