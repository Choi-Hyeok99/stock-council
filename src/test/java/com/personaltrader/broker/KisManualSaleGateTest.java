package com.personaltrader.broker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.marketdata.KisMarketDataClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.personaltrader.broker.ExecutionBrokerClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class KisManualSaleGateTest {
    private final KisMarketDataClient transport=mock(KisMarketDataClient.class);
    private final KisPaperAccountSettings paper=mock(KisPaperAccountSettings.class);
    private final KisExecutionSettings settings=mock(KisExecutionSettings.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final OrderRequest sell=new OrderRequest("000001",Side.SELL,2,new BigDecimal("1000"));
    private KisExecutionBrokerClient client()throws Exception{
        when(transport.executionEnvironment()).thenReturn(Environment.PAPER);when(transport.configured()).thenReturn(true);
        when(paper.configured()).thenReturn(true);var credentials=mock(KisPaperAccountSettings.Credentials.class);
        when(credentials.number()).thenReturn("11111111");when(credentials.product()).thenReturn("01");when(paper.credentials()).thenReturn(credentials);
        when(settings.identityAvailable()).thenReturn(true);when(settings.accountFingerprint(any(),anyString(),anyString())).thenReturn("fixture-scope");
        var c=new KisExecutionBrokerClient(transport,paper,settings,new ObjectMapper(),Clock.fixed(Instant.parse("2026-10-16T00:45:00Z"),ZoneOffset.UTC));c.setJdbc(jdbc);return c;
    }
    @Test void normalAutomaticPaperSaleIsStillBlocked()throws Exception{
        var c=client();assertThatThrownBy(()->c.place(Environment.PAPER,sell)).isInstanceOf(NotSubmitted.class);
        verify(transport,never()).executionPost(any(),anyString(),anyString(),anyMap());
    }
    @Test void absentDurableConfirmationCannotReachNetwork()throws Exception{
        var c=client();assertThatThrownBy(()->c.placeConfirmedPaperSale(1,UUID.randomUUID(),sell)).isInstanceOf(NotSubmitted.class);
        verify(transport,never()).executionPost(any(),anyString(),anyString(),anyMap());
    }
    @Test void manualPathCannotBeUsedForBuying()throws Exception{
        var c=client();assertThatThrownBy(()->c.placeConfirmedPaperSale(1,UUID.randomUUID(),new OrderRequest("000001",Side.BUY,2,new BigDecimal("1000")))).isInstanceOf(NotSubmitted.class);
        verifyNoInteractions(jdbc);verify(transport,never()).executionPost(any(),anyString(),anyString(),anyMap());
    }
    @Test void persistedExactApprovalEnablesOnlyOnePaperSellPost()throws Exception{
        var c=client();when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        var body=new ObjectMapper().readTree("{\"output\":{\"ODNO\":\"1\",\"KRX_FWDG_ORD_ORGNO\":\"1\",\"ORD_TMD\":\"094500\"}}");
        when(transport.executionPost(eq(Environment.PAPER),endsWith("order-cash"),eq("VTTC0011U"),anyMap())).thenReturn(new KisMarketDataClient.Page(body,""));
        assertThat(c.placeConfirmedPaperSale(1,UUID.randomUUID(),sell).orderNumber()).isEqualTo("1");
        verify(transport,times(1)).executionPost(eq(Environment.PAPER),endsWith("order-cash"),eq("VTTC0011U"),argThat(m->m.get("ORD_QTY").equals("2")&&m.get("ORD_UNPR").equals("1000")));
        verify(jdbc).queryForObject(argThat(sql->sql.contains("m.scope_id=?")&&sql.contains("m.expires_at>?")&&sql.contains("o.status='SUBMITTING'")&&sql.contains("m.quantity=?")&&sql.contains("m.limit_price=?")),eq(Boolean.class),any(Object[].class));
    }
    @Test void liveTransportCannotUsePaperManualException()throws Exception{
        var c=client();when(transport.executionEnvironment()).thenReturn(Environment.LIVE);
        assertThatThrownBy(()->c.placeConfirmedPaperSale(1,UUID.randomUUID(),sell)).isInstanceOf(NotSubmitted.class);
        verifyNoInteractions(jdbc);verify(transport,never()).executionPost(any(),anyString(),anyString(),anyMap());
    }
}
