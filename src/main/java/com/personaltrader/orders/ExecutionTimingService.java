package com.personaltrader.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class ExecutionTimingService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    public ExecutionTimingService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
    public void save(String scope,String operation,OrderLatencyContext trace){
        if(scope==null)return;
        try{jdbc.update("INSERT INTO execution_latency_measurement(id,scope_id,operation,measured_at,order_id,outcome,dispatch_ms,total_ms,stages) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(),scope,operation,clock.instant().atOffset(ZoneOffset.UTC),trace.orderId(),trace.outcome(),trace.dispatchMs(),trace.elapsedMs(),mapper.writeValueAsString(trace.stages()));}
        catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(ExecutionTimingService.class).warn("Order latency measurement persistence unavailable");}
    }
    public List<Measurement> recent(String scope){if(scope==null)return List.of();return jdbc.query("SELECT operation,measured_at,order_id,outcome,dispatch_ms,total_ms FROM execution_latency_measurement WHERE scope_id=? ORDER BY measured_at DESC LIMIT 20",
        (rs,n)->new Measurement(rs.getString(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getObject(3,Long.class),rs.getString(4),rs.getObject(5,Long.class),rs.getLong(6)),scope);}
    public record Measurement(String operation,Instant measuredAt,Long orderId,String outcome,Long dispatchMs,long totalMs){}
}
