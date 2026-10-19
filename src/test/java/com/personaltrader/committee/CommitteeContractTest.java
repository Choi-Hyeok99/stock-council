package com.personaltrader.committee;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommitteeContractTest {
 private final Instant now=Instant.parse("2026-10-15T00:00:00Z");
 private List<CommitteeContract.Opinion> opinions(String version,int approvals){
  List<CommitteeContract.Opinion> result=new ArrayList<>();int index=0;
  for(var r:CommitteeContract.ROLES)result.add(new CommitteeContract.Opinion(r.key(),"FINAL",index++<approvals?"APPROVE":"ABSTAIN",
   "검토 근거",List.of("출처 사실"),List.of("가정"),List.of("반대 근거"),List.of("source"),"","fingerprint",version,now));
  return result;
 }
 @Test void legacyVotesStayReadableUnderTheirOriginalVersion(){
  assertThat(CommitteeContract.tally(opinions("COMMITTEE_EVIDENCE_V2",6),"fingerprint",Set.of("source"),now,"COMMITTEE_EVIDENCE_V2").thresholdMet()).isTrue();
  assertThat(CommitteeContract.tally(opinions("COMMITTEE_EVIDENCE_V2",6),"fingerprint",Set.of("source"),now).complete()).isFalse();
 }
 @Test void sixOfEightRequiredAndAbstentionsRemainInDenominator(){
  assertThat(CommitteeContract.tally(opinions(CommitteeContract.PROMPT_VERSION,5),"fingerprint",Set.of("source"),now).thresholdMet()).isFalse();
  var t=CommitteeContract.tally(opinions(CommitteeContract.PROMPT_VERSION,6),"fingerprint",Set.of("source"),now);
  assertThat(t.percent()).isEqualByComparingTo("75");assertThat(t.thresholdMet()).isTrue();
 }
 @Test void mandateSeparatesValueThesisFromMovingAverageAndOrderTiming(){
  assertThat(CommitteeContract.investmentMandate()).contains("20일 이동평균", "만으로 REJECT하지", "가치 함정", "1~3년", "조건부 매수", "금융/보험업");
 }
 @Test void absentValuationDoesNotInventDiscountOrPrice(){
  var v=CommitteeValueReview.from(null,new java.math.BigDecimal("100"),java.time.LocalDate.of(2026,10,14));
  assertThat(v.valuationState()).isEqualTo("WAITING");assertThat(v.conservativePerShare()).isNull();
  assertThat(v.discountToConservativePercent()).isNull();assertThat(v.missing()).isNotEmpty();
 }
 private com.personaltrader.research.CompanyThesisService.Company company(com.personaltrader.research.EquityScenarioModel.Result value){
  return new com.personaltrader.research.CompanyThesisService.Company("000001","Fixture",false,"WAIT","WAIT",false,
   null,null,null,null,0,false,value,null,null,List.of(),List.of(),List.of(),List.of(),"Fixture only");
 }
 @Test void discountUsesScenarioPriceAndItsExplicitAssumptions(){
  var scenario=new com.personaltrader.research.EquityScenarioModel.Scenario("보수",new java.math.BigDecimal("8"),new java.math.BigDecimal("12"),new java.math.BigDecimal("40"),null,new java.math.BigDecimal("100"),List.of());
  var result=new com.personaltrader.research.EquityScenarioModel.Result("SCENARIOS",List.of(),null,null,null,null,"verified",null,List.of(scenario),List.of(),List.of(),List.of("연구 가정"));
  var review=CommitteeValueReview.from(company(result),new java.math.BigDecimal("80"),java.time.LocalDate.of(2026,10,14));
  assertThat(review.discountToConservativePercent()).isEqualByComparingTo("20");
  assertThat(review.assumptions()).anySatisfy(s->assertThat(s).contains("ROE 8%","할인율 12%","배당성향 40%"));
 }
 @Test void totalOnlyRetainsShareClassReasonAndNoPerShareValue(){
  var result=new com.personaltrader.research.EquityScenarioModel.Result("TOTAL_ONLY",List.of(),null,null,null,null,"주식 종류별 배분 미확인",null,List.of(),List.of(),List.of(),List.of());
  var review=CommitteeValueReview.from(company(result),new java.math.BigDecimal("80"),java.time.LocalDate.of(2026,10,14));
  assertThat(review.missing()).contains("주식 종류별 배분 미확인");
  assertThat(review.conservativePerShare()).isNull();assertThat(review.discountToConservativePercent()).isNull();
 }
}
