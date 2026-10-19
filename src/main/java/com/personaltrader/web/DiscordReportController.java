package com.personaltrader.web;

import com.personaltrader.discord.DiscordReportService;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class DiscordReportController {
    private final DiscordReportService reports;
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.discord.DiscordReportFormatter formatter;
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.IdeaBoardService ideas;
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.CompanyThesisService theses;
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.MarketSessionService sessions;
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
    public DiscordReportController(DiscordReportService reports){this.reports=reports;}
    /** Authenticated stored-state read only. Never sends a message or returns the webhook URL. */
    @GetMapping("/api/discord") @ResponseBody
    public ResponseEntity<DiscordReportService.View> status(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.view());
    }
    /** Authenticated formatting preview from stored snapshots only. No send, queue insert or remote collection. */
    @GetMapping("/api/discord-preview") @ResponseBody
    public ResponseEntity<Preview> preview(){
        var now=clock.instant();var board=ideas.view();var company=theses.view();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Preview(now,false,
            formatter.ideas(board.current()?board.entry():null,now,"📌 관심 기업 업데이트",sessions.reportLabel()),
            company.entry()==null?java.util.List.of():formatter.theses(company.entry(),now,"기업 검토 요약")));
    }
    public record Preview(java.time.Instant formattedAt,boolean sent,java.util.List<com.personaltrader.discord.DiscordReportFormatter.Packet> ideas,
        java.util.List<com.personaltrader.discord.DiscordReportFormatter.Packet> companies){}
}
