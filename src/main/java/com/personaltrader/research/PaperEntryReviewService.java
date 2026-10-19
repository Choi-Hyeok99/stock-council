package com.personaltrader.research;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.personaltrader.research.InvestmentSignalEngine.*;

/** Actual financial/title checks for the owner-selected PAPER falling-market experiment. */
@Service
public class PaperEntryReviewService {
    public static final String VERSION="PAPER_FALLING_FINANCIAL_NEWS_V1";
    private final DartFinancialService financials;private final PaperPortfolioPolicy policy;private final Clock clock;
    public PaperEntryReviewService(DartFinancialService financials,PaperPortfolioPolicy policy,Clock clock){this.financials=financials;this.policy=policy;this.clock=clock;}
    public Review evaluate(Signal s){
        if(s==null)return new Review(VERSION,false,null,null,List.of(),List.of());
        if(!policy.fallingMarketEntries())return new Review(VERSION,s.paperEligible(),null,null,List.of(),List.of());
        var f=financials.view();
        var company=f.current()&&f.issue()==null&&f.report()!=null?f.report().companies().stream().filter(c->c.code().equals(s.code())).findFirst().orElse(null):null;
        int year=clock.instant().atZone(ZoneId.of("Asia/Seoul")).getYear();
        List<NumericCheck> checks=new ArrayList<>();List<String> sources=new ArrayList<>();
        checks.add(check("FIN_CURRENT","현재 기업 재무",company==null?"최신 재무 확인 대기":"재무 기록 #"+f.id(),"현재 계좌 수집 정상·비금융 기업",company!=null&&!company.financialSectorReview()));
        var annual=company==null?null:company.annuals().stream().filter(p->p.year()==year-1).findFirst().orElse(null);
        var interim=company==null?null:company.latestInterim();
        for(var named:List.of(new PeriodInput("ANNUAL","최근 연간",annual,year-1),new PeriodInput("INTERIM","최신 분기 누적",interim,year))){
            var p=named.period();Instant now=clock.instant();
            boolean usable=p!=null&&p.year()==named.year()&&"READY".equals(p.state())&&"CFS".equals(p.fs())&&!p.changedSinceFirstFetch()
                &&p.fetchedAt()!=null&&!p.fetchedAt().isAfter(now)&&Duration.between(p.fetchedAt(),now).toHours()<24
                &&p.disclosureDate()!=null&&!p.disclosureDate().isAfter(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
            checks.add(check(named.key(),named.label()+" 원본",p==null?"자료 없음":p.label()+" / "+p.fs()+" / 조회 "+p.fetchedAt(),"당해 기준 연결 재무·24시간 내 조회·정정 검토 없음",usable));
            for(String key:List.of("REVENUE","OPERATING_PROFIT","NET_PROFIT","OPERATING_CASH_FLOW","EQUITY")){
                var m=p==null?null:p.metrics().stream().filter(x->x.key().equals(key)).findFirst().orElse(null);
                checks.add(check(named.key()+"_"+key,named.label()+" "+(m==null?key:m.label()),m==null?"계정 없음":money(m.value()),"단일 KRW 계정의 실제 금액 >0",usable&&m!=null&&"AVAILABLE".equals(m.state())&&"KRW".equals(m.currency())&&m.value()!=null&&m.value().signum()>0));
            }
            if(p!=null&&p.sourceUrl()!=null)sources.add(p.sourceUrl());
        }
        var news=s.explanation()==null?null:s.explanation().news();
        boolean titles=news!=null&&news.linkedCount()>0&&!Set.of("FEED_UNAVAILABLE","RISK_TITLE","CORRECTION").contains(news.state());
        checks.add(check("NEWS_REVIEW","기업 뉴스·공시 제목",news==null?"자료 없음":news.state()+" / "+news.linkedCount()+"건","기업 연결 자료 존재·핵심 피드 정상·위험/정정 없음 · 본문 영향은 미검증",titles));
        return new Review(VERSION,checks.stream().allMatch(NumericCheck::pass),company==null?null:f.id(),company==null?null:f.generatedAt(),List.copyOf(checks),List.copyOf(sources));
    }
    /** Use the same review and public explanation in ideas, plans, quotes and order-time checks. */
    public Signal qualify(Signal s){
        if(s==null||!policy.fallingMarketEntries())return s;
        var r=evaluate(s);var e=s.explanation();if(e==null)return s;
        var reviewKeys=r.checks().stream().map(NumericCheck::key).collect(java.util.stream.Collectors.toSet());
        List<NumericCheck> checks=new ArrayList<>(e.entryChecks().stream().filter(c->!reviewKeys.contains(c.key())).toList());
        boolean baseline=checks.stream().allMatch(NumericCheck::pass);checks.addAll(r.checks());
        boolean pass=baseline&&r.ready();
        String summary=pass?"하락 매수 예비 점검 통과 · 실제 연간/분기 이익·현금 양수와 제목 점검 · 현재 가격·계좌·분할 관문은 주문 직전 확인":
            "매수 대기: "+String.join(" / ",checks.stream().filter(c->!c.pass()).map(NumericCheck::label).toList());
        var explanation=new ResearchExplanation(e.policyVersion(),"FALLING_FINANCIAL_NEWS",summary,e.rawScore(),e.scoreBreakdown(),List.copyOf(checks),e.news(),e.stockReturn(),e.benchmarkReturn(),e.returnIntervals(),e.volumeRatio(),e.volumeWindow(),e.extensionPercent(),e.limitations());
        List<String> reasons=new ArrayList<>(s.reasons().stream().filter(x->!x.startsWith("실제 최근 연간·최신 분기 연결")).toList());
        if(pass)reasons.add(0,"실제 최근 연간·최신 분기 연결 이익/영업현금/자본 양수와 기업 제목 점검 통과 · 재무 기록 #"+r.financialId());
        return new Signal(s.code(),s.name(),s.market(),s.state(),s.label(),s.score(),pass,pass?policy.target(s.score()):BigDecimal.ZERO,s.basisDate(),s.chartReceivedAt(),s.lastClose(),s.average5(),s.average20(),s.change5(),s.relativeStrength(),s.rsi14(),s.atrPercent(),List.copyOf(reasons),s.risks(),s.invalidation(),s.evidence(),s.chart(),s.scope(),explanation);
    }
    private record PeriodInput(String key,String label,DartFinancialService.Period period,int year){}
    public record Review(String version,boolean ready,Long financialId,Instant financialAt,List<NumericCheck> checks,List<String> sources){}
}
