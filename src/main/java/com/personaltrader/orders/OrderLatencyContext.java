package com.personaltrader.orders;

import java.util.*;
import java.util.function.LongSupplier;

/** Monotonic timing only; never contains a symbol, account, token, body or provider message. */
public final class OrderLatencyContext implements AutoCloseable {
    private static final ThreadLocal<OrderLatencyContext> CURRENT=new ThreadLocal<>();
    private final LongSupplier nanos;private final long started;private final boolean bounded;
    private final Map<String,Long> stages=new LinkedHashMap<>();private boolean dispatched;private Long orderId,dispatchMs;
    private String outcome="NOT_SENT";
    private OrderLatencyContext(boolean bounded,LongSupplier nanos){this.bounded=bounded;this.nanos=nanos;started=nanos.getAsLong();}
    public static OrderLatencyContext begin(boolean bounded){return begin(bounded,System::nanoTime);}
    static OrderLatencyContext begin(boolean bounded,LongSupplier nanos){if(CURRENT.get()!=null)throw new IllegalStateException("Timing context already active");var t=new OrderLatencyContext(bounded,nanos);CURRENT.set(t);return t;}
    public static void mark(String stage){var t=CURRENT.get();if(t!=null)t.stages.put(stage,t.elapsedMs());}
    public static void order(long id){var t=CURRENT.get();if(t!=null)t.orderId=id;}
    public static long remainingNanos(){var t=CURRENT.get();return t==null||!t.bounded?Long.MAX_VALUE:Math.max(0,3_000_000_000L-(t.nanos.getAsLong()-t.started));}
    public static void requireTime(){if(remainingNanos()==0)throw new DispatchDeadline();}
    public static void dispatch(){requireTime();var t=CURRENT.get();if(t!=null){t.dispatched=true;t.dispatchMs=t.elapsedMs();mark("HTTP_DISPATCH");}}
    public void outcome(String value){outcome=value;}
    public long elapsedMs(){return Math.max(0,(nanos.getAsLong()-started)/1_000_000);}
    public Long dispatchMs(){return dispatchMs;}public boolean dispatched(){return dispatched;}public Long orderId(){return orderId;}public String outcome(){return outcome;}
    public Map<String,Long> stages(){return Map.copyOf(stages);}
    @Override public void close(){CURRENT.remove();}
    public static final class DispatchDeadline extends IllegalStateException {public DispatchDeadline(){super("3초 전송 준비 시간을 초과했어요. 새 주문은 보내지 않았어요. 잔고·시세를 다시 확인해 주세요.");}}
}
