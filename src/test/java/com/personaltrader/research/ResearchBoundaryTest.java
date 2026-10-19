package com.personaltrader.research;

import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.marketdata.KisMarketDataClient;
import com.personaltrader.orders.LegacyPortfolioHistory;
import com.personaltrader.web.RetiredTradingController;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class ResearchBoundaryTest {
    @Test void automaticTradingEntryPointsAreRemovedButManualHistoryRemains(){
        assertThat(Arrays.stream(com.personaltrader.orders.ManualTradingService.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("chooseEntry","monitorPosition","setAutomation","requestExit","automaticCancellationAllowed");
        assertThat(Arrays.stream(LegacyPortfolioHistory.class.getMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("submit","cancel","refresh","setAutomation");
    }
    @Test void holdingsApiIsNotShadowedByRetiredPaths()throws Exception{
        var controller=new com.personaltrader.web.PortfolioController(org.mockito.Mockito.mock(com.personaltrader.orders.ManualTradingService.class),org.mockito.Mockito.mock(com.personaltrader.research.MarketSessionService.class));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller,new RetiredTradingController()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @Test void retiredRequestsHaveAnExplicitGoneResponse(){
        var response=new RetiredTradingController().retired();assertThat(response.getStatusCode().value()).isEqualTo(410);assertThat(response.getBody()).containsEntry("code","TRADING_REMOVED");
    }
    @Test void unknownArchiveDoesNotReleaseBudgetOrModifyCalls()throws Exception{
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V35__archive_unconfirmed_ai_cost.sql"));
        assertThat(sql).contains("preserve_committee_record","REFERENCES committee_ai_call").doesNotContain("UPDATE committee_ai_call","DELETE FROM committee_ai_call","UPDATE committee_ai_budget_month");
        String ledger=Files.readString(Path.of("src/main/java/com/personaltrader/committee/CommitteeAiLedger.java"));
        assertThat(ledger).contains("retired.call_id=u.id","archivedReservedUsd");
    }
    @Test void scheduledDigestsCannotEnqueuePaidAiOrSubmitOrders()throws Exception{
        String source=Files.readString(Path.of("src/main/java/com/personaltrader/research/ResearchDigestService.java"));
        assertThat(source).doesNotContain("CommitteeAiCoordinator","ExecutionBrokerClient",".enqueue(",".submit(");
    }
}
