package com.personaltrader.marketdata;

import com.personaltrader.analysis.RiskBot;
import com.personaltrader.analysis.RiskAssessment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class UniverseService {
    public static final String RULE_VERSION = com.personaltrader.research.InvestmentSignalEngine.VERSION;
    public static final int LIMIT = 10;
    public static final int QUOTE_LIMIT = LIMIT;
    private static final BigDecimal MIN_PRICE = new BigDecimal("1000");
    private static final BigDecimal MIN_AVERAGE_TURNOVER = new BigDecimal("1000000000");
    private final KisMarketDataClient client;
    private final StockMasterClient master;
    private final RiskBot risk;
    private final UniverseRepository repository;
    private final Clock clock;
    private final com.personaltrader.research.PublicInformationService information;
    private final com.personaltrader.research.InvestmentSignalEngine signals;
    private final com.personaltrader.research.PaperPortfolioPolicy policy;
    private final Map<String,ObservedStock> researchCache=new HashMap<>();
    private volatile List<ObservedStock> researchCandidates=List.of();
    private int poolRotation;
    // Broad sector research pool, verified against today's official master. Events can introduce other companies.
    private static final List<String> SECTOR_POOL=List.of("005930","000660","005380","000270","035420","035720",
        "051910","006400","373220","005490","003670","105560","055550","086790","316140","000810",
        "207940","068270","128940","000100","326030","009540","329180","010140","042660","034020",
        "012450","047810","079550","015760","017670","030200","032640","028260","066570","036570",
        "251270","352820","097950","021240");
    private volatile Result result = new Result("WAITING", "종목 선정 대기", null, null, null, List.of(), List.of(), List.of());
    private volatile boolean scanning;
    private volatile Instant lastAttempt;

    public UniverseService(KisMarketDataClient client, StockMasterClient master, RiskBot risk,
                           UniverseRepository repository, Clock clock, com.personaltrader.research.PublicInformationService information,
                           com.personaltrader.research.InvestmentSignalEngine signals, com.personaltrader.research.PaperPortfolioPolicy policy) {
        this.client = client; this.master = master; this.risk = risk; this.repository = repository; this.clock = clock; this.information=information; this.signals=signals; this.policy=policy;
    }

    @Scheduled(fixedDelayString = "${universe.refresh-interval-ms:300000}", initialDelay = 8000)
    public synchronized void refresh() {
        if (!client.configured()) return;
        ZonedDateTime local = clock.instant().atZone(TimedMarketQuote.SEOUL);
        // Research continues on closed days using confirmed daily history. Execution
        // still requires a regular session and independently verified trading calendar.
        boolean daytime = local.getDayOfWeek().getValue() <= 5
                && !local.toLocalTime().isBefore(LocalTime.of(8, 0))
                && !local.toLocalTime().isAfter(LocalTime.of(15, 45));
        if (!daytime && lastAttempt != null
                && Duration.between(lastAttempt, clock.instant()).compareTo(Duration.ofMinutes(15)) < 0) return;
        Instant started = clock.instant(); lastAttempt = started; scanning = true;
        UUID run = UUID.randomUUID();
        List<Decision> decisions = new ArrayList<>();
        List<ObservedStock> selected = new ArrayList<>();
        List<IndexData> indices = new ArrayList<>();
        Instant rankingAt = null;
        try {
            Map<String, StockMasterClient.MasterStock> catalog = master.load();
            MarketHistory kospi = client.history("0001", true);
            indices.add(new IndexData("0001", "KOSPI", kospi));
            indices.add(new IndexData("1001", "KOSDAQ", client.history("1001", true)));
            Set<String> eventCodes=new HashSet<>(information.eventCandidates());
            // Held symbols get research even when they no longer rank among the ten observation targets.
            LinkedHashSet<String> pool=new LinkedHashSet<>(repository.heldCodes());
            pool.addAll(information.eventCandidates());
            researchCandidates.stream().limit(10).forEach(s->pool.add(s.code()));
            for(int i=0;i<SECTOR_POOL.size();i++)pool.add(SECTOR_POOL.get((poolRotation+i)%SECTOR_POOL.size()));
            poolRotation=(poolRotation+12)%SECTOR_POOL.size();
            rankingAt=clock.instant();
            List<ObservedStock> examined=new ArrayList<>(); int enriched=0,rank=0;
            for(String code:pool){
                rank++;
                StockMasterClient.MasterStock info=catalog.get(code);
                if(enriched>=30||Duration.between(started,clock.instant()).getSeconds()>=180){
                    decisions.add(new Decision(code,info==null?code:info.name(),"DEFERRED","이번 주기 상세 30개/180초 예산 도달"));continue;
                }
                if(info==null||!info.allowed()){
                    researchCache.remove(code);
                    decisions.add(new Decision(code,info==null?code:info.name(),"REJECTED",info==null?"공식 마스터 식별 미확인":info.exclusionReason()));continue;
                }
                enriched++;
                try{
                    ObservedStock cached=researchCache.get(code);
                    MarketHistory history;
                    if(cached!=null&&cached.history().basisDate().equals(kospi.basisDate())
                            &&Duration.between(cached.history().receivedAt(),clock.instant()).toMinutes()<15)history=cached.history();
                    else{
                        var security=client.securityCheck(code);
                        if(!security.allowed())throw new IllegalArgumentException();
                        history=client.history(code,false);
                    }
                    if(!history.basisDate().equals(kospi.basisDate())||history.lastClose().compareTo(MIN_PRICE)<0
                            ||history.averageTurnover20().compareTo(MIN_AVERAGE_TURNOVER)<0){
                        researchCache.remove(code);
                        decisions.add(new Decision(code,info.name(),"REJECTED","일봉 기준일/최소 가격/20일 평균 거래대금 미충족"));continue;
                    }
                    var signal=signals.evaluate(code,info.name(),info.market(),history,kospi);
                    RiskAssessment assessment=risk.assess(history.lastClose(),signal.targetBudget());
                    String origin=eventCodes.contains(code)?"실제 기사/공시 기업명·발행사":"여러 업종의 기초 연구 후보";
                    String reason=signal.label()+" · 근거 우선순위 "+signal.score()+"/100 · "+origin
                        +" · "+(signal.reasons().isEmpty()?"확인 대기":signal.reasons().getFirst());
                    List<SelectionCheck> checks=List.of(
                        new SelectionCheck("Universe Bot","PASS","기업 탐색",origin+" · 후보 탐색 순서 "+rank+"번, 거래대금 순위 아님"),
                        new SelectionCheck("Stock Bot",signal.state(),"차트/상대 강도",signal.label()+" · "+(signal.relativeStrength()==null?"기준 지수 미확인":signal.relativeStrength()+"%p")+" · RSI "+signal.rsi14()),
                        new SelectionCheck("Risk Bot",assessment.pass()?"PASS":"WAIT","모의 배정",signal.targetBudget().toPlainString()+"원 최초 연구 배정 상한 · 보유 확대는 별도 재평가, 실제 수량은 현금/예상 손실 한도로 축소"),
                        new SelectionCheck("News & Event Bot",signal.evidence().isEmpty()?"UNKNOWN":"TITLE_ONLY","기사/공시",signal.evidence().size()+"개 명확한 연결 · 제목 기준, 본문 영향 미검증"));
                    ObservedStock observation=new ObservedStock(code,info.name(),info.market(),history.lastClose(),history.averageTurnover20(),history,
                        assessment.quantity(),assessment.estimatedLoss(),reason,new SelectionRationale(rank,0,rankingAt,history.basisDate(),checks,RULE_VERSION));
                    examined.add(observation);researchCache.put(code,observation);
                }catch(RuntimeException failure){researchCache.remove(code);decisions.add(new Decision(code,info.name(),"REJECTED","실제 종목/차트/근거 조회·검증 실패"));}
            }
            // Previously researched companies are retained only with matching daily basis and bounded cache age.
            for(var cached:researchCache.values())if(examined.stream().noneMatch(s->s.code().equals(cached.code()))
                    &&cached.history().basisDate().equals(kospi.basisDate())
                    &&Duration.between(cached.history().receivedAt(),clock.instant()).toMinutes()<20)examined.add(cached);
            Map<String,Integer> scores=new HashMap<>();
            for(var stock:examined)scores.put(stock.code(),signals.evaluate(stock.code(),stock.name(),stock.market(),stock.history(),kospi).score());
            examined.sort(Comparator.comparingInt((ObservedStock s)->scores.get(s.code())).reversed()
                .thenComparing(ObservedStock::code));
            researchCandidates=List.copyOf(examined);
            selected.addAll(examined.stream().limit(LIMIT).toList());
            for(var observation:examined)decisions.add(new Decision(observation.code(),observation.name(),
                selected.stream().anyMatch(s->s.code().equals(observation.code()))?"SELECTED":"RESEARCHED",observation.reason()));
            Map<String,Decision> uniqueDecisions=new LinkedHashMap<>();
            decisions.forEach(decision->uniqueDecisions.put(decision.code(),decision));
            decisions=new ArrayList<>(uniqueDecisions.values());
            Instant completed = clock.instant();
            String state = selected.isEmpty() ? "EMPTY" : "READY";
            String detail = "차트·상대 강도·과열/위험·실제 기사/공시 제목으로 연구 " + examined.size() + "개 중 관심 " + selected.size() + "개 · 관찰 선정은 주문 승인이 아닙니다.";
            repository.save(run, started, completed, rankingAt, state, detail, indices, selected, decisions);
            result = new Result(state, detail, completed, rankingAt, run, List.copyOf(indices), List.copyOf(selected), List.copyOf(decisions));
        } catch (RuntimeException exception) {
            String detail = "종목 선정 입력 조회·검증 또는 기록 저장 실패 · 이전 목록은 거래 판단에 사용할 수 없습니다.";
            try { repository.save(run, started, clock.instant(), rankingAt, "ERROR", detail, List.of(), List.of(), List.of()); }
            catch (RuntimeException ignored) { /* no transport or DB exception text is exposed */ }
            Result previous = result;
            result = new Result("ERROR", detail, previous.completed(), previous.rankingAt(), previous.run(),
                    previous.indices(), previous.stocks(), previous.decisions());
        } finally { scanning = false; }
    }

    public Snapshot snapshot() {
        Result current = result;
        Instant now = clock.instant();
        boolean valid = "READY".equals(current.state()) && current.completed() != null && !current.completed().isAfter(now)
                && Duration.between(current.completed(), now).compareTo(Duration.ofMinutes(20)) <= 0;
        String state = !client.configured() ? "UNCONFIGURED" : scanning ? "SCANNING" : current.state();
        String label = !client.configured() ? "키 설정 필요" : scanning ? "자동 선정 중" : switch (state) {
            case "READY" -> valid ? "자동 선정 완료" : "선정 결과 갱신 필요";
            case "ERROR" -> "선정 실패";
            case "EMPTY" -> "조건 통과 종목 없음";
            default -> "첫 선정 대기";
        };
        boolean usable = valid && TimedMarketQuote.regularSession(now);
        return new Snapshot(state, label, current.detail(), RULE_VERSION, lastAttempt, current.completed(), current.rankingAt(),
                current.stocks(), current.indices(), current.decisions(), usable,
                "관심 최대 10개 · 평일 08:00–15:45 목표 5분, 주말·장외 목표 15분 재선정 · 모의 보유 최대 "+policy.maxPositions()+"개");
    }

    public List<ObservedStock> quoteTargets() {
        Snapshot snapshot = snapshot();
        if (!snapshot.usable()) return List.of();
        return snapshot.stocks().stream().limit(QUOTE_LIMIT).toList();
    }
    public List<ObservedStock> researchCandidates(){return researchCandidates;}
    public com.personaltrader.research.InvestmentSignalEngine.Signal signal(String code){
        Snapshot current=snapshot();
        var stock=current.stocks().stream().filter(s->s.code().equals(code)).findFirst().orElse(null);
        var benchmark=current.indices().stream().filter(i->i.code().equals("0001")).findFirst().orElse(null);
        return stock==null?null:signals.evaluate(stock.code(),stock.name(),stock.market(),stock.history(),benchmark==null?null:benchmark.history());
    }
    public com.personaltrader.research.InvestmentSignalEngine.Signal holdingSignal(String code){
        Snapshot current=snapshot();Instant now=clock.instant();
        if(!current.usable())return null;
        var benchmark=current.indices().stream().filter(i->i.code().equals("0001")).findFirst().orElse(null);
        var stock=researchCandidates.stream().filter(s->s.code().equals(code)).findFirst().orElse(null);
        if(stock==null||benchmark==null||!stock.history().basisDate().equals(benchmark.history().basisDate())
                ||stock.history().receivedAt().isAfter(now)
                ||Duration.between(stock.history().receivedAt(),now).compareTo(Duration.ofMinutes(20))>0)return null;
        return signals.evaluate(stock.code(),stock.name(),stock.market(),stock.history(),benchmark.history());
    }
    private record Result(String state, String detail, Instant completed, Instant rankingAt, UUID run,
                          List<IndexData> indices, List<ObservedStock> stocks, List<Decision> decisions) {}
    public record ObservedStock(String code, String name, String market, BigDecimal rankingPrice,
                                BigDecimal rankingTurnover, MarketHistory history, int indicativeQuantity,
                                BigDecimal indicativeLoss, String reason, SelectionRationale rationale) {}
    public record SelectionCheck(String role, String state, String title, String detail) {}
    public record SelectionRationale(int rankingRank, int selectedOrder, Instant rankingReceivedAt,
                                     LocalDate dailyBasisDate, List<SelectionCheck> checks,String method) {}
    private static String money(BigDecimal value) { return String.format(Locale.KOREA, "%,.0f", value); }
    public record IndexData(String code, String name, MarketHistory history) {}
    public record Decision(String code, String name, String state, String reason) {}
    public record Snapshot(String state, String label, String detail, String ruleVersion, Instant lastAttemptAt,
                           Instant completedAt, Instant rankingReceivedAt, List<ObservedStock> stocks,
                           List<IndexData> indices, List<Decision> decisions, boolean usable, String policy) {}
}
