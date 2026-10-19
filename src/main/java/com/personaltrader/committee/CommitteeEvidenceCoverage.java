package com.personaltrader.committee;

import java.util.*;

/** Deterministic input inventory. Counts describe supplied evidence, never correctness or a vote. */
public final class CommitteeEvidenceCoverage {
    public record RoleCoverage(String key,String name,String state,String task,List<String> available,List<String> gaps,List<String> evidenceIds){}
    public static List<RoleCoverage> from(InvestmentCommitteeService.Brief r){
        if(r==null)return List.of();
        boolean financial=r.financialEvidence()!=null&&r.financialEvidence().financialSectorReview();
        var passages=r.businessEvidence()==null?List.<CommitteeBusinessEvidence.Passage>of():r.businessEvidence().passages();
        List<RoleCoverage> out=new ArrayList<>();
        for(var role:CommitteeContract.ROLES){
            List<String> ids=new ArrayList<>(),available=new ArrayList<>(),gaps=new ArrayList<>();String task=role.focus();
            Set<String> categories=switch(role.key()){
                case "ECONOMY","POLICY"->Set.of("BUSINESS","RISK","MANAGEMENT");
                case "INDUSTRY"->Set.of("BUSINESS","OPERATIONS","MANAGEMENT");
                case "SCIENCE"->financial?Set.of("BUSINESS","OPERATIONS","RISK"):Set.of("TECHNOLOGY","OPERATIONS","BUSINESS");
                case "FINANCE","RISK"->Set.of("CAPITAL","AUDIT","RISK","OPERATIONS");
                default->Set.of();
            };
            for(var p:passages)if(categories.contains(p.category())){ids.add(p.sourceId());available.add(p.period()+" · "+p.title()+" 본문 발췌"+(p.truncated()?"(일부 생략)":""));}
            for(var e:r.evidence()){
                boolean match=switch(role.key()){
                    case "ECONOMY"->"MARKET_CONTEXT_ONLY".equals(e.relevance());
                    case "NEWS"->Set.of("TITLE_ONLY","PUBLISHER_SUMMARY","PUBLISHER_EXCERPT").contains(e.accessLevel())&&!"MARKET_CONTEXT_ONLY".equals(e.relevance());
                    case "POLICY"->Set.of("TITLE_ONLY","PUBLISHER_SUMMARY","PUBLISHER_EXCERPT").contains(e.accessLevel())&&e.title()!=null&&e.title().matches(".*(정책|규제|법안|법률|관세|수출|제재|허가|인가|금리|통화|금융당국|policy|tariff|sanction|regulat).*");
                    case "INDUSTRY","SCIENCE"->Set.of("PUBLISHER_SUMMARY","PUBLISHER_EXCERPT").contains(e.accessLevel())&&!"MARKET_CONTEXT_ONLY".equals(e.relevance());
                    case "FINANCE","RISK"->"FINANCIAL_VALUES".equals(e.accessLevel());
                    case "MARKET"->Set.of("MARKET_VALUES","SHARES_VALUES","VALUATION_ASSUMPTIONS").contains(e.accessLevel());
                    default->false;
                };
                if(match){ids.add(e.id());available.add(e.source()+" · "+e.title()+
                    ("TITLE_ONLY".equals(e.accessLevel())?"(제목)":"PUBLISHER_SUMMARY".equals(e.accessLevel())?"(발행처 제공 요약)":"PUBLISHER_EXCERPT".equals(e.accessLevel())?"(공개 기사 일부)":""));}
            }
            if(Set.of("ECONOMY","INDUSTRY","SCIENCE","FINANCE","RISK").contains(role.key())&&r.financialEvidence()!=null){
                var annual=r.financialEvidence().periods().stream().filter(p->"11011".equals(p.report())&&"READY".equals(p.state())&&p.sourceId()!=null)
                    .max(Comparator.comparingInt(CommitteeFinancialEvidence.PeriodFacts::year)).orElse(null);
                if(annual!=null&&!annual.values().isEmpty()){ids.add(annual.sourceId());available.add(annual.label()+" · 확인된 재무 항목 "+annual.values().size()+"개 · 역할별 계산 자료 참고");}
            }
            switch(role.key()){
                case "ECONOMY"->{task="공시의 금리·환율·경기 노출과 제공된 거시 맥락을 연결. 실제 수혜/피해 경로와 반대 조건을 평가";gaps.add("거시 제목은 실제 정책금리 수치·기업 영향 검증이 아님");}
                case "POLICY"->{task="공시/제목에 명시된 규제·정책만 평가. 새 정책이 없다는 사실을 기업 악재로 판단하지 않음";gaps.add("법령·정책 원문과 시행일은 별도 확인; 관련 정책 부재와 자료 수집 실패를 구분");}
                case "NEWS"->{task="기업 공시와 발행처 제공 요약을 날짜순으로 연결하고 사건→매출/비용/자본 경로와 반대 근거를 제시. ISSUANCE_TITLE을 실적 호재로 간주하지 않음";gaps.add("발행처 요약은 전체 본문/교차검증이 아님; 제목만 있는 항목은 제목 밖 내용을 추정하지 않음");}
                case "INDUSTRY"->{task="공식 사업·영업 설명으로 수익원·수요·경쟁 가설을 평가. 모든 재무 심사를 반복하지 않음";gaps.add("독립 업종 점유율·경쟁사 수치 비교 미연결");}
                case "SCIENCE"->{task=financial?"금융서비스의 디지털 채널·운영 역량·위험모형 가설을 검토. 임상·특허를 요구하지 않으며 자본적정성 판정을 반복하지 않음":"사업과 관련된 기술·제품·서비스 운영과 상용화 단계를 검토. 임상·특허가 무관하면 제품·운영 역량으로 적용 범위를 정함";gaps.add("공시의 회사 설명과 독립 검증을 구분; 연구개발비 누락을 기술력 부재로 단정하지 않음");}
                case "FINANCE"->{task="연간/중간 누계 수치와 본문에서 일회성·재무건전성·가치 함정을 검토";if(financial)gaps.add("금융업 본문이 있어도 NCR 등 표의 기간·열·연결 범위를 확인; 제조업 CF/부채 기준으로 일괄 거절하지 않음");}
                case "MARKET"->{task="기준일 확정 가격·배수·명시된 가치 가정의 민감도를 평가. 다른 역할의 미검증 항목을 전부 반복하지 않음";gaps.add("업종 평균/검증된 시장 적정가 미연결 · 실시간 주문 판단 아님");}
                case "RISK"->{task="제공된 사실로 가장 큰 반대 가설을 특정. 핵심 정보 공백과 이미 있는 정보의 해석 불확실성을 구분";gaps.add("표본·발췌 범위 밖 위험이 없다고 보증하지 않음");}
            }
            boolean measured="FINANCE".equals(role.key())&&r.financialEvidence()!=null&&r.financialEvidence().periods().stream().anyMatch(p->"READY".equals(p.state())&&!p.values().isEmpty())
                ||"MARKET".equals(role.key())&&r.referenceClose()!=null;
            String state=ids.isEmpty()?"NO_DIRECT_EVIDENCE":measured?"DATA_AVAILABLE":"PARTIAL";
            if(passages.isEmpty()&&Set.of("INDUSTRY","SCIENCE").contains(role.key()))gaps.add("공식 사업 본문 수집 대기");
            var allowed=r.evidence().stream().map(InvestmentCommitteeService.Evidence::id).collect(java.util.stream.Collectors.toSet());
            out.add(new RoleCoverage(role.key(),role.name(),state,task,available.stream().distinct().limit(6).toList(),List.copyOf(gaps),ids.stream().filter(allowed::contains).distinct().toList()));
        }
        return List.copyOf(out);
    }
    public static String roleInstructions(InvestmentCommitteeService.Brief r,String key){
        var coverage=from(r).stream().filter(x->x.key().equals(key)).findFirst().orElseThrow();
        return "당신의 평가 범위: "+coverage.task()+". 입력 범위: "+coverage.state()+". 제공 근거ID는 roleCoverage에서 확인하세요. 확인 범위: "+String.join(" / ",coverage.gaps())+". "
            +"확인한 사실→내 분야의 조건부 해석→가장 큰 반론 순으로 평가하세요. 다른 역할에 필요한 미확인 지표 하나를 모든 분야의 자동 기권 사유로 복사하지 마세요. "
            +"researchDossier의 관측 수치·공식 본문을 먼저 확인하고, 질문은 상대 역할의 실제 답과 함께 검토하세요. "
            +"ABSTAIN일 때 무엇이 없어서 어떤 판단이 불가능한지 특정하고, 관련 없는 분야/사건 부재인지 수집 누락인지 구분하세요. 정보가 실제 부족하면 기권을 유지하세요. ";
    }
    private CommitteeEvidenceCoverage(){}
}
