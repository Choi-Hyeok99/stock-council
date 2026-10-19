package com.personaltrader.web;

import com.personaltrader.marketdata.MarketIndexCollector;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MarketIndexController {
    private final MarketIndexCollector collector;
    public MarketIndexController(MarketIndexCollector collector) { this.collector = collector; }
    @GetMapping("/api/market-indices")
    public ResponseEntity<MarketIndexCollector.Snapshot> snapshot() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collector.snapshot());
    }
}
