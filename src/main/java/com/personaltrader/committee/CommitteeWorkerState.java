package com.personaltrader.committee;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** In-process gap between provider settlement and durable opinion storage. */
@Component
public final class CommitteeWorkerState {
    private final AtomicLong activeJob=new AtomicLong();
    public void begin(long id){activeJob.set(id);}
    public void clear(){activeJob.set(0);}
    public boolean busy(long id){return activeJob.get()==id;}
}
