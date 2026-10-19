package com.personaltrader.committee;

import java.math.*;
import java.util.List;

/** Forecast only. No provider calls, actual spend, FX quotes or claimed model quality. */
public final class CommitteeCostPlan {
    public record Option(String model,BigDecimal inputPerMillion,BigDecimal outputPerMillion,
        BigDecimal meetingUsd,BigDecimal month10PerDayUsd,BigDecimal month50PerDayUsd,String source,
        BigDecimal plannedMonthUsd,boolean recommended){}
    public record View(String asOf,int tradingDays,int expertInputTokens,int expertOutputTokens,
        int summaryInputTokens,int summaryOutputTokens,BigDecimal proposedMonthlyCeilingUsd,
        BigDecimal incurredModelCostUsd,List<Option> options,String explanation,int weekdayMeetings,int weekendMeetings,int assumedMonthlyMeetings){}
    public static View view(BigDecimal incurred){return new View("2026-10-15",22,360000,18000,16000,1600,
        new BigDecimal("10"),incurred,List.of(
            option("Luna 8명 + mini 요약","0.20","1.20","https://developers.openai.com/api/docs/models/gpt-5.6-luna",false),
            option("Luna 6명 + mini 2명·요약","0.3375","2.025","https://developers.openai.com/api/docs/models/gpt-5.6-luna",true),
            option("mini 8명 + mini 요약","0.75","4.50","https://developers.openai.com/api/docs/models/gpt-5.4-mini",false)),
        "8명 독립 의견·질문8회+질문 답변8회+최종표8회+요약1회, 총25회. 전문가 합계 입력360,000/출력18,000 토큰, 요약 mini 입력16,000/출력1,600 가정. "
        +"요약 단가는 입력$0.75/출력$4.50 per 1M. 캐시/무료 티어 할인 미반영. 실제 비용은 토큰·추론·검색·환율·세금에 따라 달라져요. "
        +"월$10에서 아래 252회 예상액을 전부 사용할 수 있다는 뜻이 아니에요. 혼합 단가 가정으로는 월 약56회이며 입력량에 따라 더 줄어요. 평일10개·주말4개(22+8일 가정252개)는 기존 일일 상한일 뿐이에요. 모델 구성은 Luna 6역할 + mini 재무/위험2역할·요약이에요. "
        +"혼합 단가는 전문가 토큰75%/25% 가중 평균이며 역할별 사용량은 달라질 수 있어요. 앱은 호출 전 월$10 예약/사용량 기록을 적용하고 동일 자료를 재사용해요. "
        +"평일10개·주말4개는 최대치이고 실제 회의 수는 새 자료·실제 토큰·잔여 예산에 따라 줄어요. 실제 연결/진행 상태와 사용량 비용은 위 진행 카드에서 확인해요. 공급자 청구서 대조는 별도예요.",10,4,252);}
    private static Option option(String model,String in,String out,String source,boolean recommended){
        BigDecimal input=new BigDecimal(in),output=new BigDecimal(out);
        BigDecimal cost=input.multiply(new BigDecimal("360000")).add(output.multiply(new BigDecimal("18000")))
            .add(new BigDecimal("0.75").multiply(new BigDecimal("16000")))
            .add(new BigDecimal("4.50").multiply(new BigDecimal("1600"))).divide(new BigDecimal("1000000"),6,RoundingMode.HALF_UP);
        return new Option(model,input,output,cost,cost.multiply(new BigDecimal("220")),cost.multiply(new BigDecimal("1100")),source,
            cost.multiply(new BigDecimal("252")),recommended);
    }
    private CommitteeCostPlan(){}
}
