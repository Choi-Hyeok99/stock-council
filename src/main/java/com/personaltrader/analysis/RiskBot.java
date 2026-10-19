package com.personaltrader.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.personaltrader.research.PaperPortfolioPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Profile("paper")
public class RiskBot {
    private static final BigDecimal PILOT_BUDGET = BigDecimal.valueOf(300_000);
    private static final BigDecimal MAX_ORDER_VALUE = BigDecimal.valueOf(100_000);
    private final BigDecimal commissionRate;
    private final BigDecimal taxRate;
    private final BigDecimal slippageBps;
    private final PaperPortfolioPolicy policy;

    @Autowired
    public RiskBot(@Value("${paper.costs.commission-rate}") BigDecimal commissionRate,
                   @Value("${paper.costs.transaction-tax-rate}") BigDecimal taxRate,
                   @Value("${paper.costs.slippage-bps}") BigDecimal slippageBps, PaperPortfolioPolicy policy) {
        this.commissionRate = commissionRate;
        this.taxRate = taxRate;
        this.slippageBps = slippageBps;
        this.policy=policy;
    }
    public RiskBot(BigDecimal commissionRate,BigDecimal taxRate,BigDecimal slippageBps){
        this(commissionRate,taxRate,slippageBps,new PaperPortfolioPolicy("LIVE",PILOT_BUDGET,MAX_ORDER_VALUE,1));
    }

    public RiskAssessment assess(BigDecimal lastPrice) {
        return assess(lastPrice,policy.positionLimit());
    }
    public RiskAssessment assess(BigDecimal lastPrice,BigDecimal target) {
        BigDecimal maxAllowedLoss = maxAllowedLoss();
        if(lastPrice==null||lastPrice.signum()<=0||target==null)
            return new RiskAssessment(false,0,BigDecimal.ZERO,BigDecimal.ZERO,
                    policy.expectedLossSizingEnabled()?maxAllowedLoss:null,"가격·배정 예산 확인 전 수량 계산 보류");
        BigDecimal roundTripRiskRate = policy.assumedDrawdownRate().add(commissionRate.multiply(BigDecimal.valueOf(2)))
                .add(taxRate).add(slippageBps.movePointLeft(4).multiply(BigDecimal.valueOf(2)));
        BigDecimal positionLimit = policy.positionLimit().min(target.max(BigDecimal.ZERO)).min(policy.budget());
        if (policy.expectedLossSizingEnabled())
            positionLimit = positionLimit.min(maxAllowedLoss.divide(roundTripRiskRate, 0, RoundingMode.DOWN));
        // PAPER capacity includes buy commission; this is a budget limit, not a loss limit.
        BigDecimal unitCommitment = policy.expectedLossSizingEnabled() ? lastPrice : lastPrice.multiply(BigDecimal.ONE.add(commissionRate));
        int quantity = unitCommitment.signum() > 0 ? positionLimit.divide(unitCommitment, 0, RoundingMode.DOWN).intValueExact() : 0;
        BigDecimal orderValue = lastPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal estimatedLoss = orderValue.multiply(roundTripRiskRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commitment = orderValue.add(orderValue.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));
        boolean pass = quantity > 0 && commitment.compareTo(policy.minEntryCommitment()) >= 0
                && (!policy.expectedLossSizingEnabled() || estimatedLoss.compareTo(maxAllowedLoss) <= 0);
        String reason = !policy.expectedLossSizingEnabled()
                ? pass ? "모의 예상손실 수량 제한 해제 · 매수 비용 포함 " + commitment.toPlainString() + "원 · 8% 하락 가정 손실 " + estimatedLoss.toPlainString() + "원은 표시용 시나리오"
                       : "정수 수량과 배정 예산 안에서 최초 매수 총액 150만원을 충족하지 못해 대기"
                : pass ? "예상 손실 " + estimatedLoss.toPlainString() + "원 · 계획 하락률 시나리오, 실제 손실 상한 아님"
                       : "위험 한도 안에서 주문 가능한 수량이 없습니다.";
        return new RiskAssessment(pass, quantity, orderValue, estimatedLoss, policy.expectedLossSizingEnabled() ? maxAllowedLoss : null, reason);
    }

    public BigDecimal maximumAffordablePrice() {
        if (!policy.expectedLossSizingEnabled())
            return policy.positionLimit().min(policy.budget()).divide(BigDecimal.ONE.add(commissionRate), 8, RoundingMode.DOWN);
        BigDecimal rate = policy.assumedDrawdownRate().add(commissionRate.multiply(BigDecimal.valueOf(2)))
                .add(taxRate).add(slippageBps.movePointLeft(4).multiply(BigDecimal.valueOf(2)));
        return maxAllowedLoss().divide(rate, 0, RoundingMode.DOWN).min(policy.positionLimit()).min(policy.budget());
    }

    public BotStatus summarize() {
        BigDecimal maxLoss = maxAllowedLoss();
        if (!policy.expectedLossSizingEnabled())
            return new BotStatus("Risk Bot", "GUARDED", "모의 예상손실 수량 제한 해제 · 최초 매수 총액 150만원 이상",
                    "총 " + policy.budget().toPlainString() + "원 · 종목당 최대 " + policy.positionLimit().toPlainString() + "원 · 최대 " + policy.maxPositions() + "종목 · 시세/근거/현금/당일 실현손실 관문 유지");
        return new BotStatus("Risk Bot", "GUARDED", "종목 누적 예상손실 한도 " + maxLoss.toPlainString() + "원",
                "봇 배정 한도 "+policy.budget().toPlainString()+"원 · 종목당 최대 "+policy.positionLimit().toPlainString()+"원 · 보유 최대 "+policy.maxPositions()+"종목 · 근거 강도/손실/현금으로 수량 축소");
    }

    private BigDecimal maxAllowedLoss() {
        return policy.lossLimit();
    }
}
