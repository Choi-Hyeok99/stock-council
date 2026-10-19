package com.personaltrader.research;
import com.personaltrader.broker.ExecutionBrokerClient.Environment;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class WeekendSessionTest {
    @Test void weekendIsClosedEvenWithoutTheOfficialCalendar(){
        var clock=Clock.fixed(LocalDate.of(2026,10,17).atTime(10,0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),ZoneOffset.UTC);
        var session=new MarketSessionService(clock);
        assertThat(session.status().phase()).isEqualTo("HOLIDAY");assertThat(session.status().detail()).contains("주말");assertThat(session.newEntriesAllowed()).isFalse();
    }
    @Test void unknownWeekdayCalendarDoesNotClaimTheMarketIsOpen(){
        var clock=Clock.fixed(LocalDate.of(2026,10,19).atTime(10,0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),ZoneOffset.UTC);
        assertThat(new MarketSessionService(clock).status().phase()).isEqualTo("CHECK_REQUIRED");
        assertThat(BerkshireMandate.entriesPaused(Environment.PAPER)).isTrue();assertThat(BerkshireMandate.entriesPaused(Environment.LIVE)).isTrue();
    }
}
