package com.personaltrader.committee;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommitteeQuantitativeFactsTest {
    private CommitteeFinancialEvidence.PeriodFacts period(int year,String fs,Map<String,BigDecimal> values){return new CommitteeFinancialEvidence.PeriodFacts(year,"11011",year+"년 연간",fs,"READY","financial:"+year,Instant.EPOCH,LocalDate.of(year+1,3,1),false,values,Map.of());}
    private CommitteeFinancialEvidence data(CommitteeFinancialEvidence.PeriodFacts... periods){return new CommitteeFinancialEvidence(true,false,"KRW",List.of(periods),List.of());}
    private Map<String,BigDecimal> values(String... pairs){var m=new HashMap<String,BigDecimal>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],new BigDecimal(pairs[i+1]));return m;}
    @Test void calculatesFromOfficialNumbersAndRetainsNegativeCash(){
        var facts=CommitteeQuantitativeFacts.from(data(period(2025,"CFS",values("REVENUE","1000","OPERATING_PROFIT","100","NET_PROFIT","50","OPERATING_CASH_FLOW","-20","EQUITY","200","LIABILITIES","300","PPE_PURCHASE","-10","INTANGIBLE_PURCHASE","5")),period(2024,"CFS",values("REVENUE","800"))));
        assertThat(facts.stream().filter(f->f.key().equals("OPERATING_MARGIN")).findFirst().orElseThrow().value()).isEqualByComparingTo("10");
        assertThat(facts.stream().filter(f->f.key().equals("AFTER_ASSET_PURCHASE")).findFirst().orElseThrow().value()).isEqualByComparingTo("-35");
        var growth=facts.stream().filter(f->f.key().equals("REVENUE_YOY")).findFirst().orElseThrow();assertThat(growth.value()).isEqualByComparingTo("25");assertThat(growth.evidenceIds()).containsExactly("financial:2025","financial:2024");
    }
    @Test void missingAssetPurchasesNeverBecomeZeroAndLossDoesNotProduceConversionRatio(){
        var facts=CommitteeQuantitativeFacts.from(data(period(2025,"CFS",values("NET_PROFIT","-50","OPERATING_CASH_FLOW","100","PPE_PURCHASE","20"))));
        for(String key:List.of("AFTER_ASSET_PURCHASE","CASH_CONVERSION","REVENUE_YOY"))assertThat(facts.stream().filter(f->f.key().equals(key)).findFirst().orElseThrow().value()).isNull();
    }
    @Test void revenueComparisonRequiresConsecutiveYearsAndSameAccountingBasis(){
        var now=period(2025,"CFS",values("REVENUE","1000"));
        for(var old:List.of(period(2024,"OFS",values("REVENUE","500")),period(2023,"CFS",values("REVENUE","500")),period(2024,"CFS",values("REVENUE","0"))))
            assertThat(CommitteeQuantitativeFacts.from(data(now,old)).stream().filter(f->f.key().equals("REVENUE_YOY")).findFirst().orElseThrow().value()).isNull();
    }
    @Test void historicalDebateVersionIsStillAFullConversation(){
        assertThat(CommitteeContract.supportedVersion("COMMITTEE_DEBATE_V5")).isTrue();assertThat(CommitteeContract.usesDebate("COMMITTEE_DEBATE_V5")).isTrue();assertThat(CommitteeContract.usesDebate(CommitteeContract.PROMPT_VERSION)).isTrue();assertThat(CommitteeContract.usesDebate("COMMITTEE_FINANCIAL_V4")).isFalse();
        assertThat(CommitteeContract.investmentMandate()).contains("기업 연구 전용","quantitativeFacts","null");
    }
}
