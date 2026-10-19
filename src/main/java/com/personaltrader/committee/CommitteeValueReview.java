package com.personaltrader.committee;
import com.personaltrader.research.CompanyThesisService;
import com.personaltrader.research.EquityScenarioModel;
import java.math.*;
import java.time.LocalDate;
import java.util.List;
/** Frozen assumptions from actual financial inputs, never a broker price or guaranteed valuation. */
public record CommitteeValueReview(String horizon,String businessFacts,String potentialStrength,String counterEvidence,
    boolean financialSectorReview,String valuationState,BigDecimal conservativePerShare,BigDecimal basePerShare,
    BigDecimal optimisticPerShare,BigDecimal referenceClose,LocalDate referenceDate,BigDecimal discountToConservativePercent,
    List<String> assumptions,List<String> missing,List<String> invalidation) {
    public static CommitteeValueReview from(CompanyThesisService.Company c,BigDecimal close,LocalDate date){
        var v=c==null?null:c.valuation();var b=c==null?null:c.business();
        BigDecimal low=price(v,"보수"),base=price(v,"기준"),high=price(v,"낙관");
        BigDecimal discount=low==null||low.signum()<=0||close==null?null:
            BigDecimal.ONE.subtract(close.divide(low,MathContext.DECIMAL128)).multiply(new BigDecimal("100")).setScale(2,RoundingMode.HALF_UP);
        return new CommitteeValueReview("1~3년 사업·현금 창출 가설 · 기간별 주가 예측 아님",b==null?null:b.facts(),
            b==null?null:b.potentialStrength(),b==null?null:b.counterEvidence(),c!=null&&c.financialSectorReview(),
            v==null?"WAITING":v.state(),low,base,high,close,date,discount,
            assumptions(v),
            missing(c,v),
            c==null?List.of("사업 논거 무효화 조건 수집 대기"):c.invalidation());
    }
    private static List<String> assumptions(EquityScenarioModel.Result v){
        if(v==null)return List.of("가치 시나리오 자료 미확인");
        return java.util.stream.Stream.concat(v.scenarios().stream().map(s->s.label()+" 가정: 시작 ROE "+percent(s.startRoePercent())+" · 할인율 "+percent(s.discountPercent())+" · 배당성향 "+percent(s.assumedPayoutPercent())),v.limitations().stream()).toList();
    }
    private static String percent(BigDecimal n){return n==null?"미확인":n.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"%";}
    private static List<String> missing(CompanyThesisService.Company c,EquityScenarioModel.Result v){
        if(c==null)return List.of("기업 검토·공식 재무 입력 필요");
        var modelMissing=v==null?java.util.stream.Stream.of("가치 입력 미확인"):java.util.stream.Stream.concat(
            v.missing().stream(),"TOTAL_ONLY".equals(v.state())?java.util.stream.Stream.of(v.shareNote()):java.util.stream.Stream.empty());
        return java.util.stream.Stream.concat(modelMissing,c.missing().stream()).distinct().limit(8).toList();
    }
    private static BigDecimal price(EquityScenarioModel.Result v,String label){return v==null?null:
        v.scenarios().stream().filter(s->s.label().equals(label)).map(EquityScenarioModel.Scenario::perCommonShare).filter(java.util.Objects::nonNull).findFirst().orElse(null);}
}
