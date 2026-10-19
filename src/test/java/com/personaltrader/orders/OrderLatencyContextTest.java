package com.personaltrader.orders;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
class OrderLatencyContextTest {
    @Test void dispatchIsRejectedAtThreeSecondsWithoutMarkingSent(){
        var now=new AtomicLong(1);
        try(var t=OrderLatencyContext.begin(true,now::get)){
            now.addAndGet(3_000_000_000L);
            assertThatThrownBy(OrderLatencyContext::dispatch).isInstanceOf(OrderLatencyContext.DispatchDeadline.class);
            assertThat(t.dispatched()).isFalse();assertThat(t.dispatchMs()).isNull();
        }
    }
    @Test void dispatchWithinBudgetIsRecordedAndLaterAckDoesNotBecomeAnotherSend(){
        var now=new AtomicLong(1);
        try(var t=OrderLatencyContext.begin(true,now::get)){
            now.addAndGet(1_100_000_000L);OrderLatencyContext.dispatch();
            now.addAndGet(10_000_000_000L);OrderLatencyContext.mark("BROKER_RESPONSE");
            assertThat(t.dispatchMs()).isEqualTo(1100L);assertThat(t.elapsedMs()).isEqualTo(11100L);
            assertThat(t.dispatched()).isTrue();
        }
        assertThat(OrderLatencyContext.remainingNanos()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void readOnlyProbeHasNoDispatchDeadlineAndNoOrder(){
        var now=new AtomicLong(1);
        try(var t=OrderLatencyContext.begin(false,now::get)){
            now.addAndGet(10_000_000_000L);OrderLatencyContext.requireTime();
            assertThat(t.orderId()).isNull();assertThat(t.dispatched()).isFalse();
        }
    }
}
