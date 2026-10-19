package com.personaltrader.web;

import com.personaltrader.research.*;
import com.personaltrader.committee.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

@RestController
public class ResearchStatusController {
    private final PublicInformationService information;private final DartFinancialService financials;
    private final MarketSessionService sessions;private final CommitteeReviewLibrary library;
    private final CommitteeAiLedger ledger;private final ResearchWorkspace workspace;private final ResearchDigestService digests;
    private final JdbcTemplate jdbc;private final Clock clock;
    public ResearchStatusController(PublicInformationService information,DartFinancialService financials,MarketSessionService sessions,
        CommitteeReviewLibrary library,CommitteeAiLedger ledger,ResearchWorkspace workspace,ResearchDigestService digests,JdbcTemplate jdbc,Clock clock){
        this.information=information;this.financials=financials;this.sessions=sessions;this.library=library;this.ledger=ledger;this.workspace=workspace;this.digests=digests;this.jdbc=jdbc;this.clock=clock;
    }
    @GetMapping("/api/research-status")public ResponseEntity<View> view(){var finance=financials.view();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(clock.instant(),false,true,false,false,
            Objects.equals(1,jdbc.queryForObject("SELECT 1",Integer.class)),sessions.view(),information.view(),
            finance.current(),finance.issue(),finance.report()==null?0:finance.report().companies().size(),library.view(),ledger.view(workspace.id()),
            digests.latest(),digests.issue(),List.of("원문 접근이 허용된 해외 기사 본문과 산업 비교 데이터 연결","공시 정정·사업 이슈와 수치 변화의 연결 검증","실제 휴대폰 로그인·좁은 화면 확인")));}
    public record View(Instant checkedAt,boolean researchOnly,boolean orderExecutionAvailable,boolean automaticAiEnabled,boolean automaticTradingEnabled,
        boolean databaseHealthy,MarketSessionService.View session,PublicInformationService.View information,
        boolean financialCurrent,String financialIssue,int financialCompanies,CommitteeReviewLibrary.Inbox reviews,
        CommitteeAiLedger.Budget budget,List<ResearchDigestService.Entry> digests,String digestIssue,List<String> nextSteps){}
}
