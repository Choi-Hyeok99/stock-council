package com.personaltrader.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class DailyReportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DailyReportRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public boolean exists(LocalDate day, String slot) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM daily_bot_report WHERE report_date = ? AND slot = ?)", Boolean.class, day, slot));
    }
    public void save(DailyReport report) {
        try {
            jdbc.update("""
                    INSERT INTO daily_bot_report (report_date, slot, scheduled_at, generated_at, timing, curriculum_version, report)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (report_date, slot) DO NOTHING
                    """, report.reportDate(), report.slot(), utc(report.scheduledAt()), utc(report.generatedAt()),
                    report.timing(), report.curriculum().version(), mapper.writeValueAsString(report));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { throw new IllegalStateException("정기 보고서 변환 실패"); }
    }
    public Optional<Entry> find(long id) {
        return jdbc.query("SELECT id, report FROM daily_bot_report WHERE id = ?", this::read, id).stream().findFirst();
    }
    public History history(Long before) {
        List<Summary> rows = jdbc.query("""
                SELECT id, report_date, slot, scheduled_at, generated_at, timing FROM daily_bot_report
                WHERE id < ? ORDER BY id DESC LIMIT 21
                """, (rs, n) -> new Summary(rs.getLong("id"), rs.getObject("report_date", LocalDate.class), rs.getString("slot"),
                rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(), rs.getObject("generated_at", OffsetDateTime.class).toInstant(), rs.getString("timing")),
                before == null ? Long.MAX_VALUE : before);
        return new History(List.copyOf(rows.subList(0, Math.min(20, rows.size()))), rows.size() > 20);
    }
    public Entry latest() {
        return jdbc.query("SELECT id, report FROM daily_bot_report ORDER BY id DESC LIMIT 1", this::read).stream().findFirst().orElse(null);
    }
    private Entry read(ResultSet rs, int n) throws SQLException {
        try { return new Entry(rs.getLong("id"), mapper.readValue(rs.getString("report"), DailyReport.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { throw new IllegalStateException("정기 보고서 읽기 실패"); }
    }
    private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    public record Entry(long id, DailyReport report) {}
    public record Summary(long id, LocalDate reportDate, String slot, Instant scheduledAt, Instant generatedAt, String timing) {}
    public record History(List<Summary> items, boolean hasMore) {}
}
