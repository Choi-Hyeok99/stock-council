package com.personaltrader.research;

import com.personaltrader.marketdata.MarketHistory;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;

/** An explicit experiment policy. Scores and entry checks are not calibrated probabilities. */
@Component
public class InvestmentSignalEngine {
    public static final String VERSION="CHART_EVENT_RESEARCH_V5";
    private final PublicInformationService information;
    private final PaperPortfolioPolicy policy;
    private final Clock clock;
    public InvestmentSignalEngine(PublicInformationService information,PaperPortfolioPolicy policy,Clock clock){
        this.information=information;this.policy=policy;this.clock=clock;
    }
    public Signal evaluate(String code,String name,String market,MarketHistory history,MarketHistory benchmark){
        List<MarketHistory.CompletedBar> bars=history.bars();int size=bars.size();
        List<PublicInformationService.Evidence> evidence=information.evidence(code);
        double close=history.lastClose().doubleValue(),ma5=history.average5().doubleValue(),ma20=history.average20().doubleValue();
        double ret5=rounded(percent(close,bars.get(Math.max(0,size-6)).close().doubleValue()));
        // Keep the actual number of intervals explicit; a 20-bar fallback is 19 returns.
        int returnIntervals=Math.min(20,size-1);
        double ret20=rounded(percent(close,bars.get(size-1-returnIntervals).close().doubleValue()));
        boolean benchmarkValid=benchmark!=null&&benchmark.basisDate().equals(history.basisDate())
            &&benchmark.bars()!=null&&benchmark.bars().size()>returnIntervals
            &&benchmark.bars().get(benchmark.bars().size()-1-returnIntervals).date().equals(bars.get(size-1-returnIntervals).date());
        Double market20=benchmarkValid?rounded(percent(benchmark.lastClose().doubleValue(),
            benchmark.bars().get(benchmark.bars().size()-1-returnIntervals).close().doubleValue())):null;
        Double relative=market20==null?null:rounded(ret20-market20);
        double atr=0,gain=0,loss=0;
        for(int i=Math.max(1,size-14);i<size;i++){
            var bar=bars.get(i);double previous=bars.get(i-1).close().doubleValue();
            atr+=Math.max(bar.high().subtract(bar.low()).doubleValue(),Math.max(Math.abs(bar.high().doubleValue()-previous),Math.abs(bar.low().doubleValue()-previous)));
            double delta=bar.close().doubleValue()-previous;gain+=Math.max(delta,0);loss+=Math.max(-delta,0);
        }
        double atrPct=rounded(atr/14/close*100),rsi=rounded(gain==0&&loss==0?50:loss==0?100:100-100/(1+gain/loss));
        double extension=rounded(percent(close,ma20));
        int volumeWindow=Math.min(20,size-1);
        BigDecimal priorVolume=bars.subList(size-1-volumeWindow,size-1).stream().map(MarketHistory.CompletedBar::volume)
            .reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(volumeWindow),8,RoundingMode.HALF_UP);
        BigDecimal volumeRatio=priorVolume.signum()>0?bars.getLast().volume().divide(priorVolume,2,RoundingMode.HALF_UP):null;
        boolean trend=close>ma20&&ma5>ma20,rebound=!trend&&close>ma5&&ret5>0;
        List<String> reasons=new ArrayList<>(),risks=new ArrayList<>();
        List<ScoreComponent> breakdown=new ArrayList<>();
        breakdown.add(new ScoreComponent("BASE","기본 점수",20,"초기값 20점","미검증 실험용 기본값 · 시장 관측으로 얻은 점수 아님"));
        add(breakdown,"MA20","종가와 20일 평균",close>ma20?20:0,money(history.lastClose())+" / "+money(history.average20()),"종가 > 20일 평균이면 +20점");
        if(close>ma20)reasons.add("확정 종가 "+money(history.lastClose())+" > 20일 평균 "+money(history.average20()));
        else risks.add("확정 종가가 20일 평균 아래 · 추세 전환 확인 전");
        add(breakdown,"MA5","5일·20일 평균 배열",ma5>ma20?15:rebound?12:0,money(history.average5())+" / "+money(history.average20()),"5일 평균 > 20일 평균 +15점 · 반등 관찰만 해당하면 +12점");
        if(ma5>ma20)reasons.add("5일 평균 "+money(history.average5())+" > 20일 평균 "+money(history.average20()));
        else if(rebound)reasons.add("5일 평균 회복과 최근 5일 상승 · 반등 관찰, 자동 진입은 대기");
        add(breakdown,"RELATIVE","KOSPI 대비 상대 강도",relative!=null&&relative>0?12:0,relative==null?"기준 지수 미확인":number(relative)+"%p", "같은 기준일/기간의 주식 수익률 − KOSPI 수익률 > 0이면 +12점");
        if(relative!=null&&relative>0)reasons.add(returnIntervals+"거래일 주식 "+number(ret20)+"% − KOSPI "+number(market20)+"% = 상대 강도 "+number(relative)+"%p");
        else risks.add(relative==null?"같은 기간 KOSPI 기준 자료 확인 필요":"KOSPI 대비 상대 강도 "+number(relative)+"%p · "+(policy.fallingMarketEntries()?"상대 약세 관찰, 재무 검토와 분할 조건 확인":"신규 진입 보류"));
        add(breakdown,"MOMENTUM","최근 5거래일 변화",ret5>0&&ret5<10?8:0,number(ret5)+"%","0% 초과 10% 미만이면 +8점");
        if(ret5>0&&ret5<10)reasons.add("최근 5거래일 "+number(ret5)+"% 상승");
        else risks.add("최근 5거래일 변화 "+number(ret5)+"% · 상승/과열 조건 재확인");
        boolean liquid=history.averageTurnover20().compareTo(BigDecimal.valueOf(1000000000))>=0;
        add(breakdown,"LIQUIDITY","20일 평균 거래대금",liquid?10:0,money(history.averageTurnover20()),"10억원 이상 +10점 · 주문 유동성이며 상승 근거와 구분");
        if(liquid)reasons.add("20일 평균 거래대금 "+money(history.averageTurnover20())+" · 유동성 조건 충족");
        List<NewsItem> classified=evidence.stream().map(InvestmentSignalEngine::classify).toList();
        boolean headlineRisk=classified.stream().anyMatch(n->n.classification().equals("RISK_TITLE"));
        boolean correction=evidence.stream().anyMatch(e->contains(e.title().replace(" ",""),"정정","철회"));
        long catalystCount=classified.stream().filter(n->n.classification().equals("TITLE_CATALYST")).count();
        long routineCount=classified.stream().filter(n->n.classification().equals("ROUTINE_DISCLOSURE")).count();
        boolean catalyst=catalystCount>0&&!headlineRisk&&!correction;
        add(breakdown,"NEWS","촉매 가능성 제목",catalyst?10:0,catalystCount+"건 · 일반 공시 "+routineCount+"건","명확한 기업 연결/촉매 키워드, 위험·정정 없음 +10점 · 본문 효과 미검증");
        if(catalyst)reasons.add("촉매 가능성 제목 "+catalystCount+"건 · 본문/규모/가격 반영 확인 전");
        else risks.add("뉴스 호재 가점 0점 · 가격 근거만으로 판단하는 경로");
        if(evidence.isEmpty())risks.add("최근 3일 명확히 연결된 뉴스/공시 없음 · 위험이 없다는 뜻 아님");
        else risks.add("제목 기준 자료 "+evidence.size()+"건 · 본문/재무 영향/이미 반영된 가격 미검증");
        add(breakdown,"NEWS_RISK","위험 제목",headlineRisk?-25:0,headlineRisk?"발견":"수집된 제목에서 미발견","발견하면 -25점 및 진입 보류 · 미발견은 위험 없음의 증거가 아님");
        if(headlineRisk)risks.add("위험 사건 키워드가 있는 제목 · 신규 진입 보류");
        add(breakdown,"CORRECTION","정정·철회",correction?-10:0,correction?"발견":"미발견","발견하면 -10점 및 진입 보류");
        if(correction)risks.add("정정/철회 자료 포함 · 기존 호재 판단 재확인 필요");
        boolean overheated=rsi>=75||extension>=15;
        add(breakdown,"HEAT","과열",overheated?-15:0,"단순 RSI "+number(rsi)+" / 이격 "+number(extension)+"%","RSI ≥75 또는 종가/20일 평균 이격 ≥15%이면 -15점 및 진입 보류");
        if(overheated)risks.add("RSI/20일 평균 이격 과열 · 신규 진입 보류");
        add(breakdown,"VOLATILITY","평균 변동 폭",atrPct>=5?-12:0,number(atrPct)+"%",policy.fallingMarketEntries()?"단순 평균 TR/종가 ≥5%이면 -12점 · 재무 점검 후 분할 매수, 점수는 순위 참고":"단순 평균 TR/종가 ≥5%이면 -12점, 배정 예산 절반");
        if(atrPct>=5)risks.add("최근 14일 평균 변동 폭 "+number(atrPct)+"% · "+(policy.fallingMarketEntries()?"고변동 주의 · 재무 점검 후 분할 매수":"배정 예산 절반"));
        boolean healthy=information.healthy();
        if(!healthy)risks.add("뉴스/공시 핵심 피드 수집 상태 미확인 · 신규 진입 보류");
        int rawScore=breakdown.stream().mapToInt(ScoreComponent::points).sum(),score=Math.max(0,Math.min(100,rawScore));
        BigDecimal proposedTarget=policy.target(score);
        if(atrPct>=5&&!policy.fallingMarketEntries())proposedTarget=proposedTarget.multiply(new BigDecimal("0.5"));
        List<NumericCheck> checks=policy.fallingMarketEntries()?List.of(
            check("LIQUIDITY","주문 유동성",money(history.averageTurnover20()),"20일 평균 거래대금 10억원 이상",liquid),
            check("BENCHMARK","동기간 지수 자료",relative==null?"동일 기준 자료 없음":number(relative)+"%p","같은 기준일·기간 지수 존재 · 상대 약세도 허용",benchmarkValid),
            check("DECLINE","급격한 하락 재검토",number(ret5)+"%","확정 5거래일 하락15% 이내 · 상승 전환은 필수 아님",ret5>=-15),
            check("HEAT","과열 확인","RSI "+number(rsi)+" / 이격 "+number(extension)+"%","RSI <75 및 이격 <15%",!overheated),
            check("NEWS","뉴스·공시 피드",headlineRisk?"위험 제목":correction?"정정/철회":"제목 위험/정정 미발견","핵심 피드 정상·위험/정정 없음 · 기업별 재무/제목 점검은 별도 필수",healthy&&!headlineRisk&&!correction),
            check("ENTRY_BUDGET","종목 누적 배정",money(proposedTarget),"일반300만원·첫 제출150만원 이상·분할 실행",proposedTarget.compareTo(policy.minEntryCommitment())>=0)) : List.of(
            check("TREND","중기 추세",money(history.lastClose())+" / "+money(history.average20()),"종가 > 20일 평균",close>ma20),
            check("ARRAY","평균선 배열",money(history.average5())+" / "+money(history.average20()),"5일 평균 > 20일 평균",ma5>ma20),
            check("SHORT","단기 가격 위치",money(history.lastClose())+" / "+money(history.average5()),"종가 > 5일 평균",close>ma5),
            check("MOMENTUM","최근 상승 확인",number(ret5)+"%","5거래일 변화 >0%",ret5>0),
            check("RELATIVE","시장 대비 강세",relative==null?"지수 기준 미확인":number(relative)+"%p","같은 기간 상대 강도 >0%p",relative!=null&&relative>0),
            check("SCORE","연구 점수",score+"/100","65점 이상 · 확률 아님",score>=65),
            check("HEAT","과열 확인","단순 RSI "+number(rsi)+" / 이격 "+number(extension)+"%","RSI <75 및 이격 <15%",!overheated),
            check("NEWS","기사·공시 검토",headlineRisk?"위험 제목":correction?"정정/철회":"확인한 제목에서 위험/정정 미발견","핵심 피드 정상, 위험·정정 없음",healthy&&!headlineRisk&&!correction),
            check("ENTRY_BUDGET","최초 매수 배정",money(proposedTarget),"최소 배정 "+money(policy.minEntryCommitment())+" · 정수 수량/매수 비용은 주문 직전 재검사",proposedTarget.compareTo(policy.minEntryCommitment())>=0));
        boolean eligible=checks.stream().allMatch(NumericCheck::pass);
        String state=headlineRisk?"DOWNSIDE_RISK":policy.fallingMarketEntries()&&eligible?"FALLING_BUY_WATCH":trend&&score>=65?"UPTREND_WATCH":rebound?"REBOUND_WATCH":"WAIT";
        String label=switch(state){case "DOWNSIDE_RISK"->"하락 위험";case "FALLING_BUY_WATCH"->"하락 분할 매수 검토";case "UPTREND_WATCH"->"상승 흐름 관찰";case "REBOUND_WATCH"->"반등 관찰";default->"확인 대기";};
        BigDecimal target=eligible?proposedTarget:BigDecimal.ZERO;
        String newsState=!healthy?"FEED_UNAVAILABLE":headlineRisk?"RISK_TITLE":correction?"CORRECTION":catalyst?"TITLE_CATALYST":evidence.isEmpty()?"NO_LINKED_NEWS":"NO_CATALYST";
        NewsAssessment news=new NewsAssessment(newsState,catalyst?10:0,evidence.size(),(int)catalystCount,(int)routineCount,classified,
            "발행처 제목과 명확한 기업 연결만 확인 · 본문/공시 금액/재무 영향은 미검증");
        String path=policy.fallingMarketEntries()?"FALLING_RESEARCH_PRECHECK":catalyst?"CHART_WITH_TITLE_CATALYST":"CHART_LED";
        String summary=eligible?(policy.fallingMarketEntries()?"하락 매수 기초 점검 통과 · 현재 실제 재무·기업 기사 점검이 필요":catalyst?"가격·상대 강도·모멘텀 조건 충족, 촉매 가능성 제목 보조":"호재 가점 없이 가격·상대 강도·모멘텀 조건으로 검토")
            :"신규 진입 보류: "+String.join(" · ",checks.stream().filter(c->!c.pass()).map(NumericCheck::label).toList());
        ResearchExplanation explanation=new ResearchExplanation(VERSION,path,summary,rawScore,breakdown,checks,news,
            decimal(ret20),market20==null?null:decimal(market20),returnIntervals,volumeRatio,volumeWindow,decimal(extension),
            List.of("점수 가중치·절단값·배정 비율은 미검증 실험 정책", "RSI/ATR은 단순 14구간 평균 구현 · Wilder 방식과 구분", "거래량 배수는 마지막 확정일/직전 평균이며 장중 거래량이 아님", "호재가 없어도 모든 가격/위험 관문을 통과한 PAPER 진입은 가능", "수익률·승률·목표주가를 예측한 설명이 아님"));
        List<ChartPoint> chart=bars.stream().skip(Math.max(0,size-30)).map(b->new ChartPoint(b.date(),b.close())).toList();
        String invalidation=policy.dipEntry()?"확정 20일 평균 "+money(history.average20())+" 아래1~15%·실제 재무/제목 점검·분할 매수를 재확인 · 손실 자동매도 보류 · 실제 매수 원금+매수 비용 대비 비용 후 순이익5%에서 한 번 절반 익절":"확정 20일 평균 "+money(history.average20())+" 하회, 매입가 -5% 또는 새 위험 입력 시 재검토 · 실제 청산은 기존 보유 감시 정책";
        return new Signal(code,name,market,state,label,score,eligible,target,history.basisDate(),history.receivedAt(),history.lastClose(),history.average5(),history.average20(),
            decimal(ret5),relative==null?null:decimal(relative),decimal(rsi),decimal(atrPct),List.copyOf(reasons),List.copyOf(risks),invalidation,evidence,chart,
            "근거 우선순위 점수 / 100 · 상승 확률 아님 · 실제 주문에는 당일 시세/계좌/수량 관문 필요",explanation);
    }
    static NewsItem classify(PublicInformationService.Evidence item){
        String title=item.title().replace(" ","");String kind,label;
        if(contains(title,"횡령","배임","상장폐지","거래정지","회생절차","감사의견","적자","실적악화","급락","계약해지","유상증자","전환사채")){kind="RISK_TITLE";label="위험 키워드 · 실제 내용 재확인 필요";}
        else if(contains(title,"정정","철회")){kind="CORRECTION";label="정정/철회 · 기존 판단 재확인";}
        else if(contains(title,"기업설명회","일괄신고","증권신고서","발행실적보고","파생결합사채","파생결합증권","임원ㆍ주요주주","대량보유상황")){kind="ROUTINE_DISCLOSURE";label="일반 공시/상품 신고 · 실적 호재 가점 없음";}
        else if(contains(title,"부인","루머","미확정","검토","추진","전망","가능성")){kind="UNVERIFIED_TITLE";label="예상/검토/미확정 제목 · 촉매 가점 없음";}
        else if(contains(title,"수주","계약체결","흑자","실적개선","자사주취득","배당","상향","영업이익증가")){kind="TITLE_CATALYST";label="촉매 가능성 키워드 · 본문/규모/가격 반영 미검증";}
        else{kind="GENERAL_TITLE";label="관련 제목 · 촉매 가점 없음";}
        return new NewsItem(item,kind,label);
    }
    private static void add(List<ScoreComponent> parts,String key,String label,int points,String observed,String rule){parts.add(new ScoreComponent(key,label,points,observed,rule));}
    public static NumericCheck check(String key,String label,String observed,String threshold,boolean pass){return new NumericCheck(key,label,observed,threshold,pass);}
    private static boolean contains(String title,String...words){return Arrays.stream(words).anyMatch(title::contains);}
    private static double rounded(double value){return decimal(value).doubleValue();}
    private static double percent(double a,double b){return (a/b-1)*100;}
    public static String number(double value){return String.format(Locale.KOREA,"%.2f",value);}
    public static String money(BigDecimal value){return value==null?"미확인":String.format(Locale.KOREA,"%,.2f원",value);}
    private static BigDecimal decimal(double value){return BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP);}
    public record ChartPoint(LocalDate date,BigDecimal close){}
    public record ScoreComponent(String key,String label,int points,String observed,String rule){}
    public record NumericCheck(String key,String label,String observed,String threshold,boolean pass){}
    public record NewsItem(PublicInformationService.Evidence evidence,String classification,String explanation){}
    public record NewsAssessment(String state,int scoreContribution,int linkedCount,int catalystCount,int routineCount,List<NewsItem>items,String scope){}
    public record ResearchExplanation(String policyVersion,String path,String summary,int rawScore,List<ScoreComponent>scoreBreakdown,List<NumericCheck>entryChecks,NewsAssessment news,
        BigDecimal stockReturn,BigDecimal benchmarkReturn,int returnIntervals,BigDecimal volumeRatio,int volumeWindow,BigDecimal extensionPercent,List<String>limitations){}
    public record Signal(String code,String name,String market,String state,String label,int score,boolean paperEligible,BigDecimal targetBudget,LocalDate basisDate,Instant chartReceivedAt,
        BigDecimal lastClose,BigDecimal average5,BigDecimal average20,BigDecimal change5,BigDecimal relativeStrength,BigDecimal rsi14,BigDecimal atrPercent,List<String>reasons,List<String>risks,
        String invalidation,List<PublicInformationService.Evidence>evidence,List<ChartPoint>chart,String scope,ResearchExplanation explanation){}
}
