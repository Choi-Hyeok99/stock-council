package com.personaltrader.research;

import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;

/** Illustrative excess-earnings equity scenarios. Never prices supplied to the broker. */
@Component
public class EquityScenarioModel {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    public Result evaluate(DartFinancialService.Company c,CompanySourceService.Source shares) {
        List<String> missing=new ArrayList<>();
        var years=c.annuals().stream().filter(p->"READY".equals(p.state())).sorted(Comparator.comparingInt(DartFinancialService.Period::year)).toList();
        if(years.size()<3)missing.add("최소 3개 연속 사업연도의 지배주주 이익·자본 필요");
        List<DartFinancialService.Period> last=years.size()>=3?years.subList(years.size()-3,years.size()):years;
        List<AnnualInput> inputs=new ArrayList<>();List<BigDecimal> roes=new ArrayList<>();
        String fs=last.isEmpty()?"":last.getLast().fs();
        for(int i=0;i<last.size();i++){
            var p=last.get(i);var profit=metric(p,"OWNER_PROFIT");var equity=metric(p,"OWNER_EQUITY");
            if(!"CFS".equals(p.fs())||!p.fs().equals(fs))missing.add(p.year()+" 연결 지배주주 기준 확인 필요");
            if(i>0&&p.year()!=last.get(i-1).year()+1)missing.add("사업연도 연속성 확인 필요");
            if(profit==null||equity==null||equity.previous()==null||equity.value().signum()<=0||equity.previous().signum()<=0){missing.add(p.year()+" 지배주주 이익 또는 당기/전기 자본 미확인");continue;}
            if(i>0){var previous=metric(last.get(i-1),"OWNER_EQUITY");var previousProfit=metric(last.get(i-1),"OWNER_PROFIT");
                if(previous!=null&&equity.previous().compareTo(previous.value())!=0)missing.add(p.year()+" 전기 자본 재분류/정정 비교 검토 필요");
                if(previousProfit!=null&&(profit.previous()==null||profit.previous().compareTo(previousProfit.value())!=0))missing.add(p.year()+" 전기 이익 재분류/정정 비교 검토 필요");}
            BigDecimal roe=profit.value().divide(equity.value().add(equity.previous()).divide(BigDecimal.TWO,MC),MC);
            inputs.add(new AnnualInput(p.year(),profit.value(),equity.value(),equity.previous(),roe.multiply(HUNDRED),p.rawId(),p.sourceUrl(),p.contentHash()));roes.add(roe);
        }
        BigDecimal common=null;String shareDate=null;String shareNote="주식 총수 수집 대기";
        if(shares!=null&&shares.state().equals("READY")){
            var rows=shares.payload().path("classes");BigDecimal total=null;boolean other=false,invalid=false;
            for(var row:rows){String type=row.path("type").asText();BigDecimal amount=row.path("circulating").isNumber()?row.path("circulating").decimalValue():null;
                if(type.contains("보통")){common=amount;shareDate=row.path("asOfDate").asText();invalid=!row.path("arithmeticVerified").asBoolean();}
                else if(type.equals("합계")){total=amount;invalid|=!row.path("arithmeticVerified").asBoolean();}
                else if(amount!=null&&amount.signum()>0)other=true;
            }
            if(common==null||common.signum()<=0||total==null||invalid){common=null;shareNote="주식 총수·자기주식·유통주식 산술 검증 미완료";}
            else if(other||total.compareTo(common)!=0){common=null;shareNote="보통주 외 주식 종류 존재 · 종류별 청구권 배분 미검토로 주당 계산 보류";}
            else shareNote="공시 합계 유통주식과 보통주 유통주식 일치 · 공시 이후 증자/소각 등 별도 검토 필요";
        }
        List<String> limitations=List.of("잔여이익 모형의 연구용 가정이며 버크셔의 실제 가치 계산법 또는 목표 주가가 아니다.",
            "자본 장부가치의 경제적 회수 가능성·정상화 이익·회계 정책·현금전환·자본비용을 전문가가 확정하지 않았다.",
            "3년 관측 ROE 중앙값을 참고한다. 향후 ROE는 5년에 걸쳐 할인율로 수렴한다고 가정하고 이후 초과이익은 0으로 둔다.",
            "배당 성향 40%, 할인율 12/10/8%, ROE -3/0/+3%p는 비교를 위한 가정이며 실제 예측·상승 확률이 아니다.",
            "주가 급변·실적 정정·공시 이후 주식 수 변화, 금융업 자본 적정성, 사이클 및 일회성 이익은 별도 검토가 필요하다.");
        if(!missing.isEmpty()||roes.size()!=3)return new Result("WAITING",inputs,null,null,common,shareDate,shareNote,shares==null?null:shares.id(),List.of(),List.of(),List.copyOf(new LinkedHashSet<>(missing)),limitations);
        roes.sort(Comparator.naturalOrder());BigDecimal reference=roes.get(1),book=inputs.getLast().ownerEquity();
        List<Scenario> scenarios=new ArrayList<>();
        scenarios.add(scenario("보수",book,reference.subtract(new BigDecimal("0.03")),new BigDecimal("0.12"),common));
        scenarios.add(scenario("기준",book,reference,new BigDecimal("0.10"),common));
        scenarios.add(scenario("낙관",book,reference.add(new BigDecimal("0.03")),new BigDecimal("0.08"),common));
        List<Sensitivity> sensitivity=new ArrayList<>();
        for(String shock:List.of("-0.01","0","0.01"))for(String rate:List.of("0.09","0.10","0.11")){
            var row=scenario("민감도",book,reference.add(new BigDecimal(shock)),new BigDecimal(rate),common);
            sensitivity.add(new Sensitivity(row.startRoePercent(),row.discountPercent(),row.totalOwnerValue(),row.perCommonShare()));}
        return new Result(common==null?"TOTAL_ONLY":"SCENARIOS",inputs,book,reference.multiply(HUNDRED),common,shareDate,shareNote,shares==null?null:shares.id(),scenarios,sensitivity,List.of(),limitations);
    }
    private Scenario scenario(String label,BigDecimal initialBook,BigDecimal startRoe,BigDecimal discount,BigDecimal shares){
        BigDecimal book=initialBook,pv=BigDecimal.ZERO,payout=new BigDecimal("0.4");List<Projection> rows=new ArrayList<>();
        for(int t=1;t<=5;t++){
            BigDecimal roe=startRoe.add(discount.subtract(startRoe).multiply(BigDecimal.valueOf(t-1).divide(BigDecimal.valueOf(4),MC)));
            BigDecimal income=book.multiply(roe,MC),excess=income.subtract(book.multiply(discount,MC));
            BigDecimal discounted=excess.divide(BigDecimal.ONE.add(discount).pow(t,MC),MC);pv=pv.add(discounted);
            BigDecimal next=book.add(income.multiply(BigDecimal.ONE.subtract(payout),MC));
            rows.add(new Projection(t,book,roe.multiply(HUNDRED),income,excess,discounted,next));book=next;
        }
        BigDecimal value=initialBook.add(pv).max(BigDecimal.ZERO).setScale(0,RoundingMode.HALF_UP);
        return new Scenario(label,startRoe.multiply(HUNDRED),discount.multiply(HUNDRED),payout.multiply(HUNDRED),value,shares==null?null:value.divide(shares,2,RoundingMode.HALF_UP),rows);
    }
    public static DartFinancialService.Metric metric(DartFinancialService.Period p,String key){return p==null?null:p.metrics().stream().filter(m->m.key().equals(key)&&m.state().equals("AVAILABLE")&&m.value()!=null&&"KRW".equals(m.currency())).findFirst().orElse(null);}
    public record AnnualInput(int year,BigDecimal ownerProfit,BigDecimal ownerEquity,BigDecimal priorOwnerEquity,BigDecimal ownerRoePercent,Long financialRawId,String sourceUrl,String hash){}
    public record Projection(int year,BigDecimal openingBook,BigDecimal roePercent,BigDecimal assumedIncome,BigDecimal excessIncome,BigDecimal presentValue,BigDecimal closingBook){}
    public record Scenario(String label,BigDecimal startRoePercent,BigDecimal discountPercent,BigDecimal assumedPayoutPercent,BigDecimal totalOwnerValue,BigDecimal perCommonShare,List<Projection> projection){}
    public record Sensitivity(BigDecimal startRoePercent,BigDecimal discountPercent,BigDecimal totalOwnerValue,BigDecimal perCommonShare){}
    public record Result(String state,List<AnnualInput> inputs,BigDecimal bookValue,BigDecimal referenceRoePercent,BigDecimal commonCirculating,String sharesAsOf,String shareNote,Long sharesSourceId,List<Scenario> scenarios,List<Sensitivity> sensitivity,List<String> missing,List<String> limitations){}
}
