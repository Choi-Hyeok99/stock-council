package com.personaltrader.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.marketdata.UniverseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class IdeaBoardService {
    @org.springframework.beans.factory.annotation.Autowired private PaperEntryReviewService entryReviews;
    private final UniverseService universe;
    private final InvestmentSignalEngine engine;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Reuse an explicitly dated real signal when a recently requested company leaves today's top ten. */
    public InvestmentSignalEngine.Signal referenceSignal(String code){
        if(code==null||!code.matches("[0-9]{6}"))return null;
        var rows=jdbc.query("""
            SELECT s.value::text FROM (SELECT id,report FROM investment_idea_snapshot WHERE generated_at>=? ORDER BY id DESC LIMIT 500) t
            CROSS JOIN LATERAL jsonb_array_elements(t.report->'signals') s(value)
            WHERE s.value->>'code'=? ORDER BY t.id DESC LIMIT 1
            """,(rs,n)->rs.getString(1),utc(clock.instant().minus(Duration.ofDays(7))),code);
        if(rows.isEmpty())return null;
        try{return mapper.readValue(rows.getFirst(),InvestmentSignalEngine.Signal.class);}catch(Exception e){return null;}
    }
    private volatile Entry latest;
    private volatile Instant computedAt;
    private volatile String issue;
    public IdeaBoardService(UniverseService universe,InvestmentSignalEngine engine,JdbcTemplate jdbc,ObjectMapper mapper,
                            Clock clock,PlatformTransactionManager transactions){
        this.universe=universe;this.engine=engine;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(transactions);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public synchronized void refresh(){
        try{
            var selection=universe.snapshot();
            var benchmark=selection.indices().stream().filter(i->i.code().equals("0001")).findFirst().orElse(null);
            if(benchmark==null||universe.researchCandidates().isEmpty())return;
            Instant now=clock.instant();
            List<InvestmentSignalEngine.Signal> allSignals=universe.researchCandidates().stream()
                .filter(s->s.history().basisDate().equals(benchmark.history().basisDate()))
                .filter(s->!s.history().receivedAt().isAfter(now)&&Duration.between(s.history().receivedAt(),now).toMinutes()<20)
                .map(s->engine.evaluate(s.code(),s.name(),s.market(),s.history(),benchmark.history()))
                .map(s->entryReviews==null?s:entryReviews.qualify(s))
                .sorted(Comparator.comparingInt(InvestmentSignalEngine.Signal::score).reversed().thenComparing(InvestmentSignalEngine.Signal::code))
                .toList();
            List<InvestmentSignalEngine.Signal> signals=allSignals.stream().limit(10).toList();
            List<InvestmentSignalEngine.Signal> riskSignals=allSignals.stream().filter(s->s.state().equals("DOWNSIDE_RISK")).limit(3).toList();
            if(signals.isEmpty())return;
            if(latest==null)latest=loadLatest();
            String fingerprint=PublicInformationService.hash(java.util.stream.Stream.concat(signals.stream(),riskSignals.stream())
                .map(IdeaBoardService::changeKey).toList().toString()+":"+BerkshireMandate.VERSION+":"+InvestmentSignalEngine.VERSION);
            computedAt=now;
            if(latest!=null&&latest.fingerprint().equals(fingerprint)&&Duration.between(latest.generatedAt(),now).toMinutes()<10){issue=null;return;}
            Entry previous=latest;
            Report report=new Report(InvestmentSignalEngine.VERSION,now,selection.completedAt(),universe.researchCandidates().size(),signals,riskSignals,
                "업종별 기초 후보 40개 + 실제 기사/공시 기업을 순환 연구 · 상세 조회 최대 30개/주기 · 관심 최대 10개",
                "차트와 제목의 관찰 우선순위 · 자동매매 없음 · 직접 모의 매도는 사용자 확인 · 사업/재무/가치와 실제 AI 토론은 투자위원회에서 확인",BerkshireMandate.VERSION,BerkshireMandate.entriesPaused(com.personaltrader.broker.ExecutionBrokerClient.Environment.PAPER));
            Entry stored=tx.execute(transaction->{
                try{
                    long id=jdbc.queryForObject("INSERT INTO investment_idea_snapshot(generated_at,fingerprint,report) VALUES (?,?,?::jsonb) RETURNING id",Long.class,utc(now),fingerprint,mapper.writeValueAsString(report));
                    List<InvestmentSignalEngine.Signal> old=previous==null?List.of():previous.report().signals();
                    for(var signal:signals){
                        var before=old.stream().filter(s->s.code().equals(signal.code())).findFirst().orElse(null);
                        if(before==null||meaningfullyChanged(before,signal))
                            change(now,signal,before==null?"ADDED":"UPDATED",before==null?null:before.state(),signal.state(),
                                signal.label()+" · 점수 "+signal.score()+"/100 · "+(signal.reasons().isEmpty()?signal.risks().getFirst():signal.reasons().getFirst()));
                    }
                    for(var before:old)if(signals.stream().noneMatch(s->s.code().equals(before.code())))
                        change(now,before,"REMOVED",before.state(),null,"관심 상위 10개에서 제외 · 보유분 매도 명령 아님, 보유 위험 감시는 유지");
                    return new Entry(id,now,fingerprint,report);
                }catch(Exception failure){throw new IllegalStateException("연구 기록 저장 실패");}
            });
            latest=stored;issue=null;
        }catch(RuntimeException failure){issue="관심 기업 근거 갱신 실패 · 이전 기록의 기준 시각을 확인해 주세요.";}
    }
    // Snapshot/Discord fingerprints and visible change history must use the same criteria.
    // A publisher can correct a title without changing the URL/ID, and a source failure
    // can change entry eligibility without changing the chart state or score band.
    static String changeKey(InvestmentSignalEngine.Signal signal){
        return signal.code()+":"+signal.state()+":"+signal.score()/5+":"+signal.paperEligible()+":"
            +signal.targetBudget().stripTrailingZeros().toPlainString()+":"
            +(signal.explanation()==null?"LEGACY":signal.explanation().policyVersion()+":"+signal.explanation().news().state()+":"
                +signal.explanation().entryChecks().stream().map(c->c.key()+"="+c.pass()).toList())+":"
            +signal.evidence().stream().map(e->e.id()+":"+PublicInformationService.hash(e.title())).sorted().toList();
    }
    static boolean meaningfullyChanged(InvestmentSignalEngine.Signal before,InvestmentSignalEngine.Signal after){
        return !changeKey(before).equals(changeKey(after));
    }
    private void change(Instant now,InvestmentSignalEngine.Signal signal,String kind,String before,String after,String reason){
        jdbc.update("INSERT INTO investment_idea_change(occurred_at,stock_code,stock_name,kind,before_state,after_state,reason) VALUES (?,?,?,?,?,?,?)",
            utc(now),signal.code(),signal.name(),kind,before,after,reason);
    }
    private Entry loadLatest(){return jdbc.query("SELECT * FROM investment_idea_snapshot ORDER BY id DESC LIMIT 1",(rs,n)->{
        try{return new Entry(rs.getLong("id"),instant(rs,"generated_at"),rs.getString("fingerprint"),mapper.readValue(rs.getString("report"),Report.class));}
        catch(Exception failure){throw new IllegalStateException("연구 원본 확인 필요");}}).stream().findFirst().orElse(null);}
    public Entry latest(){return latest;}
    /** Historical reference only; does not mark current, invoke collectors or alter latest. */
    public Entry referenceLatest(){return latest==null?loadLatest():latest;}
    public View view(){
        Entry entry=latest;
        var selection=universe.snapshot();
        boolean current=entry!=null&&computedAt!=null&&!computedAt.isAfter(clock.instant())
            &&Duration.between(computedAt,clock.instant()).toMinutes()<5&&"READY".equals(selection.state())
            &&selection.completedAt()!=null&&Duration.between(selection.completedAt(),clock.instant()).toMinutes()<20;
        List<Change> changes=jdbc.query("SELECT * FROM investment_idea_change ORDER BY id DESC LIMIT 12",(rs,n)->new Change(rs.getLong("id"),instant(rs,"occurred_at"),rs.getString("stock_code"),rs.getString("stock_name"),rs.getString("kind"),rs.getString("before_state"),rs.getString("after_state"),rs.getString("reason")));
        return new View(entry,computedAt,current,selection.label(),issue,changes,"뉴스·공시 목표 2분 · 평일 08:00–15:45 후보 목표 5분 · 주말·장외 후보 목표 15분 · 확정 일봉 기준일과 조회 시각은 별도, 장 시작 후 실행 조건 재확인");
    }
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{return rs.getObject(column,OffsetDateTime.class).toInstant();}
    public record Report(String ruleVersion,Instant generatedAt,Instant selectionAt,int researchedCount,List<InvestmentSignalEngine.Signal>signals,List<InvestmentSignalEngine.Signal>riskSignals,String coverage,String scope,String philosophyVersion,boolean entriesPaused){
        public Report(String ruleVersion,Instant generatedAt,Instant selectionAt,int researchedCount,List<InvestmentSignalEngine.Signal>signals,List<InvestmentSignalEngine.Signal>riskSignals,String coverage,String scope){
            this(ruleVersion,generatedAt,selectionAt,researchedCount,signals,riskSignals,coverage,scope,null,false);
        }
    }
    public record Entry(long id,Instant generatedAt,String fingerprint,Report report){}
    public record Change(long id,Instant occurredAt,String code,String name,String kind,String beforeState,String afterState,String reason){}
    public record View(Entry entry,Instant lastComputedAt,boolean current,String selectionLabel,String issue,List<Change>changes,String cadence){}
}
