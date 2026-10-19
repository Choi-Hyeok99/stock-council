package com.personaltrader.orders;
import com.personaltrader.broker.ExecutionBrokerClient.*;
import com.personaltrader.marketdata.*;
import com.personaltrader.research.ResearchWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only compatibility for immutable historic mock records. No scheduler, broker calls or order mutation. */
@Service
public class LegacyPortfolioHistory {
    private static final String OPEN="('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','CANCEL_PENDING','UNKNOWN')";
    private final JdbcTemplate jdbc;private final Clock clock;private final ResearchWorkspace workspace;
    private final PositionQuoteCollector heldQuotes;private final OrderDecisionRepository decisions;
    private final com.personaltrader.research.PaperPortfolioPolicy policy;private final Environment environment;
    private final BigDecimal commissionRate,taxRate;
    public LegacyPortfolioHistory(JdbcTemplate jdbc,Clock clock,ResearchWorkspace workspace,PositionQuoteCollector quotes,
        OrderDecisionRepository decisions,com.personaltrader.research.PaperPortfolioPolicy policy,
        @Value("${execution.environment:PAPER}")String environment,@Value("${paper.costs.commission-rate}")BigDecimal fee,
        @Value("${paper.costs.transaction-tax-rate}")BigDecimal tax){
        this.jdbc=jdbc;this.clock=clock;this.workspace=workspace;this.heldQuotes=quotes;this.decisions=decisions;this.policy=policy;
        this.environment=Environment.valueOf(environment);commissionRate=fee;taxRate=tax;
    }
    private String scope(){return workspace.id();}
    public String ledgerScope(){return scope();}
    public Environment environment(){return environment;}
    private String newsPolicyDescription(){return "과거 모의 원장 조회 전용 · 주문 실행은 별도 사용자 확인 경로에서만 가능";}
    public View view(){return view(false);}
    public View displayView(){return view(true);}
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
    private BigDecimal principal(PositionRow p){return p==null?BigDecimal.ZERO:p.averagePrice().multiply(BigDecimal.valueOf(p.quantity())).setScale(2,RoundingMode.HALF_UP);}
    private BigDecimal committed(PositionRow p){return p==null?BigDecimal.ZERO:principal(p).add(p.entryCost());}
    public long closedTradeCount(Instant cutoff) {
        String scope=scope(); if(scope==null)return 0;
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
              SELECT entry_order_id FROM execution_episode_fill WHERE scope_id=? AND occurred_at<=?
              GROUP BY entry_order_id
              HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE 0 END)>0
                 AND SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END)=0
            ) completed
            """,Long.class,scope,utc(cutoff));
    }
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
                quote==null?null:quote.barAt(),quote==null?null:Duration.between(quote.tradeAt(),checkedAt).getSeconds(),"과거 모의 보유 기록 · 현재 증권사 보유/주문 기능과 연결하지 않음",null,0,
                SellCostCalculator.calculate(p.averagePrice(),p.quantity(),p.entryCost(),price,commissionRate,taxRate),netReturnPercent,priceReturnPercent);
        }).toList();
        BigDecimal strategyValue=positions.stream().anyMatch(p->p.marketValue()==null)?null:positions.stream().map(PositionView::marketValue).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal unrealized=positions.stream().anyMatch(p->p.estimatedNetPnl()==null)?null:positions.stream().map(PositionView::estimatedNetPnl).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal committed=held.stream().map(this::committed).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal reserved=rows.stream().filter(o->o.side()==Side.BUY&&!terminal(o.status()))
            .map(o->o.limitPrice().multiply(BigDecimal.valueOf(o.quantity()-o.filledQuantity()))
                .multiply(BigDecimal.ONE.add(o.commissionRate().max(BigDecimal.ZERO)))).reduce(BigDecimal.ZERO,BigDecimal::add);
        PortfolioView allocation=new PortfolioView(policy.budget(),policy.positionLimit(),policy.maxPositions(),committed,reserved,
            policy.budget().subtract(committed).subtract(reserved).max(BigDecimal.ZERO),null,policy.dayLossLimit(),false,null,
            "과거 모의 설정 보존 · 분석 전용 · 신규 배정 없음",true,"RESEARCH_ONLY_V1","과거 원장 조회 전용 · 주문 전송 없음",false,
            policy.minEntryCommitment(),policy.normalPositionLimit(),false,null,com.personaltrader.research.PaperTradingTempoPolicy.at(checkedAt,true),true);
        BigDecimal net=scope==null?BigDecimal.ZERO:jdbc.queryForObject("SELECT COALESCE(SUM(f.net_pnl),0) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=? AND o.side='SELL'",BigDecimal.class,scope);
        BigDecimal costs=scope==null?BigDecimal.ZERO:jdbc.queryForObject("SELECT COALESCE(SUM(f.commission+f.tax),0) FROM execution_fill f JOIN execution_order o ON o.id=f.order_id WHERE o.scope_id=?",BigDecimal.class,scope);
        List<EventView> events=scope==null?List.of():jdbc.query("SELECT event_type,detail,occurred_at FROM execution_event WHERE scope_id=? ORDER BY id DESC LIMIT 30",(rs,n)->new EventView(rs.getString(1),rs.getString(2),instant(rs,"occurred_at")),scope);
        String issue=null;String readiness="과거 모의 원장 조회 · 직접 모의 매도는 별도 보유·매도 화면에서 확인";
        return new View(environment.name(),scope!=null,false,false,readiness,newsPolicyDescription(),issue,
            a==null?null:a.lastReconciledAt(),a==null?null:a.totalAssets(),a==null?null:a.cash(),strategyValue,net,costs,
            positions.size(),(int)rows.stream().filter(o->!terminal(o.status())).count(),rows.stream().map(o->new OrderView(
                o.id(),o.code(),o.name(),o.side().name(),o.status(),label(o.status()),o.quantity(),o.filledQuantity(),o.limitPrice(),o.filledAmount(),
                o.commission(),o.tax(),o.netPnl(),o.reason(),o.createdAt(),o.updatedAt(),false,explanation.get(o.id()),results.get(o.id()))).toList(),positions,events,performance(scope),checkedAt,unrealized,allocation,true,true);
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
    private static boolean terminal(String state) { return Set.of("FILLED","CANCELED","REJECTED","EXPIRED").contains(state); }
    private static String label(String status) { return switch(status) {
        case "CREATED","SUBMITTING"->"접수 중"; case "SUBMITTED"->"접수 · 미체결"; case "PARTIALLY_FILLED"->"부분 체결";
        case "FILLED"->"체결 완료"; case "CANCEL_PENDING"->"취소 확인 중"; case "CANCELED"->"잔량 취소 완료";
        case "EXPIRED"->"당일 주문 잔량 만료";
        case "REJECTED"->"거절"; default->"응답 미확정"; }; }
    private BigDecimal fee(BigDecimal value,BigDecimal rate) {return value.multiply(rate).setScale(2,RoundingMode.HALF_UP);}
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String name)throws SQLException{OffsetDateTime t=rs.getObject(name,OffsetDateTime.class);return t==null?null:t.toInstant();}
    public record AdditionPlan(String state,boolean ready,String reason,Instant evaluatedAt,Instant researchCompletedAt,
        Integer score,BigDecimal cumulativeTarget,BigDecimal committedCapital,BigDecimal remainingExpectedLoss,
        int indicativeQuantity,BigDecimal combinedEstimatedLoss,Instant nextEarliestBuyAt){}
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
