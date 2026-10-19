package com.personaltrader.committee;

import java.math.*;
import java.time.*;
import java.util.*;

/** Role-specific observations from the frozen brief only; no forecasts, network or paid calls. */
public record CommitteeResearchDossier(String roleKey,List<Finding> findings,List<Question> researchQuestions,List<String> limitations) {
    public record Finding(String key,String label,String observation,List<String> evidenceIds,String caveat){}
    public record Question(String toRole,String question){}

    public static List<CommitteeResearchDossier> from(InvestmentCommitteeService.Brief brief){
        if(brief==null)return List.of();
        return CommitteeContract.ROLES.stream().map(role->forRole(brief,role.key())).toList();
    }

    public static CommitteeResearchDossier forRole(InvestmentCommitteeService.Brief brief,String roleKey){
        if(CommitteeContract.ROLES.stream().noneMatch(role->role.key().equals(roleKey)))throw new IllegalArgumentException("전문 분야 확인 필요");
        Builder b=new Builder(brief,roleKey);
        if(brief==null){b.limits.add("고정된 기업 연구 자료 수집 대기");return b.finish();}
        var annual=annual(brief.financialEvidence());
        switch(roleKey){
            case "ECONOMY"->{
                b.fact("REVENUE_YOY",false);
                b.passage(List.of("RISK","BUSINESS"),"기업의 경기 노출 근거");
                b.sources(true,null,1);
                b.ask("FINANCE","경기·환율 가설이 매출·마진·현금 변화와 맞는지 기간을 맞춰 확인해 주세요.");
                b.ask("FINANCE","경기 회복 가설이 틀려도 현재 이익·현금으로 버틸 수 있는지 기간을 맞춰 반문해 주세요.");
                b.limits.add("거시 발표와 기업 실적 사이 인과관계는 별도 가설이며 제목만으로 확정하지 않음");
            }
            case "NEWS"->{
                b.sources(false,null,2);
                b.passage(List.of("OPERATIONS","BUSINESS"),"사건을 연결할 기존 사업");
                b.ask("ECONOMY","최근 기업 사건과 경기·환율 흐름이 같은 방향인지, 반대 영향도 가능한지 근거로 설명해 주세요.");
                b.ask("ECONOMY","호재로 읽은 사건이 기업 고유 변화인지 시장 전체 변화인지 구분할 수 있나요?");
                b.limits.add("발행처 요약은 전문이 아니며 사건 부재·미수집·제목만 확인을 구분");
            }
            case "POLICY"->{
                b.sources(false,"정책|규제|법안|법률|관세|수출|제재|허가|인가|금융당국|policy|tariff|sanction|regulat",2);
                b.passage(List.of("RISK","CAPITAL","BUSINESS"),"공식 사업·규제 노출 설명");
                b.ask("NEWS","정책 관련 보도에서 발표·제안·시행 중 확인된 단계와 기업 직접 관련성을 구분해 주세요.");
                b.ask("NEWS","정정 보도나 적용 제외 조건이 있는지 제공 자료에서 확인해 주세요.");
                b.limits.add("관련 정책 미발견은 악재가 아님; 법령 원문·시행일이 없으면 영향은 조건부");
            }
            case "INDUSTRY"->{
                b.passage(List.of("OPERATIONS","BUSINESS"),"사업과 수익원");
                b.fact("REVENUE_YOY",false);b.fact("OPERATING_MARGIN",false);
                b.ask("SCIENCE","매출·마진을 지속시킨다는 기술·제품 가설이 실제 상용화·고객 사용 근거와 연결되나요?");
                b.ask("SCIENCE","제품·서비스의 강점이 실제 고객·매출로 이어졌다는 근거와 모방 가능성을 제시해 주세요.");
                b.limits.add("경쟁사 비교자료가 없으면 점유율·업종 평균·독점 지위를 만들지 않음");
            }
            case "SCIENCE"->{
                b.passage(List.of("TECHNOLOGY","OPERATIONS","BUSINESS"),"기술·제품·서비스 운영 근거");
                b.assets(annual);
                b.sources(false,"기술|제품|개발|서비스|출시|상용|임상|특허|technology|product|launch|trial",1);
                b.ask("POLICY","제품·기술의 상용화에 필요한 허가·규제 조건과 현재 확인된 단계를 구분해 주세요.");
                b.ask("POLICY","사업 확대를 막거나 돕는 정책이 실제 적용된다는 근거가 있나요? 관련이 없으면 그렇게 밝혀 주세요.");
                b.limits.add("유·무형자산 취득은 연구개발비가 아님; 회사 설명과 독립 기술 검증을 구분");
            }
            case "FINANCE"->{
                b.fact("CASH_CONVERSION",false);b.fact("AFTER_ASSET_PURCHASE",false);b.fact("INTERIM_OPERATING_MARGIN",true);
                if(b.findings.size()<3)b.cashAndProfit(annual);
                b.ask("RISK","관측된 이익·현금과 부채 중 사업 가치 가설을 가장 크게 훼손하는 항목을 특정해 주세요.");
                b.ask("RISK","일회성 이익·운전자본 변동이나 재작성으로 실적이 과대평가됐다는 실제 근거가 있나요?");
                b.limits.add(brief.financialEvidence()!=null&&brief.financialEvidence().financialSectorReview()?"금융업 현금흐름·부채비율은 제조업 기준으로 판정하지 않고 규제 자본을 별도 확인":"연간과 중간 누계는 혼합·연율화하지 않으며 현금흐름이 미래 이익을 보장하지 않음");
            }
            case "MARKET"->{
                b.multiples();b.fact("REVENUE_YOY",false);b.price();
                b.ask("INDUSTRY","낮은 배수가 지속 가능한 매출·마진에 기반한 것인지 사업 악화를 반영한 것인지 검토해 주세요.");
                b.ask("INDUSTRY","가격 하락에도 유지되는 사업 가치와, 저평가가 아닌 가치 함정일 수 있는 조건을 각각 제시해 주세요.");
                b.limits.add("참고 배수는 직전 연간 수치 기준; TTM·예상 배수·업종 비교·상승 확률이 아님");
            }
            case "RISK"->{
                b.cashAndProfit(annual);b.fact("DEBT_RATIO",false);
                b.passage(List.of("CAPITAL","AUDIT","RISK"),"공식 건전성·감사·위험 설명");
                b.ask("MARKET","이 위험이 실현되면 현재 가격의 매력 가설이 무너지는지, 입력된 가치 가정으로 반문해 주세요.");
                b.ask("MARKET","저평가라는 긍정 주장에 가격만으로 확인할 수 없는 가정이 무엇인지 밝혀 주세요.");
                b.limits.add("자료 누락·현재 손실·사업 가치 훼손을 구분; 발췌만으로 감사 적정·위험 부재를 확정하지 않음");
            }
            default->throw new IllegalArgumentException("전문 분야 확인 필요");
        }
        if(b.findings.isEmpty())b.limits.add("이 역할의 직접 관측 근거가 부족함; 다른 역할의 미확인 항목을 자동 기권 사유로 복사하지 않음");
        else b.limits.add("고정 자료의 관측 사실이며 최신성·인과관계·미래 가정은 별도로 판단");
        return b.finish();
    }

    private static CommitteeFinancialEvidence.PeriodFacts annual(CommitteeFinancialEvidence f){
        return f==null?null:f.periods().stream().filter(p->"11011".equals(p.report())&&"READY".equals(p.state())&&p.sourceId()!=null&&!p.changedSinceFirstFetch())
            .max(Comparator.comparingInt(CommitteeFinancialEvidence.PeriodFacts::year)).orElse(null);
    }
    private static String n(BigDecimal value){return value==null?"미확인":value.stripTrailingZeros().toPlainString();}
    private static String clipped(String value,int max){if(value==null)return "";String text=value.strip();return text.length()<=max?text:text.substring(0,max-5)+" …[생략]";}
    private static LocalDate date(InvestmentCommitteeService.Evidence e){return e.publishedDate()!=null?e.publishedDate():e.publishedAt()==null?null:e.publishedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();}
    private static final class Builder{
        final InvestmentCommitteeService.Brief brief;final String role;final Set<String> allowed;
        final List<Finding> findings=new ArrayList<>();final List<Question> questions=new ArrayList<>();final List<String> limits=new ArrayList<>();
        Builder(InvestmentCommitteeService.Brief brief,String role){this.brief=brief;this.role=role;allowed=new HashSet<>();if(brief!=null)brief.evidence().forEach(e->allowed.add(e.id()));}
        void add(String key,String label,String observation,List<String> ids,String caveat){
            var valid=ids.stream().filter(Objects::nonNull).filter(allowed::contains).distinct().limit(3).toList();
            if(findings.size()<3&&!valid.isEmpty())findings.add(new Finding(key,clipped(label,35),clipped(observation,145),valid,clipped(caveat,70)));
        }
        void ask(String to,String text){if(questions.size()<2)questions.add(new Question(to,clipped(text,85)));}
        void fact(String key,boolean interim){
            var facts=interim?CommitteeQuantitativeFacts.interim(brief.financialEvidence()):CommitteeQuantitativeFacts.from(brief.financialEvidence());
            facts.stream().filter(f->key.equals(f.key())&&f.value()!=null).findFirst().ifPresent(f->add(f.key(),f.label(),f.period()+" · "+f.fs()+" · "+n(f.value())+f.unit()+" · "+f.formula(),f.evidenceIds(),f.limitation()));
        }
        void passage(List<String> categories,String label){
            if(brief.businessEvidence()==null)return;
            for(String category:categories){
                var passage=brief.businessEvidence().passages().stream().filter(p->category.equals(p.category())).findFirst().orElse(null);
                if(passage!=null){add("DOCUMENT_"+category,label,passage.period()+" · "+passage.text(),List.of(passage.sourceId()),"회사 공시 일부 발췌 · 전체 표·독립 검증 아님");return;}
            }
        }
        void sources(boolean macro,String pattern,int count){
            brief.evidence().stream().filter(e->Set.of("TITLE_ONLY","PUBLISHER_SUMMARY","PUBLISHER_EXCERPT").contains(e.accessLevel()))
                .filter(e->macro=="MARKET_CONTEXT_ONLY".equals(e.relevance()))
                .filter(e->pattern==null||e.title()!=null&&e.title().toLowerCase(Locale.ROOT).matches(".*("+pattern+").*"))
                .sorted(Comparator.comparing((InvestmentCommitteeService.Evidence e)->date(e),Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(count).forEach(e->{boolean article=e.articleExcerpt()!=null&&!e.articleExcerpt().isBlank();String body=article?e.articleExcerpt():e.publisherSummary();boolean summary=body!=null&&!body.isBlank();
                    add("SOURCE_"+e.id(),macro?"거시 맥락":"최근 기업 사건",(date(e)==null?"발행일 미확인":date(e))+" · "+clipped(e.title(),65)+(summary?" · "+body:""),List.of(e.id()),article?"발행처 공개 본문 일부 · 전문·교차검증 아님":summary?"발행처 제공 요약 · 전문·기업 영향 검증 아님":"제목만 확인 · 제목 밖 사실 추정 금지");});
        }
        void assets(CommitteeFinancialEvidence.PeriodFacts p){
            if(p==null)return;var v=p.values();
            if(v.get("PPE_PURCHASE")==null&&v.get("INTANGIBLE_PURCHASE")==null)return;
            add("ASSET_PURCHASES","자산 취득 관측",p.label()+" · "+p.fs()+" · 유형자산 취득 "+n(v.get("PPE_PURCHASE"))+"원 · 무형자산 취득 "+n(v.get("INTANGIBLE_PURCHASE"))+"원",List.of(p.sourceId()),"연구개발비·기술력·증설 효과로 대체 해석하지 않음");
        }
        void cashAndProfit(CommitteeFinancialEvidence.PeriodFacts p){
            if(p==null)return;var v=p.values();
            if(v.get("NET_PROFIT")==null&&v.get("OPERATING_CASH_FLOW")==null)return;
            add("CASH_AND_PROFIT","이익과 현금의 실제 관측",p.label()+" · "+p.fs()+" · 순이익 "+n(v.get("NET_PROFIT"))+"원 · 영업현금 "+n(v.get("OPERATING_CASH_FLOW"))+"원",List.of(p.sourceId()),"차이는 확인된 사실일 뿐 원인·일회성 조정은 별도 검증");
        }
        void multiples(){
            var m=brief.multiples();if(m==null||m.per()==null&&m.pbr()==null&&m.psr()==null)return;
            add("VALUATION_MULTIPLES","가격과 직전 연간 실적",m.priceDate()+" 가격 / "+m.year()+"년 "+m.fs()+" · PER "+n(m.per())+"배 · PBR "+n(m.pbr())+"배 · PSR "+n(m.psr())+"배",m.evidenceIds(),"당시 공시 보통주 수 사용 · 일회성 미조정 · 낮은 배수만으로 저평가 확정 불가");
        }
        void price(){
            if(brief.referenceClose()==null)return;
            var e=brief.evidence().stream().filter(x->"ISSUER_CHART".equals(x.relevance())).findFirst().orElse(null);
            if(e!=null)add("CONFIRMED_CLOSE","가격의 기준일",brief.chartBasisDate()+" 확정 종가 "+n(brief.referenceClose())+"원",List.of(e.id()),"실시간 가격 아님 · 이동평균 아래라는 이유만으로 반대하지 않음");
        }
        CommitteeResearchDossier finish(){return new CommitteeResearchDossier(role,List.copyOf(findings),List.copyOf(questions),limits.stream().map(x->clipped(x,85)).distinct().limit(2).toList());}
    }
}
