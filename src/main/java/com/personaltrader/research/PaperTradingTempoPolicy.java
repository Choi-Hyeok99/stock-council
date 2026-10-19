package com.personaltrader.research;

import java.math.BigDecimal;
import java.time.*;

/** Owner-approved, date-bounded PAPER override. Expiry needs no timer, restart or setting mutation. */
public final class PaperTradingTempoPolicy {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final LocalDate APPROVED_DATE=LocalDate.of(2026,10,13);
    private static final Instant START=APPROVED_DATE.atStartOfDay(SEOUL).toInstant();
    private static final Instant END=APPROVED_DATE.plusDays(1).atStartOfDay(SEOUL).toInstant();
    private PaperTradingTempoPolicy(){}
    public static State at(Instant now,boolean paper){
        boolean active=paper&&!now.isBefore(START)&&now.isBefore(END);
        return new State(active?"PAPER_TEMPO_2026_10_13_5MIN_0P5":"BASE_15MIN_1PCT",active,
            active?APPROVED_DATE:null,active?END:null,active?5:15,
            new BigDecimal(active?"0.005":"0.01"),active?
            "10월13일만 모의 추가 매수: 최소5분 간격·직전 확인 매수가보다0.5% 이상 아래. 10월14일00:00 KST부터15분·1% 자동 복귀":
            "기본 추가 매수: 최소15분 간격·직전 확인 매수가보다1% 이상 아래");
    }
    public record State(String id,boolean active,LocalDate approvedDate,Instant expiresAt,
            int minimumIntervalMinutes,BigDecimal requiredDiscount,String description){
        public Duration interval(){return Duration.ofMinutes(minimumIntervalMinutes);}
        public BigDecimal priceMultiplier(){return BigDecimal.ONE.subtract(requiredDiscount);}
        public String discountLabel(){return requiredDiscount.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()+"%";}
    }
}
