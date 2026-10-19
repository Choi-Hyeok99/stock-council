package com.personaltrader.orders;

import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.broker.ExecutionBrokerClient.*;
import com.personaltrader.marketdata.TimedMarketQuote;
import com.personaltrader.marketdata.MarketHistory;
import com.personaltrader.marketdata.DipEntryGate;
import com.personaltrader.research.PaperPortfolioPolicy;
import com.personaltrader.research.MarketSessionService;
import com.personaltrader.research.InvestmentSignalEngine.*;
import com.personaltrader.orders.OrderDecisionSnapshot.*;
import static com.personaltrader.research.InvestmentSignalEngine.check;
import static com.personaltrader.research.InvestmentSignalEngine.money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Owner-confirmed PAPER sales and read-only reconciliation of current and historical broker orders. */
@Service
public class ManualTradingService {
    private final java.util.concurrent.locks.ReentrantLock executionLock=new java.util.concurrent.locks.ReentrantLock(true);
    private <T>T locked(java.util.function.Supplier<T> action,boolean manual){
        boolean acquired=false;
        try{
            acquired=manual?executionLock.tryLock(Math.min(250_000_000L,OrderLatencyContext.remainingNanos()),java.util.concurrent.TimeUnit.NANOSECONDS):acquireExecutionLock();
            if(!acquired)throw new IllegalStateException("잔고 대조 작업 중이에요. 주문은 보내지 않았어요. 잠시 뒤 다시 확인해 주세요.");
            if(executionLock.getHoldCount()==1)OrderLatencyContext.mark("LOCK_ACQUIRED");
            OrderLatencyContext.requireTime();return action.get();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("주문 준비 대기 중단 · 전송 없음");}
        finally{if(acquired)executionLock.unlock();}
    }
    private boolean acquireExecutionLock(){executionLock.lock();return true;}
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String OPEN = "('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','CANCEL_PENDING','UNKNOWN')";
    private static final String OTHER_SCOPE_ISSUE = "이전 계좌의 미확정 주문·보유 원장이 있습니다. 기존 계좌 설정과 식별 salt를 복구해 대조해 주세요.";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExecutionBrokerClient broker;
    private final Clock clock;
    private final OrderDecisionRepository decisions;
    private final PositionQuoteCollector heldQuotes;
    private final PaperPortfolioPolicy policy;
    private final MarketSessionService sessions;
    private final Environment environment;
    private final BigDecimal commissionRate, taxRate;
    private volatile Account currentAccount;
    private volatile String currentScope;
    private volatile Instant lastRefreshAttempt;
    private volatile String runtimeIssue;
    private Instant lastFailureDiagnostic;
    /** Only class/method/line metadata, never provider messages, parameters or response bodies. */
    private void diagnoseFailure(String stage,RuntimeException problem){
        Instant now=clock.instant();
        if(lastFailureDiagnostic!=null&&Duration.between(lastFailureDiagnostic,now).getSeconds()<60)return;
        lastFailureDiagnostic=now;
        Throwable cause=problem;
        for(int i=0;i<8&&cause.getCause()!=null&&cause.getCause()!=cause;i++)cause=cause.getCause();
        StackTraceElement site=cause.getStackTrace().length==0?null:cause.getStackTrace()[0];
        org.slf4j.LoggerFactory.getLogger(ManualTradingService.class).warn(
            "Order cycle diagnostic stage={} type={} site={}",stage,cause.getClass().getSimpleName(),
            site==null?"unavailable":site.getClassName()+"."+site.getMethodName()+":"+site.getLineNumber());
    }

    public ManualTradingService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ExecutionBrokerClient broker,
            Clock clock, PositionQuoteCollector heldQuotes,
            @Value("${execution.environment:PAPER}") String environment,
            @Value("${paper.costs.commission-rate}") BigDecimal paperCommission,
            @Value("${paper.costs.transaction-tax-rate}") BigDecimal paperTax,
            @Value("${execution.live-commission-rate:-1}") BigDecimal liveCommission,
            @Value("${execution.live-tax-rate:-1}") BigDecimal liveTax,
            PaperPortfolioPolicy policy, MarketSessionService sessions, OrderDecisionRepository decisions) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(transactions); this.broker=broker; this.clock=clock;
        this.heldQuotes=heldQuotes; this.policy=policy; this.sessions=sessions; this.decisions=decisions;
        this.environment=Environment.valueOf(environment.toUpperCase(Locale.ROOT));
        this.commissionRate=this.environment==Environment.PAPER ? paperCommission : liveCommission;
        this.taxRate=this.environment==Environment.PAPER ? paperTax : liveTax;
        if (commissionRate.compareTo(BigDecimal.ONE)>0 || taxRate.compareTo(BigDecimal.ONE)>0)
            throw new IllegalArgumentException("비용 요율 설정 범위를 확인해 주세요.");
    }

    private String scope() { return broker.configured(environment) ? broker.accountKey(environment) : null; }
    private String ensureScope() {
        String scope=scope();
        if (scope==null) throw new IllegalStateException("선택한 환경의 API 키와 계좌를 등록해 주세요.");
        Long unfinished=jdbc.queryForObject("""
            SELECT COUNT(*) FROM execution_account a WHERE a.environment=? AND a.scope_id<>?
              AND (EXISTS (SELECT 1 FROM execution_order o WHERE o.scope_id=a.scope_id AND o.status IN
                  ('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','CANCEL_PENDING','UNKNOWN'))
                OR EXISTS (SELECT 1 FROM execution_position p WHERE p.scope_id=a.scope_id))
            """,Long.class,environment.name(),scope);
        if(unfinished>0) { runtimeIssue=OTHER_SCOPE_ISSUE; throw new IllegalStateException(OTHER_SCOPE_ISSUE); }
        jdbc.update("INSERT INTO execution_account(scope_id, environment) VALUES (?,?) ON CONFLICT DO NOTHING",scope,environment.name());
        if (!scope.equals(currentScope)) { currentScope=scope; currentAccount=null; lastRefreshAttempt=null; runtimeIssue=null; }
        return scope;
    }
    private void sameAccount(String scope) {
        if (!scope.equals(scope())) throw new IllegalStateException("계좌 설정이 바뀌었습니다. 연결 상태를 다시 확인해 주세요.");
    }
    private AccountRow accountRow(String scope) {
        if(scope==null) return null;
        return jdbc.query("SELECT * FROM execution_account WHERE scope_id=?", (rs,n)->new AccountRow(
            rs.getBigDecimal("broker_cash"),rs.getBigDecimal("broker_total_assets"),
            instant(rs,"last_reconciled_at"),rs.getString("issue")),scope).stream().findFirst().orElse(null);
    }
    private List<OrderRow> orderRows(String scope, boolean pendingOnly) {
        if(scope==null) return List.of();
        return jdbc.query("SELECT * FROM execution_order WHERE scope_id=? "+(pendingOnly?"AND status IN "+OPEN:"")
                +" ORDER BY id DESC"+(pendingOnly?"":" LIMIT 100"),this::mapOrder,scope);
    }
    private List<PositionRow> positionRows(String scope) {
        if(scope==null) return List.of();
        return jdbc.query("SELECT * FROM execution_position WHERE scope_id=? ORDER BY stock_code",(rs,n)->new PositionRow(
            rs.getString("stock_code"),rs.getString("stock_name"),rs.getInt("quantity"),rs.getBigDecimal("average_price"),
            rs.getBigDecimal("entry_cost"),instant(rs,"opened_at"),rs.getLong("entry_order_id")),scope);
    }
    private OrderRow order(long id,String scope) {
        return jdbc.query("SELECT * FROM execution_order WHERE id=? AND scope_id=?",this::mapOrder,id,scope).stream()
            .findFirst().orElseThrow(()->new IllegalArgumentException("현재 계좌에서 주문을 찾을 수 없습니다."));
    }

    @Scheduled(fixedDelay=5000, initialDelay=25000)
    public void cycle(){if(!executionLock.tryLock())return;try{cycleLocked();}finally{executionLock.unlock();}}
    private void cycleLocked() {
        if(!broker.configured(environment))return;
        try{String s=ensureScope();long interval=orderRows(s,true).isEmpty()?30:10;
            if(lastRefreshAttempt==null||Duration.between(lastRefreshAttempt,clock.instant()).getSeconds()>=interval)refresh();
        }catch(RuntimeException problem){diagnoseFailure("manual-reconcile",problem);runtimeIssue="증권사 주문·체결·잔고 대조 확인 필요 · 자동 주문 없음";}
    }

    /** Explicit read refresh / reconciliation timer only. Never creates or cancels an order. */
    public void refresh(){locked(()->{refreshLocked();return null;},false);}
    private void refreshLocked() {
        String scope=ensureScope(); lastRefreshAttempt=clock.instant();OrderLatencyContext.mark("RECONCILE_BEGIN");
        try {
            // Crash after reservation or after POST stays unknown; never repeat the write.
            jdbc.update("UPDATE execution_order SET status='UNKNOWN',updated_at=? WHERE scope_id=? AND status IN ('CREATED','SUBMITTING')",utc(clock.instant()),scope);
            List<OrderRow> active=orderRows(scope,true);
            LocalDate today=clock.instant().atZone(SEOUL).toLocalDate();
            LocalDate from=active.stream().map(o->o.createdAt().atZone(SEOUL).toLocalDate()).min(LocalDate::compareTo).orElse(today.minusDays(1));
            if(from.isBefore(today.minusDays(89))) throw new IllegalStateException("오래된 미확정 주문은 별도 내역 대조가 필요합니다.");
            List<BrokerOrder> facts=broker.orders(environment,from,today);OrderLatencyContext.mark("ORDERS_READ");
            sameAccount(scope);
            List<OrderRow> recent=jdbc.query("SELECT * FROM execution_order WHERE scope_id=? AND (status IN "+OPEN+" OR updated_at>=?) ORDER BY id",this::mapOrder,scope,utc(clock.instant().minus(Duration.ofDays(2))));
            for(OrderRow row:recent) {
                if(row.brokerOrderId()==null) continue;
                BrokerOrder fact=findFact(facts,row);
                if(fact!=null) applyFact(scope,row.id(),fact);
                else if(!terminal(row.status())) throw new IllegalStateException("접수된 주문을 조회에서 확인하지 못했습니다. 재전송 없이 대조를 기다립니다.");
            }
            Account received=broker.account(environment);OrderLatencyContext.mark("BALANCE_READ");
            sameAccount(scope);
            if(received.receivedAt().isAfter(clock.instant()) || Duration.between(received.receivedAt(),clock.instant()).getSeconds()>120)
                throw new IllegalStateException("계좌 조회 시각 검증에 실패했습니다.");
            List<PositionRow> positions=positionRows(scope);
            for(PositionRow position:positions) {
                int actual=received.holdings().stream().filter(h->h.code().equals(position.code())).mapToInt(Holding::quantity).sum();
                if(actual!=position.quantity()) throw new IllegalStateException("증권사 보유 수량과 봇 원장이 다릅니다. 주문을 보류합니다.");
            }
            // Ordinary cash orders are day orders. Historical remainder alone is not a live reservation.
            // Expire only the next date after COMPLETE order inquiry and matching current holdings; never UNKNOWN.
            for (OrderRow row : orderRows(scope,true)) {
                if(row.brokerOrderId()==null||row.brokerDate()==null||!row.brokerDate().isBefore(today)
                        ||row.status().equals("UNKNOWN")) continue;
                BrokerOrder fact=findFact(facts,row);
                if(fact==null) throw new IllegalStateException("이전 거래일 주문 대조가 필요합니다.");
                jdbc.update("UPDATE execution_order SET status='EXPIRED',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),row.id(),scope);
                event(scope,row.id(),"EXPIRED","다음 날짜 전체 주문 내역·누적 체결·보유 대조 완료 · 당일 주문 잔량 만료",clock.instant());
            }
            String issue=orderRows(scope,true).stream().anyMatch(o->o.status().equals("UNKNOWN"))
                ? "응답이 불명확한 주문이 있습니다. 한국투자 주문번호를 확인해 연결해 주세요. 재전송하지 않습니다." : null;
            List<OrderRow> allMapped=jdbc.query("SELECT * FROM execution_order WHERE scope_id=? AND broker_order_id IS NOT NULL",this::mapOrder,scope);
            if (facts.stream().anyMatch(f->f.date().equals(today)&&f.filledQuantity()<f.quantity()&&!f.canceled()&&!f.rejected()
                    &&allMapped.stream().noneMatch(o->numericEquals(o.brokerOrderId(),f.orderNumber())&&Objects.equals(o.brokerDate(),f.date()))))
                issue="봇 외부의 미체결 주문이 있습니다. 한국투자에서 정리한 뒤 대조해 주세요.";
            jdbc.update("UPDATE execution_account SET broker_cash=?,broker_total_assets=?,last_reconciled_at=?,issue=? WHERE scope_id=?",
                received.cash(),received.totalAssets(),utc(received.receivedAt()),issue,scope);
            currentAccount=received; runtimeIssue=null;OrderLatencyContext.mark("RECONCILE_DONE");
        } catch(RuntimeException exception) {
            diagnoseFailure("reconcile",exception);
            runtimeIssue="증권사 주문·체결·잔고 대조 실패 · 새 주문 보류";
            jdbc.update("UPDATE execution_account SET issue=? WHERE scope_id=?",runtimeIssue,scope);
            throw new IllegalStateException(runtimeIssue);
        }
    }

    private BrokerOrder findFact(List<BrokerOrder> facts,OrderRow row) {
        List<BrokerOrder> matches=facts.stream().filter(f->numericEquals(row.brokerOrderId(),f.orderNumber())
                && Objects.equals(row.brokerDate(),f.date())).toList();
        if(matches.size()>1) throw new IllegalStateException("주문 조회에 중복 식별자가 있습니다.");
        return matches.isEmpty()?null:matches.getFirst();
    }
    private void applyFact(String scope,long id,BrokerOrder fact) {
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);
            OrderRow row=order(id,scope);
            if(!row.code().equals(fact.code())||row.side()!=fact.side()||row.quantity()!=fact.quantity()
                    || row.limitPrice().compareTo(fact.limitPrice())!=0)
                throw new IllegalStateException("증권사 주문과 요청 내용이 다릅니다.");
            if(fact.filledQuantity()<row.filledQuantity()||fact.filledQuantity()>row.quantity()
                    ||fact.filledAmount().compareTo(row.filledAmount())<0)
                throw new IllegalStateException("체결 누적 수량·금액 대조에 실패했습니다.");
            int delta=fact.filledQuantity()-row.filledQuantity();
            BigDecimal amount=fact.filledAmount().subtract(row.filledAmount());
            if(delta==0&&amount.signum()!=0 || delta>0&&amount.signum()<=0)
                throw new IllegalStateException("체결 수량과 금액이 일치하지 않습니다.");
            BigDecimal net=BigDecimal.ZERO;
            BigDecimal cumulativeCommission=fee(fact.filledAmount(),row.commissionRate());
            BigDecimal cumulativeTax=row.side()==Side.SELL?fee(fact.filledAmount(),row.taxRate()):BigDecimal.ZERO;
            if(delta>0) {
                BigDecimal commission=cumulativeCommission.subtract(row.commission());
                BigDecimal tax=cumulativeTax.subtract(row.tax());
                BigDecimal fillPrice=amount.divide(BigDecimal.valueOf(delta),8,RoundingMode.HALF_UP);
                if(row.side()==Side.BUY && fillPrice.compareTo(row.limitPrice())>0
                        || row.side()==Side.SELL && fillPrice.compareTo(row.limitPrice())<0)
                    throw new IllegalStateException("지정가와 체결가를 대조하지 못했습니다.");
                BigDecimal grossPnl=BigDecimal.ZERO;
                List<PositionRow> positions=positionRows(scope);
                PositionRow existing=positions.stream().filter(p->p.code().equals(row.code())).findFirst().orElse(null);
                if(row.side()==Side.BUY) {
                    long root=row.entryOrderId()==null?row.id():row.entryOrderId();
                    if(existing==null&&row.entryOrderId()!=null || existing!=null&&existing.entryOrderId()!=root)
                        throw new IllegalStateException("추가 매수 체결과 원래 보유 묶음이 다릅니다.");
                    if(existing==null) jdbc.update("INSERT INTO execution_position(scope_id,stock_code,stock_name,quantity,average_price,entry_cost,entry_order_id,opened_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        scope,row.code(),row.name(),delta,fillPrice,commission,id,utc(fact.date().atTime(fact.time()).atZone(SEOUL).toInstant()),utc(clock.instant()));
                    else {
                        BigDecimal avg=existing.averagePrice().multiply(BigDecimal.valueOf(existing.quantity())).add(amount)
                            .divide(BigDecimal.valueOf(existing.quantity()+delta),8,RoundingMode.HALF_UP);
                        jdbc.update("UPDATE execution_position SET quantity=?,average_price=?,entry_cost=entry_cost+?,updated_at=? WHERE scope_id=? AND stock_code=?",
                            existing.quantity()+delta,avg,commission,utc(clock.instant()),scope,row.code());
                    }
                } else {
                    if(existing==null || existing.quantity()<delta || !Objects.equals(row.entryOrderId(),existing.entryOrderId())) throw new IllegalStateException("매도 체결에 대응하는 보유 수량·묶음이 다릅니다.");
                    BigDecimal entry=delta==existing.quantity()?existing.entryCost():existing.entryCost().multiply(BigDecimal.valueOf(delta))
                        .divide(BigDecimal.valueOf(existing.quantity()),2,RoundingMode.HALF_UP);
                    grossPnl=amount.subtract(existing.averagePrice().multiply(BigDecimal.valueOf(delta))).setScale(2,RoundingMode.HALF_UP);
                    net=grossPnl.subtract(entry).subtract(commission).subtract(tax);
                    if(existing.quantity()==delta) jdbc.update("DELETE FROM execution_position WHERE scope_id=? AND stock_code=?",scope,row.code());
                    else jdbc.update("UPDATE execution_position SET quantity=quantity-?,entry_cost=entry_cost-?,updated_at=? WHERE scope_id=? AND stock_code=?",
                        delta,entry,utc(clock.instant()),scope,row.code());
                }
                Instant occurred=fact.date().atTime(fact.time()).atZone(SEOUL).toInstant();
                if(occurred.isAfter(clock.instant())) throw new IllegalStateException("미래 주문 시각입니다.");
                // REST provides cumulative fills and order time, not a per-fill execution timestamp. Use observation time.
                jdbc.update("INSERT INTO execution_fill(order_id,cumulative_quantity,quantity,price,gross_amount,commission,tax,gross_pnl,net_pnl,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id,fact.filledQuantity(),delta,fillPrice,amount,commission,tax,grossPnl,net,utc(clock.instant()));
                event(scope,id,"FILL_CONFIRMED","증권사 확인 체결 "+delta+"주 · 비용은 설정 요율 추정",clock.instant());
            }
            String next=fact.filledQuantity()==row.quantity()?"FILLED":fact.canceled()?"CANCELED":fact.rejected()?"REJECTED"
                : row.status().equals("CANCEL_PENDING")?"CANCEL_PENDING":fact.filledQuantity()>0?"PARTIALLY_FILLED":"SUBMITTED";
            if(row.status().equals("EXPIRED")&&delta==0&&next.equals(fact.filledQuantity()>0?"PARTIALLY_FILLED":"SUBMITTED")) next="EXPIRED";
            if(terminal(row.status())&&!next.equals(row.status())) throw new IllegalStateException("완료 주문의 상태가 변경되어 대조가 필요합니다.");
            jdbc.update("UPDATE execution_order SET filled_quantity=?,filled_amount=?,commission=?,tax=?,net_pnl=net_pnl+?,status=?,updated_at=? WHERE id=? AND scope_id=?",
                fact.filledQuantity(),fact.filledAmount(),cumulativeCommission,cumulativeTax,net,next,utc(clock.instant()),id,scope);
            if(!next.equals(row.status())) event(scope,id,next,label(next),clock.instant());
        });
    }

    public Environment environment() { return environment; }
    public BigDecimal commissionRate() { return commissionRate.max(BigDecimal.ZERO); }
    public BigDecimal taxRate() { return taxRate.max(BigDecimal.ZERO); }
    /** Preserve unknown/invalid cost configuration when displaying pre-order estimates. */
    public boolean costsConfigured() { return commissionRate.signum()>=0 && taxRate.signum()>=0
        && commissionRate.add(taxRate).compareTo(BigDecimal.ONE)<0; }
    public String newsPolicyDescription() {
        return "자동매매 없음 · AI는 연구 참고 자료 · 직접 모의 매도·잔량 취소는 사용자 확인 후 전송 · 실제 시세와 주문·체결·잔고 대조 유지";
    }
    public void assertAccountChangeAllowed(){locked(()->{assertAccountChangeAllowedLocked();return null;},false);}
    private void assertAccountChangeAllowedLocked() {
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM execution_order WHERE status IN "+OPEN,Long.class);
        long positions=jdbc.queryForObject("SELECT COUNT(*) FROM execution_position",Long.class);
        if(pending>0||positions>0) throw new IllegalStateException("미확정 주문이나 봇 보유 종목이 있어 계좌를 변경할 수 없습니다.");
    }
    public void changeAccount(Runnable save){locked(()->{changeAccountLocked(save);return null;},false);}
    private void changeAccountLocked(Runnable save) {
        assertAccountChangeAllowed();
        save.run();
        currentAccount=null; currentScope=null; lastRefreshAttempt=null;
    }
    /** Internal reporting join key. This opaque value is never returned in HTTP models. */
    public String ledgerScope() { return scope(); }

    private BigDecimal principal(PositionRow p){return p==null?BigDecimal.ZERO:p.averagePrice().multiply(BigDecimal.valueOf(p.quantity())).setScale(2,RoundingMode.HALF_UP);}
    private BigDecimal committed(PositionRow p){return p==null?BigDecimal.ZERO:principal(p).add(p.entryCost());}

    /** Retained API shape for clients reading older portfolio responses; no new addition plans are generated. */
    public record AdditionPlan(String state,boolean ready,String reason,Instant evaluatedAt,Instant researchCompletedAt,
        Integer score,BigDecimal cumulativeTarget,BigDecimal committedCapital,BigDecimal remainingExpectedLoss,
        int indicativeQuantity,BigDecimal combinedEstimatedLoss,Instant nextEarliestBuyAt){}

    /** Read-only owner sale estimates. Automatic sell pauses do not prohibit an explicit confirmed PAPER sale. */
    public ManualSaleView manualSaleView(){return locked(()->manualSaleViewLocked(),false);}
    private ManualSaleView manualSaleViewLocked() {
        String s=scope();List<ManualSaleQuote> held=new ArrayList<>();
        if(s!=null)for(var p:positionRows(s))held.add(manualSaleQuote(p.code(),p.quantity()));
        return new ManualSaleView(clock.instant(),environment.name(),List.copyOf(held),currentAccount==null||!Objects.equals(s,currentScope)?null:currentAccount.receivedAt(),
            "매도대금·거래세·매도 수수료와 배분된 매수 수수료를 반영한 추정입니다. 개인별 양도소득세 등은 미포함이며 실제 증권사 정산과 다를 수 있어요.");
    }
    public ManualSaleQuote manualSaleQuote(String code,int quantity){return locked(()->manualSaleQuoteLocked(code,quantity),false);}
    private ManualSaleQuote manualSaleQuoteLocked(String code,int quantity) {
        if(code==null||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("종목 확인 필요");
        String s=scope();PositionRow p=positionRows(s).stream().filter(x->x.code().equals(code)).findFirst().orElseThrow(()->new IllegalArgumentException("현재 보유 종목 확인 필요"));
        if(quantity<1||quantity>p.quantity())throw new IllegalArgumentException("보유 수량 안에서 선택해 주세요.");
        Instant now=clock.instant();var quote=heldQuotes.displayQuote(code);var fresh=heldQuotes.freshQuote(code);
        BigDecimal price=quote==null?null:PriceTicks.floor(quote.price());
        ManualSaleEconomics economics=price==null||!costsConfigured()?null:ManualSaleEconomics.calculate(p.averagePrice(),p.quantity(),p.entryCost(),quantity,price,commissionRate,taxRate);
        List<String> blockers=new ArrayList<>();
        if(environment!=Environment.PAPER)blockers.add("직접 매도는 현재 모의계좌에서만 지원해요.");
        if(!costsConfigured())blockers.add("매도 비용 요율 확인 필요");
        if(fresh==null||fresh.freshnessProblem(now)!=null)blockers.add("당일 120초 이내 검증 시세가 필요해요. 금액은 마지막 실제 시세 기준이에요.");
        if(!TimedMarketQuote.regularSession(now)||sessions.knownClosed())blockers.add("정규장 거래 가능 시간 확인 필요");
        AccountRow account=accountRow(s);
        if(account==null||account.issue()!=null||runtimeIssue!=null||account.lastReconciledAt()==null
            ||account.lastReconciledAt().isAfter(now)||Duration.between(account.lastReconciledAt(),now).getSeconds()>120)
            blockers.add("증권사 주문·체결·잔고 대조가 완료되어야 전송할 수 있어요.");
        if(currentAccount==null||!Objects.equals(s,currentScope))blockers.add("현재 계좌 잔고 재확인 필요");
        Integer sellable=currentAccount==null||!Objects.equals(s,currentScope)?null:currentAccount.holdings().stream().filter(x->x.code().equals(code)).mapToInt(Holding::sellableQuantity).sum();
        if(sellable==null)blockers.add("증권사 매도 가능 수량 확인 대기");
        else if(sellable<quantity)blockers.add("선택 수량이 증권사 매도 가능 수량을 초과해요.");
        if(!orderRows(s,true).isEmpty())blockers.add("미확정·미체결 주문을 먼저 확인해 주세요.");
        boolean fullProfit=price!=null&&costsConfigured()&&SellCostCalculator.reachesNetReturn(p.averagePrice(),p.quantity(),p.entryCost(),p.quantity(),price,commissionRate,taxRate,new BigDecimal("0.05"));
        String suggestion=fullProfit?"비용 후 +5% 이상 · 일부 익절 검토":economics==null?"시세·비용 확인 후 판단":economics.netPnl().signum()<0?"손실 구간 · 보유 우선 검토":"비용 후 이익 · 목표 +5%와 비교";
        String reason=fullProfit?"기존 비용 후 +5% 기준에 도달했어요. 절반 매도를 비교할 수 있어요. 최적 매도 시점이나 향후 하락을 예측한 것은 아니에요.":"이익·손실만으로 매도를 확정하지 않아요. 기업 전망과 자금 계획을 보고 직접 결정해 주세요.";
        return new ManualSaleQuote(code,p.name(),quantity,p.quantity(),sellable,price,quote==null?null:quote.tradeAt(),fresh!=null,
            costsConfigured()?commissionRate():null,costsConfigured()?taxRate():null,economics,suggestion,reason,fullProfit?Math.max(1,p.quantity()/2):p.quantity(),List.copyOf(blockers),now);
    }
    /** POST preparation freezes one price/quantity for 60 seconds. No broker write. */
    public PreparedManualSale prepareManualSale(String code,int quantity){return locked(()->prepareManualSaleLocked(code,quantity),true);}
    private PreparedManualSale prepareManualSaleLocked(String code,int quantity) {
        // Read/reconcile before the human final confirmation; this does not create an order.
        if(environment!=Environment.PAPER)throw new IllegalStateException("모의 직접 매도만 지원해요.");
        refresh();OrderLatencyContext.mark("RECONCILED");
        var preview=manualSaleQuote(code,quantity);
        if(!preview.blockers().isEmpty()||preview.economics()==null)return new PreparedManualSale(null,null,preview,currentAccount==null?null:currentAccount.receivedAt());
        String s=ensureScope();var p=positionRows(s).stream().filter(x->x.code().equals(code)).findFirst().orElseThrow();
        var fresh=heldQuotes.freshQuote(code);Instant now=clock.instant();
        if(fresh==null||fresh.freshnessProblem(now)!=null||PriceTicks.floor(fresh.price()).compareTo(preview.limitPrice())!=0||!basicReady(s))
            throw new IllegalStateException("시세·계좌 상태가 바뀌었어요. 금액을 다시 확인해 주세요.");
        UUID id=UUID.randomUUID();Instant expires=now.plusSeconds(60);
        jdbc.update("INSERT INTO manual_sell_intent(id,scope_id,stock_code,quantity,holding_fingerprint,limit_price,commission_rate,tax_rate,estimated_net_pnl,quote_at,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            id,s,code,quantity,manualFingerprint(p),preview.limitPrice(),commissionRate,taxRate,preview.economics().netPnl(),utc(fresh.tradeAt()),utc(now),utc(expires));
        return new PreparedManualSale(id,expires,preview,currentAccount.receivedAt());
    }
    /** Explicit final owner confirmation only. Repeated confirmation returns the original order, never a second POST. */
    public ManualSaleResult confirmManualSale(UUID id,boolean confirmed,boolean lossAcknowledged){return locked(()->confirmManualSaleLocked(id,confirmed,lossAcknowledged),true);}
    private ManualSaleResult confirmManualSaleLocked(UUID id,boolean confirmed,boolean lossAcknowledged) {
        if(!confirmed||id==null)throw new IllegalArgumentException("수량과 비용을 확인한 뒤 매도해 주세요.");
        if(environment!=Environment.PAPER)throw new IllegalStateException("모의계좌 직접 매도만 지원해요.");
        String s=ensureScope();var approval=manualApproval(id,s,false);
        var existing=jdbc.queryForList("SELECT id FROM execution_order WHERE scope_id=? AND decision_key=?",Long.class,s,"MANUAL_SELL:"+id);
        if(!existing.isEmpty())return manualSaleResult(existing.getFirst(),s);
        if(approval.netPnl().signum()<0&&!lossAcknowledged)throw new IllegalArgumentException("손실 금액을 확인하고 손실 매도에 동의해 주세요.");
        // A recent complete reconciliation is reused; the post-click path performs no collector round trip.
        AccountRow ready=accountRow(s);
        if(ready==null||ready.lastReconciledAt()==null||ready.issue()!=null||runtimeIssue!=null
            ||ready.lastReconciledAt().isAfter(clock.instant())||Duration.between(ready.lastReconciledAt(),clock.instant()).compareTo(Duration.ofSeconds(15))>0)
            throw new IllegalStateException("잔고 대조가 15초보다 오래됐어요. 최신 금액을 다시 확인해 주세요. 주문은 보내지 않았어요.");
        OrderLatencyContext.mark("PREFLIGHT_READY");
        var p=positionRows(s).stream().filter(x->x.code().equals(approval.code())).findFirst().orElseThrow(()->new IllegalStateException("보유 원장 변경 · 다시 확인해 주세요."));
        validateManualSale(approval,p,heldQuotes.freshQuote(p.code()));
        var history=heldQuotes.history(p.code());
        var evaluation=new ExitEvaluation(p,heldQuotes.freshQuote(p.code()),history,
            heldQuotes.confirmedTradingDaysSince(p.code(),p.openedAt(),history),clock.instant());
        long orderId=submitManualSale(s,evaluation,approval,
            "사용자 비용·수량 확인 후 직접 모의 매도 · "+approval.quantity()+"주"+(approval.netPnl().signum()<0?" · 비용 후 손실 확인·동의":""));
        return manualSaleResult(orderId,s);
    }
    private ManualSaleResult manualSaleResult(long id,String s){
        var row=order(id,s);var events=jdbc.queryForList("SELECT event_type FROM execution_event WHERE scope_id=? AND order_id=? ORDER BY id DESC LIMIT 1",String.class,s,id);
        boolean notSent="REJECTED".equals(row.status())&&!events.isEmpty()&&"NOT_SUBMITTED".equals(events.getFirst());
        return new ManualSaleResult(id,row.status(),notSent?"전송 준비 중 중단되어 증권사에 주문을 보내지 않았어요. 최신 금액을 확인하고 새로 요청해 주세요.":"모의 지정가 주문 상태예요. 접수와 실제 체결은 다르며 미확정 결과를 재전송하지 않아요.",notSent);
    }
    private ManualSaleApproval manualApproval(UUID id,String s,boolean lock){
        return jdbc.query("SELECT * FROM manual_sell_intent WHERE id=? AND scope_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new ManualSaleApproval(id,s,rs.getString("stock_code"),rs.getInt("quantity"),rs.getString("holding_fingerprint"),rs.getBigDecimal("limit_price"),rs.getBigDecimal("commission_rate"),rs.getBigDecimal("tax_rate"),rs.getBigDecimal("estimated_net_pnl"),instant(rs,"created_at"),instant(rs,"expires_at"),rs.getObject("order_id",Long.class)),id,s)
            .stream().findFirst().orElseThrow(()->new IllegalArgumentException("이 계좌의 매도 확인 내용을 찾지 못했어요."));
    }
    private void validateManualSale(ManualSaleApproval a,PositionRow p,TimedMarketQuote quote){
        Instant now=clock.instant();
        if(environment!=Environment.PAPER||a==null||a.orderId()!=null||!Objects.equals(a.scope(),scope())||a.createdAt().isAfter(now)||!a.expiresAt().isAfter(now)
            ||a.commission().compareTo(commissionRate)!=0||a.tax().compareTo(taxRate)!=0||!costsConfigured()||p==null||!manualFingerprint(p).equals(a.holdingFingerprint())
            ||a.quantity()<1||a.quantity()>p.quantity()||quote==null||quote.freshnessProblem(now)!=null||!quote.code().equals(a.code())
            ||quote.price().subtract(a.limit()).abs().compareTo(a.limit().multiply(new BigDecimal("0.01")))>0)
            throw new IllegalStateException("매도 확인이 만료됐거나 시세·수량·원가·요율이 바뀌었어요. 다시 계산해 주세요.");
    }
    private static String manualFingerprint(PositionRow p){return com.personaltrader.research.PublicInformationService.hash(p.toString());}
    private record ManualSaleApproval(UUID id,String scope,String code,int quantity,String holdingFingerprint,BigDecimal limit,
        BigDecimal commission,BigDecimal tax,BigDecimal netPnl,Instant createdAt,Instant expiresAt,Long orderId){}
    public record ManualSaleQuote(String code,String name,int quantity,int heldQuantity,Integer sellableQuantity,BigDecimal limitPrice,Instant quoteAt,boolean quoteFresh,
        BigDecimal commissionRate,BigDecimal taxRate,ManualSaleEconomics economics,String suggestion,String reason,int suggestedQuantity,List<String> blockers,Instant checkedAt){}
    public record ManualSaleView(Instant checkedAt,String environment,List<ManualSaleQuote> positions,Instant reconciledAt,String costNote){}
    public record PreparedManualSale(UUID intentId,Instant expiresAt,ManualSaleQuote quote,Instant reconciledAt){}
    public record ManualSaleResult(long orderId,String status,String message,boolean notSubmitted){}

    private record ExitEvaluation(PositionRow position,TimedMarketQuote quote,MarketHistory history,
            int tradingDays,Instant evaluatedAt){}
    private ExitDecision exitDecision(String scope,ExitEvaluation evaluation,int requested,int submitted,int sellable,BigDecimal price){
        if(evaluation==null||evaluation.quote()==null)throw new IllegalStateException("매도 판단 원본 확인 필요");
        PositionRow p=evaluation.position();MarketHistory history=evaluation.history();
        BigDecimal average=history==null?null:history.average20();
        List<ExitCheck> checks=List.of(new ExitCheck("USER_REQUEST","사용자 직접 매도 요청",
            "인증된 사용자의 매도 요청","사용자가 비용·수량을 확인한 직접 모의 매도",true));
        BigDecimal entryAmount=p.averagePrice().multiply(BigDecimal.valueOf(submitted)).setScale(2,RoundingMode.HALF_UP);
        BigDecimal entryCost=submitted==p.quantity()?p.entryCost():p.entryCost().multiply(BigDecimal.valueOf(submitted))
            .divide(BigDecimal.valueOf(p.quantity()),2,RoundingMode.HALF_UP);
        BigDecimal amount=price.multiply(BigDecimal.valueOf(submitted)),commission=fee(amount,commissionRate),tax=fee(amount,taxRate);
        BigDecimal proceeds=amount.subtract(commission).subtract(tax),pnl=proceeds.subtract(entryAmount).subtract(entryCost);
        String exitPolicy="사용자가 비용·수량을 확인한 뒤 직접 지정가 매도를 요청";
        String explanation=exitPolicy+". 주문 가격 기준 비용 후 손익은 "+money(pnl)+"로 추정되며 실제 체결 후 결과와 달라질 수 있다."
            +(pnl.signum()<0?" 사용자가 추정 손실을 확인하고 매도에 동의했다.":"");
        return new ExitDecision("USER_REQUEST","사용자 직접 매도 요청",exitPolicy,evaluation.evaluatedAt(),
            evaluation.quote(),new ExitPosition(p.name(),p.quantity(),p.averagePrice(),p.entryCost(),p.openedAt(),
                p.entryOrderId(),order(p.entryOrderId(),scope).reason()),null,average,history==null?null:history.basisDate(),
            history==null?null:history.receivedAt(),evaluation.tradingDays(),checks,
            new ExitEconomics(requested,submitted,sellable,price,entryAmount,entryCost,amount,commission,tax,proceeds,pnl),explanation);
    }
    private boolean basicReady(String scope) {
        AccountRow a=accountRow(scope);
        return broker.configured(environment)&&(environment!=Environment.LIVE||broker.liveArmed())
            &&commissionRate.signum()>=0&&taxRate.signum()>=0&&a!=null&&a.issue()==null&&runtimeIssue==null
            &&a.lastReconciledAt()!=null&&Duration.between(a.lastReconciledAt(),clock.instant()).getSeconds()<=120
            &&currentAccount!=null&&scope.equals(currentScope)&&TimedMarketQuote.regularSession(clock.instant())&&!sessions.knownClosed();
    }
    private boolean decisionExists(String scope,String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution_order WHERE scope_id=? AND decision_key=?",Long.class,scope,key)>0;
    }
    /** Only this confirmed, expiring owner intent can reach the broker write adapter. */
    private long submitManualSale(String scope,ExitEvaluation evaluation,ManualSaleApproval manual,String reason) {
        if(environment!=Environment.PAPER||manual==null||evaluation==null)
            throw new IllegalStateException("사용자가 확인한 모의 매도 의도만 전송할 수 있어요.");
        if(!basicReady(scope))throw new IllegalStateException("장 시간·계좌·비용·대조 상태를 먼저 확인해 주세요.");
        if(!orderRows(scope,true).isEmpty())throw new IllegalStateException("미확정 주문이 있어 새 주문을 보류합니다.");
        PositionRow position=evaluation.position();
        String code=position.code(),key="MANUAL_SELL:"+manual.id();
        TimedMarketQuote quote=heldQuotes.freshQuote(code);
        validateManualSale(manual,position,quote);
        if(!manual.code().equals(code)||!Objects.equals(evaluation.quote(),quote))
            throw new IllegalStateException("매도 판단의 종목·시세가 바뀌었어요. 다시 확인해 주세요.");
        BigDecimal limit=manual.limit();int qty=manual.quantity();
        Holding holding=currentAccount.holdings().stream().filter(h->h.code().equals(code)).findFirst()
            .orElseThrow(()->new IllegalStateException("증권사에서 매도 보유 수량을 확인하지 못했습니다."));
        if(qty>holding.sellableQuantity())throw new IllegalStateException("매도 가능 수량이 바뀌었어요. 다시 확인해 주세요.");
        sameAccount(scope);
        Long id=tx.execute(status->{
            jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);
            var approval=manualApproval(manual.id(),scope,true);
            if(!Objects.equals(approval,manual))throw new IllegalStateException("매도 확인 상태가 변경됐어요.");
            PositionRow lockedPosition=positionRows(scope).stream().filter(p->p.code().equals(code)).findFirst()
                .orElseThrow(()->new IllegalStateException("주문 보유 원장이 변경됐습니다."));
            if(!Objects.equals(lockedPosition,position))throw new IllegalStateException("주문 보유 수량·원가가 변경됐습니다.");
            Instant intentAt=clock.instant();
            validateManualSale(approval,lockedPosition,heldQuotes.freshQuote(code));
            if(!basicReady(scope)||quote.freshnessProblem(intentAt)!=null)
                throw new IllegalStateException("주문 사전 조회 중 계좌·시세가 오래되어 보류합니다.");
            if(!orderRows(scope,true).isEmpty()||decisionExists(scope,key))
                throw new IllegalStateException("이미 처리 중이거나 처리한 판단입니다.");
            List<NumericCheck> checks=List.of(
                check("QUOTE","주문 가격 원본 시각",quote.tradeAt()+" / 조회 "+quote.receivedAt(),"당일 정규장 체결·분봉, 120초 이내",true),
                check("ACCOUNT","계좌 대조",accountRow(scope).lastReconciledAt().toString(),"계좌·주문 대조 120초 이내, 미확정 주문 없음",true));
            ExitDecision exit=exitDecision(scope,evaluation,qty,qty,holding.sellableQuantity(),limit);
            OrderDecisionSnapshot decision=new OrderDecisionSnapshot(OrderDecisionSnapshot.VERSION,"ORDER_TIME",intentAt,intentAt,
                environment.name(),"SELL",reason,null,quote,null,null,checks,null,
                List.of("사용자가 결정한 매도이며 AI가 상승·하락이나 최적 매도 시점을 검증한 판단은 아님"),
                List.of("고정된 판단 원본은 주문 접수 의도이며 접수·체결·취소 결과와 별도",
                    "지정가·미체결·실제 정산에 따라 예상 금액과 최종 손익이 달라질 수 있음"),exit);
            String encoded=decisions.encode(decision); // Freeze evidence in the same transaction before the single broker POST.
            String strategy=jdbc.queryForObject("SELECT strategy_code FROM execution_order WHERE id=? AND scope_id=?",String.class,position.entryOrderId(),scope);
            if(!Set.of("CHART_RSS_MAIN_V1","LEGACY_UNCLASSIFIED",DipEntryGate.STRATEGY).contains(strategy))
                throw new IllegalStateException("해당 보유 전략의 실행 모듈이 없습니다.");
            long orderId=jdbc.queryForObject("INSERT INTO execution_order(scope_id,decision_key,stock_code,stock_name,side,quantity,limit_price,status,reason,created_at,updated_at,submitted_at,commission_rate,tax_rate,entry_order_id,decision_explanation,strategy_code,strategy_policy,exit_purpose) VALUES (?,?,?,?,?,?,?,'SUBMITTING',?,?,?,?,?,?,?,?::jsonb,?,?,?) RETURNING id",
                Long.class,scope,key,code,position.name(),"SELL",qty,limit,reason,utc(intentAt),utc(intentAt),utc(intentAt),
                commissionRate,taxRate,position.entryOrderId(),encoded,strategy,"MANUAL_PAPER_SELL_V1","USER_REQUEST");
            if(jdbc.update("UPDATE manual_sell_intent SET order_id=?,consumed_at=? WHERE id=? AND scope_id=? AND order_id IS NULL",
                orderId,utc(intentAt),manual.id(),scope)!=1)throw new IllegalStateException("이미 처리된 매도 확인이에요.");
            event(scope,orderId,"SUBMITTING",reason,intentAt);return orderId;
        });
        try {
            sameAccount(scope);
            OrderLatencyContext.order(id);OrderLatencyContext.mark("INTENT_SAVED");OrderLatencyContext.requireTime();
            Acknowledgement ack=broker.placeConfirmedPaperSale(id,manual.id(),new OrderRequest(code,Side.SELL,qty,limit));
            OrderLatencyContext.mark("BROKER_RESPONSE");
            if(ack.date()==null||!ack.date().equals(today()))throw new IllegalStateException("접수 날짜 검증 실패");
            jdbc.update("UPDATE execution_order SET status='SUBMITTED',broker_order_id=?,organization=?,broker_date=?,broker_time=?,updated_at=? WHERE id=? AND scope_id=?",
                ack.orderNumber(),ack.organization(),ack.date(),ack.time(),utc(clock.instant()),id,scope);
            event(scope,id,"SUBMITTED","증권사 접수 완료 · 체결 확인 대기",clock.instant());lastRefreshAttempt=null;
        } catch(ExecutionBrokerClient.NotSubmitted|OrderLatencyContext.DispatchDeadline noSubmission) {
            jdbc.update("UPDATE execution_order SET status='REJECTED',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),id,scope);
            event(scope,id,"NOT_SUBMITTED","전송 전 실패 · 실제 주문 요청 전송 없음",clock.instant());lastRefreshAttempt=null;
        } catch(ExecutionBrokerClient.Rejected rejected) {
            jdbc.update("UPDATE execution_order SET status='REJECTED',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),id,scope);
            event(scope,id,"REJECTED","증권사 접수 거절 · 코드 "+rejected.code(),clock.instant());lastRefreshAttempt=null;
        } catch(RuntimeException ambiguous) {
            jdbc.update("UPDATE execution_order SET status='UNKNOWN',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),id,scope);
            jdbc.update("UPDATE execution_account SET issue=? WHERE scope_id=?","접수 응답 불명확 · 주문 내역 확인 전 재전송 금지",scope);
            event(scope,id,"UNKNOWN","접수 결과 미확정 · 한국투자 내역 대조 필요",clock.instant());lastRefreshAttempt=null;
        }
        return id;
    }

    public void cancel(long id){locked(()->{cancelLocked(id);return null;},false);}
    private void cancelLocked(long id) {
        String scope=ensureScope();
        OrderRow row=order(id,scope);
        if(!cancelable(row)) throw new IllegalStateException("현재 상태에서 취소할 수 없습니다. 접수·체결 상태를 먼저 확인해 주세요.");
        if(!TimedMarketQuote.regularSession(clock.instant())||sessions.knownClosed()) throw new IllegalStateException("정규장 시간에 취소를 요청해 주세요.");
        if(environment==Environment.LIVE&&!broker.liveArmed()) throw new IllegalStateException("실전 주문 활성화 설정이 필요합니다.");
        List<BrokerOrder> facts=broker.orders(environment,row.brokerDate(),today());
        BrokerOrder fact=findFact(facts,row);
        if(fact==null) throw new IllegalStateException("취소 전 원주문 확인에 실패했습니다.");
        applyFact(scope,id,fact); row=order(id,scope);
        if(!cancelable(row)) return; // A fill won the race with the cancellation request.
        final OrderRow original=row;
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);
            OrderRow latest=order(id,scope);
            if(!cancelable(latest)) throw new IllegalStateException("취소 가능 상태가 변경됐습니다.");
            jdbc.update("UPDATE execution_order SET status='CANCEL_PENDING',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),id,scope);
            event(scope,id,"CANCEL_PENDING","미체결 잔량 취소 요청 · 확인 전 잔량 예약 유지",clock.instant());
        });
        try {
            sameAccount(scope);
            Acknowledgement ack=broker.cancel(environment,new Acknowledgement(original.brokerOrderId(),original.organization(),original.brokerDate(),original.brokerTime()),
                original.code(),original.quantity()-original.filledQuantity(),original.limitPrice());
            jdbc.update("UPDATE execution_order SET cancel_order_id=?,updated_at=? WHERE id=? AND scope_id=?",ack.orderNumber(),utc(clock.instant()),id,scope);
            event(scope,id,"CANCEL_ACCEPTED","취소 접수 완료 · 취소 확정 또는 추가 체결 대기",clock.instant());
        } catch(ExecutionBrokerClient.NotSubmitted|OrderLatencyContext.DispatchDeadline noSubmission) {
            jdbc.update("UPDATE execution_order SET status=?,updated_at=? WHERE id=? AND scope_id=?",original.status(),utc(clock.instant()),id,scope);
            event(scope,id,"CANCEL_NOT_SUBMITTED","취소 요청 전송 전 실패 · 원주문 대조 유지",clock.instant());
        } catch(ExecutionBrokerClient.Rejected rejected) {
            jdbc.update("UPDATE execution_order SET status=?,updated_at=? WHERE id=? AND scope_id=?",
                original.filledQuantity()>0?"PARTIALLY_FILLED":"SUBMITTED",utc(clock.instant()),id,scope);
            event(scope,id,"CANCEL_REJECTED","취소 거절 · 원주문 잔량과 추가 체결 재확인",clock.instant());
        } catch(RuntimeException ambiguous) {
            event(scope,id,"CANCEL_UNKNOWN","취소 응답 미확정 · 재취소 없이 원주문 내역 대조",clock.instant());
        }
        lastRefreshAttempt=null;
    }
    public void resolveUnknown(long id,String brokerOrderNumber){locked(()->{resolveUnknownLocked(id,brokerOrderNumber);return null;},false);}
    private void resolveUnknownLocked(long id,String brokerOrderNumber) {
        if(brokerOrderNumber==null||!brokerOrderNumber.matches("[0-9]{1,10}")) throw new IllegalArgumentException("한국투자 주문번호를 확인해 주세요.");
        String scope=ensureScope(); OrderRow row=order(id,scope);
        if(!row.status().equals("UNKNOWN")||row.brokerOrderId()!=null) throw new IllegalStateException("주문번호가 없는 응답 미확정 주문만 연결할 수 있습니다.");
        List<BrokerOrder> facts=broker.orders(environment,row.createdAt().atZone(SEOUL).toLocalDate(),today());
        List<BrokerOrder> matches=facts.stream().filter(f->numericEquals(f.orderNumber(),brokerOrderNumber)).toList();
        if(matches.size()!=1) throw new IllegalStateException("주문번호에 대응하는 고유한 내역을 확인하지 못했습니다.");
        BrokerOrder fact=matches.getFirst();
        Instant submitted=row.createdAt(); Instant factTime=fact.date().atTime(fact.time()).atZone(SEOUL).toInstant();
        if(!row.code().equals(fact.code())||row.side()!=fact.side()||row.quantity()!=fact.quantity()
                ||row.limitPrice().compareTo(fact.limitPrice())!=0||Duration.between(submitted,factTime).abs().getSeconds()>120)
            throw new IllegalStateException("종목·방향·수량·가격·접수 시각이 일치하지 않습니다.");
        sameAccount(scope);
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);
            OrderRow latest=order(id,scope);
            if(!latest.status().equals("UNKNOWN")||latest.brokerOrderId()!=null)
                throw new IllegalStateException("주문 상태가 변경됐습니다. 다시 대조해 주세요.");
            Long alreadyMapped=jdbc.queryForObject("SELECT COUNT(*) FROM execution_order WHERE scope_id=? AND broker_date=? AND broker_order_id::NUMERIC=? AND id<>?",
                Long.class,scope,fact.date(),new BigDecimal(fact.orderNumber()),id);
            if(alreadyMapped>0) throw new IllegalStateException("이미 다른 기록에 연결된 증권사 주문입니다.");
            jdbc.update("UPDATE execution_order SET broker_order_id=?,organization=?,broker_date=?,broker_time=?,status='SUBMITTED',updated_at=? WHERE id=? AND scope_id=? AND status='UNKNOWN' AND broker_order_id IS NULL",
                fact.orderNumber(),fact.organization(),fact.date(),fact.time(),utc(clock.instant()),id,scope);
            event(scope,id,"UNKNOWN_RESOLVED","사용자가 확인한 주문번호와 증권사 내역 대조 완료",clock.instant());
        });
        applyFact(scope,id,fact); lastRefreshAttempt=null; refresh();
    }
    private boolean numericEquals(String a,String b) { try{return new BigInteger(a).equals(new BigInteger(b));}catch(RuntimeException e){return false;} }

    /** Human review plus complete broker facts, after the day session settles, releases a genuinely unsent intent. */
    public void confirmNoSubmission(long id){locked(()->{confirmNoSubmissionLocked(id);return null;},false);}
    private void confirmNoSubmissionLocked(long id) {
        String scope=ensureScope(); OrderRow row=order(id,scope);
        if(!row.status().equals("UNKNOWN")||row.brokerOrderId()!=null)
            throw new IllegalStateException("주문번호가 없는 응답 미확정 주문만 대조할 수 있습니다.");
        ZonedDateTime now=clock.instant().atZone(SEOUL);
        LocalDate day=row.createdAt().atZone(SEOUL).toLocalDate();
        if(now.toLocalDate().equals(day)&&now.toLocalTime().isBefore(LocalTime.of(15,45))
                ||Duration.between(row.createdAt(),clock.instant()).toMinutes()<15)
            throw new IllegalStateException("장 마감 15분 이후 또는 다음 날짜에 원주문 내역을 대조해 주세요.");
        List<BrokerOrder> facts=broker.orders(environment,day,day);
        if(facts.stream().anyMatch(f->f.code().equals(row.code())&&f.side()==row.side()
                &&f.quantity()==row.quantity()&&f.limitPrice().compareTo(row.limitPrice())==0))
            throw new IllegalStateException("일치할 수 있는 증권사 주문이 있습니다. 주문번호 연결을 사용해 주세요.");
        Account account=broker.account(environment);
        sameAccount(scope);
        int expected=positionRows(scope).stream().filter(p->p.code().equals(row.code())).mapToInt(PositionRow::quantity).sum();
        int actual=account.holdings().stream().filter(h->h.code().equals(row.code())).mapToInt(Holding::quantity).sum();
        if(actual!=expected) throw new IllegalStateException("보유 수량이 달라 미접수로 확정할 수 없습니다.");
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT scope_id FROM execution_account WHERE scope_id=? FOR UPDATE",String.class,scope);
            OrderRow latest=order(id,scope);
            if(!latest.status().equals("UNKNOWN")||latest.brokerOrderId()!=null)
                throw new IllegalStateException("주문 상태가 변경됐습니다. 다시 대조해 주세요.");
            jdbc.update("UPDATE execution_order SET status='REJECTED',updated_at=? WHERE id=? AND scope_id=?",utc(clock.instant()),id,scope);
            event(scope,id,"NO_SUBMISSION_CONFIRMED","사용자 확인 + 해당 거래일 전체 주문 내역·보유 수량 대조 · 미접수 예약 해제",clock.instant());
        });
        lastRefreshAttempt=null; refresh();
    }

    public View view() { return view(false); }
    /** Orders dashboard valuation may retain old genuine quotes; execution inputs remain strict. */
    public View displayView() { return view(true); }
    private View view(boolean retainObservedPrices) {
        Instant checkedAt=clock.instant();
        String scope=scope(); AccountRow a=accountRow(scope);
        List<OrderRow> rows=orderRows(scope,false); List<PositionRow> held=positionRows(scope);
        Map<Long,OrderDecisionSnapshot> explanation=decisions.views(scope);
        Map<Long,OrderTradeResult> results=decisions.tradeResults(scope,explanation);
        List<PositionView> positions=held.stream().map(p->{
            TimedMarketQuote fresh=heldQuotes.freshQuote(p.code());
            boolean quoteFresh=fresh!=null&&fresh.freshnessProblem(checkedAt)==null;
            TimedMarketQuote quote=retainObservedPrices?heldQuotes.displayQuote(p.code()):quoteFresh?fresh:null;
            // Persisted observations value the display only; no synthetic/entry-price fallback.
            BigDecimal price=quote==null?null:quote.price();
            quoteFresh=quoteFresh&&quote!=null&&quote.equals(fresh);
            BigDecimal value=price==null?null:price.multiply(BigDecimal.valueOf(p.quantity()));
            boolean costsConfigured=commissionRate.signum()>=0&&taxRate.signum()>=0;
            BigDecimal sellCommission=value==null||!costsConfigured?null:fee(value,commissionRate);
            BigDecimal sellTax=value==null||!costsConfigured?null:fee(value,taxRate);
            BigDecimal proceeds=sellCommission==null?null:value.subtract(sellCommission).subtract(sellTax);
            BigDecimal entryAmount=principal(p);
            BigDecimal pnl=proceeds==null?null:proceeds.subtract(entryAmount).subtract(p.entryCost());
            BigDecimal entryBasis=entryAmount==null||p.entryCost()==null?null:entryAmount.add(p.entryCost());
            BigDecimal netReturnPercent=pnl==null||entryBasis==null||entryBasis.signum()<=0?null:
                pnl.multiply(BigDecimal.valueOf(100)).divide(entryBasis,4,RoundingMode.HALF_UP);
            BigDecimal priceReturnPercent=price==null||price.signum()<=0||p.averagePrice()==null||p.averagePrice().signum()<=0?null:
                price.subtract(p.averagePrice()).multiply(BigDecimal.valueOf(100)).divide(p.averagePrice(),4,RoundingMode.HALF_UP);
            String reason=order(p.entryOrderId(),scope).reason();
            return new PositionView(p.code(),p.name(),p.quantity(),p.averagePrice(),price,value,pnl,p.openedAt(),
                price==null?null:quote.tradeAt(),price==null?null:quote.receivedAt(),
                price==null?"확인된 체결 시세 없음":quoteFresh?"최신 시세 · 당일 체결·분봉 검증됨":
                    TimedMarketQuote.regularSession(checkedAt)?"이전 시세 · 새 시세를 기다리는 중":"장외 · 마지막 확인 시세",
                entryAmount,p.entryCost(),sellCommission,sellTax,proceeds,reason,explanation.get(p.entryOrderId()),null,
                decisions.entryBuys(scope,p.entryOrderId(),checkedAt,explanation),quoteFresh,costsConfigured,
                quote==null?null:quote.barAt(),quote==null?null:Duration.between(quote.tradeAt(),checkedAt).getSeconds(),"자동 매도 없음 · 직접 모의 매도는 비용·수량 확인 후 사용자 전송 · 평가손실은 남음",null,0,
                SellCostCalculator.calculate(p.averagePrice(),p.quantity(),p.entryCost(),price,commissionRate,taxRate),netReturnPercent,priceReturnPercent);
        }).toList();
        BigDecimal strategyValue=positions.stream().anyMatch(p->p.marketValue()==null)?null:positions.stream().map(PositionView::marketValue).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal unrealized=positions.stream().anyMatch(p->p.estimatedNetPnl()==null)?null:positions.stream().map(PositionView::estimatedNetPnl).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal committed=held.stream().map(this::committed).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal reserved=rows.stream().filter(o->o.side()==Side.BUY&&!terminal(o.status()))
            .map(o->o.limitPrice().multiply(BigDecimal.valueOf(o.quantity()-o.filledQuantity()))
                .multiply(BigDecimal.ONE.add(o.commissionRate().max(BigDecimal.ZERO)))).reduce(BigDecimal.ZERO,BigDecimal::add);
        // Keep the response shape for existing readers; retired automatic capabilities are always disabled.
        PortfolioView allocation=new PortfolioView(policy.budget(),policy.positionLimit(),policy.maxPositions(),committed,reserved,
            policy.budget().subtract(committed).subtract(reserved).max(BigDecimal.ZERO),null,policy.dayLossLimit(),false,null,
            "보유·과거 주문의 자금 사용 현황 · 자동 신규·추가 매수 없음",true,"OWNER_MANUAL_PAPER_SELL_V1",
            "자동매매 없음 · 직접 모의 매도·잔량 취소는 사용자 확인 후 전송",false,null,
            policy.normalPositionLimit(),false,null,null,true);
        BigDecimal net=scope==null?BigDecimal.ZERO:jdbc.queryForObject("SELECT COALESCE(SUM(f.net_pnl),0) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=? AND o.side='SELL'",BigDecimal.class,scope);
        BigDecimal costs=scope==null?BigDecimal.ZERO:jdbc.queryForObject("SELECT COALESCE(SUM(f.commission+f.tax),0) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=?",BigDecimal.class,scope);
        List<EventView> events=scope==null?List.of():jdbc.query("SELECT event_type,detail,occurred_at FROM execution_event WHERE scope_id=? ORDER BY id DESC LIMIT 30",(rs,n)->new EventView(rs.getString(1),rs.getString(2),instant(rs,"occurred_at")),scope);
        String issue=runtimeIssue!=null?runtimeIssue:a==null?null:a.issue();
        String readiness=scope==null?"선택한 환경의 키·계좌 등록 필요":issue!=null?issue:
            currentAccount==null||!Objects.equals(scope,currentScope)?"서버 시작 후 증권사 계좌 대조 대기":
            environment!=Environment.PAPER?"계좌 조회만 · 실전 주문 전송 미연결":
            sessions.knownClosed()||!TimedMarketQuote.regularSession(checkedAt)?"장외 · 금액 확인 가능, 주문은 정규장에 직접 확인":
            "자동매매 OFF · 직접 모의 매도와 잔량 취소만 사용자 확인 후 전송";
        return new View(environment.name(),scope!=null,false,false,readiness,newsPolicyDescription(),issue,
            a==null?null:a.lastReconciledAt(),a==null?null:a.totalAssets(),a==null?null:a.cash(),strategyValue,net,costs,
            positions.size(),(int)rows.stream().filter(o->!terminal(o.status())).count(),rows.stream().map(o->new OrderView(
                o.id(),o.code(),o.name(),o.side().name(),o.status(),label(o.status()),o.quantity(),o.filledQuantity(),o.limitPrice(),o.filledAmount(),
                o.commission(),o.tax(),o.netPnl(),o.reason(),o.createdAt(),o.updatedAt(),cancelable(o),explanation.get(o.id()),results.get(o.id()))).toList(),positions,events,performance(scope),checkedAt,unrealized,allocation,true,environment!=Environment.PAPER);
    }
    private List<Performance> performance(String scope) {
        List<Performance> results=new ArrayList<>();
        int[] days={7,30,90,365}; String[] names={"1주","1개월","3개월","1년"};
        for(int i=0;i<days.length;i++) {
            Instant from=clock.instant().minus(Duration.ofDays(days[i]));
            BigDecimal net=scope==null?BigDecimal.ZERO:jdbc.queryForObject("SELECT COALESCE(SUM(f.net_pnl),0) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=? AND o.side='SELL' AND f.occurred_at>=? AND f.occurred_at<=?",BigDecimal.class,scope,utc(from),utc(clock.instant()));
            long count=scope==null?0:jdbc.queryForObject("SELECT COUNT(DISTINCT o.id) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=? AND o.side='SELL' AND f.occurred_at>=? AND f.occurred_at<=?",Long.class,scope,utc(from),utc(clock.instant()));
            results.add(new Performance(names[i],days[i],net,count));
        } return List.copyOf(results);
    }
    private void note(String scope,String type,String detail) {
        String previous=jdbc.query("SELECT detail FROM execution_event WHERE scope_id=? ORDER BY id DESC LIMIT 1",rs->rs.next()?rs.getString(1):null,scope);
        if(!detail.equals(previous)) event(scope,null,type,detail,clock.instant());
    }
    private void event(String scope,Long id,String type,String detail,Instant at) {
        jdbc.update("INSERT INTO execution_event(scope_id,order_id,event_type,detail,occurred_at) VALUES (?,?,?,?,?)",scope,id,type,detail,utc(at));
    }

    private boolean cancelable(OrderRow o) { return o.brokerOrderId()!=null&&(o.status().equals("SUBMITTED")||o.status().equals("PARTIALLY_FILLED"))&&o.filledQuantity()<o.quantity(); }
    private static boolean terminal(String state) { return Set.of("FILLED","CANCELED","REJECTED","EXPIRED").contains(state); }
    private static String label(String status) { return switch(status) {
        case "CREATED","SUBMITTING"->"접수 중"; case "SUBMITTED"->"접수 · 미체결"; case "PARTIALLY_FILLED"->"부분 체결";
        case "FILLED"->"체결 완료"; case "CANCEL_PENDING"->"취소 확인 중"; case "CANCELED"->"잔량 취소 완료";
        case "EXPIRED"->"당일 주문 잔량 만료";
        case "REJECTED"->"거절"; default->"응답 미확정"; }; }
    private BigDecimal fee(BigDecimal value,BigDecimal rate) {return value.multiply(rate).setScale(2,RoundingMode.HALF_UP);}
    private LocalDate today(){return clock.instant().atZone(SEOUL).toLocalDate();}
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String name)throws SQLException{OffsetDateTime t=rs.getObject(name,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private OrderRow mapOrder(ResultSet rs,int n)throws SQLException{return new OrderRow(rs.getLong("id"),rs.getString("stock_code"),rs.getString("stock_name"),Side.valueOf(rs.getString("side")),rs.getInt("quantity"),rs.getBigDecimal("limit_price"),rs.getString("status"),rs.getString("broker_order_id"),rs.getString("organization"),rs.getObject("broker_date",LocalDate.class),rs.getObject("broker_time",LocalTime.class),rs.getInt("filled_quantity"),rs.getBigDecimal("filled_amount"),rs.getBigDecimal("commission"),rs.getBigDecimal("tax"),rs.getBigDecimal("net_pnl"),rs.getString("reason"),instant(rs,"created_at"),instant(rs,"updated_at"),rs.getBigDecimal("commission_rate"),rs.getBigDecimal("tax_rate"),rs.getObject("entry_order_id")==null?null:rs.getLong("entry_order_id"));}
    private record AccountRow(BigDecimal cash,BigDecimal totalAssets,Instant lastReconciledAt,String issue){}
    private record OrderRow(long id,String code,String name,Side side,int quantity,BigDecimal limitPrice,String status,String brokerOrderId,String organization,LocalDate brokerDate,LocalTime brokerTime,int filledQuantity,BigDecimal filledAmount,BigDecimal commission,BigDecimal tax,BigDecimal netPnl,String reason,Instant createdAt,Instant updatedAt,BigDecimal commissionRate,BigDecimal taxRate,Long entryOrderId){}
    private record PositionRow(String code,String name,int quantity,BigDecimal averagePrice,BigDecimal entryCost,Instant openedAt,long entryOrderId){}
    public record View(String environment,boolean configured,boolean liveEnabled,boolean automationEnabled,String readiness,String newsPolicy,String issue,Instant lastReconciledAt,BigDecimal brokerTotalAssets,BigDecimal brokerCash,BigDecimal strategyValue,BigDecimal realizedNetPnl,BigDecimal estimatedCosts,int positionCount,int pendingOrderCount,List<OrderView> orders,List<PositionView> positions,List<EventView> events,List<Performance> performance,Instant checkedAt,BigDecimal estimatedUnrealizedNetPnl,PortfolioView portfolio,boolean sellExecutionPaused,boolean manualSellPaused){}
    public record OrderView(long id,String code,String name,String side,String status,String statusLabel,int quantity,int filledQuantity,BigDecimal limitPrice,BigDecimal filledAmount,BigDecimal commission,BigDecimal tax,BigDecimal netPnl,String reason,Instant createdAt,Instant updatedAt,boolean cancelable,OrderDecisionSnapshot decision,OrderTradeResult tradeResult){}
    public record PositionView(String code,String name,int quantity,BigDecimal averagePrice,BigDecimal currentPrice,BigDecimal marketValue,BigDecimal estimatedNetPnl,Instant openedAt,Instant quoteAt,Instant quoteReceivedAt,String quoteStatus,BigDecimal entryAmount,BigDecimal entryCosts,BigDecimal estimatedSellCommission,BigDecimal estimatedSellTax,BigDecimal estimatedNetProceeds,String entryReason,OrderDecisionSnapshot decision,AdditionPlan additionPlan,List<OrderTradeResult.EntryBuy> entryBuys,boolean quoteFresh,boolean costsConfigured,Instant quoteBarAt,Long quoteAgeSeconds,String holdingPolicy,BigDecimal partialProfitTargetPrice,int partialProfitRemainingQuantity,SellCostCalculator.Analysis sellCostAnalysis,BigDecimal estimatedNetReturnPercent,BigDecimal priceReturnPercent){}
    public record PortfolioView(BigDecimal budget,BigDecimal positionLimit,int maxPositions,BigDecimal committedCapital,BigDecimal pendingBuyReservation,BigDecimal remainingAllocation,BigDecimal expectedLossLimit,BigDecimal realizedDayLossLimit,boolean stagedReinvestment,BigDecimal theoreticalRiskCapitalCeiling,String utilizationPolicy,boolean entriesPausedByMandate,String investmentPolicyVersion,String executionPolicy,boolean expectedLossSizingEnabled,BigDecimal minimumEntryCommitment,BigDecimal normalPositionLimit,boolean fallingMarketEntries,BigDecimal initialTrancheBudget,com.personaltrader.research.PaperTradingTempoPolicy.State entryTempo,boolean committeePreparation){}
    public record EventView(String type,String detail,Instant occurredAt){}
    public record Performance(String label,int days,BigDecimal netPnl,long sellCount){}
}
