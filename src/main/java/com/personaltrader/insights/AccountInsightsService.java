package com.personaltrader.insights;

import com.personaltrader.orders.LegacyPortfolioHistory;
import com.personaltrader.research.CompanySourceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.*;
import java.time.*;
import java.sql.*;
import java.util.*;

/** Observational ledger only. Never changes orders, account cash, or trading risk gates. */
@Service
public class AccountInsightsService {
 private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
 private static final BigDecimal ZERO=BigDecimal.ZERO;
 private final JdbcTemplate jdbc; private final LegacyPortfolioHistory execution;
 private final CompanySourceService sources; private final Clock clock; private final TransactionTemplate tx;
 private volatile String issue;
 public AccountInsightsService(JdbcTemplate jdbc,LegacyPortfolioHistory execution,CompanySourceService sources,Clock clock,PlatformTransactionManager manager){
  this.jdbc=jdbc;this.execution=execution;this.sources=sources;this.clock=clock;tx=new TransactionTemplate(manager);
 }
    public synchronized void capture(){
  try{
   String scope=execution.ledgerScope();if(scope==null)return;
   var v=execution.view();Instant now=clock.instant(),at=v.lastReconciledAt();
   if(v.issue()!=null||at==null||at.isAfter(now)||Duration.between(at,now).getSeconds()>120||v.brokerTotalAssets()==null||v.brokerTotalAssets().signum()<=0)return;
   BigDecimal kospi=index("0001",at),kosdaq=index("1001",at);
   if(!Objects.equals(scope,execution.ledgerScope()))return;
   jdbc.update("INSERT INTO account_valuation(scope_id,basis_at,recorded_at,assets,cash,kospi,kosdaq) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
    scope,utc(at),utc(now),v.brokerTotalAssets(),v.brokerCash(),kospi,kosdaq);issue=null;
  }catch(RuntimeException ignored){issue="계좌 성과 기록 실패 · 이전 기록의 기준 시각을 확인하세요.";}
 }
 private BigDecimal index(String code,Instant at){
  return jdbc.query("SELECT index_value FROM market_index_sample WHERE index_code=? AND state='DATED_MINUTE' AND basis_kind='DATED_MINUTE' AND basis_at<=? AND basis_at>=? AND received_at<=? AND received_at>=? ORDER BY basis_at DESC,id DESC LIMIT 1",
   (rs,n)->rs.getBigDecimal(1),code,utc(at),utc(at.minusSeconds(120)),utc(at),utc(at.minusSeconds(120))).stream().findFirst().orElse(null);
 }
 public String accountContext(){return execution.ledgerScope();}
 private String scope(String expected){String s=execution.ledgerScope();if(s==null||!Objects.equals(s,expected))throw new IllegalArgumentException("계좌 연결이 필요합니다.");return s;}
 private void lock(String scope){jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);if(!Objects.equals(scope,execution.ledgerScope()))throw new IllegalArgumentException("계좌 설정이 바뀌었습니다.");}
 public void recordFlow(UUID id,Instant at,BigDecimal amount,String expected){
  String s=scope(expected);amount=amount.setScale(2,RoundingMode.UNNECESSARY);
  if(at.isAfter(clock.instant())||amount.signum()==0||amount.abs().compareTo(new BigDecimal("1000000000"))>0)throw new IllegalArgumentException("입출금 시각·금액을 확인하세요.");
  BigDecimal value=amount;
  tx.executeWithoutResult(status->{lock(s);var first=first(s);if(first==null||at.isBefore(first.at()))throw new IllegalArgumentException("성과 기록 시작 이후의 입출금만 등록하세요.");
   // Retries of one form are idempotent; corrections are explicit opposite entries at the original time.
   jdbc.update("INSERT INTO account_cash_flow(id,scope_id,occurred_at,amount,recorded_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",id,s,utc(at),value,utc(clock.instant()));
  });
 }
 public void reviewFlows(String expected){String s=scope(expected);tx.executeWithoutResult(status->{lock(s);var first=first(s);var last=last(s);if(first==null||last==null)throw new IllegalArgumentException("성과 기록 수집을 기다려 주세요.");
  jdbc.update("INSERT INTO account_flow_review(scope_id,from_at,through_at,recorded_at) VALUES (?,?,?,?)",s,utc(first.at()),utc(last.at()),utc(clock.instant()));});}
 public void confirmCosts(UUID id,long orderId,BigDecimal commission,BigDecimal tax,String expected){
  String s=scope(expected);commission=commission.setScale(2,RoundingMode.UNNECESSARY);tax=tax.setScale(2,RoundingMode.UNNECESSARY);
  if(commission.signum()<0||tax.signum()<0)throw new IllegalArgumentException("비용은 0 이상의 금액이어야 합니다.");
  BigDecimal fee=commission,taxes=tax;
  tx.executeWithoutResult(status->{lock(s);var orders=jdbc.query("SELECT status,filled_quantity,filled_amount,side FROM execution_order WHERE id=? AND scope_id=? FOR UPDATE",
    (rs,n)->new CostOrder(rs.getString(1),rs.getInt(2),rs.getBigDecimal(3),rs.getString(4)),orderId,s);
   if(orders.isEmpty())throw new IllegalArgumentException("현재 계좌의 주문이 아닙니다.");var o=orders.getFirst();
   if(!Set.of("FILLED","CANCELED","EXPIRED").contains(o.status())||o.quantity()<1)throw new IllegalArgumentException("체결이 확정된 종료 주문만 정산 대조하세요.");
   if(fee.add(taxes).compareTo(o.amount())>=0||o.side().equals("BUY")&&taxes.signum()!=0)throw new IllegalArgumentException("주문 비용·매수 거래세를 확인하세요.");
   jdbc.update("INSERT INTO order_cost_confirmation(id,scope_id,order_id,filled_quantity,commission,tax,recorded_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,s,orderId,o.quantity(),fee,taxes,utc(clock.instant()));
  });
 }
 private Point point(ResultSet rs,int n)throws SQLException{return new Point(rs.getObject("basis_at",OffsetDateTime.class).toInstant(),rs.getBigDecimal("assets"),rs.getBigDecimal("kospi"),rs.getBigDecimal("kosdaq"));}
 private Point first(String s){return jdbc.query("SELECT * FROM account_valuation WHERE scope_id=? ORDER BY basis_at LIMIT 1",this::point,s).stream().findFirst().orElse(null);}
 private Point last(String s){return jdbc.query("SELECT * FROM account_valuation WHERE scope_id=? ORDER BY basis_at DESC LIMIT 1",this::point,s).stream().findFirst().orElse(null);}
 public View view(){
  String s=execution.ledgerScope();var state=execution.displayView();Instant now=clock.instant();
  if(s==null)return new View(now,state.environment(),issue,null,null,null,null,null,false,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of("계좌 연결 후 성과 수집을 시작합니다."));
  var first=first(s);var last=last(s);
  List<Period> periods=new ArrayList<>();for(int days:List.of(7,30,90,365))periods.add(period(s,days,last));
  var curve=jdbc.query("SELECT DISTINCT ON ((basis_at AT TIME ZONE 'Asia/Seoul')::date) * FROM account_valuation WHERE scope_id=? AND basis_at>=? ORDER BY (basis_at AT TIME ZONE 'Asia/Seoul')::date,basis_at DESC",
    this::point,s,utc(now.minus(Duration.ofDays(365))));
  var flows=jdbc.query("SELECT id,occurred_at,amount FROM account_cash_flow WHERE scope_id=? ORDER BY occurred_at DESC,recorded_at DESC LIMIT 50",
    (rs,n)->new Flow(rs.getString(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getBigDecimal(3)),s);
  var costs=jdbc.query("""
   SELECT o.id,o.stock_name,o.side,o.status,o.filled_quantity,o.commission,o.tax,c.commission AS confirmed_commission,c.tax AS confirmed_tax,c.recorded_at
   FROM execution_order o LEFT JOIN LATERAL (SELECT * FROM order_cost_confirmation WHERE scope_id=o.scope_id AND order_id=o.id AND filled_quantity=o.filled_quantity ORDER BY recorded_at DESC,id DESC LIMIT 1) c ON TRUE
   WHERE o.scope_id=? AND o.filled_quantity>0 ORDER BY o.id DESC LIMIT 100
   """,(rs,n)->new Cost(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getObject(10,OffsetDateTime.class)==null?null:rs.getObject(10,OffsetDateTime.class).toInstant()),s);
  List<String> alerts=new ArrayList<>();
  if(state.environment().equals("PAPER"))alerts.add("모의 정책: 실제 매수 원금+매수 비용 대비 비용 후 순이익5%에서 한 번 절반 익절 · 손실 자동매도/수동 매도 보류 · 종목당 최초150만원 이상·일반300만원/강화500만원·재무/기사 점검 후 하락 분할 매수 · 예상손실 수량 제한 해제, 평가손실/회복 불확실성은 유지");
  boolean fresh=state.issue()==null&&state.lastReconciledAt()!=null&&!state.lastReconciledAt().isAfter(now)&&Duration.between(state.lastReconciledAt(),now).getSeconds()<=120;
  if(!fresh)alerts.add("잔고 대조가 오래됐거나 오류가 있습니다. 마지막 확인 금액입니다.");
  if(state.positions().stream().anyMatch(p->!p.quoteFresh()))alerts.add("보유 시세 일부가 이전 기록입니다. 예상 손익·노출 금액의 시각을 확인하세요.");
  if(state.orders().stream().anyMatch(o->o.status().equals("UNKNOWN")))alerts.add("응답 미확정 주문: 재주문 금지, 증권사 주문번호와 수량을 대조하세요.");
  else if(state.pendingOrderCount()>0)alerts.add("진행 주문 "+state.pendingOrderCount()+"건: 체결·잔량 취소 대조 중입니다.");
  for(var o:state.orders())if(Set.of("CREATED","SUBMITTING","SUBMITTED","PARTIALLY_FILLED","CANCEL_PENDING").contains(o.status())&&Duration.between(o.updatedAt(),now).toMinutes()>=5)
   alerts.add("주문 #"+o.id()+" 상태 "+o.statusLabel()+"가 5분 이상 갱신되지 않음 · 주문 화면에서 대조, 중복 접수 금지");
  var sectors=sectors(state);for(var sector:sectors){if(sector.percent()!=null&&sector.percent().compareTo(new BigDecimal("40"))>=0)alerts.add(sector.label()+" 비중 "+sector.percent()+"% · 40% 집중 관찰선 초과, 자동 매도 기준 아님");}
  if(sectors.stream().anyMatch(x->x.code().equals("UNKNOWN")))alerts.add("업종 미확인 보유가 있습니다. 분산됐다고 판단하지 않습니다.");
  BigDecimal unrealized=state.estimatedUnrealizedNetPnl();
  var losses=state.positions().stream().filter(p->p.estimatedNetPnl()!=null&&p.estimatedNetPnl().signum()<0).map(p->new Loss(p.name(),p.estimatedNetPnl())).toList();
  if(losses.size()>=3)alerts.add("동시에 평가손실인 종목 "+losses.size()+"개 · 개별 손실 및 공통 시장 위험 재검토");
  var days=period(s,1,last);if(days.percent()!=null&&days.percent().compareTo(new BigDecimal("-2"))<=0)alerts.add("최근 24시간 기록의 입출금 조정 수익률 -2% 이하 · 계좌 전체 점검 필요, 자동 청산 기준 아님");
  if(costs.stream().anyMatch(c->c.confirmedCommission()==null))alerts.add("실제 정산 비용 미확인 주문이 있습니다. 체결 확인과 비용 확정을 구분하세요.");
  if(first==null)alerts.add("현재부터 계좌 성과 수집 중입니다. 이전 날짜의 총자산을 추정하지 않습니다.");
  var comparisons=comparisons(s);
  if(!Objects.equals(s,execution.ledgerScope()))throw new IllegalStateException("조회 중 계좌 설정 변경");
  return new View(now,state.environment(),issue,first==null?null:first.at(),state.lastReconciledAt(),state.brokerTotalAssets(),state.realizedNetPnl(),unrealized,fresh,List.copyOf(periods),List.copyOf(curve),flows,sectors,losses,costs,executionStrategies(s),comparisons,List.copyOf(alerts));
 }
 private Period period(String s,int days,Point last){
  String label=switch(days){case 1->"최근 24시간";case 7->"1주";case 30->"1개월";case 90->"3개월";default->"1년";};
  if(last==null)return new Period(label,days,null,null,null,null,null,null,null,null,null,"BASELINE_WAIT");
  var first=jdbc.query("SELECT * FROM account_valuation WHERE scope_id=? AND basis_at>=? AND basis_at<=? ORDER BY basis_at LIMIT 1",this::point,s,utc(last.at().minus(Duration.ofDays(days))),utc(last.at())).stream().findFirst().orElse(null);
  if(first==null||first.at().equals(last.at()))return new Period(label,days,last.at(),last.at(),ZERO,null,null,null,null,null,null,"SECOND_SAMPLE_WAIT");
  var fs=jdbc.query("SELECT occurred_at,amount,recorded_at FROM account_cash_flow WHERE scope_id=? AND occurred_at>? AND occurred_at<=?",(rs,n)->new CashFlow(rs.getObject(1,OffsetDateTime.class).toInstant(),rs.getBigDecimal(2),rs.getObject(3,OffsetDateTime.class).toInstant()),s,utc(first.at()),utc(last.at()));
  Instant newest=fs.stream().map(CashFlow::recordedAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
  Long reviewed=jdbc.queryForObject("SELECT COUNT(*) FROM account_flow_review WHERE scope_id=? AND from_at<=? AND through_at>=? AND recorded_at>=?",Long.class,s,utc(first.at()),utc(last.at()),utc(newest));
  BigDecimal net=fs.stream().map(CashFlow::amount).reduce(ZERO,BigDecimal::add),change=last.assets().subtract(first.assets());
  BigDecimal denominator=first.assets();double duration=Duration.between(first.at(),last.at()).toMillis();
  for(var f:fs){BigDecimal weight=BigDecimal.valueOf(Duration.between(f.at(),last.at()).toMillis()/duration);denominator=denominator.add(f.amount().multiply(weight));}
  BigDecimal profit=reviewed>0?change.subtract(net):null,rate=profit!=null&&denominator.signum()>0?percent(profit,denominator):null;
  BigDecimal drawdown=null;
  if(reviewed>0&&fs.isEmpty())drawdown=jdbc.queryForObject("""
   SELECT MIN((assets/peak-1)*100) FROM (SELECT assets,MAX(assets) OVER(ORDER BY basis_at ROWS UNBOUNDED PRECEDING) AS peak FROM account_valuation WHERE scope_id=? AND basis_at>=? AND basis_at<=?) p
   """,BigDecimal.class,s,utc(first.at()),utc(last.at()));
  return new Period(label,days,first.at(),last.at(),change,net,profit,rate,benchmark(first.kospi(),last.kospi()),benchmark(first.kosdaq(),last.kosdaq()),drawdown==null?null:drawdown.setScale(2,RoundingMode.HALF_UP),reviewed==0?"CASH_FLOW_REVIEW_REQUIRED":rate==null?"DENOMINATOR_INVALID":fs.isEmpty()?"NO_FLOW_RETURN":"MODIFIED_DIETZ_ESTIMATE");
 }
 private List<Sector> sectors(LegacyPortfolioHistory.View state){
  Map<String,BigDecimal> groups=new TreeMap<>();Map<String,List<String>> names=new TreeMap<>();boolean complete=true;
  for(var p:state.positions()){
   var profile=sources.latest(p.code(),"PROFILE");String code=profile!=null&&profile.state().equals("READY")&&!profile.fetchedAt().isAfter(clock.instant())&&Duration.between(profile.fetchedAt(),clock.instant()).toDays()<8?profile.payload().path("induty_code").asText():"UNKNOWN";
   if(!code.matches("[0-9]{2,6}"))code="UNKNOWN";
   names.computeIfAbsent(code,k->new ArrayList<>()).add(p.name());
   if(p.marketValue()==null){complete=false;continue;}groups.merge(code,p.marketValue(),BigDecimal::add);
  }
  BigDecimal total=groups.values().stream().reduce(ZERO,BigDecimal::add);List<Sector> result=new ArrayList<>();
  for(String code:names.keySet()){BigDecimal value=groups.get(code);result.add(new Sector(code,code.equals("UNKNOWN")?"업종 미확인":"DART 업종코드 "+code,value,complete&&value!=null&&total.signum()>0?percent(value,total):null,List.copyOf(names.get(code))));}
  return List.copyOf(result);
 }
 private List<ExecutionStrategy> executionStrategies(String s){
  return jdbc.query("SELECT o.strategy_code,STRING_AGG(DISTINCT o.strategy_policy, ' / ') AS policies,COUNT(DISTINCT o.id) AS orders,COALESCE(SUM(f.net_pnl) FILTER(WHERE o.side='SELL'),0) AS pnl FROM execution_order o LEFT JOIN execution_fill f ON f.order_id=o.id WHERE o.scope_id=? GROUP BY o.strategy_code ORDER BY o.strategy_code",
   (rs,n)->new ExecutionStrategy(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getBigDecimal(4)),s);
 }
 private List<Comparison> comparisons(String s){
  return jdbc.query("""
   SELECT o.checkpoint,COUNT(*) AS n,
    COUNT(*) FILTER (WHERE p.chart_action='OBSERVE_PASS') AS chart_n,
    COUNT(*) FILTER (WHERE p.value_action='OBSERVE_PASS') AS value_n,
    AVG(CASE WHEN p.chart_action='OBSERVE_PASS' THEN o.reference_net_return ELSE 0 END) AS chart_return,
    AVG(CASE WHEN p.value_action='OBSERVE_PASS' THEN o.reference_net_return ELSE 0 END) AS value_return
   FROM strategy_paired_observation p JOIN strategy_observation_outcome o ON o.observation_id=p.id
   WHERE p.scope_id=? AND o.same_time_window=TRUE AND o.elapsed_trading_days=SUBSTRING(o.checkpoint FROM 2)::integer
   GROUP BY o.checkpoint ORDER BY SUBSTRING(o.checkpoint FROM 2)::integer
   """,(rs,n)->new Comparison(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getBigDecimal(5),rs.getBigDecimal(6)),s);
 }
 private BigDecimal benchmark(BigDecimal first,BigDecimal last){return first==null||last==null||first.signum()<=0?null:percent(last.subtract(first),first);}
 private static BigDecimal percent(BigDecimal n,BigDecimal d){return n.multiply(new BigDecimal("100")).divide(d,2,RoundingMode.HALF_UP);}
 private static OffsetDateTime utc(Instant t){return t.atOffset(ZoneOffset.UTC);}
 private record CostOrder(String status,int quantity,BigDecimal amount,String side){}
 private record CashFlow(Instant at,BigDecimal amount,Instant recordedAt){}
 public record Point(Instant at,BigDecimal assets,BigDecimal kospi,BigDecimal kosdaq){}
 public record Flow(String id,Instant at,BigDecimal amount){}
 public record Period(String label,int requestedDays,Instant from,Instant through,BigDecimal assetChange,BigDecimal netFlow,BigDecimal profit,BigDecimal percent,BigDecimal kospiPercent,BigDecimal kosdaqPercent,BigDecimal drawdownPercent,String state){}
 public record Sector(String code,String label,BigDecimal amount,BigDecimal percent,List<String> stocks){}
 public record Loss(String name,BigDecimal netPnl){}
 public record Cost(long orderId,String name,String side,String status,int quantity,BigDecimal estimatedCommission,BigDecimal estimatedTax,BigDecimal confirmedCommission,BigDecimal confirmedTax,Instant confirmedAt){}
 public record ExecutionStrategy(String code,String policies,long orders,BigDecimal realizedNetPnl){}
 public record Comparison(String checkpoint,long samples,long chartPass,long valuePass,BigDecimal chartPercent,BigDecimal valuePercent){}
 public record View(Instant checkedAt,String environment,String issue,Instant baselineAt,Instant balanceAt,BigDecimal assets,BigDecimal realizedNetPnl,BigDecimal unrealizedNetPnl,boolean balanceFresh,List<Period> periods,List<Point> curve,List<Flow> flows,List<Sector> sectors,List<Loss> losses,List<Cost> costs,List<ExecutionStrategy> executionStrategies,List<Comparison> comparisons,List<String> alerts){}
}
