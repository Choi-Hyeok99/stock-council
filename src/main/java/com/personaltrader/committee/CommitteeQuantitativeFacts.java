package com.personaltrader.committee;

import java.math.*;
import java.util.*;

/** Deterministic derived facts. No forecasts; absent/non-comparable inputs remain unavailable. */
public final class CommitteeQuantitativeFacts {
    public record Fact(String key,String label,BigDecimal value,String unit,String period,String fs,
                       String formula,List<String> evidenceIds,String limitation){}
    public static List<Fact> from(CommitteeFinancialEvidence evidence){
        if(evidence==null)return List.of();
        var annuals=evidence.periods().stream().filter(p->"11011".equals(p.report())&&"READY".equals(p.state())&&p.sourceId()!=null)
            .sorted(Comparator.comparingInt(CommitteeFinancialEvidence.PeriodFacts::year).reversed()).toList();
        if(annuals.isEmpty())return List.of();
        var p=annuals.getFirst();var v=p.values();var facts=new ArrayList<Fact>();
        String sector=evidence.financialSectorReview()?"금융업은 업종 건전성·자본 규제 지표와 별도 비교 필요":"동종업계 비교 미연결 · 높고 낮음만으로 투자 결론을 내리지 않음";
        ratio(facts,p,"OPERATING_MARGIN","영업이익률",v.get("OPERATING_PROFIT"),v.get("REVENUE"),"영업이익 ÷ 매출 × 100",sector);
        ratio(facts,p,"DEBT_RATIO","부채비율",v.get("LIABILITIES"),v.get("EQUITY"),"부채총계 ÷ 자본총계 × 100",sector);
        ratio(facts,p,"OCF_MARGIN","영업현금흐름률",v.get("OPERATING_CASH_FLOW"),v.get("REVENUE"),"영업현금흐름 ÷ 매출 × 100",sector);
        ratio(facts,p,"CASH_CONVERSION","이익 대비 영업현금",v.get("OPERATING_CASH_FLOW"),v.get("NET_PROFIT"),"영업현금흐름 ÷ 당기순이익 × 100","순이익이 양수일 때만 계산 · 일회성·운전자본 변화 별도 확인");
        BigDecimal ocf=v.get("OPERATING_CASH_FLOW"),ppe=v.get("PPE_PURCHASE"),intangible=v.get("INTANGIBLE_PURCHASE");
        facts.add(new Fact("AFTER_ASSET_PURCHASE","유·무형자산 취득 후 현금",ocf!=null&&ppe!=null&&intangible!=null?ocf.subtract(ppe.abs()).subtract(intangible.abs()):null,"KRW",p.label(),p.fs(),
            "영업현금흐름 − |유형자산 취득| − |무형자산 취득|",List.of(p.sourceId()),"세 항목이 모두 있을 때만 계산 · 전체 투자현금·확정 FCF가 아님"));
        var prior=annuals.stream().filter(x->x.year()==p.year()-1&&Objects.equals(x.fs(),p.fs())).findFirst().orElse(null);
        BigDecimal current=v.get("REVENUE"),previous=prior==null?null:prior.values().get("REVENUE");
        BigDecimal growth=current!=null&&previous!=null&&previous.signum()>0?current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous,4,RoundingMode.HALF_UP):null;
        facts.add(new Fact("REVENUE_YOY","연간 매출 성장률",growth,"%",p.label(),p.fs(),"(당해 매출 − 전년 매출) ÷ 전년 매출 × 100",
            prior==null?List.of(p.sourceId()):List.of(p.sourceId(),prior.sourceId()),"연속 연도·동일 연결/별도 기준만 비교 · 재작성/회계정책 변경 별도 확인"));
        return List.copyOf(facts);
    }
    public static List<Fact> interim(CommitteeFinancialEvidence evidence){
        if(evidence==null)return List.of();
        var p=evidence.periods().stream().filter(x->!"11011".equals(x.report())&&"READY".equals(x.state())&&x.sourceId()!=null)
            .max(Comparator.comparingInt(CommitteeFinancialEvidence.PeriodFacts::year).thenComparing(CommitteeFinancialEvidence.PeriodFacts::label)).orElse(null);
        if(p==null)return List.of();var v=p.values();var out=new ArrayList<Fact>();
        ratio(out,p,"INTERIM_OPERATING_MARGIN","중간 누계 영업이익률",v.get("OPERATING_PROFIT"),v.get("REVENUE"),"같은 중간 누계 영업이익 ÷ 매출 × 100","연간과 혼용/단순 연율화 금지");
        ratio(out,p,"INTERIM_DEBT_RATIO","중간 결산 부채비율",v.get("LIABILITIES"),v.get("EQUITY"),"중간 결산 부채총계 ÷ 자본총계 × 100",evidence.financialSectorReview()?"금융업은 규제 자본/유동성 지표와 별도 검토":"동일 결산일 기준");
        ratio(out,p,"INTERIM_CASH_CONVERSION","중간 누계 이익 대비 영업현금",v.get("OPERATING_CASH_FLOW"),v.get("NET_PROFIT"),"같은 중간 누계 영업현금흐름 ÷ 당기순이익 × 100","영업현금 변동 원인 별도 검토 · 연간 수치로 대체하지 않음");
        return List.copyOf(out);
    }
    private static void ratio(List<Fact> out,CommitteeFinancialEvidence.PeriodFacts p,String key,String label,BigDecimal numerator,BigDecimal denominator,String formula,String limitation){
        BigDecimal result=numerator!=null&&denominator!=null&&denominator.signum()>0?numerator.multiply(BigDecimal.valueOf(100)).divide(denominator,4,RoundingMode.HALF_UP):null;
        out.add(new Fact(key,label,result,"%",p.label(),p.fs(),formula,List.of(p.sourceId()),limitation+" · 입력 누락/분모 0 이하이면 미계산"));
    }
    private CommitteeQuantitativeFacts(){}
}
