package com.personaltrader.research;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdeaChangeTest {
    @Test void correctionAtSameUrlMustAppearInChangeHistory(){
        assertThat(IdeaBoardService.meaningfullyChanged(signal(85,true,"1000000","계약 체결"),signal(85,true,"1000000","계약 체결 정정"))).isTrue();
    }
    @Test void feedFailureChangesEligibilityEvenWhenChartStateDoesNotChange(){
        assertThat(IdeaBoardService.meaningfullyChanged(signal(85,true,"1000000","계약 체결"),signal(85,false,"1000000","계약 체결"))).isTrue();
    }
    @Test void VolatilityCanChangeAllocationWithoutChangingScoreBand(){
        assertThat(IdeaBoardService.meaningfullyChanged(signal(85,true,"1000000","계약 체결"),signal(85,true,"500000","계약 체결"))).isTrue();
    }
    @Test void SameBandAndEquivalentAmountDoNotCreateNoise(){
        assertThat(IdeaBoardService.meaningfullyChanged(signal(85,true,"1000000.00","계약 체결"),signal(86,true,"1000000","계약 체결"))).isFalse();
    }
    private InvestmentSignalEngine.Signal signal(int score,boolean eligible,String budget,String title){
        var signal=mock(InvestmentSignalEngine.Signal.class);
        when(signal.code()).thenReturn("000001");when(signal.state()).thenReturn("UPTREND_WATCH");
        when(signal.score()).thenReturn(score);when(signal.paperEligible()).thenReturn(eligible);
        when(signal.targetBudget()).thenReturn(new BigDecimal(budget));
        when(signal.evidence()).thenReturn(List.of(new PublicInformationService.Evidence("fixture-url","검증용","NEWS_RSS",title,"https://www.hankyung.com/fixture",null,null,null,"EXPLICIT_NAME_IN_TITLE")));
        return signal;
    }
}
