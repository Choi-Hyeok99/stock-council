package com.personaltrader.orders;

import com.personaltrader.broker.ExecutionBrokerClient;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** KRX KOSPI/KOSDAQ common-share limit-price units; ETF/ETN/ELW units differ. */
public final class PriceTicks {
    private PriceTicks() {}

    /** Both entry and exit use an explicit bounded price, rounded down to a valid common-share tick. */
    public static BigDecimal limit(BigDecimal reference, ExecutionBrokerClient.Side side) {
        if (side == null) throw new IllegalArgumentException("주문 방향이 필요합니다.");
        return floor(reference);
    }

    public static BigDecimal floor(BigDecimal reference) {
        if (reference == null || reference.signum() <= 0)
            throw new IllegalArgumentException("지정가 기준 가격은 양수여야 합니다.");
        BigDecimal integral = reference.setScale(0, RoundingMode.DOWN);
        if (integral.signum() <= 0)
            throw new IllegalArgumentException("지정가 기준 가격은 1원 이상이어야 합니다.");
        BigDecimal unit = tick(integral);
        return integral.divide(unit, 0, RoundingMode.DOWN).multiply(unit).setScale(0);
    }

    public static BigDecimal ceiling(BigDecimal reference){
        BigDecimal lower=floor(reference);
        return lower.compareTo(reference)>=0?lower:floor(lower.add(tick(lower)));
    }
    private static BigDecimal tick(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(2_000)) < 0) return BigDecimal.ONE;
        if (price.compareTo(BigDecimal.valueOf(5_000)) < 0) return BigDecimal.valueOf(5);
        if (price.compareTo(BigDecimal.valueOf(20_000)) < 0) return BigDecimal.TEN;
        if (price.compareTo(BigDecimal.valueOf(50_000)) < 0) return BigDecimal.valueOf(50);
        if (price.compareTo(BigDecimal.valueOf(200_000)) < 0) return BigDecimal.valueOf(100);
        if (price.compareTo(BigDecimal.valueOf(500_000)) < 0) return BigDecimal.valueOf(500);
        return BigDecimal.valueOf(1_000);
    }
}
