package com.personaltrader.orders;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class SellCostCalculatorTest {
    private static BigDecimal n(String s){return new BigDecimal(s);}
    private static final BigDecimal C=n("0.000140527"),T=n("0.002"),TARGET=n("0.05");

    @Test void fivePercentPriceGainDoesNotMeetFivePercentAfterCosts() {
        assertFalse(SellCostCalculator.reachesNetReturn(n("100000"),20,n("281.05"),10,n("105000"),C,T,TARGET));
    }
    @Test void targetIsFirstValidTickMeetingExactFivePercentNet() {
        BigDecimal target=SellCostCalculator.targetForNetReturn(n("100000"),20,n("281.05"),10,C,T,TARGET);
        assertEquals(n("105300"),target);
        assertTrue(SellCostCalculator.reachesNetReturn(n("100000"),20,n("281.05"),10,target,C,T,TARGET));
        assertFalse(SellCostCalculator.reachesNetReturn(n("100000"),20,n("281.05"),10,PriceTicks.floor(target.subtract(BigDecimal.ONE)),C,T,TARGET));
    }
    @Test void partialSaleUsesAllocatedStoredAcquisitionCosts() {
        BigDecimal target=SellCostCalculator.targetForNetReturn(n("384333.33333333"),3,n("162.02"),1,C,T,TARGET);
        assertEquals(n("404500"),target);
        assertTrue(SellCostCalculator.reachesNetReturn(n("384333.33333333"),3,n("162.02"),1,target,C,T,TARGET));
        assertFalse(SellCostCalculator.reachesNetReturn(n("384333.33333333"),3,n("162.02"),1,n("404000"),C,T,TARGET));
    }
    @Test void comparisonAndExecutionTargetsAgreeForFullHolding() {
        var display=SellCostCalculator.calculate(n("301750"),2,n("84.81"),n("300000"),C,T);
        assertEquals(display.scenarios().get(2).price(),
            SellCostCalculator.targetForNetReturn(n("301750"),2,n("84.81"),2,C,T,TARGET));
    }
    @Test void exactThresholdRejectsValueThatRoundsUpToFivePercent() {
        assertFalse(SellCostCalculator.reachesNetReturn(n("100"),1,BigDecimal.ZERO,1,n("104.99999999999"),BigDecimal.ZERO,BigDecimal.ZERO,TARGET));
        assertTrue(SellCostCalculator.reachesNetReturn(n("100"),1,BigDecimal.ZERO,1,n("105"),BigDecimal.ZERO,BigDecimal.ZERO,TARGET));
    }
    @Test void invalidCostsOrQuantitiesNeverPassOrBreakComparison() {
        assertNull(SellCostCalculator.targetForNetReturn(n("100000"),1,BigDecimal.ZERO,2,C,T,TARGET));
        assertNull(SellCostCalculator.targetForNetReturn(n("100000"),1,BigDecimal.ZERO,1,n("0.8"),n("0.2"),TARGET));
        assertFalse(SellCostCalculator.reachesNetReturn(n("100000"),1,BigDecimal.ZERO,1,n("200000"),n("-1"),T,TARGET));
        assertEquals("COSTS_UNAVAILABLE",SellCostCalculator.calculate(n("100000"),1,BigDecimal.ZERO,n("200000"),n("0.8"),n("0.2")).status());
    }
    @Test void zeroBasisAndTickBandBoundaryAreHandled() {
        assertNull(SellCostCalculator.targetForNetReturn(n("0.0001"),1,BigDecimal.ZERO,1,BigDecimal.ZERO,BigDecimal.ZERO,TARGET));
        BigDecimal target=SellCostCalculator.targetForNetReturn(n("1900"),1,BigDecimal.ZERO,1,BigDecimal.ZERO,BigDecimal.ZERO,TARGET);
        assertEquals(n("1995"),target);
        assertFalse(SellCostCalculator.reachesNetReturn(n("1900"),1,BigDecimal.ZERO,1,n("1994"),BigDecimal.ZERO,BigDecimal.ZERO,TARGET));
    }
}
