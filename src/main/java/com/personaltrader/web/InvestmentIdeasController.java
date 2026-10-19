package com.personaltrader.web;

import com.personaltrader.research.*;
import com.personaltrader.orders.LegacyPortfolioHistory;
import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.marketdata.MarketIndexCollector;
import com.personaltrader.discord.DiscordReportService;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InvestmentIdeasController {
    private final IdeaBoardService ideas;
    private final PublicInformationService information;
    private final MarketSessionService sessions;
    private final MarketIndexCollector indices;
    private final DiscordReportService discord;
    public InvestmentIdeasController(IdeaBoardService ideas,PublicInformationService information,
                                     MarketSessionService sessions,MarketIndexCollector indices,DiscordReportService discord){
        this.ideas=ideas;this.information=information;this.sessions=sessions;this.indices=indices;this.discord=discord;
    }
    @GetMapping("/ideas") public String page(){return "ideas";}
    @GetMapping("/api/ideas") @ResponseBody public ResponseEntity<Board> board(){return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new Board(ideas.view(),information.view(),sessions.view(),indices.snapshot(),discord.view()));}
    public record Board(IdeaBoardService.View ideas,PublicInformationService.View information,
                        MarketSessionService.View session,MarketIndexCollector.Snapshot indices,DiscordReportService.View discord){}
}
