package com.personaltrader.research;

import com.personaltrader.analysis.RiskBot;
import com.personaltrader.marketdata.MarketHistory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic fixtures stay in tests; production only collects provider data. No broker/network calls. */
class ResearchPortfolioTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-05T02:00:00Z"),ZoneOffset.UTC);
    private PaperPortfolioPolicy paper(){return new PaperPortfolioPolicy("PAPER",bd(10000000),bd(3000000),5,bd(100000),bd(200000));}
    @Test void paperUsesUserSelectedLimits(){
        var policy=paper();
        assertThat(policy.budget()).isEqualByComparingTo("10000000");
        assertThat(policy.positionLimit()).isEqualByComparingTo("3000000");
        assertThat(policy.maxPositions()).isEqualTo(5);
        assertThat(policy.expectedLossSizingEnabled()).isFalse();
        assertThat(policy.view().lossLimit()).isNull();
        assertThat(policy.minEntryCommitment()).isEqualByComparingTo("1500000");
        assertThat(policy.dayLossLimit()).isEqualByComparingTo("200000");
        assertThat(policy.sellExecutionPaused()).isTrue();
        assertThat(policy.manualSellPaused()).isTrue();
        assertThat(policy.takeProfitRate()).isEqualByComparingTo("0.05");
    }
    @Test void paperSettingsDoNotExpandLiveLimits(){
        var policy=new PaperPortfolioPolicy("LIVE",bd(3000000),bd(1000000),3);
        assertThat(policy.budget()).isEqualByComparingTo("300000");
        assertThat(policy.positionLimit()).isEqualByComparingTo("100000");
        assertThat(policy.maxPositions()).isEqualTo(1);
        assertThat(policy.lossLimit()).isEqualByComparingTo("3000");
    }
    @Test void paperAllocationCapRemainsSeparateFromEntryChecks(){
        assertThat(paper().target(64)).isEqualByComparingTo("3000000");
        assertThat(paper().target(65)).isEqualByComparingTo("3000000");
        assertThat(paper().target(75)).isEqualByComparingTo("3000000");
        assertThat(paper().target(85)).isEqualByComparingTo("3000000");
        assertThat(paper().stagedTarget(85,bd(5))).isEqualByComparingTo("3000000");
    }
    @Test void invalidLimitsCannotStart(){
        assertThatThrownBy(()->new PaperPortfolioPolicy("PAPER",bd(3000000),bd(4000000),3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PaperPortfolioPolicy("PAPER",bd(3000000),bd(1000000),6)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void paperQuantityIncludesFeesWithoutExpectedLossCap(){
        var risk=new RiskBot(new BigDecimal("0.00015"),new BigDecimal("0.002"),bd(5),paper());
        var assessed=risk.assess(bd(50000),paper().target(85));
        assertThat(assessed.orderValue()).isLessThanOrEqualTo(paper().positionLimit());
        assertThat(assessed.pass()).isTrue();
        assertThat(assessed.quantity()).isEqualTo(59);
        assertThat(assessed.orderValue().multiply(new BigDecimal("1.00015"))).isLessThanOrEqualTo(paper().positionLimit());
        assertThat(assessed.estimatedLoss()).isGreaterThan(paper().lossLimit());
        assertThat(assessed.maxAllowedLoss()).isNull();
        assertThat(risk.assess(bd(50000),paper().target(65)).orderValue()).isEqualByComparingTo("2950000");
        assertThat(risk.assess(bd(50000),BigDecimal.ZERO).quantity()).isZero();
        assertThat(risk.assess(bd(1500000),paper().target(85)).pass()).isTrue();
        assertThat(risk.assess(bd(2000000),paper().target(85)).pass()).isTrue();
        assertThat(risk.assess(bd(3000000),paper().target(85)).pass()).isFalse();
    }
    @Test void liveRiskSizingRemainsCapped(){
        var policy=new PaperPortfolioPolicy("LIVE",bd(10000000),bd(3000000),5);
        var risk=new RiskBot(new BigDecimal("0.00015"),new BigDecimal("0.002"),bd(5),policy);
        var assessed=risk.assess(bd(1000));
        assertThat(policy.expectedLossSizingEnabled()).isTrue();
        assertThat(policy.minEntryCommitment()).isZero();
        assertThat(assessed.estimatedLoss()).isLessThanOrEqualTo(policy.lossLimit());
        assertThat(assessed.maxAllowedLoss()).isEqualByComparingTo("3000");
    }
    @Test void riskHeadlinesBlockEntryEvenWithStrongChart(){
        var s=signal("기업A 적자 확대",true);
        assertThat(s.state()).isEqualTo("DOWNSIDE_RISK");
        assertThat(s.paperEligible()).isFalse();
        assertThat(s.targetBudget()).isZero();
    }
    @Test void correctedHeadlineDoesNotBecomeBuyEvidence(){
        var s=signal("기업A 수주 계약 정정",true);
        assertThat(s.paperEligible()).isFalse();
        assertThat(s.risks()).anyMatch(r->r.contains("정정/철회"));
    }
    @Test void feedFailureBlocksNewPaperEntry(){
        var s=signal("기업A 수주 계약 체결",false);
        assertThat(s.paperEligible()).isFalse();
        assertThat(s.targetBudget()).isZero();
    }
    @Test void approvedRubricHasSourceAndCounterargumentAndRealChartFields(){
        var s=signal("기업A 수주 계약 체결",true);
        assertThat(s.state()).isEqualTo("FALLING_BUY_WATCH");
        assertThat(s.paperEligible()).isTrue();
        assertThat(s.evidence()).hasSize(1);
        assertThat(s.risks()).anyMatch(r->r.contains("본문"));
        assertThat(s.scope()).contains("상승 확률 아님");
        assertThat(s.chart()).hasSize(30);
    }
    @Test void knownHolidayAndWeekendAreClosed(){
        assertThat(new MarketSessionService(clock).knownClosed()).isTrue();
        assertThat(new MarketSessionService(Clock.fixed(Instant.parse("2026-10-10T02:00:00Z"),ZoneOffset.UTC)).knownClosed()).isTrue();
        assertThat(new MarketSessionService(Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"),ZoneOffset.UTC)).view().coverage()).contains("전체 달력 아님");
    }
    private InvestmentSignalEngine.Signal signal(String title,boolean healthy){
        var info=mock(PublicInformationService.class);
        when(info.healthy()).thenReturn(healthy);
        when(info.evidence("000001")).thenReturn(List.of(new PublicInformationService.Evidence("fixture","검증용","NEWS_RSS",title,
            "https://www.hankyung.com/fixture",clock.instant().minusSeconds(3600),LocalDate.of(2026,10,5),clock.instant(),"EXPLICIT_NAME_IN_TITLE")));
        return new InvestmentSignalEngine(info,paper(),clock).evaluate("000001","기업A","KOSPI",history(true),history(false));
    }
    private MarketHistory history(boolean rising){
        List<MarketHistory.CompletedBar> bars=new ArrayList<>();
        for(int i=0;i<30;i++){
            // Both gains and losses keep this approved-chart fixture below RSI 75.
            var close=bd(rising?1000+i*2+(i%2==0?3:-3):1000);
            bars.add(new MarketHistory.CompletedBar(LocalDate.of(2026,9,1).plusDays(i),close,close.add(bd(8)),close.subtract(bd(8)),close,bd(10000000),bd(2000000000)));
        }
        return new MarketHistory(bars.getLast().date(),bars.getLast().close(),rising?bd(1052):bd(1000),rising?bd(1037):bd(1000),bd(2000000000),30,"KRW",clock.instant(),List.copyOf(bars));
    }
    private static BigDecimal bd(long value){return BigDecimal.valueOf(value);}
}
