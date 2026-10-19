package com.personaltrader.web;

import com.personaltrader.marketdata.MarketDataCollector;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketDataController {
    private final MarketDataCollector collector;
    public MarketDataController(MarketDataCollector collector) { this.collector = collector; }
    @GetMapping("/api/market-data")
    public ResponseEntity<MarketDataCollector.Snapshot> snapshot() {
        // UI polling reads the memory snapshot and never calls KIS.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collector.snapshot());
    }
}
