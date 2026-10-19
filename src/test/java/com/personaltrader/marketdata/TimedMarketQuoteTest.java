package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TimedMarketQuoteTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-02T05:32:36Z");

    TimedMarketQuote quote(String date, String tradeTime, String barTime, String price) throws Exception {
        return TimedMarketQuote.parse("005930",
                mapper.readTree("[{\"stck_cntg_hour\":\"" + tradeTime + "\",\"stck_prpr\":\"" + price + "\"}]"),
                mapper.readTree("[{\"stck_bsop_date\":\"" + date + "\",\"stck_cntg_hour\":\"" + barTime
                        + "\",\"stck_prpr\":\"274500\"}]"), now);
    }

    @Test void validatesDateAndTradeTimeWithoutReplacingThemWithReceiptTime() throws Exception {
        var result = quote("20261002", "143232", "143200", "274750");
        assertThat(result.tradeAt()).isEqualTo(Instant.parse("2026-10-02T05:32:32Z"));
        assertThat(result.barAt()).isEqualTo(Instant.parse("2026-10-02T05:32:00Z"));
        assertThat(result.price()).isEqualByComparingTo("274750");
        assertThat(result.freshnessProblem(now)).isNull();
    }
    @Test void rejectsMissingDate() { assertThatThrownBy(() -> quote("", "143232", "143200", "1000")).isInstanceOf(IllegalArgumentException.class); }
    @Test void rejectsPreviousDate() { assertThatThrownBy(() -> quote("20261001", "143232", "143200", "1000")).hasMessageContaining("이전 기준일"); }
    @Test void rejectsFutureTime() { assertThatThrownBy(() -> quote("20261002", "143237", "143200", "1000")).hasMessageContaining("미래"); }
    @Test void rejectsStaleTrade() { assertThatThrownBy(() -> quote("20261002", "142900", "143200", "1000")).hasMessageContaining("120초"); }
    @Test void rejectsStaleBarEvenWhenTradeIsFresh() { assertThatThrownBy(() -> quote("20261002", "143232", "142900", "1000")).hasMessageContaining("120초"); }
    @Test void rejectsInvalidPrices() {
        for (String price : new String[]{"NaN", "-1", "0", "", "Infinity"})
            assertThatThrownBy(() -> quote("20261002", "143232", "143200", price)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidDateAndTime() {
        assertThatThrownBy(() -> quote("20260230", "143232", "143200", "1000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote("20261002", "246000", "143200", "1000")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void becomesStaleWithoutAnotherProviderRequest() throws Exception {
        var result = quote("20261002", "143232", "143200", "1000");
        assertThat(result.freshnessProblem(now.plusSeconds(121))).contains("120초");
        assertThat(result.freshnessProblem(Instant.parse("2026-10-02T06:30:00Z"))).contains("시간");
    }
    @Test void guardsExactBoundaryFutureClockAndWeekends() throws Exception {
        var result = quote("20261002", "143232", "143200", "1000");
        assertThat(result.freshnessProblem(Instant.parse("2026-10-02T05:34:00Z"))).isNull();
        assertThat(result.freshnessProblem(Instant.parse("2026-10-02T05:34:00.001Z"))).contains("120초");
        assertThat(result.freshnessProblem(now.minusSeconds(1))).contains("미래");
        assertThat(TimedMarketQuote.regularSession(Instant.parse("2026-10-03T05:32:36Z"))).isFalse();
    }
}
