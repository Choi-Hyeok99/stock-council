package com.personaltrader.committee;

import com.personaltrader.research.CompanyThesisService;
import java.math.*;
import java.time.*;
import java.util.*;

/** Research ratios from frozen official annuals and verified common shares, not provider EPS/TTM ratios. */
public record CommitteeMultiples(String basis,BigDecimal referenceClose,LocalDate priceDate,Integer year,String fs,
    BigDecimal commonShares,String sharesAsOf,BigDecimal commonEquityMarketValue,
    BigDecimal per,BigDecimal threeYearAveragePer,BigDecimal pbr,BigDecimal psr,BigDecimal earningsYieldPercent,
    BigDecimal revenueGrowthPercent,BigDecimal operatingMarginPercent,List<String> evidenceIds,List<String> limitations){
    private static final MathContext MC=MathContext.DECIMAL128;
    public static CommitteeMultiples from(CommitteeFinancialEvidence f,CompanyThesisService.Company c,
            BigDecimal price,LocalDate date,Instant now){
        var notes=new ArrayList<String>();var ids=new LinkedHashSet<String>();
        var annual=f.periods().stream().filter(p->"11011".equals(p.report())&&p.year()==now.atZone(ZoneId.of("Asia/Seoul")).getYear()-1
            &&"READY".equals(p.state())&&"CFS".equals(p.fs())&&!p.changedSinceFirstFetch()).findFirst().orElse(null);
        var v=c==null?null:c.valuation();var shares=c==null?null:c.shares();
        BigDecimal common=v==null?null:v.commonCirculating();
        boolean shareDateValid=false;
        try{var asOf=LocalDate.parse(v.sharesAsOf().replace('.', '-'));var today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            shareDateValid=!asOf.isAfter(today)&&!asOf.isBefore(today.minusDays(550));}catch(Exception unavailable){/* Unknown/future share basis never becomes current shares. */}
        boolean sharesReady=shareDateValid&&common!=null&&common.signum()>0&&shares!=null&&"READY".equals(shares.state())&&shares.fetchedAt()!=null
            &&!shares.fetchedAt().isAfter(now)&&Duration.between(shares.fetchedAt(),now).compareTo(Duration.ofHours(26))<=0;
        if(!sharesReady){common=null;notes.add(v==null?"보통주 수 검증 대기":v.shareNote());if(!shareDateValid)notes.add("주식 수 공시 기준일 확인 대기 또는 550일 초과");}
        else ids.add("shares:"+shares.id());
        BigDecimal market=common!=null&&price!=null&&price.signum()>0&&date!=null&&!date.isAfter(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())?price.multiply(common,MC):null;
        BigDecimal per=null,pbr=null,psr=null,avgPer=null,yield=null,growth=null,margin=null;
        if(annual!=null&&f.annualEvidenceReady()){
            ids.add(annual.sourceId());var values=annual.values();
            BigDecimal owner=values.get("OWNER_PROFIT"),equity=values.get("OWNER_EQUITY"),sales=values.get("REVENUE"),op=values.get("OPERATING_PROFIT");
            if(market!=null){per=positiveRatio(market,owner);pbr=positiveRatio(market,equity);
                if(!f.financialSectorReview())psr=positiveRatio(market,sales);
                yield=owner==null?null:scaled(owner.divide(market,MC).multiply(new BigDecimal("100")));}
            margin=positiveDenominatorPercent(op,sales);
            var previous=f.periods().stream().filter(p->p.year()==annual.year()-1&&"11011".equals(p.report())&&"READY".equals(p.state())
                &&annual.fs().equals(p.fs())&&!p.changedSinceFirstFetch()).findFirst().orElse(null);
            if(previous!=null){growth=positiveDenominatorPercent(sales==null||previous.values().get("REVENUE")==null?null:sales.subtract(previous.values().get("REVENUE")),previous.values().get("REVENUE"));ids.add(previous.sourceId());}
            BigDecimal total=BigDecimal.ZERO;boolean all=true;
            for(int y=annual.year()-2;y<=annual.year();y++){
                final int year=y;var p=f.periods().stream().filter(x->x.year()==year&&"11011".equals(x.report())&&"READY".equals(x.state())
                    &&annual.fs().equals(x.fs())&&!x.changedSinceFirstFetch()).findFirst().orElse(null);
                if(p==null||p.values().get("OWNER_PROFIT")==null){all=false;break;}
                total=total.add(p.values().get("OWNER_PROFIT"));ids.add(p.sourceId());
            }
            if(all&&market!=null)avgPer=positiveRatio(market,total.divide(new BigDecimal("3"),MC));
            if(owner==null||owner.signum()<=0)notes.add("지배주주 순이익 누락·적자는 PER을 계산하지 않음");
        }else notes.add("최근 연결 연간 재무 확인 전에는 배수 계산 보류 · 별도/연결 혼합 금지");
        if(f.financialSectorReview())notes.add("금융업 PSR 미제시 · 자본·건전성·이익의 질 별도 검토");
        notes.add("현재 기준 종가×공시 보통주 유통주식 수 / 직전 연간 연결 지배주주 이익·자본(PSR은 연결 매출). 가중평균 EPS/TTM/예상 PER과 다름");
        notes.add("3년 평균 PER은 같은 현재 시가액/3년 평균 지배주주이익이며 과거 주가 PER이 아님. 적자 연도도 평균에 포함, 평균이 0 이하이면 미계산");
        notes.add("공시 이후 증자·소각·분할 미반영 가능. 우선주 등 종류별 청구권 검증이 안 된 기업은 배수 미계산. 일회성 이익 조정 전이며 PSR은 수익성·비지배지분 영향을 함께 검토");
        notes.add("동종 기업·과거 주가 배수 비교 미연결 · 업종 평균/저평가 확정 수치 생성 금지. 낮은 배수는 매수 근거 후보이지 수익 보장이 아님");
        return new CommitteeMultiples("직전 연간 연결 실적 기준 참고 배수 · KRW · 시가액은 자기주식 제외 보통주 기준",price,date,
            annual==null?null:annual.year(),annual==null?null:annual.fs(),common,v==null?null:v.sharesAsOf(),market,
            per,avgPer,pbr,psr,yield,growth,margin,ids.stream().filter(Objects::nonNull).toList(),List.copyOf(notes));
    }
    private static BigDecimal positiveRatio(BigDecimal a,BigDecimal b){return a==null||a.signum()<=0||b==null||b.signum()<=0?null:scaled(a.divide(b,MC));}
    private static BigDecimal positiveDenominatorPercent(BigDecimal a,BigDecimal b){return a==null||b==null||b.signum()<=0?null:scaled(a.divide(b,MC).multiply(new BigDecimal("100")));}
    private static BigDecimal scaled(BigDecimal v){return v.setScale(2,RoundingMode.HALF_UP);}
}
