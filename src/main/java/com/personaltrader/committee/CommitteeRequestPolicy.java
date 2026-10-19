package com.personaltrader.committee;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.HashSet;

/** No scheduler, read request, or UI selection can authorize paid work. */
@Service
public class CommitteeRequestPolicy {
    private final JdbcTemplate jdbc;
    public CommitteeRequestPolicy(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void record(long job,Instant at){
        jdbc.update("INSERT INTO committee_manual_request(job_id,requested_at,origin) VALUES (?,?,'OWNER_CONFIRMED') ON CONFLICT DO NOTHING",job,at.atOffset(ZoneOffset.UTC));
    }
    public boolean requested(long job){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_manual_request WHERE job_id=?)",Boolean.class,job));
    }
    public boolean unknownBlocked(long brief){
        Set<Long> current=new HashSet<>(jdbc.queryForList("SELECT id FROM committee_ai_call c WHERE state='UNKNOWN' AND NOT EXISTS(SELECT 1 FROM committee_archived_call a WHERE a.call_id=c.id)",Long.class));
        if(current.isEmpty())return false;
        Set<Long> allowed=new HashSet<>(jdbc.queryForList("SELECT unknown_call_id FROM committee_unknown_review_authorization WHERE brief_id=?",Long.class,brief));
        return !allowsUnknowns(current,allowed);
    }
    static boolean allowsUnknowns(Set<Long> current,Set<Long> explicitlyAllowed){return explicitlyAllowed.containsAll(current);}
}
