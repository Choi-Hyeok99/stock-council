package com.personaltrader.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.analysis.BotStatus;
import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.broker.ExecutionBrokerClient.Environment;
import com.personaltrader.education.InvestmentKnowledge;
import com.personaltrader.marketdata.*;
import com.personaltrader.orders.PositionQuoteCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Evidence audit over stored real observations. No HTTP, invented accounts, fair values or orders. */
@Service
public class BerkshireReviewService {
    @org.springframework.beans.factory.annotation.Autowired private ResearchWorkspace workspace;

    private final InvestmentKnowledge knowledge;
    private final PublicInformationService information;
    private final DartFinancialService financials;
    private final CompanyThesisService theses;
    private final UniverseService universe;
    private final PositionQuoteCollector quotes;
    private final ExecutionBrokerClient broker;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile Entry latest;
    private volatile String latestScope;
    private volatile String issue;
    public BerkshireReviewService(InvestmentKnowledge knowledge, PublicInformationService information,
            UniverseService universe, PositionQuoteCollector quotes, ExecutionBrokerClient broker,
            @Value("${execution.environment:PAPER}") String environment, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            DartFinancialService financials,CompanyThesisService theses) {
        this.knowledge=knowledge; this.information=information; this.universe=universe; this.quotes=quotes;
        this.broker=broker; this.environment=Environment.valueOf(environment.toUpperCase(Locale.ROOT));
        this.jdbc=jdbc; this.mapper=mapper; this.clock=clock;
        this.financials=financials;this.theses=theses;
    }
    private String scope() { return broker.configured(environment)?broker.accountKey(environment):null; }
    @Scheduled(fixedDelay=60000,initialDelay=3000)
    public synchronized void review() {
        try {
            String scope=scope(); Instant now=clock.instant(); var selection=universe.snapshot();
            var inputs=information.view();
            var financeView=financials.view();
            Map<String,DartFinancialService.Company> financeCompanies=new HashMap<>();
            if(financeView.report()!=null&&financeView.generatedAt()!=null&&!financeView.generatedAt().isAfter(now))
                financeView.report().companies().forEach(c->financeCompanies.put(c.code(),c));
            Map<String,Holding> held=new LinkedHashMap<>();
            if(scope!=null) for(var h:jdbc.query("""
                SELECT p.stock_code,p.stock_name,p.quantity,p.average_price,p.opened_at,p.entry_order_id,o.reason
                FROM execution_position p JOIN execution_order o ON o.id=p.entry_order_id
                WHERE p.scope_id=? ORDER BY p.opened_at,p.stock_code
                """,(rs,n)->new Holding(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getBigDecimal(4),
                    rs.getObject(5,OffsetDateTime.class).toInstant(),rs.getLong(6),rs.getString(7)),scope)) held.put(h.code(),h);
            Map<String,String> companies=new LinkedHashMap<>();
            held.values().forEach(h->companies.put(h.code(),h.name()));
            selection.stocks().forEach(s->companies.putIfAbsent(s.code(),s.name()));
            List<CompanyReview> reviews=new ArrayList<>();
            for(var company:companies.entrySet()) {
                String code=company.getKey(); Holding h=held.get(code);
                var evidence=information.evidence(code).stream()
                    .filter(e->!e.discoveredAt().isAfter(now)&& (e.publishedAt()==null||!e.publishedAt().isAfter(now)))
                    .toList();
                var classified=evidence.stream().map(InvestmentSignalEngine::classify).toList();
                boolean alert=classified.stream().anyMatch(e->e.classification().equals("RISK_TITLE"))
                    || evidence.stream().anyMatch(e->e.title().contains("정정")||e.title().contains("철회"));
                var finance=financeCompanies.get(code);
                var thesis=theses.currentCompany(code);
                String financialReason=finance==null?"DART 다년 재무 수집 대기":
                    "연간 "+finance.annualCollected()+"/"+finance.annualRequired()+"개 수집 · "+
                    (finance.latestInterim()==null?"최신 분기 대기":finance.latestInterim().label()+" 수집")+
                    " · 재무 수치 관찰 단계, 감사/주석/차입 만기 검토와 기업 가치 확정 아님"+
                    (!financeView.current()?" · 현재 수집 상태 미확인":"");
                List<Pillar> pillars=List.of(
                    new Pillar("BUSINESS","사업·경쟁력",thesis!=null&&thesis.businessLinked()?"DATA_REVIEW":"UNKNOWN",thesis!=null&&thesis.businessLinked()?"공식 사업보고서의 사업 개요와 강점/반대 가설 연결 · /theses에서 확인 · 지속 경쟁력 검증 완료 아님":"공식 사업 원문과 요약 연결 대기","사업·경쟁력 봇"),
                    new Pillar("FINANCIAL","현금흐름·부채",finance!=null&&finance.annualCollected()>0?"DATA_REVIEW":"UNKNOWN",financialReason,"재무 검토 봇"),
                    new Pillar("VALUATION","가격·안전마진",thesis!=null&&!thesis.valuation().scenarios().isEmpty()?"DATA_REVIEW":"UNKNOWN",thesis!=null?"/theses 공식 입력·보수/기준/낙관 가정·민감도 · "+thesis.valuation().shareNote()+" · 가정 및 정상화 이익 검증 완료 아님":"공식 지배주주 입력·주식 수 확인 대기","가치평가 봇"),
                    new Pillar("MANAGEMENT","경영·자본배분","UNKNOWN","주당 성과·투자/인수·자사주/배당·희석·감사의견 미검토","자본배분 봇"),
                    new Pillar("EVENT","공시·뉴스 위험",!inputs.collectionHealthy()?"UNKNOWN":alert?"REVIEW_REQUIRED":"TITLE_ONLY",
                        !inputs.collectionHealthy()?"핵심 피드 수집 상태 확인 필요":alert?"위험/정정 제목 발견 · 원문 확인 필요 · 사실 악화 확정 아님":
                        evidence.isEmpty()?"최근30일 제한 조회에서 연결 제목 없음 · 위험 없음의 증거 아님":"연결 제목 "+evidence.size()+"건 · 본문/금액 영향 미검증","News & Event Bot"));
                String holdReason=h==null?"사업·재무·가치 근거를 관찰 · 자동 매수 없음":
                    "과거 매수 근거를 보존하며 사업 가설을 재검토 · +5%는 비용 후 금액 비교의 참고 기준 · 매도/취소는 사용자 확인";
                var q=h==null?null:quotes.freshQuote(code);
                if(q!=null&&q.receivedAt().isAfter(now))q=null;
                List<RiskCheck> riskChecks=h==null?List.of():List.of(
                    new RiskCheck("자동 매수·매도·익절·취소 없음","DISABLED",q==null?null:q.price(),null,q==null?null:q.tradeAt(),null),
                    new RiskCheck("사업·재무 가설 재검토 · 주문 지시 아님","DATA_REVIEW",null,null,null,null),
                    new RiskCheck("직접 모의 매도는 보유 화면에서 사용자 확인","MANUAL_ONLY",null,null,null,null));
                reviews.add(new CompanyReview(code,company.getValue(),h!=null,h==null?null:h.entryOrderId(),h==null?null:h.reason(),
                    "EVIDENCE_WAIT",false,0,4,pillars,holdReason,riskChecks,evidence,
                    finance==null?null:new FinancialSummary(finance.annualCollected(),finance.annualRequired(),
                        finance.latestInterim()==null?null:finance.latestInterim().label(),financeView.generatedAt(),financeView.current())));
            }
            Report report=new Report(BerkshireMandate.VERSION,knowledge.curriculum().version(),knowledge.hash(),now,
                selection.completedAt(),selection.usable(),BerkshireMandate.entriesPaused(environment),roles(),List.copyOf(reviews),
                inputs.dartApiConfigured(),"공식 공개 철학과 실제 사업·재무·가치 가정으로 근거 점검 · 모델 훈련/상승 확률/내재가치 확정 아님",
                "/theses에서 공식 기업 근거·가치 가정·미래 전략 비교 확인 → 감사/주석·경쟁력·경영/정상화 이익 검토 → 실제 AI 논거와 반론을 사용자 검토 · 직접 모의 매도는 별도 확인 화면에서 결정");
            String json=mapper.writeValueAsString(report);
            Long id=jdbc.queryForObject("INSERT INTO berkshire_review_snapshot(scope_id,generated_at,version,curriculum_hash,report) VALUES (?,?,?,?,?::jsonb) RETURNING id",
                Long.class,scope,now.atOffset(ZoneOffset.UTC),BerkshireMandate.VERSION,knowledge.hash(),json);
            latestScope=scope;latest=new Entry(id,now,report);issue=null;
        } catch(Exception failure) { issue="버크셔 점검 기록 저장 실패 · 이전 기록을 현재 판단으로 사용하지 않음"; }
    }
    public List<BotStatus> roles() {
        return List.of(
            status("사업·경쟁력 봇","이해 가능한 사업과 지속 경쟁력","/theses의 공식 원문/사업 사실/강점과 반대 가설을 점검한다. 제목 키워드·업종 이름·유명세로 경쟁우위를 확정하지 않는다."),
            status("재무 검토 봇","이익의 질·현금·차입금","다년 동일 회계기준/단위/정정 자료를 비교. 금융사에 일반 제조업 기준을 일괄 적용하지 않는다."),
            status("가치평가 봇","좋은 회사도 비싸면 기다리기","보수적 가치 범위와 가정·민감도·안전마진을 설명. 가격 목표나 확률을 임의 생성하지 않는다."),
            status("자본배분 봇","경영진의 주당 가치 창출","인수·설비투자·배당·자사주·희석과 반대 근거를 검토. 인물 평판만으로 신뢰를 확정하지 않는다."),
            status("보유 논거 봇","처음 산 가설이 아직 유효한가","가격 변화와 사업 악화를 구분. 기존 매수 근거는 보존한다. 현재 사용자 선택 매도 정책과 기업 가치 검토 완료 여부를 분리한다."));
    }
    private BotStatus status(String name,String summary,String detail) {
        var finance=financials.view();
        boolean companyData=theses.view().current()&&theses.view().entry()!=null&&theses.view().entry().report().companies().stream().anyMatch(c->name.equals("사업·경쟁력 봇")?c.businessLinked():name.equals("가치평가 봇")&&!c.valuation().scenarios().isEmpty());
        boolean data=companyData||name.equals("재무 검토 봇")&&finance.current()&&finance.report()!=null&&
            finance.report().companies().stream().anyMatch(c->c.annualCollected()>0);
        return new BotStatus(name,data?"DATA_REVIEW":"EVIDENCE_WAIT",summary,
            (data?"공식 자료 기반 검토 중 · /theses의 사실/가설/가정과 누락 확인 · 최종 검증 대기. ":"")+detail+" · 교육 기준: "+knowledge.roleBrief(name));
    }
    public View view() {
        Instant now=clock.instant();String scope=scope();
        Entry current=Objects.equals(scope,latestScope)?latest:null;
        List<History> history=jdbc.query("SELECT id,generated_at,version FROM berkshire_review_snapshot WHERE scope_id IS NOT DISTINCT FROM ? ORDER BY id DESC LIMIT 12",
            (rs,n)->new History(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getString(3)),scope);
        boolean fresh=current!=null&&issue==null&&!current.generatedAt().isAfter(now)&&Duration.between(current.generatedAt(),now).getSeconds()<150;
        return new View(current,fresh,issue,BerkshireMandate.entriesPaused(environment),BerkshireMandate.REASON,history);
    }
    public Optional<Entry> find(long id) {
        return jdbc.query("SELECT id,generated_at,report FROM berkshire_review_snapshot WHERE id=? AND scope_id IS NOT DISTINCT FROM ?",(rs,n)->{
            try{return new Entry(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),mapper.readValue(rs.getString(3),Report.class));}
            catch(Exception e){throw new IllegalStateException("점검 원본 읽기 실패");}},id,scope()).stream().findFirst();
    }
    private record Holding(String code,String name,int quantity,BigDecimal averagePrice,Instant openedAt,long entryOrderId,String reason) {}
    public record Pillar(String key,String label,String state,String reason,String role) {}
    public record RiskCheck(String label,String state,BigDecimal observed,BigDecimal threshold,Instant priceAt,LocalDate dailyBasisDate) {}
    public record CompanyReview(String code,String name,boolean held,Long entryOrderId,String originalEntryReason,String state,
        boolean valueEntryEligible,int verifiedCorePillars,int requiredCorePillars,List<Pillar> pillars,String holdReason,List<RiskCheck> legacyRiskChecks,List<PublicInformationService.Evidence> evidence,FinancialSummary financials) {}
    public record FinancialSummary(int annualCollected,int annualRequired,String interimLabel,Instant observedAt,boolean collectionCurrent) {}
    public record Report(String version,String curriculumVersion,String curriculumHash,Instant generatedAt,Instant selectionAt,
        boolean selectionUsable,boolean entriesPaused,List<BotStatus> roles,List<CompanyReview> companies,boolean dartApiConfigured,String scope,String nextStep) {}
    public record Entry(long id,Instant generatedAt,Report report) {}
    public record History(long id,Instant generatedAt,String version) {}
    public record View(Entry latest,boolean current,String issue,boolean entriesPaused,String mandate,List<History> history) {}
}
