package com.personaltrader.marketdata;

import com.personaltrader.analysis.RiskAssessment;
import com.personaltrader.analysis.RiskBot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WatchlistQuoteCollector {
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.PaperEntryReviewService entryReviews;
    private final UniverseService universe;
    private final KisMarketDataClient client;
    private final RiskBot risk;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final Map<String, QuoteResult> quotes = new ConcurrentHashMap<>();
    private final long intervalMs;
    private final DipEntryGate dip;
    private final com.personaltrader.research.PaperPortfolioPolicy policy;
    private volatile Instant cycleStartedAt, cycleCompletedAt;
    private volatile boolean collecting;
    private volatile int targetCount, successCount, failureCount;
    public WatchlistQuoteCollector(UniverseService universe, KisMarketDataClient client, RiskBot risk, Clock clock, JdbcTemplate jdbc,
                                   @Value("${kis.collection-interval-ms:60000}") long intervalMs, DipEntryGate dip, com.personaltrader.research.PaperPortfolioPolicy policy) {
        if (intervalMs < 60000) throw new IllegalArgumentException("관찰 시세 간격은 최소 60초");
        this.universe = universe; this.client = client; this.risk = risk; this.clock = clock; this.jdbc = jdbc;
        this.intervalMs = intervalMs; this.dip=dip; this.policy=policy;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 20000)
    public synchronized void collect() {
        List<UniverseService.ObservedStock> targets = universe.quoteTargets();
        Instant now = clock.instant();
        if (targets.isEmpty() || cycleStartedAt != null && Duration.between(cycleStartedAt, now).toMillis() < intervalMs) return;
        cycleStartedAt = now; cycleCompletedAt = null; collecting = true; targetCount = targets.size(); successCount = 0; failureCount = 0;
        Set<String> current = new HashSet<>();
        universe.snapshot().stocks().forEach(stock -> current.add(stock.code()));
        quotes.keySet().removeIf(code -> !current.contains(code));
        try { for (UniverseService.ObservedStock stock : targets) {
            if (!TimedMarketQuote.regularSession(clock.instant())) break;
            Instant attempt = clock.instant();
            TimedMarketQuote quote = null;
            String error = null;
            try {
                quote = client.collect(stock.code());
                if (quote.freshnessProblem(clock.instant()) != null) { error = "기준 날짜·시각이 오래되어 거래 사용 불가"; quote = null; }
            }
            catch (RuntimeException exception) { error = "체결·분봉 조회 또는 시각 검증 실패"; }
            QuoteResult old = quotes.get(stock.code());
            try {
                jdbc.update("""
                        INSERT INTO market_data_sample (stock_code, source, state, price, trade_at, bar_at,
                            received_at, attempted_at, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, stock.code(), client.sourcePrefix() + "_REST", error == null ? "FRESH" : "ERROR", quote == null ? null : quote.price(),
                        quote == null ? null : utc(quote.tradeAt()), quote == null ? null : utc(quote.barAt()),
                        quote == null ? null : utc(quote.receivedAt()), utc(attempt), error == null ? "자동 선정 관찰 종목 시세" : error);
                quotes.put(stock.code(), new QuoteResult(quote == null && old != null ? old.quote() : quote, error, attempt));
            } catch (RuntimeException exception) {
                quotes.put(stock.code(), new QuoteResult(quote == null && old != null ? old.quote() : quote, "시세 기록 저장 실패", attempt));
            }
            if (quotes.get(stock.code()).error() == null) successCount++; else failureCount++;
        } } finally { cycleCompletedAt = clock.instant(); collecting = false; }
    }

    public List<StockView> views(UniverseService.Snapshot selection) {
        Instant now = clock.instant();
        return selection.stocks().stream().map(stock -> {
            QuoteResult result = quotes.get(stock.code());
            TimedMarketQuote quote = result == null ? null : result.quote();
            boolean fresh = selection.usable() && quote != null && result.error() == null && quote.code().equals(stock.code())
                    && quote.freshnessProblem(now) == null;
            BigDecimal price = quote == null ? stock.history().lastClose() : quote.price();
            var signal=universe.signal(stock.code());
            if(entryReviews!=null)signal=entryReviews.qualify(signal);
            RiskAssessment assessment = risk.assess(price,signal==null?BigDecimal.ZERO:signal.targetBudget());
            boolean legacyTrend = fresh && price.compareTo(stock.history().average20()) > 0
                    && stock.history().average5().compareTo(stock.history().average20()) > 0
                    && price.compareTo(stock.history().average5()) > 0
                    && price.compareTo(stock.history().average20().multiply(new BigDecimal("1.15"))) < 0
                    && signal != null && signal.paperEligible();
            boolean trendPass=policy.dipEntry()?fresh&&signal!=null&&signal.paperEligible()&&dip.evaluate(quote,stock.history().average20()).ready():legacyTrend;
            String label = fresh ? "시각 검증 통과" : result != null && result.error() != null ? "조회 실패"
                    : quote == null ? "체결 시세 대기" : "시세 사용 불가";
            return new StockView(stock, price, quote == null ? "최근 확정 일봉 종가 · 거래용 시세 대기"
                    : fresh ? "검증된 체결가" : "마지막 성공 체결가 · 거래 사용 불가", label,
                    quote == null ? null : quote.tradeAt(), quote == null ? null : quote.receivedAt(), fresh, trendPass,
                    assessment.pass(), assessment.quantity(), assessment.estimatedLoss(),
                    !fresh ? "ERROR".equals(selection.state()) ? "이전 관찰 목록 · 선정 재확인과 신선한 시세 대기"
                            : "장외 또는 신선한 체결 시세 대기 · 관찰 자료로만 표시" : !trendPass ? "관찰 유지 · 가격/상대 강도/모멘텀/과열 조건 미충족"
                            : !assessment.pass()?assessment.reason():policy.dipEntry()?"하락장 재무·기사 점검 통과 · 분할/계좌/한도 재확인":"추세 조건 통과 · 계좌·뉴스 정책·위험 한도는 주문 직전 재확인",
                    result == null ? null : result.attempt(), quote == null ? null : Math.max(0, Duration.between(quote.tradeAt(), now).getSeconds()),
                    result == null ? null : result.error());
        }).toList();
    }
    public TimedMarketQuote freshQuote(String code) {
        QuoteResult result = quotes.get(code);
        return result != null && result.error() == null && result.quote() != null
                && code.equals(result.quote().code()) && result.quote().freshnessProblem(clock.instant()) == null
                ? result.quote() : null;
    }
    public TimedMarketQuote latestQuote(String code) {
        QuoteResult result = quotes.get(code);
        return result == null ? null : result.quote();
    }
    private OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    public CollectionStatus status() {
        boolean open = TimedMarketQuote.regularSession(clock.instant());
        return new CollectionStatus(intervalMs / 1000, cycleStartedAt, cycleCompletedAt,
                !open || cycleStartedAt == null ? null : cycleStartedAt.plusMillis(intervalMs),
                collecting, targetCount, successCount, failureCount, !open ? "장외 · 체결 시세 수집 대기"
                : collecting ? "관찰 종목 전체 순차 조회 중" : "목표 간격은 수집 시작 시각 기준 · 지연·실패는 실제 기록으로 확인");
    }
    private record QuoteResult(TimedMarketQuote quote, String error, Instant attempt) {}
    public record StockView(UniverseService.ObservedStock stock, BigDecimal price, String priceLabel, String label,
                            Instant tradeAt, Instant receivedAt, boolean fresh, boolean trendPass, boolean riskPass,
                            int quantity, BigDecimal estimatedLoss, String reason, Instant lastAttemptAt, Long ageSeconds, String collectionError) {}
    public record CollectionStatus(long targetIntervalSeconds, Instant cycleStartedAt, Instant cycleCompletedAt,
                                    Instant nextDueAt, boolean collecting, int targetCount, int successCount,
                                    int failureCount, String detail) {}
}
