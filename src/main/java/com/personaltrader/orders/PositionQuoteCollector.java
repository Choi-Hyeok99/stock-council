package com.personaltrader.orders;

import com.personaltrader.marketdata.KisMarketDataClient;
import com.personaltrader.marketdata.MarketHistory;
import com.personaltrader.marketdata.TimedMarketQuote;
import com.personaltrader.marketdata.UniverseService;
import com.personaltrader.marketdata.WatchlistQuoteCollector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps held symbols under observation even after they leave the entry universe.
 * All prices retain their provider timestamps. A failed attempt blocks execution
 * with the preceding value; that value remains available for labelled valuation.
 */
@Service
public class PositionQuoteCollector {
    private static final int POSITION_LIMIT = 10;
    private static final Duration QUOTE_INTERVAL = Duration.ofSeconds(30);
    private static final Duration HISTORY_RETRY_INTERVAL = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final KisMarketDataClient client;
    private final WatchlistQuoteCollector watchlist;
    private final UniverseService universe;
    private final Clock clock;
    private final Map<String, QuoteState> quotes = new ConcurrentHashMap<>();
    private final Map<String, TimedMarketQuote> displayRecovery = new ConcurrentHashMap<>();
    private final Map<String, HistoryState> histories = new ConcurrentHashMap<>();
    private volatile String collectionProblem;
    private volatile Instant lastCycleAt;
    private volatile Set<String> heldCodes = Set.of();

    public PositionQuoteCollector(JdbcTemplate jdbc, KisMarketDataClient client,
                                  WatchlistQuoteCollector watchlist, UniverseService universe, Clock clock) {
        this.jdbc = jdbc;
        this.client = client;
        this.watchlist = watchlist;
        this.universe = universe;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 25000)
    public synchronized void collect() {
        Instant now = clock.instant();
        if (!client.configured() || !TimedMarketQuote.regularSession(now)) return;
        final List<String> targets;
        try {
            // More than ten distinct holdings is an invariant violation, not a reason
            // to silently ignore the eleventh position during automated risk exits.
            List<String> found = jdbc.queryForList("""
                    SELECT stock_code FROM execution_position WHERE quantity > 0
                    GROUP BY stock_code ORDER BY stock_code LIMIT 11
                    """, String.class);
            if (found.size() > POSITION_LIMIT || found.stream().anyMatch(code -> !validCode(code))) {
                collectionProblem = "보유 종목 수 또는 식별 확인 필요 · 보유 시세 사용 차단";
                lastCycleAt = now;
                return;
            }
            targets = List.copyOf(found);
            heldCodes = Set.copyOf(found);
            Set<String> current = new HashSet<>(found);
            quotes.keySet().removeIf(code -> !current.contains(code));
            histories.keySet().removeIf(code -> !current.contains(code));
            collectionProblem = null;
            lastCycleAt = now;
        } catch (RuntimeException exception) {
            collectionProblem = "보유 원장 조회 실패 · 보유 시세 사용 차단";
            lastCycleAt = now;
            return;
        }

        Map<String, MarketHistory> selectedHistories = new java.util.HashMap<>();
        universe.snapshot().stocks().forEach(stock -> selectedHistories.put(stock.code(), stock.history()));
        for (String code : targets) {
            if (!TimedMarketQuote.regularSession(clock.instant())) return;
            QuoteState previous = quotes.get(code);
            Instant attempt = clock.instant();
            if (previous != null && !due(previous.attemptedAt(), attempt, QUOTE_INTERVAL)) continue;
            try {
                TimedMarketQuote quote = watchlist.freshQuote(code);
                if (quote == null) quote = client.collect(code);
                if (!code.equals(quote.code()) || quote.freshnessProblem(clock.instant()) != null)
                    throw new IllegalStateException("보유 시세 날짜·시각 확인 필요");
                jdbc.update("""
                        INSERT INTO market_data_sample (stock_code, source, state, price, trade_at, bar_at,
                            received_at, attempted_at, detail) VALUES (?, ?, 'FRESH', ?, ?, ?, ?, ?, ?)
                        """, code, client.sourcePrefix() + "_REST", quote.price(),
                        quote.tradeAt().atOffset(java.time.ZoneOffset.UTC), quote.barAt().atOffset(java.time.ZoneOffset.UTC),
                        quote.receivedAt().atOffset(java.time.ZoneOffset.UTC), attempt.atOffset(java.time.ZoneOffset.UTC),
                        "보유 종목 검증된 체결 시세 · 이전 시세 화면 복원용");
                quotes.put(code, new QuoteState(quote, attempt, null));
            } catch (RuntimeException exception) {
                quotes.put(code, new QuoteState(previous == null ? null : previous.quote(), attempt,
                        "보유 종목 체결·분봉 조회 또는 시각 검증 실패"));
            }
            refreshHistory(code, selectedHistories.get(code));
        }
    }

    private void refreshHistory(String code, MarketHistory selected) {
        Instant now = clock.instant();
        HistoryState previous = histories.get(code);
        if (selected != null && usableHistory(selected, now)
                && selected.receivedAt().atZone(TimedMarketQuote.SEOUL).toLocalDate().equals(today(now))) {
            histories.put(code, new HistoryState(selected, now, null));
            return;
        }
        if (previous != null && previous.error() == null && usableHistory(previous.history(), now)
                && previous.history().receivedAt().atZone(TimedMarketQuote.SEOUL).toLocalDate().equals(today(now))) return;
        if (previous != null && !due(previous.attemptedAt(), now, HISTORY_RETRY_INTERVAL)) return;
        try {
            MarketHistory history = client.history(code, false);
            if (!usableHistory(history, clock.instant())) throw new IllegalStateException("보유 확정 일봉 확인 필요");
            histories.put(code, new HistoryState(history, now, null));
        } catch (RuntimeException exception) {
            histories.put(code, new HistoryState(previous == null ? null : previous.history(), now,
                    "보유 종목 확정 일봉 조회 또는 검증 실패"));
        }
    }

    /** Strictly current, corroborated trade/minute-bar price; never a close/average fallback. */
    public TimedMarketQuote freshQuote(String code) {
        if (!validCode(code) || collectionProblem != null) return null;
        Instant now = clock.instant();
        QuoteState state = quotes.get(code);
        TimedMarketQuote shared = watchlist.freshQuote(code);
        if (state != null && state.error() != null) {
            // A newer successful shared observation may recover from our failure.
            // The same old observation cannot become fresh again after a failed attempt.
            return shared != null && shared.receivedAt().isAfter(state.attemptedAt()) ? shared : null;
        }
        TimedMarketQuote own = state == null ? null : state.quote();
        if (own != null && own.freshnessProblem(now) != null) own = null;
        return newer(own, shared);
    }

    /** Last successful observed trade price, potentially stale; the caller must label its basis. */
    public TimedMarketQuote latestQuote(String code) {
        if (!validCode(code)) return null;
        QuoteState state = quotes.get(code);
        return newer(state == null ? null : state.quote(), watchlist.latestQuote(code));
    }

    /** Display only. Recovery never enters freshQuote or any order/risk input. */
    public TimedMarketQuote displayQuote(String code) {
        if (!validCode(code)) return null;
        Instant now = clock.instant();
        TimedMarketQuote observed = latestQuote(code);
        if (displayable(observed, code, now)) return observed;
        TimedMarketQuote restored = displayRecovery.get(code);
        if (displayable(restored, code, now)) return restored;
        try {
            List<TimedMarketQuote> saved = jdbc.query("""
                    SELECT price, trade_at, bar_at, received_at FROM market_data_sample
                    WHERE stock_code=? AND state='FRESH' AND source=?
                        AND price>0 AND trade_at IS NOT NULL AND bar_at IS NOT NULL AND received_at<=?
                    ORDER BY attempted_at DESC LIMIT 1
                    """, (rs, n) -> new TimedMarketQuote(code, rs.getBigDecimal("price"),
                    rs.getTimestamp("trade_at").toInstant(), rs.getTimestamp("bar_at").toInstant(),
                    rs.getTimestamp("received_at").toInstant()), code, client.sourcePrefix() + "_REST", now.atOffset(java.time.ZoneOffset.UTC));
            if (!saved.isEmpty() && displayable(saved.getFirst(), code, now)) {
                displayRecovery.put(code, saved.getFirst());
                return saved.getFirst();
            }
        } catch (RuntimeException ignored) { /* No fabricated fallback when persistence is unavailable. */ }
        return null;
    }

    private static boolean displayable(TimedMarketQuote quote, String code, Instant now) {
        return quote != null && code.equals(quote.code()) && quote.price() != null && quote.price().signum() > 0
                && quote.tradeAt() != null && quote.barAt() != null && quote.receivedAt() != null
                && !quote.receivedAt().isAfter(now) && quote.freshnessProblem(quote.receivedAt()) == null;
    }

    /** A current trade is evidence of this symbol's active session, not an exchange holiday calendar. */
    public boolean calendarSessionVerified(String code) { return freshQuote(code) != null; }

    /** Cached, completed-session average. No remote request is made by a dashboard/order read. */
    public BigDecimal average20(String code) {
        MarketHistory history = history(code);
        return history == null ? null : history.average20();
    }

    /** The exact cached history used for exit thresholds, including its real basis/receipt dates. */
    public MarketHistory history(String code) {
        if (!validCode(code) || collectionProblem != null) return null;
        Instant now = clock.instant();
        HistoryState state = histories.get(code);
        if (state != null) return state.error() == null && usableHistory(state.history(), now)
                ? state.history() : null;
        return universe.snapshot().stocks().stream().filter(stock -> code.equals(stock.code()))
                .map(UniverseService.ObservedStock::history).filter(history -> usableHistory(history, now))
                .findFirst().orElse(null);
    }

    /**
     * Counts proven sessions after the entry day. Today's session is counted only
     * when this symbol has a fresh corroborated trade, never by counting weekdays.
     * The recent history window may provide a lower bound for older holdings.
     */
    public int confirmedTradingDaysSince(String code, Instant openedAt) {
        return confirmedTradingDaysSince(code, openedAt, history(code));
    }

    /** Counts against the same history captured by an exit evaluation. */
    public int confirmedTradingDaysSince(String code, Instant openedAt, MarketHistory history) {
        Instant now = clock.instant();
        if (openedAt == null || openedAt.isAfter(now) || freshQuote(code) == null) return -1;
        LocalDate currentDay = today(now);
        if (!usableHistory(history, now) || history.bars() == null
                || !history.receivedAt().atZone(TimedMarketQuote.SEOUL).toLocalDate().equals(currentDay)) return -1;
        LocalDate entryDay = openedAt.atZone(TimedMarketQuote.SEOUL).toLocalDate();
        long completed = history.bars().stream().map(MarketHistory.CompletedBar::date)
                .filter(date -> date.isAfter(entryDay) && date.isBefore(currentDay)).distinct().count();
        return Math.toIntExact(completed + (currentDay.isAfter(entryDay) ? 1 : 0));
    }

    public CollectionStatus status() {
        return new CollectionStatus(30, lastCycleAt, heldCodes.size(), collectionProblem,
                !client.configured() ? "시세 키 설정 필요"
                        : !TimedMarketQuote.regularSession(clock.instant()) ? "장외 · 보유 시세 수집 대기"
                        : collectionProblem != null ? collectionProblem
                        : "보유 종목별 목표 30초 · 실제 응답 지연에 따라 간격 증가 가능");
    }

    private static boolean validCode(String code) { return code != null && code.matches("[0-9]{6}"); }
    private static boolean due(Instant attemptedAt, Instant now, Duration interval) {
        return attemptedAt == null || attemptedAt.isAfter(now) || Duration.between(attemptedAt, now).compareTo(interval) >= 0;
    }
    private static LocalDate today(Instant now) { return now.atZone(TimedMarketQuote.SEOUL).toLocalDate(); }
    private static boolean usableHistory(MarketHistory history, Instant now) {
        return history != null && history.average20() != null && history.average20().signum() > 0
                && history.completedSessions() >= 20 && history.basisDate().isBefore(today(now))
                && !history.basisDate().isBefore(today(now).minusDays(7)) && !history.receivedAt().isAfter(now);
    }
    private static TimedMarketQuote newer(TimedMarketQuote one, TimedMarketQuote two) {
        if (one == null) return two;
        if (two == null) return one;
        return two.receivedAt().isAfter(one.receivedAt()) ? two : one;
    }
    private record QuoteState(TimedMarketQuote quote, Instant attemptedAt, String error) {}
    private record HistoryState(MarketHistory history, Instant attemptedAt, String error) {}
    public record CollectionStatus(long targetIntervalSeconds, Instant lastCycleAt, int heldInstrumentCount,
                                   String issue, String detail) {}
}
