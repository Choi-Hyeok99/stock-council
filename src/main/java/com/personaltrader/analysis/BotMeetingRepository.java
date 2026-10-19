package com.personaltrader.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Repository
public class BotMeetingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public BotMeetingRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Entry save(Instant time, String trigger, String hash, BotMeetingReport report) {
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO bot_meeting (occurred_at, trigger_kind, rule_version, content_hash, conclusion, summary, report)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """, Long.class, time.atOffset(ZoneOffset.UTC), trigger, report.ruleVersion(), hash,
                    report.conclusion(), report.summary(), mapper.writeValueAsString(report));
            return new Entry(id, time, trigger, report);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("회의록 변환 실패"); }
    }

    public Entry latest() {
        List<Entry> records = jdbc.query("SELECT id, occurred_at, trigger_kind, report FROM bot_meeting ORDER BY id DESC LIMIT 1", this::read);
        return records.isEmpty() ? null : records.getFirst();
    }
    public Optional<Entry> find(long id) {
        return jdbc.query("SELECT id, occurred_at, trigger_kind, report FROM bot_meeting WHERE id = ?", this::read, id).stream().findFirst();
    }
    public History history(Long before) {
        List<Summary> rows = jdbc.query("""
                SELECT id, occurred_at, trigger_kind, conclusion, summary FROM bot_meeting
                WHERE id < ? ORDER BY id DESC LIMIT 21
                """, (rs, number) -> new Summary(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("trigger_kind"), rs.getString("conclusion"), rs.getString("summary")), before == null ? Long.MAX_VALUE : before);
        boolean more = rows.size() > 20;
        return new History(List.copyOf(rows.subList(0, Math.min(rows.size(), 20))), more);
    }
    private Entry read(ResultSet rs, int number) throws SQLException {
        try {
            return new Entry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("trigger_kind"),
                    mapper.readValue(rs.getString("report"), BotMeetingReport.class));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("회의록 읽기 실패"); }
    }
    public record Entry(long id, Instant occurredAt, String trigger, BotMeetingReport report) {}
    public record Summary(long id, Instant occurredAt, String trigger, String conclusion, String summary) {}
    public record History(List<Summary> items, boolean hasMore) {}
}
