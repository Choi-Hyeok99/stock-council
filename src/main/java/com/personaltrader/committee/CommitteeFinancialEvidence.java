package com.personaltrader.committee;

import com.personaltrader.research.DartFinancialService;
import com.personaltrader.research.PublicInformationService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Frozen official values, including negatives. Missing inputs never become zero. */
public record CommitteeFinancialEvidence(boolean annualEvidenceReady,boolean financialSectorReview,
    String currency,List<PeriodFacts> periods,List<String> missing) {
    private static final List<String> KEYS=List.of("REVENUE","OPERATING_PROFIT","NET_PROFIT","OPERATING_CASH_FLOW",
        "EQUITY","OWNER_PROFIT","OWNER_EQUITY","LIABILITIES","CASH","PPE_PURCHASE","INTANGIBLE_PURCHASE");
    public static CommitteeFinancialEvidence from(DartFinancialService.Company c,Instant now){
        if(c==null)return new CommitteeFinancialEvidence(false,false,"KRW",List.of(),List.of("이 위원회 후보의 재무 수집 대기"));
        // Latest interim must not disappear when the historical annual list reaches the input cap.
        var periods=new ArrayList<DartFinancialService.Period>();
        if(c.latestInterim()!=null)periods.add(c.latestInterim());
        c.annuals().stream().sorted(Comparator.comparingInt(DartFinancialService.Period::year).reversed()).forEach(periods::add);
        var facts=new ArrayList<PeriodFacts>();
        for(var p:periods.stream().limit(6).toList()){
            Map<String,BigDecimal> values=new LinkedHashMap<>();Map<String,String> unavailable=new LinkedHashMap<>();
            for(String key:KEYS){var m=p.metrics().stream().filter(x->key.equals(x.key())).findFirst().orElse(null);
                if(m!=null&&"AVAILABLE".equals(m.state())&&m.value()!=null&&"KRW".equals(m.currency()))values.put(key,m.value());
                else unavailable.put(key,m==null?"MISSING":!"AVAILABLE".equals(m.state())?m.state():
                    !"KRW".equals(m.currency())?"CURRENCY_UNVERIFIED":"VALUE_ABSENT");}
            facts.add(new PeriodFacts(p.year(),p.report(),p.label(),p.fs(),p.state(),
                p.sourceUrl()==null?null:"financial:"+PublicInformationService.hash(p.sourceUrl()),p.fetchedAt(),p.disclosureDate(),
                p.changedSinceFirstFetch(),Collections.unmodifiableMap(values),Collections.unmodifiableMap(unavailable)));
        }
        int year=now.atZone(ZoneId.of("Asia/Seoul")).getYear()-1;
        var annual=facts.stream().filter(p->p.year()==year&&"11011".equals(p.report())).findFirst().orElse(null);
        boolean ready=annual!=null&&"READY".equals(annual.state())&&annual.sourceId()!=null&&annual.fetchedAt()!=null
            &&!annual.fetchedAt().isAfter(now)&&Duration.between(annual.fetchedAt(),now).compareTo(Duration.ofHours(26))<=0
            &&annual.disclosureDate()!=null&&!annual.disclosureDate().isAfter(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
            &&!annual.changedSinceFirstFetch()&&annual.values().containsKey("NET_PROFIT")&&annual.values().containsKey("EQUITY")
            &&(c.financialSectorReview()||annual.values().containsKey("OPERATING_CASH_FLOW"));
        var missing=new ArrayList<String>();
        if(!ready)missing.add("최근 연간 공식 재무 입력 확인 대기");
        if(c.financialSectorReview())missing.add("금융업은 영업현금을 제조업과 같은 기준으로 해석하지 않으며 업종별 건전성 확인 필요");
        missing.add("과거 현금흐름은 관측 사실이며 미래 현금 창출·사업 우위를 보장하지 않음");
        return new CommitteeFinancialEvidence(ready,c.financialSectorReview(),"KRW",List.copyOf(facts),List.copyOf(missing));
    }
    public record PeriodFacts(int year,String report,String label,String fs,String state,String sourceId,Instant fetchedAt,
        LocalDate disclosureDate,boolean changedSinceFirstFetch,Map<String,BigDecimal> values,Map<String,String> unavailable){}
}
