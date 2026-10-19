package com.personaltrader.orders;
import java.math.*;
/** Same cost-allocation/rounding convention as the execution ledger; all values are estimates. */
public record ManualSaleEconomics(BigDecimal principal,BigDecimal allocatedBuyCommission,BigDecimal costBasis,
    BigDecimal grossProceeds,BigDecimal sellCommission,BigDecimal transactionTax,BigDecimal netProceeds,
    BigDecimal netPnl,BigDecimal netReturnPercent,BigDecimal totalTradingCosts){
    public static ManualSaleEconomics calculate(BigDecimal average,int held,BigDecimal buyFees,int quantity,
        BigDecimal price,BigDecimal commission,BigDecimal tax){
        if(held<1||quantity<1||quantity>held||average==null||average.signum()<=0||buyFees==null||buyFees.signum()<0
            ||price==null||price.signum()<=0||commission==null||tax==null||commission.signum()<0||tax.signum()<0||commission.add(tax).compareTo(BigDecimal.ONE)>=0)
            throw new IllegalArgumentException("수량·원가·비용 요율 확인 필요");
        var q=BigDecimal.valueOf(quantity);var principal=amount(average.multiply(q));
        var buy=held==quantity?buyFees:buyFees.multiply(q).divide(BigDecimal.valueOf(held),2,RoundingMode.HALF_UP);
        var gross=amount(price.multiply(q));var fee=amount(gross.multiply(commission));var taxes=amount(gross.multiply(tax));
        var basis=principal.add(buy);var proceeds=gross.subtract(fee).subtract(taxes);var net=proceeds.subtract(basis);
        return new ManualSaleEconomics(principal,buy,basis,gross,fee,taxes,proceeds,net,net.multiply(new BigDecimal("100")).divide(basis,4,RoundingMode.HALF_UP),buy.add(fee).add(taxes));
    }
    private static BigDecimal amount(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP);}
}
