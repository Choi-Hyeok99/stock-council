package com.personaltrader.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.research.InvestmentSignalEngine.Signal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.math.*;

@Repository
public class OrderDecisionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OrderDecisionRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public String encode(OrderDecisionSnapshot value){
        try{
            if(value==null||!"ORDER_TIME".equals(value.provenance())||value.executionChecks().stream().anyMatch(c->!c.pass()))
                throw new IllegalArgumentException();
            if(value.side().equals("BUY")&&(value.research()==null||value.research().explanation()==null||!value.research().paperEligible()))
                throw new IllegalArgumentException();
            if(value.side().equals("BUY")&&value.environment().equals("PAPER")&&value.allocation()!=null&&value.allocation().allowedLoss()==null){
                // Owner disabled PAPER loss sizing. Require the new explicit allocation evidence instead of comparing against null.
                var entry=value.entryReview();
                if(entry==null||!com.personaltrader.research.PaperEntryReviewService.VERSION.equals(entry.version())||!entry.ready()
                    ||entry.financialId()==null||entry.checks()==null||entry.checks().size()<14||entry.checks().stream().anyMatch(c->!c.pass()))throw new IllegalArgumentException();
                var evidence=value.allocationEvidence();
                if(evidence==null||!com.personaltrader.research.PaperAllocationService.VERSION.equals(evidence.version())
                    ||evidence.target()==null||evidence.target().signum()<=0||value.allocation().totalBuyBudget().compareTo(evidence.target())!=0)
                    throw new IllegalArgumentException();
                if("STRENGTHENED".equals(evidence.tier())){
                    if(evidence.target().compareTo(new BigDecimal("5000000"))>0||evidence.financialId()==null
                        ||evidence.checks()==null||evidence.checks().size()<12||evidence.checks().stream().anyMatch(c->!c.pass()))
                        throw new IllegalArgumentException();
                }else if(!"NORMAL".equals(evidence.tier())||evidence.target().compareTo(new BigDecimal("3000000"))>0)
                    throw new IllegalArgumentException();
            }
            if(value.side().equals("BUY")&&OrderDecisionSnapshot.VERSION.equals(value.version())){
                var tempo=com.personaltrader.research.PaperTradingTempoPolicy.at(value.orderAt(),value.environment().equals("PAPER"));
                if(value.executionChecks().stream().noneMatch(c->c.key().equals("ENTRY_TEMPO")&&c.pass()
                    &&tempo.id().equals(c.observed())&&tempo.description().equals(c.threshold())))throw new IllegalArgumentException();
                if(value.additionDecision()!=null&&!value.additionDecision().nextEarliestBuyAt().equals(
                    value.additionDecision().previousBuyAt().plus(tempo.interval())))throw new IllegalArgumentException();
            }
            if(value.additionDecision()!=null){
                var a=value.additionDecision();
                if(!value.side().equals("BUY")||!value.environment().equals("PAPER")||value.allocation()==null
                    ||a.previousQuantity()<1||value.allocation().allowedLoss()!=null&&a.combinedEstimatedLoss().compareTo(value.allocation().allowedLoss())>0
                    ||a.combinedCommittedCapital().compareTo(a.cumulativeTarget())>0
                    ||a.researchCompletedAt()==null||!a.researchCompletedAt().isAfter(a.previousBuyAt())
                    ||value.orderAt().isBefore(a.nextEarliestBuyAt()))throw new IllegalArgumentException();
            }
            if(value.side().equals("SELL")&&(value.exitDecision()==null
                    ||value.exitDecision().economics()==null||value.exitDecision().position()==null
                    ||value.exitDecision().triggerQuote()==null))
                throw new IllegalArgumentException();
            if(value.side().equals("SELL")){
                var exit=value.exitDecision();var economics=exit.economics();
                if(exit.conditionChecks()==null||exit.conditionChecks().stream()
                        .noneMatch(c->c.key().equals(exit.trigger())&&Boolean.TRUE.equals(c.triggered()))
                        ||economics.submittedQuantity()<1||economics.submittedQuantity()>exit.position().quantity()
                        ||economics.submittedQuantity()>economics.sellableQuantity()
                        ||exit.triggerQuote().freshnessProblem(exit.evaluatedAt())!=null)
                    throw new IllegalArgumentException();
            }
            return mapper.writeValueAsString(value);
        }catch(Exception ignored){throw new IllegalStateException("주문 판단 근거를 고정 저장하지 못해 전송을 보류합니다.");}
    }
    /** One bounded join for visible orders + held entries. Legacy data remains a labelled reference. */
    public Map<Long,OrderDecisionSnapshot> views(String scope){
        if(scope==null)return Map.of();
        List<Map.Entry<Long,OrderDecisionSnapshot>> rows=jdbc.query("""
            SELECT o.id,o.side,o.created_at,o.reason,o.decision_explanation,a.environment,
                prior.generated_at AS prior_at,prior.signal AS prior_signal
            FROM execution_order o JOIN execution_account a ON a.scope_id=o.scope_id
            LEFT JOIN LATERAL (
                SELECT i.generated_at,s.value AS signal
                FROM investment_idea_snapshot i,
                    LATERAL jsonb_array_elements(COALESCE(i.report->'signals','[]'::jsonb)||COALESCE(i.report->'riskSignals','[]'::jsonb)) s(value)
                WHERE o.decision_explanation IS NULL AND o.side='BUY'
                    AND i.generated_at<=o.created_at AND i.generated_at>=o.created_at-INTERVAL '20 minutes'
                    AND s.value->>'code'=o.stock_code
                ORDER BY i.generated_at DESC LIMIT 1
            ) prior ON TRUE
            WHERE o.scope_id=? AND (o.id IN (SELECT id FROM execution_order WHERE scope_id=? ORDER BY id DESC LIMIT 100)
                OR EXISTS (SELECT 1 FROM execution_position p WHERE p.scope_id=o.scope_id AND p.entry_order_id=o.id)
                OR o.id IN (SELECT entry_order_id FROM
                    (SELECT entry_order_id FROM execution_order WHERE scope_id=? ORDER BY id DESC LIMIT 100) visible))
            ORDER BY o.id DESC
            """,(rs,n)->Map.entry(rs.getLong("id"),decode(rs)),scope,scope,scope);
        Map<Long,OrderDecisionSnapshot> result=new HashMap<>();rows.forEach(e->result.put(e.getKey(),e.getValue()));return Map.copyOf(result);
    }
    private OrderDecisionSnapshot decode(ResultSet rs)throws java.sql.SQLException{
        Instant orderAt=rs.getObject("created_at",OffsetDateTime.class).toInstant();
        String stored=rs.getString("decision_explanation");
        if(stored!=null){
            try{return mapper.readValue(stored,OrderDecisionSnapshot.class);}
            catch(Exception ignored){return unavailable(orderAt,rs.getString("environment"),rs.getString("side"),"저장한 주문 근거 형식 확인 필요");}
        }
        String prior=rs.getString("prior_signal");Signal research=null;
        try{if(prior!=null)research=mapper.readValue(prior,Signal.class);}catch(Exception ignored){ /* no reconstruction from current inputs */ }
        Instant reference=rs.getObject("prior_at",OffsetDateTime.class)==null?null:rs.getObject("prior_at",OffsetDateTime.class).toInstant();
        return new OrderDecisionSnapshot(VERSION(),research==null?"LEGACY_SUMMARY":"PRIOR_RESEARCH",reference,orderAt,
            rs.getString("environment"),rs.getString("side"),rs.getString("reason"),research,null,null,null,List.of(),null,
            research==null?List.of():research.risks(),List.of("기존 주문에는 주문 순간의 전체 근거가 고정 저장되지 않았음",
            "선행 연구는 참고 자료이며 주문 순간 입력과 1:1 동일하다고 단정할 수 없음", "현재 시세나 새 기사로 과거 매수 이유를 만들어내지 않음"),null);
    }
    private static String VERSION(){return OrderDecisionSnapshot.VERSION;}
    private static OrderDecisionSnapshot unavailable(Instant at,String environment,String side,String issue){
        return new OrderDecisionSnapshot(OrderDecisionSnapshot.VERSION,"UNAVAILABLE",null,at,environment,side,issue,null,null,null,null,List.of(),null,List.of(),List.of("원문 저장 상태를 확인해야 함 · 재구성으로 대체하지 않음"),null);
    }

    /** One bounded ledger join. Historical decisions are never rewritten with derived outcomes. */
    public Map<Long,OrderTradeResult> tradeResults(String scope,Map<Long,OrderDecisionSnapshot> explanations){
        if(scope==null)return Map.of();
        List<Map.Entry<Long,OrderTradeResult>> rows=jdbc.query("""
            SELECT o.id,o.side,o.filled_quantity,o.filled_amount,o.commission,o.tax,o.net_pnl,o.entry_order_id,
                b.reason AS entry_reason,
                f.qty,f.amount,f.commission AS fill_commission,f.tax AS fill_tax,f.gross_pnl,f.net_pnl AS fill_net_pnl,
                f.first_at,f.last_at
            FROM (SELECT * FROM execution_order WHERE scope_id=? ORDER BY id DESC LIMIT 100) o
            LEFT JOIN execution_order b ON b.id=o.entry_order_id AND b.scope_id=o.scope_id
                AND b.stock_code=o.stock_code AND b.side='BUY'
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(quantity),0) AS qty,COALESCE(SUM(gross_amount),0) AS amount,
                    COALESCE(SUM(commission),0) AS commission,COALESCE(SUM(tax),0) AS tax,
                    COALESCE(SUM(gross_pnl),0) AS gross_pnl,COALESCE(SUM(net_pnl),0) AS net_pnl,
                    MIN(occurred_at) AS first_at,MAX(occurred_at) AS last_at
                FROM execution_fill WHERE order_id=o.id
            ) f ON TRUE
            ORDER BY o.id DESC
            """,(rs,n)->{
                int quantity=rs.getInt("filled_quantity");
                boolean sell="SELL".equals(rs.getString("side"));
                BigDecimal amount=rs.getBigDecimal("filled_amount"),commission=rs.getBigDecimal("commission"),tax=rs.getBigDecimal("tax");
                boolean reconciled=rs.getLong("qty")==quantity&&amount.compareTo(rs.getBigDecimal("amount"))==0
                    &&commission.compareTo(rs.getBigDecimal("fill_commission"))==0&&tax.compareTo(rs.getBigDecimal("fill_tax"))==0
                    &&(!sell||rs.getBigDecimal("net_pnl").compareTo(rs.getBigDecimal("fill_net_pnl"))==0);
                BigDecimal grossPnl=sell&&quantity>0&&reconciled?rs.getBigDecimal("gross_pnl"):null;
                BigDecimal net=sell&&quantity>0&&reconciled?rs.getBigDecimal("fill_net_pnl"):null;
                BigDecimal entryAmount=grossPnl==null?null:amount.subtract(grossPnl);
                BigDecimal entryCosts=net==null?null:grossPnl.subtract(net).subtract(commission).subtract(tax);
                if(entryAmount!=null&&(entryAmount.signum()<0||entryCosts.signum()<0))reconciled=false;
                Long entryId=rs.getObject("entry_order_id")==null?null:rs.getLong("entry_order_id");
                BigDecimal average=quantity>0&&reconciled?amount.divide(BigDecimal.valueOf(quantity),8,RoundingMode.HALF_UP):null;
                BigDecimal cash=quantity>0&&reconciled?(sell?amount.subtract(commission).subtract(tax):amount.add(commission).add(tax).negate()):null;
                return Map.entry(rs.getLong("id"),new OrderTradeResult(rs.getString("side"),reconciled,quantity,average,
                    amount,reconciled?entryAmount:null,reconciled?entryCosts:null,commission,tax,cash,
                    reconciled?grossPnl:null,reconciled?net:null,instant(rs,"first_at"),instant(rs,"last_at"),
                    entryId,rs.getString("entry_reason"),entryId==null?null:explanations.get(entryId),
                    !reconciled?"체결 원장 대조 필요 · 미확인 금액을 0으로 대체하지 않음":
                    quantity==0?"확인된 체결 없음 · 주문 의도와 체결 결과 구분":
                    "증권사 확인 체결 가격/수량 · 매수 원금/비용은 보유 묶음의 가중 평균 배분 · 수수료/세금은 주문 당시 설정 요율 추정 · 시각은 체결 확인 관측 시각",
                    sell&&entryId!=null?entryBuys(scope,entryId,instant(rs,"last_at")==null?instant(rs,"first_at"):instant(rs,"last_at"),explanations):List.of()));
            },scope);
        Map<Long,OrderTradeResult> result=new HashMap<>();rows.forEach(e->result.put(e.getKey(),e.getValue()));
        return Map.copyOf(result);
    }
    /** Include only confirmed entry fills observed before this outcome's cutoff. No current-news reconstruction. */
    public List<OrderTradeResult.EntryBuy> entryBuys(String scope,long root,Instant cutoff,Map<Long,OrderDecisionSnapshot> explanations){
        if(scope==null||cutoff==null)return List.of();
        return jdbc.query("""
            SELECT b.id,b.created_at,b.reason,SUM(f.quantity) AS qty,SUM(f.gross_amount) AS amount,SUM(f.commission) AS commission,b.decision_explanation
            FROM execution_order b JOIN execution_fill f ON f.order_id=b.id
            WHERE b.scope_id=? AND b.side='BUY' AND (b.id=? OR b.entry_order_id=?) AND f.occurred_at<=?
            GROUP BY b.id ORDER BY b.id LIMIT 100
            """,(rs,n)->{
                long id=rs.getLong("id");OrderDecisionSnapshot decision=explanations.get(id);
                if(decision==null&&rs.getString("decision_explanation")!=null){
                    try{decision=mapper.readValue(rs.getString("decision_explanation"),OrderDecisionSnapshot.class);}catch(Exception ignored){ /* preserve missing state */ }
                }
                return new OrderTradeResult.EntryBuy(id,rs.getInt("qty"),rs.getBigDecimal("amount"),rs.getBigDecimal("commission"),
                    instant(rs,"created_at"),rs.getString("reason"),decision);
            },scope,root,root,cutoff.atOffset(ZoneOffset.UTC));
    }
    private static Instant instant(ResultSet rs,String column)throws java.sql.SQLException{
        OffsetDateTime at=rs.getObject(column,OffsetDateTime.class);return at==null?null:at.toInstant();
    }
}
