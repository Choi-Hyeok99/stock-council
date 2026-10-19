package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.research.*;
import com.personaltrader.marketdata.UniverseService;
import com.personaltrader.orders.LegacyPortfolioHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Pre-API evidence preparation. Reads existing collectors, never fetches news or submits orders. */
@Service
public class InvestmentCommitteeService {
    public static final String VERSION="COMMITTEE_RESEARCH_PACK_V8";
    @org.springframework.beans.factory.annotation.Autowired private CompanySourceService companySources;
    @org.springframework.beans.factory.annotation.Autowired private PublisherArticleService publisherArticles;
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    private final IdeaBoardService ideas;private final DartFinancialService financials;
    private final CompanyThesisService theses;private final PublicInformationService information;
    private final LegacyPortfolioHistory orders;private final UniverseService universe;
    private final CommitteeConversationService conversations;
    private final CommitteeAiCoordinator ai;
    private final TransactionTemplate tx;
    private volatile String issue;
    public InvestmentCommitteeService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,IdeaBoardService ideas,
        DartFinancialService financials,CompanyThesisService theses,PublicInformationService information,
        LegacyPortfolioHistory orders,
        UniverseService universe,PlatformTransactionManager transactions,CommitteeConversationService conversations,CommitteeAiCoordinator ai){
        this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.ideas=ideas;this.financials=financials;
        this.theses=theses;this.information=information;
        this.orders=orders;this.universe=universe;this.tx=new TransactionTemplate(transactions);this.conversations=conversations;this.ai=ai;
    }
    @Scheduled(fixedDelay=60000,initialDelay=55000)
    public synchronized void prepare(){
        try{
            String scope=orders.ledgerScope();if(scope==null)return;
            var board=ideas.view();
            if(board.entry()==null){
                var stored=ideas.referenceLatest();
                if(stored==null){issue="기업 연구 수집 대기";return;}
                board=new IdeaBoardService.View(stored,board.lastComputedAt(),false,board.selectionLabel(),board.issue(),board.changes(),board.cadence());
            }
            var account=orders.displayView();var finances=financials.view();var thesis=theses.view();var news=information.view();
            Map<String,InvestmentSignalEngine.Signal> selected=new LinkedHashMap<>();
            var requested=jdbc.queryForList("SELECT b.stock_code FROM committee_ai_job j JOIN committee_manual_request m ON m.job_id=j.id JOIN committee_brief b ON b.id=j.brief_id WHERE j.scope_id=? GROUP BY b.stock_code ORDER BY MAX(m.requested_at) DESC LIMIT 3",String.class,scope);
            for(String code:requested){var s=universe.holdingSignal(code);if(s==null)s=ideas.referenceSignal(code);if(s!=null)selected.put(s.code(),s);}
            board.entry().report().signals().forEach(s->selected.putIfAbsent(s.code(),s));
            for(var p:account.positions())if(!selected.containsKey(p.code())){
                var s=universe.holdingSignal(p.code());if(s!=null)selected.put(p.code(),s);
            }
            for(var s:selected.values().stream().limit(15).toList()){
                var holding=account.positions().stream().filter(p->p.code().equals(s.code())).findFirst().orElse(null);
                boolean signalFresh=s.chartReceivedAt()!=null&&!s.chartReceivedAt().isAfter(clock.instant())&&Duration.between(s.chartReceivedAt(),clock.instant()).compareTo(Duration.ofMinutes(20))<=0;
                var basis=signalFresh?board:new IdeaBoardService.View(board.entry(),board.lastComputedAt(),false,board.selectionLabel(),board.issue(),board.changes(),board.cadence());
                publish(scope,s,"BUY_REVIEW",holding,basis,finances,thesis,news,account);
            }
            issue=null;
        }catch(RuntimeException failure){issue="투자위원회 자료 저장 대기 · 이전 작성 시각을 확인하세요.";}
    }
    private void publish(String scope,InvestmentSignalEngine.Signal s,String agenda,LegacyPortfolioHistory.PositionView held,
        IdeaBoardService.View board,DartFinancialService.View finances,CompanyThesisService.View thesis,
        PublicInformationService.View news,LegacyPortfolioHistory.View account){
        Instant now=clock.instant();
        var company=finances.report()==null?null:finances.report().companies().stream().filter(c->c.code().equals(s.code())).findFirst().orElse(null);
        var business=thesis.entry()==null?null:thesis.entry().report().companies().stream().filter(c->c.code().equals(s.code())).findFirst().orElse(null);
        var valueReview=CommitteeValueReview.from(business,s.lastClose(),s.basisDate());
        var financialEvidence=CommitteeFinancialEvidence.from(company,now);
        var businessEvidence=CommitteeBusinessEvidence.from(company,companySources);
        var multiples=CommitteeMultiples.from(financialEvidence,business,s.lastClose(),s.basisDate(),now);
        List<Evidence> sources=new ArrayList<>();
        String valueSource="valuation:"+s.code()+":"+PublicInformationService.hash(json(valueReview));
        sources.add(new Evidence(valueSource,"가정 기반 기업가치 연구",valueReview.valuationState()+" · 실제 재무 입력의 잔여이익 시나리오 · 확정 적정가 아님",null,null,s.basisDate(),
            thesis.entry()==null?now:thesis.entry().generatedAt(),"VALUATION_ASSUMPTIONS","ISSUER_VALUE_RESEARCH"));
        sources.add(new Evidence("chart:"+s.code()+":"+s.basisDate(),"KIS 확정 일봉",
            "확정 종가 "+money(s.lastClose())+" · 20일 평균 "+money(s.average20()),null,null,s.basisDate(),s.chartReceivedAt(),"MARKET_VALUES","ISSUER_CHART"));
        if(s.explanation()!=null)sources.add(new Evidence("benchmark:"+s.basisDate(),"KIS 시장 비교",
            "동일 기간 시장 수익률 "+percent(s.explanation().benchmarkReturn()),null,null,s.basisDate(),s.chartReceivedAt(),"MARKET_VALUES","MARKET_CONTEXT_ONLY"));
        for(var e:information.evidence(s.code()).stream().limit(8).toList())sources.add(informationEvidence(e,PublicInformationService.evidenceKind(e)));
        for(var e:news.latest().stream().limit(4).toList())if(sources.stream().noneMatch(x->x.id().equals(e.id())))sources.add(informationEvidence(e,"MARKET_CONTEXT_ONLY"));
        for(var e:information.macroEvidence())if(sources.stream().noneMatch(x->x.id().equals(e.id())))sources.add(informationEvidence(e,"MARKET_CONTEXT_ONLY"));
        if(company!=null){
            List<DartFinancialService.Period> periods=new ArrayList<>(company.annuals());
            if(company.latestInterim()!=null)periods.add(company.latestInterim());
            periods.stream().filter(p->p.sourceUrl()!=null).limit(6).forEach(p->sources.add(new Evidence("financial:"+PublicInformationService.hash(p.sourceUrl()),"OpenDART",
                p.label()+" · "+p.fs()+" · "+p.state(),p.sourceUrl(),null,p.disclosureDate(),p.fetchedAt(),"FINANCIAL_VALUES","ISSUER_FINANCIAL")));
        }
        if(business!=null&&business.shares()!=null&&multiples.evidenceIds().contains("shares:"+business.shares().id())){
            var share=business.shares();String url=null;
            for(var item:share.payload().path("classes"))if(item.path("sourceUrl").asText().startsWith("https://dart.fss.or.kr/")){url=item.path("sourceUrl").asText();break;}
            sources.add(new Evidence("shares:"+share.id(),"OpenDART 주식 총수","공시 기준 보통주 유통주식 검증 · "+multiples.sharesAsOf(),url,null,null,share.fetchedAt(),"SHARES_VALUES","ISSUER_FINANCIAL"));
        }
        for(var p:businessEvidence.passages())if(sources.stream().noneMatch(e->e.id().equals(p.sourceId())))
            sources.add(new Evidence(p.sourceId(),"OpenDART 공시 본문",p.period()+" · 공식 본문 일부 발췌",p.sourceUrl(),null,p.disclosureDate(),p.fetchedAt(),"OFFICIAL_EXCERPT","ISSUER_BUSINESS"));
        if(business!=null&&business.business()!=null&&business.business().irUrl()!=null&&!business.business().irUrl().isBlank())
            sources.add(new Evidence("business:"+s.code(),"기업 공식 IR","기업 공식 자료",business.business().irUrl(),null,null,
                thesis.entry().generatedAt(),"OFFICIAL_LINK","ISSUER_PROFILE"));
        List<String> reasons=new ArrayList<>();
        reasons.add("사업의 1~3년 전망·현금 창출·가격 매력을 분리해 검토");
        if(multiples.revenueGrowthPercent()!=null)reasons.add("연간 매출 변화 "+percent(multiples.revenueGrowthPercent())+" · 성장 원인과 지속성 검증 필요");
        reasons.add("가격 하락과 가치 훼손을 구분 · 낮은 배수만으로 저평가를 확정하지 않음");
        List<String> cautions=new ArrayList<>(s.risks().stream().limit(4).toList());
        if(!board.current())cautions.add("연구 갱신 중 · 저장된 선행 자료이며 최신 승인 근거가 아님");
        if(!finances.current())cautions.add("재무 수집 최신 상태 확인 대기");
        cautions.add("미 연준 발표와 발행처 요약은 제공 범위 내 거시 맥락 · 해외 전체 기사·기업 영향 검증은 별도 · 기존 차트 검사 미달은 위원회 단독 거절 규칙이 아님");
        List<RolePreparation> roles=new ArrayList<>();
        for(var role:CommitteeContract.ROLES){
            List<String> facts=new ArrayList<>();List<String> ids=new ArrayList<>();
            switch(role.key()){
                case "ECONOMY"->{sources.stream().filter(e->e.source().equals("미 연준 통화정책")).forEach(e->{facts.add(e.title()+" · 제목만 확인 · 기업 영향은 별도 검증");ids.add(e.id());});if(s.explanation()!=null){facts.add("차트 비교 기간의 시장 수익률 "+percent(s.explanation().benchmarkReturn())+" · 거시 원인 분석 전");ids.add("benchmark:"+s.basisDate());}}
                case "NEWS"->{sources.stream().filter(e->Set.of("TITLE_ONLY","PUBLISHER_SUMMARY").contains(e.accessLevel())&&!e.relevance().equals("MARKET_CONTEXT_ONLY")).limit(3).forEach(e->{facts.add(e.title());ids.add(e.id());});}
                case "INDUSTRY"->{for(var p:businessEvidence.passages())if(Set.of("BUSINESS","OPERATIONS").contains(p.category())){facts.add(p.period()+" · "+p.title()+": "+p.text());ids.add(p.sourceId());}}
                case "FINANCE"->{CommitteeQuantitativeFacts.from(financialEvidence).forEach(f->facts.add(f.label()+": "+(f.value()==null?"입력 확인 대기":f.value().toPlainString()+f.unit())+" · "+f.period()));sources.stream().filter(e->e.accessLevel().equals("FINANCIAL_VALUES")).forEach(e->ids.add(e.id()));facts.add("기업가치 시나리오 "+valueReview.valuationState()+" · 금융업 별도 확인 "+valueReview.financialSectorReview());ids.add(valueSource);}
                case "MARKET"->{ids.add("chart:"+s.code()+":"+s.basisDate());facts.add("확정 종가 "+money(s.lastClose())+" · 20일 평균 "+money(s.average20()));facts.add("5일 변화 "+percent(s.change5())+" · RSI "+s.rsi14()+" · 기준일 "+s.basisDate());}
                case "RISK"->{facts.addAll(cautions.stream().limit(4).toList());}
                default->{/* No invented policy or scientific evidence. */}
            }
            roles.add(new RolePreparation(role.key(),role.name(),role.focus(),"WAITING_FOR_AI",null,
                facts.stream().filter(Objects::nonNull).toList(),List.of(role.missingRequirement(),"AI 독립 의견·반론·최종 투표 대기"),
                ids.stream().filter(id->sources.stream().anyMatch(e->e.id().equals(id))).distinct().toList()));
        }
        List<Metric> metrics=new ArrayList<>();
        if(company!=null){
            var periods=new ArrayList<>(company.annuals());if(company.latestInterim()!=null)periods.add(company.latestInterim());
            for(var p:periods) p.metrics().stream()
                .filter(m->Set.of("REVENUE","OPERATING_PROFIT","NET_PROFIT","OPERATING_CASH_FLOW","EQUITY").contains(m.key()))
                .forEach(m->metrics.add(new Metric(m.label(),m.value(),m.currency(),m.state(),p.label())));
        }
        BigDecimal latestPrice=held==null?null:held.currentPrice();
        BigDecimal quoteBand=latestPrice==null||s.average20()==null?BigDecimal.ZERO:latestPrice.divide(s.average20(),2,RoundingMode.HALF_UP);
        String fingerprint=PublicInformationService.hash(json(List.of(VERSION,s.code(),agenda,board.entry().id(),
            finances.id()==null?0:finances.id(),thesis.entry()==null?0:thesis.entry().id(),board.current(),finances.current(),
            quoteBand,now.getEpochSecond()/900,
            sources.stream().map(e->e.id()+":"+PublicInformationService.hash(e.title()+"\n"+Objects.toString(e.publisherSummary(),"")+"\n"+Objects.toString(e.articleExcerpt(),""))).toList())));
        Brief report=new Brief(VERSION,now,s.code(),s.name(),agenda,held!=null,board.entry().id(),finances.id(),
            thesis.entry()==null?null:thesis.entry().id(),board.current(),finances.current()&&financialEvidence.annualEvidenceReady(),s.basisDate(),s.chartReceivedAt(),
            "기업 연구 자료 준비 · AI 분석 요청 전",
            "공식 재무·사업 본문·확정 일봉과 발행처가 제공한 뉴스 요약을 정리했어요. 실제 AI 토론은 직접 요청한 뒤 저장돼요.",
            List.copyOf(reasons),List.copyOf(cautions),null,null,null,null,latestPrice,
            held==null?null:held.quoteAt(),held!=null&&held.quoteFresh(),List.copyOf(metrics),List.copyOf(roles),
            List.copyOf(sources),new CommitteeContract.Tally(false,0,0,0,0,null,false,List.of("AI API 연결 대기")),
            "WAITING_FOR_AI",false,"분석 참고 자료 · 투자 판단·직접 주문은 사용자 · 자동매매 없음",s.lastClose(),valueReview,financialEvidence,multiples,businessEvidence);
        if(!Objects.equals(scope,orders.ledgerScope()))throw new IllegalStateException("계좌 변경 대기");
        tx.executeWithoutResult(transaction->{
            List<Long> ids=jdbc.queryForList("INSERT INTO committee_brief(scope_id,stock_code,agenda,generated_at,fingerprint,report) VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING RETURNING id",
                Long.class,scope,s.code(),agenda,now.atOffset(ZoneOffset.UTC),fingerprint,json(report));
            if(!ids.isEmpty())jdbc.update("INSERT INTO committee_event(brief_id,occurred_at,event_type,detail) VALUES (?,?,?,?)",
                ids.getFirst(),now.atOffset(ZoneOffset.UTC),"EVIDENCE_PREPARED","기존 실제 자료를 고정 저장 · AI 발언/투표 생성0 · 주문 없음");
        });
    }
    public View view(Long selected,String code,String agenda,Integer quantity){
        if(selected!=null||code==null)return view(selected,quantity,selected==null);
        if(!code.matches("[0-9]{6}")||!Set.of("BUY_REVIEW","SELL_REVIEW").contains(agenda))throw new IllegalArgumentException("검토 대상 확인 필요");
        var ids=jdbc.queryForList("SELECT id FROM committee_brief WHERE scope_id=? AND stock_code=? AND agenda=? ORDER BY id DESC LIMIT 1",Long.class,orders.ledgerScope(),code,agenda);
        if(ids.isEmpty())throw new IllegalArgumentException("검토 자료 없음");
        return view(ids.getFirst(),quantity,true);
    }
    public View view(Long selected,Integer quantity,boolean currentSelection){
        String scope=orders.ledgerScope();
        List<Card> cards=scope==null?List.of():jdbc.query("""
            SELECT DISTINCT ON (stock_code,agenda) id,stock_code,agenda,generated_at,report->>'name' AS name,
              report->>'conclusion' AS conclusion FROM committee_brief WHERE scope_id=? AND generated_at>=?
            ORDER BY stock_code,agenda,id DESC
            """,(rs,n)->new Card(rs.getLong("id"),rs.getString("stock_code"),rs.getString("name"),rs.getString("agenda"),
                at(rs,"generated_at"),rs.getString("conclusion")),scope,clock.instant().minus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC));
        Entry entry=selected==null?(cards.isEmpty()?null:find(cards.stream().filter(c->c.agenda().equals("BUY_REVIEW")).mapToLong(Card::id).max().orElseGet(()->cards.stream().mapToLong(Card::id).max().orElseThrow()),scope).orElse(null)):
            find(selected,scope).orElseThrow(()->new IllegalArgumentException("현재 계좌의 위원회 기록을 찾지 못했습니다."));
        if(currentSelection&&entry!=null)entry=ai.referenceFor(entry);
        List<History> history=entry==null?List.of():jdbc.query("SELECT id,generated_at FROM committee_brief WHERE scope_id=? AND stock_code=? AND agenda=? ORDER BY id DESC LIMIT 12",
            (rs,n)->new History(rs.getLong("id"),at(rs,"generated_at")),scope,entry.report().code(),entry.report().agenda());
        List<Event> events=entry==null?List.of():jdbc.query("SELECT occurred_at,event_type,detail FROM committee_event WHERE brief_id=? ORDER BY id DESC LIMIT 30",
            (rs,n)->new Event(at(rs,"occurred_at"),rs.getString("event_type"),rs.getString("detail")),entry.id());
        List<Review> decisions=entry==null?List.of():jdbc.query("SELECT reviewed_at,decision,note FROM committee_review WHERE brief_id=? ORDER BY id DESC LIMIT 5",
            (rs,n)->new Review(at(rs,"reviewed_at"),rs.getString("decision"),rs.getString("note")),entry.id());
        var aiStatus=ai.status(entry);
        return new View(aiStatus.configured()?"AI_REVIEW_ONLY":"API_KEY_WAIT",clock.instant(),aiStatus.responseVerified(),false,entry,cards,history,events,decisions,
            CommitteeContract.ROLES,CommitteeCostPlan.view(aiStatus.budget().settledUsd()),issue,
            "DART 공식 재무·공시 발췌 + 국내 발행처·미 연준 RSS 요약 · 요약이 없는 항목은 제목만, 해외 기사 전체 본문은 미연결",List.of(
            new Step("자료 준비","구현"),new Step("8명 독립 분석",aiStatus.independentCount()+"/8 저장"),new Step("질문 답변·반론",aiStatus.debateCount()+"/8 저장"),new Step("최종 의견",aiStatus.finalCount()+"/8 저장"),
            new Step("최종6/8 투표",aiStatus.finalCount()==8?"실제 표 검증":"완성 대기"),new Step("사용자 검토","관심·보류 메모만 기록"),new Step("직접 매도","보유 화면에서 사용자 확인")),
            conversations.read(entry),aiStatus,candidates(cards,scope),entry==null?List.of():CommitteeQuantitativeFacts.from(entry.report().financialEvidence()),
            entry==null?List.of():CommitteeEvidenceCoverage.from(entry.report()),
            entry==null||!VERSION.equals(entry.report().version())?List.of():CommitteeResearchDossier.from(entry.report()));
    }
    /** Candidate previews read saved evidence/results only; selecting a card never starts a model call. */
    private List<Candidate> candidates(List<Card> cards,String scope){
        if(scope==null)return List.of();
        var result=new ArrayList<Candidate>();
        for(var card:cards.stream().filter(c->"BUY_REVIEW".equals(c.agenda())).limit(30).toList()){
            var saved=find(card.id(),scope).orElse(null);if(saved==null)continue;
            var e=ai.referenceFor(saved);var r=e.report();
            var states=jdbc.queryForList("SELECT state FROM committee_ai_job WHERE brief_id=? AND scope_id=?",String.class,e.id(),scope);
            String state=states.isEmpty()?"NOT_QUEUED":states.getFirst();
            var counts=jdbc.queryForList("SELECT phase,COUNT(*) AS count FROM committee_opinion WHERE brief_id=? GROUP BY phase",e.id());
            int done=counts.stream().mapToInt(x->((Number)x.get("count")).intValue()).sum();
            boolean ready=r.financialCurrent()&&r.financialEvidence()!=null&&r.financialEvidence().annualEvidenceReady();
            var m=r.multiples();var v=r.valueReview();
            String reason=ready?"공식 연간 재무 준비 · 사업 전망과 가격 매력 검토 대상":"공식 연간 재무 수집 중 · 자료가 준비되면 검토 가능";
            if(m!=null&&m.revenueGrowthPercent()!=null)reason="직전 연간 매출 변화 "+percent(m.revenueGrowthPercent())+" · 성장 지속성 검토 필요";
            String caution=v!=null&&!v.missing().isEmpty()?v.missing().getFirst():!r.cautions().isEmpty()?r.cautions().getFirst():"성장 가정과 반대 근거 확인 필요";
            result.add(new Candidate(e.id(),r.code(),r.name(),e.generatedAt(),state,ready,r.researchCurrent(),r.referenceClose(),r.chartBasisDate(),
                m==null?null:m.per(),m==null?null:m.pbr(),m==null?null:m.psr(),reason,caution,done,
                CommitteeContract.usesDebate(states.isEmpty()?CommitteeContract.PROMPT_VERSION:
                    jdbc.queryForObject("SELECT prompt_version FROM committee_ai_job WHERE brief_id=?",String.class,e.id()))?24:16));
        }
        return result.stream().sorted(Comparator.comparingInt(InvestmentCommitteeService::candidatePriority).thenComparing(Candidate::code)).toList();
    }
    private static int candidatePriority(Candidate c){
        if(Set.of("QUEUED","RUNNING","FINALIZING").contains(c.aiState()))return 0;
        if(!c.annualReady())return 4;
        if("NOT_QUEUED".equals(c.aiState()))return 1;
        return "COMPLETE".equals(c.aiState())?3:2;
    }
    public record Candidate(long briefId,String code,String name,Instant preparedAt,String aiState,boolean annualReady,
        boolean researchCurrent,BigDecimal referenceClose,LocalDate priceDate,BigDecimal per,BigDecimal pbr,BigDecimal psr,
        String reason,String caution,int savedOpinions,int expectedOpinions){}
    private Optional<Entry> find(long id,String scope){
        if(scope==null)return Optional.empty();
        return jdbc.query("SELECT id,generated_at,fingerprint,report FROM committee_brief WHERE scope_id=? AND id=?",(rs,n)->{
            try{return new Entry(rs.getLong("id"),at(rs,"generated_at"),rs.getString("fingerprint"),mapper.readValue(rs.getString("report"),Brief.class));}
            catch(Exception failure){throw new IllegalStateException("위원회 원본 확인 대기");}},scope,id).stream().findFirst();
    }
    public synchronized void review(long id,String action,String note,String clientKey){
        String scope=orders.ledgerScope();Entry entry=find(id,scope).orElseThrow(()->new IllegalArgumentException("현재 계좌의 기록이 아닙니다."));
        if(action==null||!Set.of("WATCH","DEFER","REJECT").contains(action))throw new IllegalArgumentException("관심·보류·제외 의견만 기록할 수 있어요.");
        if(note==null||note.length()>500||clientKey==null||!clientKey.matches("[a-zA-Z0-9-]{16,64}"))throw new IllegalArgumentException("검토 입력 확인 필요");
        String clean=PublicInformationService.plain(note,500);Instant now=clock.instant();
        tx.executeWithoutResult(transaction->{
            List<Long> ids=jdbc.queryForList("INSERT INTO committee_review(brief_id,reviewed_at,decision,note,client_key) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING id",
                Long.class,entry.id(),now.atOffset(ZoneOffset.UTC),action,clean,clientKey);
            if(!ids.isEmpty())jdbc.update("INSERT INTO committee_event(brief_id,occurred_at,event_type,detail,client_key) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                id,now.atOffset(ZoneOffset.UTC),"USER_REVIEW",switch(action){case "WATCH"->"사용자 관심 유지 · 주문 없음";case "DEFER"->"사용자 검토 보류 · 주문 없음";default->"SELL_REVIEW".equals(entry.report().agenda())?"사용자 매도 거절 · 이 기록의 주문 없음 · 보유 유지":"사용자 매수 거절 · 이 기록의 주문 없음";},clientKey);
        });
    }
    public String markdown(long id){
        Entry e=find(id,orders.ledgerScope()).orElseThrow(()->new IllegalArgumentException("기록 없음"));var r=e.report();
        StringBuilder text=new StringBuilder("# "+r.name()+" · 투자위원회 기록\n\n작성: "+r.preparedAt()+"\n\n"+r.conclusion()+"\n\n"+r.summary()+"\n\n## 검토 이유\n");
        r.reasons().forEach(x->text.append("- ").append(x).append('\n'));text.append("\n## 반대 근거·미확인\n");
        r.cautions().forEach(x->text.append("- ").append(x).append('\n'));text.append("\n## 역할별 준비 자료 — AI 의견 아님\n");
        r.roles().forEach(x->{text.append("\n### ").append(x.name()).append(" · 연결 대기\n");x.observations().forEach(f->text.append("- ").append(f).append('\n'));});
        text.append("\n## 원문 자료\n");r.evidence().forEach(x->text.append("- ").append(x.title()).append(x.url()==null?"":" · "+x.url()).append('\n'));
        var conversation=conversations.read(e);var status=ai.status(e);
        if(r.multiples()!=null)text.append("\n## 참고 배수\n\n").append(json(r.multiples())).append('\n');
        if(status.summary()!=null){var summary=status.summary();text.append("\n## 실제 종합 결론\n\n").append(summary.summary()).append('\n');
            if(summary.assessment()!=null)text.append("\n관심 분류: ").append(summary.assessment()).append("\n\n상승 논거: ").append(summary.bullCase())
                .append("\n\n강한 반론: ").append(summary.bearCase()).append("\n\n재판단 조건: ").append(summary.reconsiderWhen()).append('\n');}
        text.append("\n## 실제 저장된 AI 발언\n");
        for(var m:conversation.messages()){
            text.append("\n### ").append(m.roleName()).append(" · ").append(m.phase()).append(" · ").append(m.vote()).append("\n\n").append(m.summary()).append('\n');
            var d=m.discussion();if(d!=null){if(!d.question().isBlank())text.append("\n질문 → ").append(d.questionTo()).append(": ").append(d.question()).append('\n');
                if(!d.reply().isBlank())text.append("\n답변 → ").append(d.replyTo()).append(": ").append(d.reply()).append('\n');
                text.append("\n강한 반론: ").append(d.counterpoint()).append("\n\n판단 변경 조건: ").append(d.changeCondition()).append('\n');}
            if(m.changedReason()!=null&&!m.changedReason().isBlank())text.append("\n최종 판단 설명: ").append(m.changedReason()).append('\n');
            text.append("\n출처 ID: ").append(String.join(", ",m.evidenceIds())).append('\n');
        }
        var tally=conversation.tally();text.append("\n최종 표: ").append(tally.received()).append("/8 · 찬성 ").append(tally.approve()).append(" · 반대 ").append(tally.reject()).append(" · 기권 ").append(tally.abstain());
        return text.append("\n\n실제 저장된 의견만 포함 · 과거 기록은 당시 기준 · 승인/주문 연결 비활성\n").toString();
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("위원회 저장 형식 확인 필요");}}
    private static Instant at(ResultSet rs,String key)throws SQLException{return rs.getObject(key,OffsetDateTime.class).toInstant();}
    private static String money(BigDecimal x){return x==null?"자료 없음":x.setScale(0,RoundingMode.HALF_UP).toPlainString()+"원";}
    private static String percent(BigDecimal x){return x==null?"확인 대기":x.setScale(2,RoundingMode.HALF_UP).toPlainString()+"%";}
    public record Evidence(String id,String source,String title,String url,Instant publishedAt,LocalDate publishedDate,
        Instant receivedAt,String accessLevel,String relevance,String publisherSummary,boolean summaryTruncated,String articleExcerpt,Instant articleFetchedAt,boolean articleTruncated){
        public Evidence(String id,String source,String title,String url,Instant publishedAt,LocalDate publishedDate,
            Instant receivedAt,String accessLevel,String relevance){this(id,source,title,url,publishedAt,publishedDate,receivedAt,accessLevel,relevance,null,false,null,null,false);}
    }
    private Evidence informationEvidence(PublicInformationService.Evidence e,String relevance){
        var article=publisherArticles.latest(e.id());
        boolean body=article!=null&&"READY".equals(article.state())&&article.text()!=null&&!article.fetchedAt().isAfter(clock.instant())
            &&Duration.between(article.fetchedAt(),clock.instant()).compareTo(Duration.ofHours(26))<=0;
        return new Evidence(e.id(),e.source(),e.title(),e.url(),e.publishedAt(),e.publishedDate(),e.receivedAt(),
            body?"PUBLISHER_EXCERPT":e.contentLevel(),relevance,e.publisherSummary(),e.summaryTruncated(),
            body?article.text():null,body?article.fetchedAt():null,body&&article.truncated());
    }
    public record RolePreparation(String key,String name,String focus,String status,String vote,List<String>observations,List<String>missing,List<String>evidenceIds){}
    public record Metric(String label,BigDecimal value,String currency,String state,String period){}
    public record Brief(String version,Instant preparedAt,String code,String name,String agenda,boolean held,long researchId,
        Long financialId,Long thesisId,boolean researchCurrent,boolean financialCurrent,LocalDate chartBasisDate,Instant chartReceivedAt,
        String conclusion,String summary,List<String>reasons,List<String>cautions,BigDecimal cumulativeBudgetCap,BigDecimal remainingAllocation,
        BigDecimal entryLow,BigDecimal entryHigh,BigDecimal observedPrice,Instant quoteAt,boolean quoteFresh,List<Metric>metrics,
        List<RolePreparation>roles,List<Evidence>evidence,CommitteeContract.Tally tally,String state,boolean approvalEnabled,String approvalNote,
        BigDecimal referenceClose,CommitteeValueReview valueReview,CommitteeFinancialEvidence financialEvidence,CommitteeMultiples multiples,CommitteeBusinessEvidence businessEvidence){}
    public record Entry(long id,Instant generatedAt,String fingerprint,Brief report){}
    public record Card(long id,String code,String name,String agenda,Instant generatedAt,String conclusion){}
    public record History(long id,Instant generatedAt){}
    public record Event(Instant at,String type,String detail){}
    public record Review(Instant at,String decision,String note){}
    public record Step(String label,String state){}
    public record View(String mode,Instant checkedAt,boolean aiConnected,boolean orderExecutionEnabled,Entry entry,List<Card>cards,
        List<History>history,List<Event>events,List<Review>reviews,List<CommitteeContract.Role>roles,CommitteeCostPlan.View costs,
        String issue,String foreignNewsStatus,List<Step>steps,
        CommitteeConversationService.Conversation conversation,CommitteeAiCoordinator.Status aiStatus,List<Candidate> candidates,List<CommitteeQuantitativeFacts.Fact> quantitativeFacts,List<CommitteeEvidenceCoverage.RoleCoverage> coverage,List<CommitteeResearchDossier> researchDossiers){}
}
