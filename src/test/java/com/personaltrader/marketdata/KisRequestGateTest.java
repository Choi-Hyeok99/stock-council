package com.personaltrader.marketdata;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class KisRequestGateTest {
    @Test void executionTakesTheNextTurnWithoutParallelCalls()throws Exception{
        var gate=new KisRequestGate();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var order=Collections.synchronizedList(new ArrayList<String>());var active=new AtomicInteger();var maximum=new AtomicInteger();
        Thread first=Thread.startVirtualThread(()->gate.run(false,()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
        Thread background=Thread.startVirtualThread(()->gate.run(false,()->{maximum.accumulateAndGet(active.incrementAndGet(),Math::max);order.add("collector");active.decrementAndGet();return null;}));
        waitForCondition(background);
        Thread execution=Thread.startVirtualThread(()->gate.run(true,()->{maximum.accumulateAndGet(active.incrementAndGet(),Math::max);order.add("execution");active.decrementAndGet();return null;}));
        waitForCondition(execution);release.countDown();first.join(1000);execution.join(1000);background.join(1000);
        assertThat(order).containsExactly("execution","collector");assertThat(maximum.get()).isEqualTo(1);
    }
    @Test void collectorYieldsBetweenItsHttpRequests()throws Exception{
        var gate=new KisRequestGate();var between=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var sequence=Collections.synchronizedList(new ArrayList<String>());
        Thread collector=Thread.startVirtualThread(()->gate.run(false,()->{
            sequence.add("first-read");between.countDown();try{resume.await();}catch(InterruptedException e){throw new RuntimeException(e);}
            gate.yieldToPriority();sequence.add("second-read");return null;
        }));
        assertThat(between.await(1,TimeUnit.SECONDS)).isTrue();
        Thread execution=Thread.startVirtualThread(()->gate.run(true,()->{sequence.add("order");return null;}));
        waitForCondition(execution);resume.countDown();collector.join(1000);execution.join(1000);
        assertThat(sequence).containsExactly("first-read","order","second-read");
    }
    private static void waitForCondition(Thread t)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(t.getState()!=Thread.State.WAITING&&System.nanoTime()<end)Thread.sleep(1);
        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
    }
}
