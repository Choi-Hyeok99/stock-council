package com.personaltrader.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import com.personaltrader.marketdata.UniverseService;
import com.personaltrader.marketdata.WatchlistQuoteCollector;
import com.personaltrader.marketdata.MarketIndexCollector;

@Service
@Profile("paper")
public class CandidateAnalysisService {
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.PaperPortfolioPolicy policy;
    private final UniverseService universe;
    private final WatchlistQuoteCollector quotes;
    private final RiskBot riskBot;
    private final NewsEventBot newsEventBot;
    private final InvestorReferenceBot investorReferenceBot;
    private final com.personaltrader.education.InvestmentKnowledge knowledge;
    private final MarketIndexCollector indices;
    private final com.personaltrader.research.BerkshireReviewService berkshire;

    public CandidateAnalysisService(UniverseService universe, WatchlistQuoteCollector quotes, RiskBot riskBot,
                                   NewsEventBot newsEventBot, InvestorReferenceBot investorReferenceBot,
                                   com.personaltrader.education.InvestmentKnowledge knowledge, MarketIndexCollector indices, com.personaltrader.research.BerkshireReviewService berkshire) {
        this.universe = universe;
        this.quotes = quotes;
        this.riskBot = riskBot;
        this.newsEventBot = newsEventBot;
        this.investorReferenceBot = investorReferenceBot;
        this.knowledge = knowledge;
        this.indices = indices;
        this.berkshire = berkshire;
    }

    public DashboardAnalysis analyze() {
        UniverseService.Snapshot selection = universe.snapshot();
        List<WatchlistQuoteCollector.StockView> stocks = quotes.views(selection);
        return analyze(selection, stocks);
    }

    public DashboardAnalysis analyze(UniverseService.Snapshot selection, List<WatchlistQuoteCollector.StockView> stocks) {
        return analyze(selection, stocks, indices.snapshot());
    }
    public DashboardAnalysis analyze(UniverseService.Snapshot selection, List<WatchlistQuoteCollector.StockView> stocks,
                                     MarketIndexCollector.Snapshot marketIndices) {
        UniverseService.IndexData kospi = selection.indices().stream().filter(index -> index.code().equals("0001")).findFirst().orElse(null);
        MarketIndexCollector.IndexView latest = marketIndices.indices().stream().filter(index -> index.code().equals("0001")).findFirst().orElseThrow();
        boolean marketReady = selection.usable() && kospi != null && latest.fresh();
        boolean belowAverage=marketReady&&latest.quote().value().compareTo(kospi.history().average20())<=0;
        boolean marketPass = marketReady && (policy!=null&&policy.fallingMarketEntries()||latest.quote().value().compareTo(kospi.history().average20()) > 0);
        String marketDetail = (latest.quote() == null ? "최신 KOSPI 연결 대기" : "KOSPI " + latest.quote().value().toPlainString()
                + " · 기준일 " + latest.quote().businessDate() + " · 기준 시각 " + (latest.quote().basisAt() == null ? "미확인" : latest.quote().basisAt().atZone(com.personaltrader.marketdata.TimedMarketQuote.SEOUL)))
                + " · " + latest.label() + (kospi == null ? " · 평균 연결 대기" : " · " + kospi.history().basisDate() + "까지 확정 일봉 20일 평균 " + kospi.history().average20().toPlainString());
        long fresh = stocks.stream().filter(WatchlistQuoteCollector.StockView::fresh).count();
        List<BotStatus> bots = List.of(
                new BotStatus("Universe Bot", selection.state(), selection.stocks().size() + "개 관찰 종목 자동 선정", selection.detail()),
                new BotStatus("Market Bot", !marketReady ? "UNKNOWN" : marketPass ? belowAverage?"FALLING_ALLOWED":"NORMAL" : "CAUTION",
                        !marketReady ? "신선한 지수·선정 입력 대기" : marketPass ? belowAverage?"KOSPI 평균 아래 · 재무/기사 검토 후 모의 분할 매수 허용":"최신 KOSPI가 확정 일봉 20일 평균 위" : "KOSPI 조건 미충족", marketDetail),
                new BotStatus("Stock Bot", fresh > 0 ? "OBSERVING" : "WAIT", fresh + " / " + stocks.size() + "개 신선한 체결 시세",
                        "실제 확정 일봉 5/20일 평균과 검증된 체결가 비교 · 주문 환경별 사전 관문 재확인"),
                newsEventBot.summarize(),
                investorReferenceBot.summarize(),
                riskBot.summarize()
        );
        List<TradeCandidate> candidates = stocks.stream().map(stock -> new TradeCandidate(
                stock.stock().code(), stock.stock().name(), stock.price(), stock.stock().history().average5(),
                stock.stock().history().average20(), marketPass, stock.trendPass(), stock.riskPass(),
                stock.quantity(), stock.estimatedLoss(), stock.reason())).toList();
        List<BotStatus> educated = bots.stream().map(bot -> new BotStatus(bot.name(), bot.state(), bot.summary(),
                bot.detail() + " · 교육 기준: " + knowledge.roleBrief(bot.name()))).toList();
        List<BotStatus> all = new java.util.ArrayList<>(educated);
        all.addAll(berkshire.roles());
        return new DashboardAnalysis(List.copyOf(all), candidates);
    }

    public record DashboardAnalysis(List<BotStatus> bots, List<TradeCandidate> candidates) {
    }
}
