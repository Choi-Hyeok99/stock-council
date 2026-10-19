package com.personaltrader.web;

import com.personaltrader.marketdata.*;
import com.personaltrader.analysis.BotStatus;
import com.personaltrader.analysis.CandidateAnalysisService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class UniverseController {
    private final UniverseService universe;
    private final WatchlistQuoteCollector quotes;
    private final CandidateAnalysisService analysis;
    public UniverseController(UniverseService universe, WatchlistQuoteCollector quotes, CandidateAnalysisService analysis) {
        this.universe = universe; this.quotes = quotes; this.analysis = analysis;
    }
    @GetMapping("/api/universe")
    public ResponseEntity<UniverseView> snapshot() {
        UniverseService.Snapshot snapshot = universe.snapshot();
        var stocks = quotes.views(snapshot);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new UniverseView(snapshot, stocks, analysis.analyze(snapshot, stocks).bots(), quotes.status()));
    }
    public record UniverseView(UniverseService.Snapshot selection, List<WatchlistQuoteCollector.StockView> stocks, List<BotStatus> bots,
                               WatchlistQuoteCollector.CollectionStatus collection) {}
}
