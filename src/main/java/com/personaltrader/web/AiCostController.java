package com.personaltrader.web;

import com.personaltrader.committee.CommitteeCostDashboard;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AiCostController {
    private final CommitteeCostDashboard costs;
    public AiCostController(CommitteeCostDashboard costs) { this.costs=costs; }
    @GetMapping("/ai-costs") public String page() { return "ai-costs"; }
    @GetMapping("/api/ai-costs") @ResponseBody
    public ResponseEntity<CommitteeCostDashboard.View> data() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(costs.view());
    }
}
