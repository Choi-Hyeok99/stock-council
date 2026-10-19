package com.personaltrader.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** User-selected PAPER limits. LIVE retains its separate small pilot limits. */
@Component
public class PaperPortfolioPolicy {
    /** Owner permits only the net 5% partial-profit strategy; the service enforces its purpose. */
    public static final boolean PAPER_SELL_EXECUTION_PAUSED=true;
    public static final boolean PAPER_COMMITTEE_PREPARATION=true;
    private final BigDecimal budget,positionLimit,lossLimit,dayLossLimit;
    private final int positions;
    private final boolean paper;
    private java.time.Clock clock=java.time.Clock.systemUTC();
    @Autowired public void setClock(java.time.Clock clock){this.clock=clock;}
    @Autowired
    public PaperPortfolioPolicy(@Value("${execution.environment:PAPER}") String environment,
            @Value("${portfolio.paper-budget:10000000}") BigDecimal budget,
            @Value("${portfolio.paper-position-limit:5000000}") BigDecimal positionLimit,
            @Value("${portfolio.paper-max-positions:5}") int positions,
            @Value("${portfolio.paper-max-expected-loss:100000}") BigDecimal expectedLoss,
            @Value("${portfolio.paper-day-realized-loss-limit:200000}") BigDecimal realizedDayLoss) {
        boolean paper="PAPER".equals(environment);
        this.paper=paper;
        this.budget=paper?budget:BigDecimal.valueOf(300000);
        this.positionLimit=paper?positionLimit.min(BigDecimal.valueOf(5000000)):BigDecimal.valueOf(100000);
        this.positions=paper?positions:1;
        this.lossLimit=paper?expectedLoss:BigDecimal.valueOf(3000);
        this.dayLossLimit=paper?realizedDayLoss:BigDecimal.valueOf(6000);
        if(this.budget.signum()<=0||this.positionLimit.signum()<=0||this.positionLimit.compareTo(this.budget)>0
                ||this.positions<1||this.positions>5||this.budget.compareTo(BigDecimal.valueOf(10000000))>0
                ||this.lossLimit.signum()<=0||this.lossLimit.compareTo(this.budget)>0
                ||this.dayLossLimit.signum()<=0||this.dayLossLimit.compareTo(this.budget)>0)
            throw new IllegalArgumentException("모의 포트폴리오 한도를 확인해 주세요.");
    }
    // Compatibility constructor retains explicitly provided budgets; LIVE limits stay separate.
    public PaperPortfolioPolicy(String environment,BigDecimal budget,BigDecimal positionLimit,int positions){
        this(environment,budget,positionLimit,positions,budget.multiply(new BigDecimal("0.02")).min(BigDecimal.valueOf(50000)),
            budget.multiply(new BigDecimal("0.02")).min(BigDecimal.valueOf(60000)));
    }
    public BigDecimal budget(){return budget;}
    public BigDecimal positionLimit(){return positionLimit;}
    public int maxPositions(){return positions;}
    public BigDecimal lossLimit(){return lossLimit;}
    public boolean expectedLossSizingEnabled(){return !paper;}
    public BigDecimal minEntryCommitment(){return paper?BigDecimal.valueOf(1500000):BigDecimal.ZERO;}
    public BigDecimal normalPositionLimit(){return paper?positionLimit.min(BigDecimal.valueOf(3000000)):positionLimit;}
    public BigDecimal dayLossLimit(){return dayLossLimit;}
    public boolean stagedReinvestment(){return paper;}
    public boolean dipEntry(){return paper;}
    public boolean fallingMarketEntries(){return paper;}
    /** First tranche, with a whole-share allowance to meet the owner-selected minimum. */
    public BigDecimal initialTranche(BigDecimal target,BigDecimal price,BigDecimal commission){
        if(!paper)return target;
        BigDecimal unit=price.multiply(BigDecimal.ONE.add(commission));
        if(unit.signum()<=0)return BigDecimal.ZERO;
        BigDecimal minimumWhole=unit.multiply(minEntryCommitment().divide(unit,0,java.math.RoundingMode.UP));
        return new BigDecimal("2000000").max(minimumWhole).min(target).min(positionLimit);
    }
    public boolean committeePreparation(){return paper&&PAPER_COMMITTEE_PREPARATION;}
    public boolean sellExecutionPaused(){return paper&&PAPER_SELL_EXECUTION_PAUSED;}
    public boolean manualSellPaused(){return paper;}
    /** Net profit divided by allocated confirmed buy principal plus stored buy fees. */
    public BigDecimal takeProfitRate(){return new BigDecimal("0.05");}
    /** PAPER display scenario only; LIVE keeps this rate in sizing. Neither bounds realized loss. */
    public BigDecimal assumedDrawdownRate(){return paper?new BigDecimal("0.08"):new BigDecimal("0.05");}
    /** Freshly requalified PAPER holdings may grow in stages, with the volatility cap preserved. */
    public BigDecimal stagedTarget(int score,BigDecimal atrPercent){
        if(!paper||atrPercent==null)return BigDecimal.ZERO;
        return normalPositionLimit();
    }
    public BigDecimal target(int score){
        if(paper)return normalPositionLimit();
        if(score<65)return BigDecimal.ZERO;
        return positionLimit.multiply(score>=85?BigDecimal.ONE:score>=75?new BigDecimal("0.75"):new BigDecimal("0.5"));
    }
    public View view(){return new View(budget,positionLimit,positions,expectedLossSizingEnabled()?lossLimit:null,dayLossLimit,
        committeePreparation()?"투자위원회 준비 중 · 자동 매수·익절 보류 · 실제 자료 수집과 주문/체결 대조 유지 · 8명 AI 토론·사용자 승인 주문·해외 발행처 연결 전":
        paper?"사용자 선택: 모의 KOSPI 평균 하회 매수 허용 / 실제 연간·최신 분기 연결 이익/영업현금·자본 양수 및 기업 뉴스/공시 제목 점검 필수 / 가격20일 평균 아래1~15%·반등 강제 없음 / 첫 제출150만원 이상·기본 첫 분할200만원(고가주 정수 최소수량 예외) / "+PaperTradingTempoPolicy.at(clock.instant(),paper).description()+" / 추가 회차50만원(고가주 최소1주), 조건을 만족하면 손실 중에도 조건 재검사 후 추가 / 종목 누적 일반300만원·강화500만원 / 전체1,000만원·최대5개 / 비용 후5% 한 번 절반 익절·손실 자동매도/수동 매도 보류·익절 시작 후 추가 금지 / 당일·연속 실현손실 정지와 시세/달력/현금 유지 / 실험 임계값·수익 미검증":
        "실전 별도 소액 한도 · 실전 추가 매수 비활성 · 명시 활성화 및 계좌/근거 관문 필요");}
    public record View(BigDecimal budget,BigDecimal positionLimit,int maxPositions,BigDecimal lossLimit,BigDecimal dayLossLimit,String description){}
}
