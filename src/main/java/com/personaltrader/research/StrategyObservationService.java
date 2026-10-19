package com.personaltrader.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.marketdata.*;
import com.personaltrader.orders.PositionQuoteCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.util.*;

/** Forward observation of screens, not orders, fills, holdings or a backtest. */
@Service
public class StrategyObservationService {
 private final CompanyThesisService theses;private final UniverseService universe;private final PositionQuoteCollector quotes;private final MarketSessionService sessions;
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;private final BigDecimal fee,tax,slip;private final KisMarketDataClient market;private int rotation;
 private final Map<String,TimedMarketQuote> followQuotes=new HashMap<>();private final Map<String,MarketHistory> followHistory=new HashMap<>();
 private volatile String issue;private volatile Instant checkedAt;
 public StrategyObservationService(CompanyThesisService theses,UniverseService universe,PositionQuoteCollector quotes,MarketSessionService sessions,
  JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,KisMarketDataClient market,@Value("${paper.costs.commission-rate}") BigDecimal fee,@Value("${paper.costs.transaction-tax-rate}") BigDecimal tax,@Value("${paper.costs.slippage-bps}") BigDecimal slip){
  this.market=market;this.theses=theses;this.universe=universe;this.quotes=quotes;this.sessions=sessions;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.fee=fee;this.tax=tax;this.slip=slip;
 }
 @Scheduled(fixedDelay=60000,initialDelay=40000)
 public synchronized void collect(){
  Instant now=clock.instant();if(!theses.paperEnvironment()||theses.scope()==null||!sessions.newEntriesAllowed())return;
  if(fee.signum()<0||tax.signum()<0||slip.signum()<0||fee.add(tax).compareTo(new BigDecimal("0.1"))>=0||slip.compareTo(new BigDecimal("1000"))>=0){issue="비교 비용 가정 확인 필요";return;}
  try{
   var view=theses.view();var entry=view.entry();if(entry==null||!view.current())return;
   for(var saved:entry.report().companies()){
    var c=theses.currentCompany(saved.code());var quote=quotes.freshQuote(saved.code());if(c==null||quote==null)continue;
    LocalDate day=quote.tradeAt().atZone(TimedMarketQuote.SEOUL).toLocalDate();
    Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM strategy_paired_observation WHERE scope_id=? AND stock_code=? AND (trade_at AT TIME ZONE 'Asia/Seoul')::date=?",Long.class,theses.scope(),c.code(),day);
    if(existing>0)continue;
    var signal=universe.snapshot().usable()?universe.signal(c.code()):null;
    if(signal==null)signal=universe.holdingSignal(c.code());
    // A missing chart evaluation is not a WAIT decision; both strategies require observable inputs.
    if(signal==null||signal.chartReceivedAt()==null||signal.chartReceivedAt().isAfter(now)||Duration.between(signal.chartReceivedAt(),now).toMinutes()>20)continue;
    String chart=signal.paperEligible()?"OBSERVE_PASS":"WAIT";
    BigDecimal margin=CompanyThesisService.margin(c.valuation(),quote.price());
    boolean numeric=c.checks().stream().filter(x->!x.key().equals("MARGIN")&&!x.key().equals("FRESH")).allMatch(x->x.state().equals("PASS"));
    boolean freshInputs=c.checks().stream().anyMatch(x->x.key().equals("FRESH")&&x.state().equals("PASS"));
    String value=numeric&&freshInputs&&margin!=null&&margin.compareTo(new BigDecimal("25"))>=0?"OBSERVE_PASS":"WAIT";
    var origin=Map.of("policyVersion",CompanyThesisService.POLICY,"companyAtObservation",c,"chartAtObservation",signal,"quote",quote,"marginAtObservation",margin==null?"UNKNOWN":margin,
     "interpretation","선별 관찰 통과/대기 · 실제 주문 및 사람의 경쟁력/경영/뉴스 본문 검토 승인과 구분");
    jdbc.update("INSERT INTO strategy_paired_observation(scope_id,thesis_id,stock_code,observed_at,trade_at,initial_price,chart_action,value_action,commission_rate,tax_rate,slippage_bps,origin) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
     theses.scope(),entry.id(),c.code(),utc(now),utc(quote.tradeAt()),quote.price(),chart,value,fee,tax,slip,mapper.writeValueAsString(origin));
   }
   var pending=jdbc.query("SELECT * FROM strategy_paired_observation p WHERE scope_id=? AND (SELECT COUNT(*) FROM strategy_observation_outcome o WHERE o.observation_id=p.id)<3 ORDER BY id LIMIT 500",(rs,n)->new Pending(rs.getLong("id"),rs.getString("stock_code"),rs.getObject("trade_at",OffsetDateTime.class).toInstant(),rs.getBigDecimal("initial_price"),rs.getBigDecimal("commission_rate"),rs.getBigDecimal("tax_rate"),rs.getBigDecimal("slippage_bps")),theses.scope());
   // Keep following a sampled security even if it leaves holdings/the watchlist.
   // One missing symbol per cycle bounds additional broker reads and rotates fairly.
   var missingCodes=pending.stream().map(Pending::code).distinct().filter(code->quotes.freshQuote(code)==null).sorted().toList();
   if(!missingCodes.isEmpty()&&TimedMarketQuote.regularSession(now)&&market.configured()){
    String code=missingCodes.get(Math.floorMod(rotation++,missingCodes.size()));
    try{var q=market.collect(code);if(q.freshnessProblem(clock.instant())==null){followQuotes.put(code,q);var h=followHistory.get(code);
      if(h==null||!h.receivedAt().atZone(TimedMarketQuote.SEOUL).toLocalDate().equals(now.atZone(TimedMarketQuote.SEOUL).toLocalDate()))followHistory.put(code,market.history(code,false));}}
    catch(RuntimeException unavailable){followQuotes.remove(code);}
   }
   for(var p:pending){
    var quote=quotes.freshQuote(p.code());if(quote==null)quote=followQuotes.get(p.code());if(quote==null||quote.freshnessProblem(clock.instant())!=null)continue;
    int days=quotes.confirmedTradingDaysSince(p.code(),p.tradeAt());
    if(days<0){var h=followHistory.get(p.code());LocalDate today=now.atZone(TimedMarketQuote.SEOUL).toLocalDate(),origin=p.tradeAt().atZone(TimedMarketQuote.SEOUL).toLocalDate();
     if(h!=null&&h.bars()!=null&&h.receivedAt().atZone(TimedMarketQuote.SEOUL).toLocalDate().equals(today)&&!h.receivedAt().isAfter(now))
      days=Math.toIntExact(h.bars().stream().map(MarketHistory.CompletedBar::date).filter(d->d.isAfter(origin)&&d.isBefore(today)).distinct().count()+(today.isAfter(origin)?1:0));}
    if(days<1)continue;
    var done=jdbc.queryForList("SELECT checkpoint FROM strategy_observation_outcome WHERE observation_id=?",String.class,p.id());
    for(int checkpoint:new int[]{1,5,20})if(days>=checkpoint&&!done.contains("D"+checkpoint)){
     long offset=Duration.between(p.tradeAt().atZone(TimedMarketQuote.SEOUL).toLocalTime(),quote.tradeAt().atZone(TimedMarketQuote.SEOUL).toLocalTime()).getSeconds();
     if(days==checkpoint&&offset<0)continue; // Wait until the original observation time on the exact checkpoint day.
     BigDecimal spread=p.slip().divide(new BigDecimal("10000"),MathContext.DECIMAL128);
     BigDecimal entryCost=p.price().multiply(BigDecimal.ONE.add(spread)).multiply(BigDecimal.ONE.add(p.fee()));
     BigDecimal exitNet=quote.price().multiply(BigDecimal.ONE.subtract(spread)).multiply(BigDecimal.ONE.subtract(p.fee()).subtract(p.tax()));
     BigDecimal result=exitNet.divide(entryCost,MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
     jdbc.update("INSERT INTO strategy_observation_outcome(observation_id,checkpoint,observed_at,trade_at,price,reference_net_return,elapsed_trading_days,same_time_window) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",p.id(),"D"+checkpoint,utc(now),utc(quote.tradeAt()),quote.price(),result,days,offset>=0&&offset<=300);
    }
   }
   checkedAt=now;issue=null;
  }catch(Exception e){issue="전략 관찰 기록 수집 실패 · 기존 기록 확인 필요";}
 }
 public Optional<com.fasterxml.jackson.databind.JsonNode> origin(long id){return jdbc.query("SELECT origin FROM strategy_paired_observation WHERE id=? AND scope_id=?",(rs,n)->{try{return mapper.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException("관찰 근거 읽기 실패");}},id,theses.scope()).stream().findFirst();}
 public View view(){
  String scope=theses.scope();List<Summary> summary=scope==null?List.of():jdbc.query("""
   SELECT o.checkpoint,COUNT(*) AS pairs,
    COUNT(*) FILTER(WHERE p.chart_action='OBSERVE_PASS') AS chart_count,
    COUNT(*) FILTER(WHERE p.value_action='OBSERVE_PASS') AS value_count,
    AVG(o.reference_net_return) FILTER(WHERE p.chart_action='OBSERVE_PASS') AS chart_return,
    AVG(o.reference_net_return) FILTER(WHERE p.value_action='OBSERVE_PASS') AS value_return
   FROM strategy_observation_outcome o JOIN strategy_paired_observation p ON p.id=o.observation_id WHERE p.scope_id=? GROUP BY o.checkpoint ORDER BY CASE o.checkpoint WHEN 'D1' THEN 1 WHEN 'D5' THEN 5 ELSE 20 END
   """,(rs,n)->new Summary(rs.getString("checkpoint"),rs.getLong("pairs"),rs.getLong("chart_count"),rs.getLong("value_count"),rs.getBigDecimal("chart_return"),rs.getBigDecimal("value_return")),scope);
  Long count=scope==null?0L:jdbc.queryForObject("SELECT COUNT(*) FROM strategy_paired_observation WHERE scope_id=?",Long.class,scope);
  List<Observation> history=scope==null?List.of():jdbc.query("SELECT p.*,origin->'companyAtObservation'->>'name' AS name FROM strategy_paired_observation p WHERE scope_id=? ORDER BY id DESC LIMIT 30",(rs,n)->{
   long id=rs.getLong("id");var outcomes=jdbc.query("SELECT checkpoint,trade_at,price,reference_net_return FROM strategy_observation_outcome WHERE observation_id=? ORDER BY id",(os,k)->new Outcome(os.getString(1),os.getObject(2,OffsetDateTime.class).toInstant(),os.getBigDecimal(3),os.getBigDecimal(4)),id);
   return new Observation(id,rs.getString("stock_code"),rs.getString("name"),rs.getLong("thesis_id"),rs.getObject("trade_at",OffsetDateTime.class).toInstant(),rs.getBigDecimal("initial_price"),rs.getString("chart_action"),rs.getString("value_action"),rs.getBigDecimal("commission_rate"),rs.getBigDecimal("tax_rate"),rs.getBigDecimal("slippage_bps"),outcomes);
  },scope);
  String readiness=!theses.paperEnvironment()?"PAPER 외 환경 비교 비활성":scope==null?"모의 계좌 연결 대기":!TimedMarketQuote.regularSession(clock.instant())?"정규장 실제 시세 관찰 대기":!sessions.newEntriesAllowed()?"공식 거래 달력 확인 대기":!universe.snapshot().usable()?"차트 후보/일봉 수집 대기 · 두 규칙을 같은 실제 시세로 평가할 수 있어야 관찰 쌍 저장":"당일 검증 시세와 두 규칙 입력을 확인하며 최초 하루 한 쌍 기록";
  return new View("FORWARD_SCREEN_OBSERVATION_V1",count,checkedAt,issue,readiness,summary,history,
   "확인된 1/5/20거래일 이상 경과 후 첫 이용 가능한 당일 시세 · 정확한 해당 일 종가 아님 · 관측 누락 시 늦어질 수 있음. 동일 시각의 두 선별 규칙 비교; 각 통과 표본의 비용 가정 차감 1주 기준 가격 변화 평균, 실제 수익·동일 자금 포트폴리오·승률 아님. 대기/누락 수를 별도 표시하며 시작 이전 데이터로 성과를 만들지 않음.");
 }
 private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
 private record Pending(long id,String code,Instant tradeAt,BigDecimal price,BigDecimal fee,BigDecimal tax,BigDecimal slip){}
 public record Summary(String checkpoint,long completedPairs,long chartPassCount,long valuePassCount,BigDecimal chartReferenceReturn,BigDecimal valueReferenceReturn){}
 public record Outcome(String checkpoint,Instant tradeAt,BigDecimal price,BigDecimal referenceNetReturn){}
 public record Observation(long id,String code,String name,long thesisId,Instant tradeAt,BigDecimal price,String chartAction,String valueAction,BigDecimal fee,BigDecimal tax,BigDecimal slippageBps,List<Outcome> outcomes){}
 public record View(String version,long observationCount,Instant checkedAt,String issue,String readiness,List<Summary> summary,List<Observation> history,String scope){}
}
