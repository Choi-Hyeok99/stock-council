package com.personaltrader.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;

/** A PAPER experiment using two distinct, dated actual observations, never a forecast. */
@Component
public class DipEntryGate {
    public static final String STRATEGY="FALLING_FINANCIAL_PAPER_V1";
    public static final String POLICY="FALLING_MA20_1_15_FIN_NEWS_TRANCHE2M_ADD1PCT_TP5_HALF_V5";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public DipEntryGate(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public Result evaluate(TimedMarketQuote quote,BigDecimal average){
        if(quote==null||quote.freshnessProblem(clock.instant())!=null||average==null||average.signum()<=0)
            return new Result(false,"당일 신선한 시세·20일 평균 확인 대기",null,null,null);
        BigDecimal low=average.multiply(new BigDecimal("0.85")),high=average.multiply(new BigDecimal("0.99"));
        if(quote.price().compareTo(low)<0||quote.price().compareTo(high)>0)
            return new Result(false,"현재가가 20일 평균 아래1~15% 구간 밖",low,high,null);
        return new Result(true,"평균 아래1~15% · 최신 실제 재무/뉴스 점검 후 분할 매수 · 반등 강제 없음",low,high,null);
    }
    public record Previous(BigDecimal price,Instant at){}
    public record Result(boolean ready,String reason,BigDecimal lowerPrice,BigDecimal upperPrice,Previous previous){}
}
