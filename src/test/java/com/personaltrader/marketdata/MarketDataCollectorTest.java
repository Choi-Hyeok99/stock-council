package com.personaltrader.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataCollectorTest {
    final KisMarketDataClient client = mock(KisMarketDataClient.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T05:32:36Z"));
    final MarketDataCollector collector = new MarketDataCollector(client, clock, jdbc);
    MarketDataCollectorTest() { when(client.configured()).thenReturn(true); }
    TimedMarketQuote goodQuote() {
        return new TimedMarketQuote("005930", new BigDecimal("274750"), clock.instant().minusSeconds(4),
                clock.instant().minusSeconds(36), clock.instant());
    }
    @Test void readsSnapshotWithoutCallingProviderAndPersistsEachCollection() {
        assertThat(collector.snapshot().state()).isEqualTo("WAITING");
        when(client.collect("005930")).thenReturn(goodQuote());
        collector.collect();
        assertThat(collector.snapshot().state()).isEqualTo("FRESH");
        collector.snapshot(); collector.snapshot();
        verify(client, times(1)).collect("005930");
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        clock.now = clock.now.plusSeconds(121);
        assertThat(collector.snapshot().state()).isEqualTo("STALE");
        assertThat(collector.snapshot().fresh()).isFalse();
    }
    @Test void errorImmediatelyInvalidatesPreviousFreshQuoteWithoutLeakingException() {
        when(client.collect("005930")).thenReturn(goodQuote()).thenThrow(new IllegalStateException("private transport details"));
        collector.collect(); collector.collect();
        assertThat(collector.snapshot().state()).isEqualTo("ERROR");
        assertThat(collector.snapshot().fresh()).isFalse();
        assertThat(collector.snapshot().price()).isEqualByComparingTo("274750");
        assertThat(collector.snapshot().detail()).doesNotContain("private transport details");
    }
    @Test void absentCredentialsAndClosedMarketNeverCallProvider() {
        when(client.configured()).thenReturn(false);
        collector.collect();
        assertThat(collector.snapshot().state()).isEqualTo("UNCONFIGURED");
        when(client.configured()).thenReturn(true);
        clock.now = Instant.parse("2026-10-02T06:30:00Z");
        collector.collect();
        assertThat(collector.snapshot().state()).isEqualTo("CLOSED");
        verify(client, never()).collect(anyString()); verifyNoInteractions(jdbc);
    }
    @Test void freshDiagnosticQuoteStillCannotEnableTrading() {
        when(client.collect("005930")).thenReturn(goodQuote()); collector.collect();
        assertThat(collector.snapshot().executionAllowed()).isFalse();
    }
    @Test void databaseWriteFailurePreventsDataBeingMarkedReady() {
        when(client.collect("005930")).thenReturn(goodQuote());
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("private database details"));
        collector.collect();
        assertThat(collector.snapshot().state()).isEqualTo("ERROR");
        assertThat(collector.snapshot().detail()).contains("저장").doesNotContain("private database details");
    }
    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
