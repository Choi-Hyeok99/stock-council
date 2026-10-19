package com.personaltrader.committee;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Clock;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommitteeLedgerRequestTest {
    @Test void unauthorizedJobCannotReserveBudgetEvenIfCoordinatorIsBypassed() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var ledger=new CommitteeAiLedger(jdbc,Clock.systemUTC(),tx);
        assertThat(ledger.reserve(42L,"ECONOMY","INDEPENDENT","model","hash",BigDecimal.ONE)).isNull();
        verify(jdbc).queryForList(argThat(sql->sql.contains("committee_manual_request")&&sql.contains("committee_unknown_review_authorization")),eq(String.class),eq(42L));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void sendClaimAlsoRequiresRequestAndNoNewUnknown() {
        var jdbc=mock(JdbcTemplate.class);
        var ledger=new CommitteeAiLedger(jdbc,Clock.systemUTC(),mock(PlatformTransactionManager.class));
        assertThat(ledger.claimSend(42L,"hash")).isFalse();
        verify(jdbc).update(argThat(sql->sql.contains("committee_manual_request")&&sql.contains("committee_unknown_review_authorization")&&sql.contains("c.state='RESERVED'")),any(),eq(42L),eq("hash"));
        verifyNoMoreInteractions(jdbc);
    }
}
