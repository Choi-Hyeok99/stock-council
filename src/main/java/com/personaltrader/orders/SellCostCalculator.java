package com.personaltrader.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Predicate;

/** Read-only sale scenarios, using remaining acquisition costs and configured estimated rates. */
public final class SellCostCalculator {
    private SellCostCalculator() {}
    private static final BigDecimal TWO=BigDecimal.valueOf(2), THREE=BigDecimal.valueOf(3);
    public record Scenario(String label,BigDecimal price,BigDecimal netProfitPerShare,
        BigDecimal netReturnPercent,BigDecimal roundTripCostsPerShare,BigDecimal requiredRisePercent) {}
    public record Analysis(int calculationQuantity,BigDecimal entryPrincipalPerShare,BigDecimal entryCostsPerShare,
        BigDecimal sellCommissionRate,BigDecimal sellTaxRate,BigDecimal currentNetProfitPerShare,
        BigDecimal currentRoundTripCostsPerShare,BigDecimal breakEvenPrice,Scenario costCoverageReview,
        List<Scenario> scenarios,String status,String explanation) {}
    private record Economics(BigDecimal net,BigDecimal costs,BigDecimal basis) {}

    /** Net threshold for an actual sell quantity, sharing the display's rounded cost convention. */
    public static boolean reachesNetReturn(BigDecimal average,int heldQuantity,BigDecimal remainingEntryCosts,
        int sellQuantity,BigDecimal price,BigDecimal commission,BigDecimal tax,BigDecimal targetReturn) {
        if(!validSale(average,heldQuantity,remainingEntryCosts,sellQuantity,commission,tax,targetReturn)
            ||price==null||price.signum()<=0)return false;
        BigDecimal q=BigDecimal.valueOf(sellQuantity),entry=allocatedEntryCost(remainingEntryCosts,heldQuantity,sellQuantity);
        Economics e=economics(average.multiply(q).setScale(2,RoundingMode.HALF_UP),entry,q,price,commission,tax);
        return e.basis().signum()>0&&e.net().compareTo(e.basis().multiply(targetReturn))>=0;
    }

    public static BigDecimal targetForNetReturn(BigDecimal average,int heldQuantity,BigDecimal remainingEntryCosts,
        int sellQuantity,BigDecimal commission,BigDecimal tax,BigDecimal targetReturn) {
        if(!validSale(average,heldQuantity,remainingEntryCosts,sellQuantity,commission,tax,targetReturn))return null;
        try {
            BigDecimal q=BigDecimal.valueOf(sellQuantity),entry=allocatedEntryCost(remainingEntryCosts,heldQuantity,sellQuantity);
            BigDecimal principal=average.multiply(q).setScale(2,RoundingMode.HALF_UP),basis=principal.add(entry);
            if(basis.signum()<=0)return null;
            BigDecimal lower=basis.multiply(BigDecimal.ONE.add(targetReturn)).subtract(new BigDecimal("0.01"))
                .divide(q.multiply(BigDecimal.ONE.subtract(commission).subtract(tax)),12,RoundingMode.FLOOR);
            return threshold(lower,price->{var e=economics(principal,entry,q,price,commission,tax);
                return e.net().compareTo(basis.multiply(targetReturn))>=0;});
        } catch(ArithmeticException|IllegalStateException exception){return null;}
    }

    private static BigDecimal allocatedEntryCost(BigDecimal costs,int heldQuantity,int soldQuantity) {
        return heldQuantity==soldQuantity?costs:costs.multiply(BigDecimal.valueOf(soldQuantity))
            .divide(BigDecimal.valueOf(heldQuantity),2,RoundingMode.HALF_UP);
    }
    private static boolean validSale(BigDecimal average,int heldQuantity,BigDecimal entryCosts,int soldQuantity,
        BigDecimal commission,BigDecimal tax,BigDecimal rate) {
        return average!=null&&average.signum()>0&&heldQuantity>0&&soldQuantity>0&&soldQuantity<=heldQuantity
            &&entryCosts!=null&&entryCosts.signum()>=0&&commission!=null&&tax!=null&&commission.signum()>=0
            &&tax.signum()>=0&&commission.add(tax).compareTo(BigDecimal.ONE)<0&&rate!=null&&rate.signum()>=0;
    }

    public static Analysis calculate(BigDecimal average,int quantity,BigDecimal entryCosts,BigDecimal current,
        BigDecimal commission,BigDecimal tax) {
        try{return calculateChecked(average,quantity,entryCosts,current,commission,tax);}
        catch(ArithmeticException|IllegalStateException exception) {
            // A problematic scenario must never break account/holding display or arm an order.
            return unavailable(average,quantity,commission,tax,"비용 역산 범위 확인 전 계산 보류");
        }
    }
    private static Analysis calculateChecked(BigDecimal average,int quantity,BigDecimal entryCosts,BigDecimal current,
        BigDecimal commission,BigDecimal tax) {
        if(average==null||average.signum()<=0||quantity<1||entryCosts==null||entryCosts.signum()<0
            ||commission==null||tax==null||commission.signum()<0||tax.signum()<0
            ||commission.add(tax).compareTo(BigDecimal.ONE)>=0)
            return unavailable(average,quantity,commission,tax,"원가·매수 비용·매도 요율 확인 전 계산 보류");
        BigDecimal q=BigDecimal.valueOf(quantity),principal=average.multiply(q).setScale(2,RoundingMode.HALF_UP),basis=principal.add(entryCosts);
        if(basis.signum()<=0)return unavailable(average,quantity,commission,tax,"매수 원가 확인 전 계산 보류");
        BigDecimal rates=commission.add(tax);
        Economics now=current==null||current.signum()<=0?null:economics(principal,entryCosts,q,current,commission,tax);
        // Two rounded sale fees differ from continuous fees by at most 0.01 in total.
        BigDecimal breakEven=threshold(basis.subtract(new BigDecimal("0.01")).divide(q.multiply(BigDecimal.ONE.subtract(rates)),12,RoundingMode.FLOOR),
            price->economics(principal,entryCosts,q,price,commission,tax).net().signum()>=0);
        Scenario review=null;
        // Net >= 2 * ALL costs, including sale costs at the proposed sale price.
        if(rates.multiply(THREE).compareTo(BigDecimal.ONE)<0) {
            BigDecimal raw=principal.add(entryCosts.multiply(THREE)).subtract(new BigDecimal("0.03"))
                .divide(q.multiply(BigDecimal.ONE.subtract(rates.multiply(THREE))),12,RoundingMode.FLOOR);
            BigDecimal price=threshold(raw,p->{var e=economics(principal,entryCosts,q,p,commission,tax);
                return e.net().compareTo(e.costs().multiply(TWO))>=0;});
            review=scenario("순이익 ≥ 총 거래비용의 2배",price,principal,entryCosts,q,current,commission,tax);
        }
        List<Scenario> scenarios=List.of("0.01","0.03","0.05").stream().map(value->{
            BigDecimal rate=new BigDecimal(value),required=basis.multiply(rate);
            BigDecimal raw=basis.multiply(BigDecimal.ONE.add(rate)).subtract(new BigDecimal("0.01"))
                .divide(q.multiply(BigDecimal.ONE.subtract(rates)),12,RoundingMode.FLOOR);
            BigDecimal price=threshold(raw,p->economics(principal,entryCosts,q,p,commission,tax).net().compareTo(required)>=0);
            return scenario("비용 후 순이익 "+rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()+"%",
                price,principal,entryCosts,q,current,commission,tax);
        }).toList();
        return new Analysis(quantity,average,perShare(entryCosts,q),commission,tax,
            now==null?null:perShare(now.net(),q),now==null?null:perShare(now.costs(),q),breakEven,review,scenarios,
            "READY","보유 "+quantity+"주 전량 매도를 가정해 1주로 환산한 계산입니다. 매수 비용은 남은 보유 원장, 매도 수수료·세금은 설정 요율 추정입니다. 호가 올림과 비용 반올림을 반영합니다. 비용의 2배는 비교용 가정이며 최적 매도가·상승 예측·매도 지시가 아닙니다. 시세가 이전 값이면 현재 손익도 그 시각 기준입니다. 실제 정산·양도소득세 등 개인별 과세·미체결/가격 변동은 확정하지 않습니다.");
    }
    private static Economics economics(BigDecimal principal,BigDecimal entryCosts,BigDecimal q,BigDecimal price,
        BigDecimal commission,BigDecimal tax) {
        BigDecimal gross=q.multiply(price),costs=entryCosts.add(fee(gross,commission)).add(fee(gross,tax));
        return new Economics(gross.subtract(principal).subtract(costs),costs,principal.add(entryCosts));
    }
    private static Scenario scenario(String label,BigDecimal price,BigDecimal principal,BigDecimal entryCosts,
        BigDecimal q,BigDecimal current,BigDecimal commission,BigDecimal tax) {
        Economics e=economics(principal,entryCosts,q,price,commission,tax);
        BigDecimal rise=current==null||current.signum()<=0?null:price.subtract(current)
            .multiply(BigDecimal.valueOf(100)).divide(current,4,RoundingMode.HALF_UP);
        return new Scenario(label,price,perShare(e.net(),q),e.net().multiply(BigDecimal.valueOf(100))
            .divide(e.basis(),4,RoundingMode.HALF_UP),perShare(e.costs(),q),rise);
    }
    private static BigDecimal fee(BigDecimal amount,BigDecimal rate){return amount.multiply(rate).setScale(2,RoundingMode.HALF_UP);}
    private static BigDecimal perShare(BigDecimal amount,BigDecimal q){return amount.divide(q,2,RoundingMode.HALF_UP);}
    private static Analysis unavailable(BigDecimal average,int quantity,BigDecimal commission,BigDecimal tax,String reason) {
        return new Analysis(quantity,average,null,commission,tax,null,null,null,null,List.of(),"COSTS_UNAVAILABLE",reason);
    }
    private static BigDecimal threshold(BigDecimal raw,Predicate<BigDecimal> satisfied) {
        BigDecimal price=PriceTicks.floor(raw.max(BigDecimal.ONE));
        // Start below the proven rounding-aware lower bound, then find the first passing tick.
        // Extreme invalid economic configurations return unavailable rather than blocking the API.
        for(int i=0;i<64;i++) {
            if(satisfied.test(price))return price;
            price=PriceTicks.ceiling(price.add(BigDecimal.ONE));
        }
        throw new IllegalStateException("비용 기준 호가 역산 확인 필요");
    }
}
